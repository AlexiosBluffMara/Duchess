package com.duchess.companion.di

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing Android system services and app-wide singletons.
 *
 * Alex: We centralize all system service lookups here instead of calling
 * getSystemService() scattered across the codebase. Benefits:
 *   1. Single place to null-check (system services CAN be null)
 *   2. @Singleton ensures we reuse the same instance everywhere
 *   3. Easy to swap with fakes in tests via Hilt testing modules
 *   4. Explicit dependency graph — you can see what the app needs at a glance
 *
 * InstallIn(SingletonComponent) means these live for the entire Application lifetime.
 * Don't put Activity-scoped things here — use ActivityComponent for those.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provide the system NotificationManager.
     *
     * Alex: Used by GemmaInferenceService for foreground notification,
     * and by the alert system for PPE violation push notifications.
     * We use three notification channels: CRITICAL, WARNING, INFO.
     * The channels themselves are created at notification time, not here.
     */
    @Provides
    @Singleton
    fun provideNotificationManager(
        @ApplicationContext context: Context
    ): NotificationManager {
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    /**
     * Provide the system BluetoothManager.
     *
     * Alex: Used by BleGattServer to open the GATT server. BluetoothManager
     * is the modern way to access Bluetooth on Android — don't use the deprecated
     * BluetoothAdapter.getDefaultAdapter() anymore.
     *
     * This CAN be null on devices without Bluetooth hardware, but since we
     * require BLE in the manifest (<uses-feature bluetooth_le required="true">),
     * Play Store filters those out. Still, the BleGattServer handles null gracefully.
     */
    @Provides
    @Singleton
    fun provideBluetoothManager(
        @ApplicationContext context: Context
    ): BluetoothManager? {
        // Alex: Returning nullable here because getSystemService can return null
        // on devices where BT hardware is present but the service failed to start.
        // Rare, but I've seen it on cheap Chinese tablets running AOSP forks.
        return context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }
}
