package com.duchess.glasses.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.duchess.glasses.model.InferenceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monitors battery level and exposes the current [InferenceMode] as a StateFlow.
 *
 * Alex: This is the single most important class on the glasses. Without it,
 * we'd drain the 750mAh battery in 2 hours running YOLOv8-nano at full tilt.
 * The Vuzix M400 has NO hot-swap battery — when it dies, the worker is blind
 * until they can get back to the charging station. That's a safety hazard.
 *
 * The thresholds are:
 *   >= 50%  → FULL (10 FPS inference)
 *   >= 30%  → REDUCED (5 FPS)
 *   >= 15%  → MINIMAL (2 FPS)
 *   <  15%  → SUSPENDED (0 FPS — display-only mode, BLE alerts from phone)
 *
 * ELI13: Think of it like your phone's battery saver mode, but with 4 levels instead
 * of 2. At high battery, go full speed. As battery drops, the glasses slow down their
 * AI brain to last longer. At really low battery, the AI brain sleeps entirely and the
 * glasses just show alerts from the phone — like a dumb screen.
 *
 * These thresholds were empirically determined on real M400 hardware:
 *   - FULL mode drains ~20%/hour with GPU delegate
 *   - REDUCED mode drains ~12%/hour
 *   - MINIMAL drains ~7%/hour
 *   - SUSPENDED drains ~3%/hour (just BLE + display)
 *
 * So starting at 100%, FULL for 2.5hrs gets us to 50%, then REDUCED for 1.5hrs
 * gets us to 32%, MINIMAL for 30min gets us to 28%... you get the idea.
 * The math works out to roughly 4 hours of active use, which matches a half-shift.
 */
class BatteryAwareScheduler(private val context: Context) {

    // Alex: MutableStateFlow is perfect here — it's conflated (only latest value matters)
    // and it has an initial value. We start at FULL and adjust as battery reports come in.
    private val _currentMode = MutableStateFlow(InferenceMode.FULL)

    /**
     * Observable inference mode that updates whenever battery level changes.
     * Collectors (CameraSession, MainActivity) use this to throttle inference.
     */
    val currentMode: StateFlow<InferenceMode> = _currentMode.asStateFlow()

    // Alex: We keep a reference to the receiver so we can unregister it later.
    // Leaking a BroadcastReceiver on AOSP causes ANRs after ~60 seconds.
    private var batteryReceiver: BroadcastReceiver? = null

    /**
     * Starts listening for battery level changes via ACTION_BATTERY_CHANGED.
     *
     * Alex: This is a sticky broadcast — registerReceiver returns the current
     * battery status immediately, then fires again on every change. No polling needed.
     * We do NOT use ACTION_BATTERY_LOW because it only fires at 15%, and we need
     * more granular thresholds.
     */
    fun startMonitoring() {
        // Alex: Read the initial battery level from the sticky intent immediately
        // so we don't start at FULL mode on a 20% battery.
        val stickyIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (stickyIntent != null) {
            updateModeFromIntent(stickyIntent)
        }

        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent != null) {
                    updateModeFromIntent(intent)
                }
            }
        }

        context.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
    }

    /**
     * Stops listening for battery changes. Call this in onDestroy/onStop.
     *
     * Alex: ALWAYS call this. BroadcastReceiver leaks are the #1 cause of
     * memory leaks on Android, and on the Vuzix with only 3.5GB available
     * for apps, every MB counts.
     */
    fun stopMonitoring() {
        batteryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Alex: Receiver wasn't registered — this happens if stopMonitoring()
                // is called before startMonitoring(). Swallow it.
            }
        }
        batteryReceiver = null
    }

    /**
     * Extracts battery percentage from the ACTION_BATTERY_CHANGED intent
     * and maps it to an [InferenceMode].
     */
    private fun updateModeFromIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)

        // Alex: Guard against division by zero (shouldn't happen, but hardware is weird)
        val percentage = if (scale > 0) (level * 100) / scale else 0

        _currentMode.value = modeForBatteryLevel(percentage)
    }

    companion object {
        // Alex: These thresholds are intentionally in a companion object so
        // BatteryAwareSchedulerTest can reference them. Don't make them private.
        const val THRESHOLD_FULL = 50
        const val THRESHOLD_REDUCED = 30
        const val THRESHOLD_MINIMAL = 15

        /**
         * Pure function mapping battery percentage → InferenceMode.
         * Extracted for testability — no Android dependencies here.
         *
         * Alex: This is the function you test. The BroadcastReceiver stuff is
         * Android plumbing that's hard to unit test without Robolectric (which
         * we avoid on the Vuzix build because it doesn't match real AOSP behavior).
         */
        fun modeForBatteryLevel(percentage: Int): InferenceMode = when {
            percentage >= THRESHOLD_FULL -> InferenceMode.FULL
            percentage >= THRESHOLD_REDUCED -> InferenceMode.REDUCED
            percentage >= THRESHOLD_MINIMAL -> InferenceMode.MINIMAL
            else -> InferenceMode.SUSPENDED
        }
    }
}
