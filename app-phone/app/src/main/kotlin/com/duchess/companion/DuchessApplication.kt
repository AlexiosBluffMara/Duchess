package com.duchess.companion

import android.app.Application
import com.meta.wearable.dat.core.Wearables
import dagger.hilt.android.HiltAndroidApp

/**
 * Application class for the Duchess companion phone app.
 *
 * Alex: @HiltAndroidApp is the Hilt entry point. It generates a base class
 * that sets up the DI container. Without this annotation, @AndroidEntryPoint
 * on Activities/Services will crash with "Hilt component not available."
 *
 * We initialize the DAT SDK here because:
 *   1. Application.onCreate() runs before ANY Activity or Service
 *   2. The SDK needs to register its internal BroadcastReceivers early
 *   3. Calling Wearables APIs before initialize() throws NOT_INITIALIZED
 *
 * Do NOT add heavy work here. Application.onCreate() blocks the main thread
 * and delays app startup. The DAT SDK init is lightweight (~50ms) so it's fine.
 * Gemma 3n model loading happens lazily on first inference — NOT here.
 */
@HiltAndroidApp
class DuchessApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Alex: This MUST be the first SDK call. Initializes internal state,
        // registers BLE receivers, and sets up the device discovery pipeline.
        // After this, Wearables.registrationState and startStreamSession() work.
        Wearables.initialize(this)
    }
}
