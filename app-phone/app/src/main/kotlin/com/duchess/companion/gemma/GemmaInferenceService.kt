package com.duchess.companion.gemma

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.meta.wearable.dat.camera.types.VideoFrame
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

sealed interface GemmaState {
    data object Idle : GemmaState
    data object Loading : GemmaState
    data object Ready : GemmaState
    data object Running : GemmaState
    data class Error(val message: String) : GemmaState
}

@AndroidEntryPoint
class GemmaInferenceService : Service() {

    companion object {
        private const val CHANNEL_ID = "duchess_gemma"
        private const val NOTIFICATION_ID = 1001
    }

    @Inject
    lateinit var notificationManager: NotificationManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<GemmaState>(GemmaState.Idle)
    val state: StateFlow<GemmaState> = _state.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        _state.value = GemmaState.Idle
    }

    /**
     * Load the Gemma 3n model into memory.
     * Called lazily on first inference request, not at startup (saves memory).
     */
    suspend fun loadModel() {
        _state.value = GemmaState.Loading
        // TODO: Load Gemma 3n E2B model via MediaPipe LLM Inference API
        // Model path: loaded from app assets or downloaded on first run
        _state.value = GemmaState.Ready
    }

    /**
     * Analyze a video frame for safety violations.
     * Returns a stub JSON result with EN/ES descriptions.
     *
     * PRIVACY: Input frame is processed on-device only. No data leaves the device.
     */
    suspend fun analyze(frame: VideoFrame): String {
        if (_state.value != GemmaState.Ready) {
            loadModel()
        }

        _state.value = GemmaState.Running

        // TODO: Run actual Gemma 3n inference on frame
        // - Convert VideoFrame bitmap to model input tensor
        // - Run inference with temperature=0.1 for deterministic safety output
        // - Parse structured JSON response
        val stubResult = """
            {
                "violation_detected": false,
                "violation_type": null,
                "severity": 0,
                "description_en": "No safety violations detected",
                "description_es": "No se detectaron violaciones de seguridad",
                "confidence": 0.0
            }
        """.trimIndent()

        _state.value = GemmaState.Ready
        return stubResult
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Gemma AI Inference",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Running on-device AI analysis"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Duchess AI")
            .setContentText("Safety analysis active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }
}
