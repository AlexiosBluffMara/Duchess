package com.duchess.companion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.MockRaybanMeta
import org.junit.After
import org.junit.Before

/**
 * Reusable test base for instrumented tests that need a simulated
 * Meta wearable device (MockRaybanMeta).
 *
 * Alex: MockDeviceKit is the DAT SDK's answer to "I don't have $300 glasses
 * on every CI machine." It simulates the full device lifecycle — pairing,
 * connection state, camera feeds, the works. The test flow is:
 *   1. Initialize Wearables SDK (just like DuchessApplication does)
 *   2. Get a MockDeviceKit instance
 *   3. Pair a MockRaybanMeta device
 *   4. Simulate wearing the glasses (powerOn → unfold → don)
 *   5. Run your test
 *   6. Reset everything in tearDown so the next test starts clean
 *
 * The mock device feeds can be configured with video/image URIs for camera
 * testing. See MockCameraKit.setCameraFeed() and setCapturedImage().
 *
 * Usage: extend this class, then call [mockDevice] to interact with the
 * paired mock glasses. The device is already powered on, unfolded, and donned.
 */
abstract class MockDeviceKitTestBase {

    protected lateinit var targetContext: Context
    protected lateinit var mockDeviceKit: MockDeviceKit
    protected lateinit var mockDevice: MockRaybanMeta

    @Before
    open fun setUp() {
        targetContext = ApplicationProvider.getApplicationContext()
        Wearables.initialize(targetContext)

        // Alex: getInstance() returns the singleton MockDeviceKit for this process.
        // pairRaybanMeta() creates a new simulated device and registers it with
        // the Wearables SDK so it appears as a connected device.
        mockDeviceKit = MockDeviceKit.getInstance(targetContext)
        mockDevice = mockDeviceKit.pairRaybanMeta()

        // Alex: Simulate a worker putting on their glasses.
        // The state transitions are: powerOn → unfold → don.
        // "don" = wearing the glasses on your face. "doff" = taking them off.
        // The DAT SDK uses these terms because they're standard in wearable UX.
        mockDevice.powerOn()
        mockDevice.unfold()
        mockDevice.don()
    }

    @After
    open fun tearDown() {
        // Alex: reset() removes all mock devices and clears state.
        // This is critical for test isolation — without it, a paired device
        // from test A would leak into test B and cause flaky failures.
        mockDeviceKit.reset()
    }
}
