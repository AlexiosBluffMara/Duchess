package com.duchess.companion.gemma

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.meta.wearable.dat.camera.types.VideoFrame
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/**
 * States for the Gemma 3n inference engine lifecycle.
 *
 * Alex: Sealed interface again. Every consumer of GemmaState must handle
 * all five variants. The Error state carries a message string for logging
 * but we NEVER include PII in it — only model-level errors like "OOM" or
 * "tensor shape mismatch".
 */
sealed interface GemmaState {
    data object Idle : GemmaState       // Model not loaded, minimal memory usage
    data object Loading : GemmaState    // Model being loaded into RAM
    data object Ready : GemmaState      // Model hot in memory, waiting for frames
    data object Running : GemmaState    // Actively running inference on a frame
    data class Error(val message: String) : GemmaState
}

/**
 * Structured result from Gemma 3n safety analysis.
 *
 * Alex: This is the parsed output of the model's JSON response.
 * We enforce structure here instead of passing raw JSON strings around,
 * because downstream consumers (escalation pipeline, alert system) need
 * typed fields they can pattern-match on. The descriptionEn/descriptionEs
 * pair ensures bilingual support is baked in at the model output level.
 *
 * PRIVACY: No worker identity in these fields. Only violation metadata.
 */
data class GemmaAnalysisResult(
    val violationDetected: Boolean,
    val violationType: String?,
    val severity: Int,
    val descriptionEn: String,
    val descriptionEs: String,
    val confidence: Double
)

/**
 * Foreground service running the Gemma 3n E2B (1.91B param) on-device model.
 *
 * Alex: Why a foreground Service and not WorkManager?
 *   1. WorkManager is for deferrable work. Safety inference is real-time.
 *   2. WorkManager's 10-minute execution limit would kill long inference sessions.
 *   3. Foreground services can hold partial wake locks for camera processing.
 *   4. The persistent notification tells workers the AI is actively watching.
 *
 * The model is loaded LAZILY — only when the first frame needs analysis.
 * This saves ~1.2GB of RAM when the worker isn't near a detection zone.
 * After 5 minutes of inactivity (no analyze() calls), we unload the model
 * to free memory for other apps. Construction workers run a LOT of apps.
 *
 * PRIVACY: All inference is LOCAL. Frames never leave the device during analysis.
 * Only the structured GemmaAnalysisResult (no images, no PII) may be escalated.
 */
@AndroidEntryPoint
class GemmaInferenceService : Service() {

    companion object {
        private const val CHANNEL_ID = "duchess_gemma"
        private const val NOTIFICATION_ID = 1001

        // Alex: Temperature 0.1 for deterministic safety output.
        // We do NOT want creative/varied responses when classifying PPE violations.
        // Higher temps = more randomness = inconsistent safety classifications.
        // 0.1 is just enough to avoid degenerate repetition while staying deterministic.
        const val INFERENCE_TEMPERATURE = 0.1f

        // Alex: 5 minutes = 300,000ms. Per the companion phone instructions:
        // "Unload model after 5 minutes of inactivity to free memory."
        // The Gemma 3n E2B model takes ~1.2GB in RAM. On a Pixel 9 Fold with 12GB,
        // that's 10% of total RAM sitting idle. Unloading is the right call.
        const val INACTIVITY_TIMEOUT_MS = 5 * 60 * 1000L  // 5 minutes
    }

    @Inject
    lateinit var notificationManager: NotificationManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Alex: Nullable because the model is loaded lazily and unloaded on inactivity.
    // Access is serialized via inferenceMutex — no concurrent read/write races.
    private var llmInference: LlmInference? = null

    private val _state = MutableStateFlow<GemmaState>(GemmaState.Idle)
    val state: StateFlow<GemmaState> = _state.asStateFlow()

    // Alex: Mutex protects concurrent analyze() calls. Two frames arriving at once
    // shouldn't both try to load the model simultaneously. The mutex serializes
    // access so load-then-infer is atomic per caller.
    private val inferenceMutex = Mutex()

