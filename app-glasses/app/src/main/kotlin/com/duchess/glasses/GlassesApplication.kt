package com.duchess.glasses

import android.app.Application
import com.duchess.glasses.model.InferenceMode

/**
 * Custom Application class for the Duchess Glasses app on Vuzix M400.
 *
 * Alex: On Android, the Application class is the FIRST thing created and the LAST thing
 * destroyed. We use it for:
 * 1. Global uncaught exception handler — on the Vuzix, a crash means the worker loses
 *    their safety system. We want to know about crashes but we CAN'T send crash reports
 *    to Firebase (no Google Play Services) or over the internet (glasses are offline).
 *    So we log to local storage and let the companion phone pull crash logs over BLE.
 * 2. App-wide state that survives Activity recreation (like the battery receiver).
 *
 * PRIVACY REMINDER: The uncaught exception handler logs stack traces ONLY.
 * No PII. No camera frames. No detection data. No worker identifiers.
 * If there's PII in your stack trace, you put PII somewhere it shouldn't be.
 */
class GlassesApplication : Application() {

    // Alex: These are lazy-initialized because Context isn't fully available in the
    // Application constructor. They're safe to access after onCreate() completes.
    // Using lateinit instead of lazy because we need explicit lifecycle control.

    private var defaultExceptionHandler: Thread.UncaughtExceptionHandler? = null

    override fun onCreate() {
        super.onCreate()

        setupUncaughtExceptionHandler()
    }

    /**
     * Installs a global uncaught exception handler that logs crashes to local storage.
     *
     * Alex: We chain with the default handler so Android's normal crash behavior
     * still works (process termination, ANR dialog). We just intercept first to
     * write the crash log where the companion phone can pull it via BLE.
     *
     * The crash log file is stored in the app's internal storage (getFilesDir()).
     * It's NOT in external storage (no SD card on the M400). The companion phone's
     * BleGattServer can request the crash log via a dedicated characteristic.
     *
     * PRIVACY: Stack traces only. No PII. No user data. No detection frames.
     * If a stack trace contains PII, the bug is in the code that put PII there,
     * not in this handler.
     */
    private fun setupUncaughtExceptionHandler() {
        defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Alex: Write crash log to internal storage. We use a fixed filename
                // so the companion phone knows where to look. Each crash overwrites
                // the previous one — we only care about the LATEST crash for debugging.
                // If we need crash history, the phone can pull and archive them.
                val crashFile = java.io.File(filesDir, CRASH_LOG_FILENAME)
                val timestamp = System.currentTimeMillis()
                val stackTrace = throwable.stackTraceToString()

                // Alex: Simple format. No JSON library available on AOSP without
                // adding a dependency, and we don't want to add complexity here.
                // The phone-side parser handles this format.
                val logContent = buildString {
                    appendLine("=== DUCHESS GLASSES CRASH LOG ===")
                    appendLine("timestamp=$timestamp")
                    appendLine("thread=${thread.name}")
                    appendLine("exception=${throwable.javaClass.name}")
                    appendLine("message=${throwable.message}")
                    appendLine("--- STACK TRACE ---")
                    appendLine(stackTrace)
                }

                crashFile.writeText(logContent)
            } catch (_: Exception) {
                // Alex: If we can't write the crash log, there's nothing we can do.
                // Don't throw from an exception handler — that's a one-way ticket
                // to an infinite crash loop. Just let the default handler take over.
            }

            // Alex: Delegate to the default handler for normal crash behavior.
            // This terminates the process and (on debug builds) shows the crash dialog.
            defaultExceptionHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        // Alex: On the Vuzix M400 with 6GB RAM, if we get onLowMemory() it means
        // the system is in serious trouble. The only thing we can do is suggest the
        // detection pipeline to back off. The BatteryAwareScheduler handles this
        // through its own battery monitoring, but onLowMemory() is an additional
        // signal that we should reduce memory pressure.
        //
        // We don't hold any static caches to clear here — the TFLite interpreter
        // and camera buffer pool are managed by their respective classes.
        // This callback is mostly a diagnostic signal.
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Alex: Same story as onLowMemory(). On the M400, we should NEVER hit
        // TRIM_MEMORY_RUNNING_CRITICAL because our total footprint should be <200MB.
        // If we do, something is leaking (probably RenderScript allocations or
        // undestroyed TFLite interpreters). Check for those first.
    }

    companion object {
        // Alex: Crash log filename. The companion phone's BLE service knows this name.
        // Don't change it without updating the phone-side parser.
        const val CRASH_LOG_FILENAME = "duchess_crash.log"
    }
}
