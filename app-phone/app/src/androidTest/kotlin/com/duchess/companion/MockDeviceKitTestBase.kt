package com.duchess.companion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import org.junit.After
import org.junit.Before

/**
 * Reusable test base for instrumented tests that need a simulated
 * Meta wearable device (MockRaybanMeta).
 *
 * Usage: extend this class, then call [mockDevice] to interact
 * with the paired mock glasses.
 */
abstract class MockDeviceKitTestBase {

    protected lateinit var mockDevice: MockDeviceKit

    @Before
    open fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Wearables.initialize(context)

        // Create a mock Ray-Ban Meta device for testing
        mockDevice = MockDeviceKit.create()

        // Pair the mock device so Wearables SDK sees it as connected
        mockDevice.pair()
        mockDevice.grantAllPermissions()
    }

    @After
    open fun tearDown() {
        mockDevice.reset()
    }
}