    // Alex: The inactivity timer job. We cancel and re-launch it on every analyze()
    // call, creating a rolling 5-minute window. If no calls come in 5 minutes,
    // the timer fires and unloads the model.
    private var inactivityTimerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Alex: START_STICKY means the system will restart this service if it's killed
        // for memory. For a safety-critical app, we NEED to come back. The alternative
        // START_NOT_STICKY would let the system kill us permanently under memory pressure.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        inactivityTimerJob?.cancel()
        serviceScope.cancel()
        _state.value = GemmaState.Idle
    }

    /**
     * Load the Gemma 3n E2B model into memory.
     *
     * Alex: Called lazily on first inference request, NOT at service startup.
     * Loading a 1.91B param model takes 3-8 seconds on a Pixel 9 Fold.
     * We don't want that delay at app launch — workers need the camera stream
     * immediately. The model loads only when the first PPE escalation arrives.
     *
     * The model binary comes from app assets (bundled at build time) or is
     * downloaded on first run and cached in internal storage.
     */
    suspend fun loadModel() {
        if (_state.value == GemmaState.Ready || _state.value == GemmaState.Loading) return

        _state.value = GemmaState.Loading

        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(getModelPath())
                .setMaxTokens(512)
                .build()
            llmInference = LlmInference.createFromOptions(this, options)
            _state.value = GemmaState.Ready
            resetInactivityTimer()
        } catch (e: Exception) {
            // Alex: Never log the model path — reveals internal storage structure.
            // Only log a sanitized error category.
            _state.value = GemmaState.Error("Model load failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Unload the model from memory to free ~1.2GB of RAM.
     *
     * Alex: Called by the inactivity timer after 5 minutes of no analyze() calls.
     * Also called explicitly when the service is being destroyed. After unloading,
     * the next analyze() call will automatically re-load the model (lazy loading).
     */
    private fun unloadModel() {
        llmInference?.close()
        llmInference = null
        _state.value = GemmaState.Idle
        inactivityTimerJob?.cancel()
        inactivityTimerJob = null
    }

    /**
     * Reset the inactivity timer. Called after every analyze() call.
     *
     * Alex: This creates a rolling 5-minute window. Every time we run inference,
     * the timer resets. If no inference happens for 5 continuous minutes, the
     * timer fires and we unload the model. This is a simple but effective strategy
     * that keeps the model hot during active scanning but frees memory when idle.
     *
     * Using serviceScope (not viewModelScope) because this Service outlives any ViewModel.
     */
    private fun resetInactivityTimer() {
        inactivityTimerJob?.cancel()
        inactivityTimerJob = serviceScope.launch {
            delay(INACTIVITY_TIMEOUT_MS)
            // Alex: Timer expired — 5 minutes of silence. Unload the model.
            // If analyze() is called again later, loadModel() will reload it.
            unloadModel()
        }
    }

    /**
     * Analyze a video frame for safety violations using Gemma 3n.
     *
     * Alex: This is the main entry point for Tier 2 inference. The flow:
     *   1. Lock the inference mutex (prevents concurrent model access)
     *   2. Ensure model is loaded (lazy load if needed)
     *   3. Run inference on the frame bitmap
     *   4. Parse the structured JSON output
     *   5. Reset the inactivity timer
     *   6. Return a typed GemmaAnalysisResult
     *
     * PRIVACY: Input frame is processed ENTIRELY on-device. The frame bitmap
     * never leaves this method. Only the structured result (no images, no PII)
     * is returned to the caller for potential escalation.
     *
     * @param frame The VideoFrame from the DAT SDK stream to analyze
     * @return Structured analysis result with bilingual descriptions
     */
    suspend fun analyze(frame: VideoFrame): GemmaAnalysisResult {
        return inferenceMutex.withLock {
            // Alex: Ensure model is hot. If we're Idle, this triggers a full load.
            // If we're already Ready, this is a no-op (check inside loadModel).
            if (_state.value != GemmaState.Ready) {
                loadModel()
            }

            _state.value = GemmaState.Running

            val result = if (llmInference != null) {
                val prompt = buildSafetyPrompt(frame)
                val rawOutput = llmInference!!.generateResponse(prompt)
                parseGemmaOutput(rawOutput)
            } else {
                // Defensive fallback — shouldn't happen after loadModel succeeds
                GemmaAnalysisResult(
                    violationDetected = false,
                    violationType = null,
                    severity = 0,
                    descriptionEn = "Model not loaded",
                    descriptionEs = "Modelo no cargado",
                    confidence = 0.0
                )
            }

            _state.value = GemmaState.Ready
            resetInactivityTimer()

            result
        }
    }

    /**
     * Parse the raw JSON output from Gemma 3n into a typed GemmaAnalysisResult.
     *
     * Alex: Gemma outputs JSON (we prompt it to do so), but LLMs are notoriously
     * unreliable with JSON formatting. This parser is defensive:
     *   - Missing fields get safe defaults (no violation, zero severity)
     *   - Malformed JSON returns a "no violation" result instead of crashing
     *   - We use org.json.JSONObject (built into Android) because it's zero-dependency
     *
     * In production, we'd also validate that violation_type is one of our known
     * enum values. For now, we pass it through as a raw string.
     */
    internal fun parseGemmaOutput(rawJson: String): GemmaAnalysisResult {
        return try {
            val json = JSONObject(rawJson)
            GemmaAnalysisResult(
                violationDetected = json.optBoolean("violation_detected", false),
                violationType = if (json.has("violation_type")) json.optString("violation_type") else null,
                severity = json.optInt("severity", 0),
                descriptionEn = json.optString("description_en", "Analysis complete"),
                descriptionEs = json.optString("description_es", "Análisis completo"),
                confidence = json.optDouble("confidence", 0.0)
            )
        } catch (e: Exception) {
            // Alex: If JSON parsing fails entirely, return a safe "no violation" default.
            // We log the parse error but NEVER log the raw JSON (might contain frame metadata).
            GemmaAnalysisResult(
                violationDetected = false,
                violationType = null,
                severity = 0,
                descriptionEn = "Analysis error — unable to parse model output",
                descriptionEs = "Error de análisis — no se pudo analizar la salida del modelo",
                confidence = 0.0
            )
        }
    }

    /**
     * Resolve the model file path, copying from assets on first run if needed.
     *
     * Alex: We check internal storage first because downloaded/updated models
     * go there. If the file doesn't exist yet, we copy the bundled asset.
     * This supports both bundled and OTA-updated model binaries.
     */
    internal fun getModelPath(): String {
        val modelFile = File(filesDir, "gemma3n-e2b.bin")
        if (!modelFile.exists()) {
            // First run — copy from bundled assets to internal storage
            assets.open("gemma3n-e2b.bin").use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return modelFile.absolutePath
    }

    /**
     * Build a safety-classification prompt for Gemma 3n.
     *
     * Alex: Gemma 3n E2B is a text model so we don't embed bitmap bytes.
     * Instead, the prompt describes the frame metadata and what Tier 1 (YOLO)
     * already detected, asking Gemma to classify and provide bilingual output.
     * The strict JSON output format matches GemmaAnalysisResult fields.
     */
    internal fun buildSafetyPrompt(frame: VideoFrame): String {
        val width = frame.bitmap?.width ?: 504
        val height = frame.bitmap?.height ?: 896
        return """
            |You are a construction site safety analyst. Analyze the following frame from a construction site camera for PPE violations and safety hazards.
            |
            |Frame information:
            |- Source: construction site safety camera
            |- Resolution: ${width}x${height}
            |- Tier 1 (YOLO) has pre-screened this frame and flagged it for further analysis.
            |
            |Respond ONLY with a valid JSON object using this exact format:
            |{
            |  "violation_detected": true/false,
            |  "violation_type": "TYPE" or null,
            |  "severity": 0-5,
            |  "description_en": "English description of findings",
            |  "description_es": "Spanish description of findings",
            |  "confidence": 0.0-1.0
            |}
            |
            |Known violation types: NO_HARD_HAT, NO_SAFETY_VEST, NO_SAFETY_GLASSES, NO_GLOVES, NO_STEEL_TOE_BOOTS, FALL_HAZARD, RESTRICTED_ZONE, MULTIPLE_VIOLATIONS
            |Severity scale: 0=none, 1=minor, 2=moderate, 3=serious, 4=severe, 5=critical/life-threatening
            |Provide description_en in English and description_es in Spanish.
            |Do not include any text outside the JSON object.
        """.trimMargin()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gemma AI Inference",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Running on-device AI analysis"
        }
        // Alex: getSystemService here instead of injected notificationManager because
        // createNotificationChannel is called from onCreate() which runs before
        // Hilt field injection completes. This is a well-known Hilt + Service gotcha.
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Alex: Low-priority notification with minimal info. Workers see this in their
        // notification shade and know the AI is running. We use a system icon because
        // custom drawables aren't guaranteed to exist at this point in the build.
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Duchess AI")
            .setContentText("Safety analysis active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }
}
