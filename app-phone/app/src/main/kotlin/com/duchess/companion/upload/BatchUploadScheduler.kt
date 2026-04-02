package com.duchess.companion.upload

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules the nightly batch upload worker.
 *
 * Alex: The nightly batch is the Tier 2 → Tier 4 data pipeline for
 * retrospective cloud analysis. It runs ONLY after shift ends (2 AM),
 * ONLY on WiFi, ONLY while charging. This is deliberate:
 *   - WiFi: cellular data on construction sites is expensive and unreliable
 *   - Charging: phones are plugged in overnight; we don't drain battery
 *   - 2 AM: well after any shift ends (even overtime), low network contention
 *
 * We use ExistingPeriodicWorkPolicy.KEEP so that if the worker is already
 * scheduled, we don't replace it. This makes schedule() idempotent — safe
 * to call from Application.onCreate() on every app launch.
 */
object BatchUploadScheduler {

    internal const val WORK_NAME = "duchess_nightly_batch_upload"

    /**
     * Schedule the nightly batch upload worker.
     * Safe to call repeatedly — uses KEEP policy to avoid duplicates.
     */
    fun schedule(context: Context) {
        val constraints = buildConstraints()
        val initialDelay = calculateDelayUntil2AM()

        val workRequest = PeriodicWorkRequestBuilder<NightlyBatchWorker>(
            24, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    /**
     * Cancel the nightly batch upload worker.
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Build work constraints: WiFi only, charging, battery not low.
     */
    internal fun buildConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .build()
    }

    /**
     * Calculate milliseconds from now until the next 2:00 AM local time.
     *
     * Alex: If it's currently before 2 AM, the delay targets today's 2 AM.
     * If it's after 2 AM, it targets tomorrow's 2 AM. This ensures the
     * first run always happens after shift ends regardless of when the
     * app is installed or the worker is first scheduled.
     */
    internal fun calculateDelayUntil2AM(): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 2)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // If 2 AM has already passed today, schedule for tomorrow
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }

        return target.timeInMillis - now.timeInMillis
    }
}
