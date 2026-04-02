package com.duchess.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test for the end-to-end streaming pipeline using MockDeviceKit.
 *
 * Alex: This test runs on a REAL Android device or emulator (androidTest/).
 * It uses MockDeviceKit to simulate Meta glasses without physical hardware.
 * The flow is:
 *   1. MockDeviceKitTestBase sets up a paired, powered-on mock device
 *   2. We configure a mock camera feed
 *   3. Start a stream session via the real Wearables SDK
 *   4. Collect at least one frame
 *   5. Verify the frame has a non-null bitmap
 *   6. Stop the session cleanly
 *
 * This tests the REAL DAT SDK code path, not mocks. The only simulated part
 * is the physical glasses hardware. This catches integration issues like:
 *   - Wrong API signatures
 *   - Incorrect StreamConfiguration values
 *   - State machine race conditions
 *   - Frame delivery failures
 *
 * These tests are slower (~5-10s each) because they go through the full
 * SDK initialization and BLE simulation stack. Run them on CI against a
 * real Android device, not just the emulator (BLE emulation is flaky).
 */
@RunWith(AndroidJUnit4::class)
class StreamIntegrationTest : MockDeviceKitTestBase() {

    @Test
    fun endToEndStreamReceivesFrame() = runTest {
        // Alex: Configure the mock camera with a test feed.
        // In a real test, we'd point this at a video file in androidTest/assets.
        // The mock device simulates frame delivery through the SDK's normal pipeline.
        val camera = mockDevice.getCameraKit()
        // Note: setCameraFeed requires a URI to a video file.
        // For this test, we rely on the mock device's default behavior
        // which generates synthetic frames when streaming is active.

        // Alex: Start a stream session using the REAL Wearables SDK API.
        // This is the same code path that StreamViewModel uses.
        // AutoDeviceSelector will find our mock device automatically.
        val config = StreamConfiguration(
            videoQuality = VideoQuality.MEDIUM,
            frameRate = 24
        )

        val result = Wearables.startStreamSession(
            context = targetContext,
            deviceSelector = AutoDeviceSelector(),
            streamConfiguration = config
        )

        // Alex: DatResult.fold — NEVER getOrThrow(), even in tests.
        // If the session fails to start, we want a clear assertion error
        // with the SDK error message, not a generic exception.
        var session: StreamSession? = null
        result.fold(
            onSuccess = { session = it },
            onFailure = { error ->
                throw AssertionError("Failed to start stream session: $error")
            }
        )

        assertNotNull("Session should not be null after successful start", session)
        val activeSession = session!!

        // Alex: Wait for the session to reach STREAMING state.
        // The state machine goes: STARTING → STARTED → STREAMING.
        // We use a timeout because if mock setup is wrong, this would hang forever.
        val streamingState = withTimeoutOrNull(10_000L) {
            activeSession.state.first { it == StreamSessionState.STREAMING }
        }
        assertNotNull(
            "Session should reach STREAMING state within 10 seconds",
            streamingState
        )

        // Alex: Collect the first frame from the video stream.
        // The mock device should start delivering frames once STREAMING.
        // We use firstOrNull with a timeout to avoid hanging on broken mocks.
        val frame = withTimeoutOrNull(10_000L) {
            activeSession.videoStream.firstOrNull()
        }
        assertNotNull(
            "Should receive at least one video frame from mock device",
            frame
        )
        assertNotNull(
            "Video frame should contain a non-null bitmap",
            frame?.bitmap
        )

        // Alex: Verify the bitmap has reasonable dimensions.
        // MEDIUM quality is 504x896 per the DAT SDK docs.
        val bitmap = frame!!.bitmap
        assertTrue(
            "Frame width should be > 0, was ${bitmap.width}",
            bitmap.width > 0
        )
        assertTrue(
            "Frame height should be > 0, was ${bitmap.height}",
            bitmap.height > 0
        )

        // Alex: Stop the session cleanly. This releases the BLE connection
        // and stops frame delivery. After stop(), the session transitions to
        // STOPPING → STOPPED → CLOSED.
        activeSession.stop()

        // Alex: Verify the session reaches a terminal state.
        val terminalState = withTimeoutOrNull(5_000L) {
            activeSession.state.first {
                it == StreamSessionState.STOPPED || it == StreamSessionState.CLOSED
            }
        }
        assertNotNull(
            "Session should reach STOPPED or CLOSED state after stop()",
            terminalState
        )
    }

    @Test
    fun startStreamWithAutoDeviceSelectorFindsMockDevice() = runTest {
        // Alex: Verifies that AutoDeviceSelector discovers the mock device
        // that MockDeviceKitTestBase paired in setUp(). If this fails, it
        // means the mock device isn't being registered properly with the SDK.
        val config = StreamConfiguration(
            videoQuality = VideoQuality.LOW,  // LOW for faster test execution
            frameRate = 15
        )

        val result = Wearables.startStreamSession(
            context = targetContext,
            deviceSelector = AutoDeviceSelector(),
            streamConfiguration = config
        )

        var succeeded = false
        result.fold(
            onSuccess = { session ->
                succeeded = true
                // Clean up
                session.stop()
            },
            onFailure = { error ->
                // Alex: In some test environments, the mock device may not be
                // fully ready. We still want this test to be meaningful.
                throw AssertionError(
                    "AutoDeviceSelector should find the mock device: $error"
                )
            }
        )

        assertTrue("Stream session should start successfully", succeeded)
    }
}
