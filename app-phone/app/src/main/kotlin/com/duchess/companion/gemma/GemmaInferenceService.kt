package com.duchess.companion.gemma

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the Gemma 4 E2B model warm during active scanning.
 *
 * Alex: This service's ONE job is Android lifecycle management:
 *   1. Hold a foreground notification so Android doesn't kill inference mid-analysis
 *   2. Run START_STICKY so the system restarts us if we're killed under memory pressure
 *   3. Drive the inactivity timer to unload the model after 5 minutes of silence
 *
 * ALL inference logic lives in GemmaInferenceEngine — which is a @Singleton that
 * can be @Inject-ed anywhere. This separation solves the "can't inject a Service"
 * problem: other classes use GemmaInferenceEngine directly; this service just keeps
 * the process alive and the notification showing.
 *
 * Why a foreground service at all?
 *   1. WorkManager has a 10-minute execution limit. Safety inference sessions last hours.
 *   2. Foreground services can hold partial wake locks during camera processing.
 *   3. The persistent notification ("AI Safety Monitoring Active") builds worker trust
 *      and satisfies OSHA transparency requirements for AI monitoring disclosure.
 *   4. START_STICKY guarantees recovery after OOM kills — safety cannot go dark silently.
 */
@AndroidEntryPoint
class GemmaInferenceService : Service() {

    companion object {
        private const val CHANNEL_ID = "duchess_gemma"
        private const val NOTIFICATION_ID = 1001
    }

    // Alex: GemmaInferenceEngine is the actual brain. This service just wraps it.
    @Inject
    lateinit var engine: GemmaInferenceEngine

    @Inject
    lateinit var notificationManager: NotificationManager

    // Alex: SupervisorJob so a failed inactivity timer doesn't cancel the whole scope.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var inactivityTimerJob: Job? = null

    /**
     * Expose the engine's state so the UI layer can observe Idle/Loading/Ready/Running/Error.
     * StreamScreen uses this to show the "AI Active" indicator.
     */
    val engineState: StateFlow<GemmaState>
        get() = engine.state

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Alex: START_STICKY = system restarts this service if it's killed for memory.
        // Safety inference must come back automatically. The alternative START_NOT_STICKY
        // would let the system kill us permanently — unacceptable for a safety app.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        inactivityTimerJob?.cancel()
        serviceScope.cancel()
        engine.unloadModel()
    }

    /**
     * Reset the 5-minute inactivity timer.
     *
     * Alex: Called by InferencePipelineCoordinator after each successful frame analysis.
     * Creates a rolling window: every analyze() call pushes the unload deadline 5 minutes
     * into the future. If the site goes quiet for 5 continuous minutes, we unload and free
     * ~1.2GB. On the next frame, GemmaInferenceEngine.analyze() reloads lazily (3-8s delay).
     */
    fun resetInactivityTimer() {
        inactivityTimerJob?.cancel()
        inactivityTimerJob = serviceScope.launch {
            delay(GemmaInferenceEngine.INACTIVITY_TIMEOUT_MS)
            engine.unloadModel()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AI Safety Monitoring",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Gemma 4 E2B on-device safety analysis"
        }
        // Alex: Must use getSystemService here (not injected notificationManager) because
        // createNotificationChannel is called from onCreate() before Hilt field injection
        // completes. Classic @AndroidEntryPoint gotcha — injection runs AFTER super.onCreate().
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Duchess AI Safety")
            .setContentText("Monitoring active — Gemma 4 E2B")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }
}
