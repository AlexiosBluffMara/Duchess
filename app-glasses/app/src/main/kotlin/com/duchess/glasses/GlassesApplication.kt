package com.duchess.glasses

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class GlassesApplication : Application() {

    companion object {
        const val CHANNEL_DETECTION = "duchess_detection"
        const val CHANNEL_ALERT = "duchess_alert"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val detectionChannel = NotificationChannel(
            CHANNEL_DETECTION,
            "PPE Detection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active PPE detection service"
        }

        val alertChannel = NotificationChannel(
            CHANNEL_ALERT,
            "Safety Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Critical safety alerts"
        }

        manager.createNotificationChannel(detectionChannel)
        manager.createNotificationChannel(alertChannel)
    }
}
