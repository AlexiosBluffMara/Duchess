package com.duchess.companion.gemma

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.meta.wearable.dat.camera.types.VideoFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// TODO-PRINCIPAL: This class is the single most critical path in the app.
// If GemmaInferenceEngine is slow, wrong, or leaks memory, the entire product fails.
// Current issues I'd flag in a code review:
//   1. No retry/backoff on model load failure — if the model file is corrupted mid-download,
//      we enter GemmaState.Error permanently and the user has to force-kill the app.
//   2. The Mutex serializes ALL inference. At 1 frame/sec this is fine, but if someone
//      cranks the throttle to 5fps we'll queue up and OOM from queued bitmaps. Need a
//      bounded channel or drop-oldest policy.
//   3. parseGemmaOutput() uses JSONObject (Android's built-in) which silently returns null
//      on malformed JSON instead of throwing. This masks Gemma output format drift.
//   4. No telemetry. We have zero visibility into inference latency distribution, failure
//      rate, or memory pressure in production. Add Firebase Performance traces.
//   5. SAFETY_PROMPT is a giant string constant. It should be loaded from assets/ so we
//      can A/B test prompt variants without recompiling.
//
// TODO-ML-PROF: The real question is whether MediaPipe LlmInference even uses the
// Tensor G4 NPU effectively. Google's docs claim "hardware acceleration" but don't
// specify whether the NNAPI delegate actually routes MoE expert dispatch to the NPU
// or falls back to GPU shaders. We need to benchmark with:
//   - systrace to confirm NPU activity during inference
//   - Memory profiler to measure actual loaded model footprint (claimed ~1.4GB)
//   - Thermal throttling curve: sustained inference at 1fps for 30min on Pixel 9 Fold
//   - Compare MediaPipe vs llama.cpp Android backend on same model/hardware
// Also: the 0.1 temperature is cargo-culted. For classification tasks with function
// calling, temperature=0.0 (greedy) is correct. 0.1 introduces noise. The "same answer
// every time" comment is wrong — 0.1 is NOT deterministic, it's just low-entropy.
// For the Unsloth fine-tuned model: we need to verify that QLoRA adapters don't degrade
// the vision pathway. Unsloth's Dynamic QLoRA claims 0% accuracy loss but that's measured
// on text benchmarks, not multimodal. Run the vision PPE eval BEFORE and AFTER adapter merge.

/**
 * Injectable singleton encapsulating the Gemma 4 E2B inference logic.
 *
 * Alex: Why extract this from GemmaInferenceService?
 *   GemmaInferenceService is an Android Service — you can't @Inject it into
 *   ViewModels or other singletons. The fix is to extract the actual inference
 *   brain here as a @Singleton, so InferencePipelineCoordinator and anything else
 *   can use it directly. GemmaInferenceService keeps the foreground notification
 *   lifecycle (START_STICKY, persistent notification) and delegates here.
 *
 * KEY FIX vs. the old buildSafetyPrompt() approach:
 *   The old code passed only frame dimensions (width×height) as text to Gemma.
 *   Gemma 4 E2B has NATIVE VISION — we must pass the actual bitmap. This class
 *   uses the MediaPipe session API (session.addImage + session.addQueryChunk)
 *   to pass the real frame as MPImage alongside the text prompt.
 *
 * PRIVACY: Frame bitmaps never leave analyzeWithVision(). Only the typed
 * GemmaAnalysisResult (violation metadata, bilingual text, no image data) exits.
 */
