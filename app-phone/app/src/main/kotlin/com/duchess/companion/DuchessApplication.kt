package com.duchess.companion

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.duchess.companion.ble.BleGattServer
import com.duchess.companion.upload.BatchUploadScheduler
import com.meta.wearable.dat.core.Wearables
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class for the Duchess companion phone app.
 *
 * Alex: @HiltAndroidApp is the Hilt entry point. It generates a base class
 * that sets up the DI container. Without this annotation, @AndroidEntryPoint
 * on Activities/Services will crash with "Hilt component not available."
 *
 * DAT SDK and BLE initialization are DEFERRED until BLUETOOTH_CONNECT
 * permission is granted. On Android 12+ (API 31+), BLUETOOTH_CONNECT is a
 * runtime permission — calling Bluetooth APIs without it throws SecurityException
 * in Application.onCreate(), which is fatal (crashes before any Activity starts).
 *
 * Call [initializeBluetoothServices] from MainActivity after permission is granted.
 */
@HiltAndroidApp
class DuchessApplication : Application() {

    @Inject lateinit var bleGattServer: BleGattServer

    @Volatile
    var isBluetoothInitialized = false
        private set

    override fun onCreate() {
        super.onCreate()

        // Register the nightly batch upload WorkManager task. WorkManager deduplicates
        // via WORK_NAME so calling this on every launch is safe — it won't create
        // duplicate jobs. This does NOT require Bluetooth permission.
        BatchUploadScheduler.schedule(this)

        // Attempt BLE init if permission is already granted (e.g., returning user).
        // If not granted, MainActivity will call initializeBluetoothServices() after
        // the user grants permission.
        if (hasBluetoothPermission()) {
            initializeBluetoothServices()
        }
    }

    /**
     * Initialize DAT SDK and BLE GATT server. Safe to call multiple times — idempotent.
     * MUST only be called after BLUETOOTH_CONNECT permission is granted.
     */
    fun initializeBluetoothServices() {
        if (isBluetoothInitialized) return
        try {
            Wearables.initialize(this)
            bleGattServer.start()
            isBluetoothInitialized = true
        } catch (e: SecurityException) {
            // Permission not actually granted or revoked — stay uninitialized.
            // MainActivity will retry after permission grant.
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }
}