@Singleton
class GemmaInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Alex: Temperature 0.1 = deterministic output.
        // Safety classification CANNOT be creative. "Was that a hard hat?" needs
        // the same answer every time, not a bell curve of different answers.
        const val INFERENCE_TEMPERATURE = 0.1f

        // Alex: 5 minutes inactivity → unload to free ~1.2GB RAM.
        // Used by GemmaInferenceService to drive the inactivity timer.
        const val INACTIVITY_TIMEOUT_MS = 5 * 60 * 1000L

        // Alex: Max 1 image per inference call. Gemma 4 E2B supports multi-image
        // but we only need one frame at a time for PPE detection.
        const val MAX_IMAGES = 1

        // Alex: The safety analysis prompt. Kept as a constant so we don't
        // rebuild the string on every frame (minor GC pressure savings at 24fps).
        // Structure: role + output format + violation taxonomy + severity scale.
        // Gemma 4 is asked to produce ONLY JSON — no markdown, no prose.
        // The extractJson() method handles cases where it wraps it anyway.
        val SAFETY_PROMPT: String = """
            |You are a construction site safety analyst. Analyze this image for PPE violations and safety hazards.
            |
            |Respond ONLY with a valid JSON object in this exact format, no other text:
            |{
            |  "violation_detected": true or false,
            |  "violation_type": "TYPE" or null,
            |  "severity": 0,
            |  "description_en": "English description of findings",
            |  "description_es": "Spanish description of findings",
            |  "confidence": 0.0
            |}
            |
            |Violation types: NO_HARD_HAT, NO_SAFETY_VEST, NO_SAFETY_GLASSES, NO_GLOVES, NO_STEEL_TOE_BOOTS, FALL_HAZARD, RESTRICTED_ZONE, MULTIPLE_VIOLATIONS
            |Severity scale: 0=none, 1=minor, 2=moderate, 3=serious, 4=severe, 5=critical/life-threatening
            |Fill description_en in English and description_es in Spanish.
        """.trimMargin()
    }

    private val _state = MutableStateFlow<GemmaState>(GemmaState.Idle)
    val state: StateFlow<GemmaState> = _state.asStateFlow()

    // Alex: Nullable because we lazy-load. The mutex serializes access so two
    // coroutines can't race to load the model simultaneously.
    private var llmInference: LlmInference? = null
    private val inferenceMutex = Mutex()

    /**
     * Load the Gemma 4 E2B model into memory. Idempotent — safe to call multiple times.
     *
     * Alex: setMaxImages(MAX_IMAGES) is the critical addition over the old code.
     * Without it, the MediaPipe runtime refuses to accept MPImage inputs from
     * session.addImage() and throws "model does not support image input."
     * Gemma 4 E2B was trained with vision — we just need to tell the runtime.
     */
    suspend fun loadModel() {
        if (_state.value == GemmaState.Ready || _state.value == GemmaState.Loading) return
        _state.value = GemmaState.Loading

        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(getModelPath())
                .setMaxTokens(512)
                .setMaxNumImages(MAX_IMAGES)
                .build()
            llmInference = LlmInference.createFromOptions(context, options)
            _state.value = GemmaState.Ready
        } catch (e: Exception) {
            // Alex: Never log the model path — reveals internal storage structure.
            _state.value = GemmaState.Error("Model load failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Unload the model from memory, freeing ~1.2GB RAM.
     *
     * Alex: Called by the inactivity timer in GemmaInferenceService. After this,
     * the next analyze() call will trigger a fresh lazy load (3-8 seconds on Pixel 9 Fold).
     */
    fun unloadModel() {
        llmInference?.close()
        llmInference = null
        _state.value = GemmaState.Idle
    }

    /**
     * Analyze a video frame for PPE violations using Gemma 4 E2B multimodal vision.
     *
     * Alex: This is the fixed version of the old analyze(). The old version called
     *   val prompt = buildSafetyPrompt(frame)  // just text with frame width/height
     *   val rawOutput = llmInference!!.generateResponse(prompt)  // TEXT ONLY
     * which completely ignored the actual frame pixels. Gemma saw "resolution: 504x896"
     * as text and had to hallucinate what might be in such a frame. Useless.
     *
     * This version uses the session API:
     *   session.addImage(MPImage)   ← actual bitmap pixels
     *   session.addQueryChunk(text) ← the analysis prompt
     *   session.generateResponse()  ← multimodal output
     *
     * Falls back to text-only if the bitmap is null (e.g., frame arrived before
     * the Bluetooth Classic pipe could decode it).
     *
     * PRIVACY: The bitmap is passed to the LOCAL model only. It is not serialized,
     * logged, or transmitted anywhere. Only the parsed result leaves this method.
     */
    suspend fun analyze(frame: VideoFrame): GemmaAnalysisResult {
        return inferenceMutex.withLock {
            if (_state.value != GemmaState.Ready) {
                loadModel()
            }

            val engine = llmInference ?: return@withLock GemmaAnalysisResult(
                violationDetected = false,
                violationType = null,
                severity = 0,
                descriptionEn = "Model not available",
                descriptionEs = "Modelo no disponible",
                confidence = 0.0
            )

            _state.value = GemmaState.Running

            val result = runCatching {
                analyzeWithVision(engine, frame.bitmap)
            }.getOrElse { e ->
                // Alex: getOrElse catches ANYTHING thrown inside runCatching, including
                // UnsupportedOperationException if a device doesn't support vision input.
                // We return a safe "no violation" result rather than crashing.
                GemmaAnalysisResult(
                    violationDetected = false,
                    violationType = null,
                    severity = 0,
                    descriptionEn = "Analysis error: ${e.javaClass.simpleName}",
                    descriptionEs = "Error de análisis: ${e.javaClass.simpleName}",
                    confidence = 0.0
                )
            }

            _state.value = GemmaState.Ready
            result
        }
    }

    /**
     * Run the session-based multimodal inference.
     *
     * Alex: Opens a new session per call. Sessions are lightweight — the heavy
     * resource is the model itself (already loaded by loadModel()). Each session
     * holds the current conversation context. We use single-turn inference:
     * one image + one prompt → one response → session closed.
     *
     * Why close() in finally? Sessions hold a KV cache and tokenizer state.
     * Forgetting to close leaks ~10-50MB per unclosed session. At 1 FPS inference
     * that's a memory leak of ~50MB/second. Always close in finally.
     *
     * Vision input: session.addImage(MPImage) passes the actual bitmap pixels to
     * the model's visual encoder (ViT patch embedding for Gemma 4 E2B). This runs
     * BEFORE the text generation, encoding the image into embedding space.
     *
     * Text-only fallback: If bitmap is null, we skip addImage() and fall back to
     * the text-only path. The model will still respond but with low confidence
     * (it has no image data). The calling layer may filter by confidence threshold.
     */
    private fun analyzeWithVision(engine: LlmInference, bitmap: Bitmap?): GemmaAnalysisResult {
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setGraphOptions(
                GraphOptions.builder().setEnableVisionModality(true).build()
            )
            .build()
        val session = LlmInferenceSession.createFromOptions(engine, sessionOptions)
        try {
            if (bitmap != null) {
                // Alex: BitmapImageBuilder wraps the Bitmap in MediaPipe's MPImage format.
                // addImage() feeds it to the model's visual encoder before text generation.
                val mpImage = BitmapImageBuilder(bitmap).build()
                session.addImage(mpImage)
            }
            // Alex: addQueryChunk() buffers the text prompt. We call generateResponse()
            // synchronously — it blocks until generation completes (~500ms-2s).
            session.addQueryChunk(SAFETY_PROMPT)
            val rawOutput = session.generateResponse()
            return parseGemmaOutput(rawOutput)
        } finally {
            // Alex: ALWAYS close in finally — see javadoc above.
            session.close()
        }
    }

    /**
     * Parse the raw JSON output from Gemma 4 into a typed GemmaAnalysisResult.
     *
     * Alex: Defensive parsing:
     *   - extractJson() strips markdown code fences (Gemma sometimes wraps in ```json)
     *   - optBoolean/optInt/optString have safe defaults for missing fields
     *   - coerceIn() clamps severity and confidence to valid ranges
     *   - null violation_type returned as null (not empty string) for clean downstream logic
     *   - The entire parse is wrapped in try/catch — malformed JSON returns "no violation"
     *
     * Internal visibility so tests can call it directly with synthetic JSON strings.
     */
    internal fun parseGemmaOutput(rawJson: String): GemmaAnalysisResult {
        val json = extractJson(rawJson)
        return try {
            val obj = JSONObject(json)
            GemmaAnalysisResult(
                violationDetected = obj.optBoolean("violation_detected", false),
                violationType = if (obj.has("violation_type") && !obj.isNull("violation_type")) {
                    obj.optString("violation_type").takeIf { it.isNotBlank() && it != "null" }
                } else {
                    null
                },
                severity = obj.optInt("severity", 0).coerceIn(0, 5),
                descriptionEn = obj.optString("description_en", "Analysis complete"),
                descriptionEs = obj.optString("description_es", "Análisis completo"),
                confidence = obj.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
            )
        } catch (e: Exception) {
            GemmaAnalysisResult(
                violationDetected = false,
                violationType = null,
                severity = 0,
                descriptionEn = "Unable to parse model output",
                descriptionEs = "No se pudo analizar la salida del modelo",
                confidence = 0.0
            )
        }
    }

    /**
     * Extract a JSON object from raw model output, stripping markdown fences if present.
     *
     * Alex: Gemma 4 sometimes wraps JSON in ```json ... ``` even with explicit instructions
     * not to. This is a known quirk of instruction-tuned models trained on code examples.
     * We handle:
     *   1. ```json { ... } ``` — explicit json fence
     *   2. ``` { ... } ``` — generic fence
     *   3. { ... } with leading/trailing prose — find first { and last }
     *   4. Clean JSON — returned as-is
     *
     * Internal visibility for unit testing.
     */
    internal fun extractJson(raw: String): String {
        val trimmed = raw.trim()

        // Try explicit code fence first: ```json ... ``` or ``` ... ```
        val fenceRegex = Regex("""```(?:json)?\s*([\s\S]*?)\s*```""")
        fenceRegex.find(trimmed)?.let { return it.groupValues[1].trim() }

        // Find first { and last } to handle leading/trailing prose
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else trimmed
    }

    /**
     * Resolve the model file path, copying from assets on first run if needed.
     *
     * Alex: Strategy:
     *   1. Check internal storage (filesDir/gemma4-e2b.bin) — used by OTA update path
     *   2. If absent, copy from bundled assets — first-run path
     *   3. Never expose the path in logs (internal storage structure is private)
     *
     * OTA update path: The update manager downloads a new .bin to filesDir before
     * deleting the old one. On next analyze() → loadModel() we pick up the new file.
     *
     * Internal visibility for tests that mock asset loading.
     */
    internal fun getModelPath(): String {
        val modelFile = File(context.filesDir, "gemma4-e2b.bin")
        if (!modelFile.exists()) {
            context.assets.open("gemma4-e2b.bin").use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return modelFile.absolutePath
    }
}
