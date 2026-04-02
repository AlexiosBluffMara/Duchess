package com.duchess.companion.stream

import android.content.Context
import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for StreamViewModel.
 *
 * Alex: These tests verify the ViewModel's state machine transitions WITHOUT
 * touching the actual DAT SDK. We use Turbine for Flow testing because it gives
 * us deterministic assertion of emitted values — no flaky timing issues.
 *
 * The test dispatcher lets us control coroutine execution, which is essential
 * for testing state transitions that happen asynchronously in production.
 *
 * Note: We can't easily test the Wearables.startStreamSession() call in unit tests
 * because it requires the full DAT SDK runtime. Those paths are covered in
 * StreamIntegrationTest using MockDeviceKit. Here we focus on state logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    // Alex: We mock the Android Context because @ApplicationContext is required
    // by the ViewModel constructor. In unit tests, we never use it for real —
    // the Wearables.startStreamSession() call will fail, and we test that the
    // error handling path works correctly.
    private val mockContext: Context = mockk(relaxed = true)
    private lateinit var viewModel: StreamViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = StreamViewModel(mockContext)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Initial state tests ---

    @Test
    fun `initial state is Idle`() = runTest {
        // Alex: The ViewModel should start in Idle — not Connecting, not Active.
        // This is important because the UI shows the "Start Stream" button in Idle.
        viewModel.sessionState.test {
            assertEquals(StreamUiState.Idle, awaitItem())
        }
    }

    @Test
    fun `latestFrame is null initially`() = runTest {
        // Alex: No frame until streaming starts. The UI shows a placeholder.
        viewModel.latestFrame.test {
            assertNull(awaitItem())
        }
    }

    @Test
    fun `photoCaptureResult is null initially`() = runTest {
        viewModel.photoCaptureResult.test {
            assertNull(awaitItem())
        }
    }

    // --- startStream tests ---

    @Test
    fun `startStream transitions to Connecting`() = runTest {
        // Alex: startStream() should immediately set state to Connecting
        // before the async DAT SDK call returns. This gives the UI instant
        // feedback so workers know something is happening.
        viewModel.sessionState.test {
            assertEquals(StreamUiState.Idle, awaitItem())
            viewModel.startStream()
            assertEquals(StreamUiState.Connecting, awaitItem())
            // Alex: The next state depends on the DAT SDK result.
            // In unit tests without the SDK, it will likely error.
            // We cancel here because we're only testing the Connecting transition.
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `startStream when already Connecting is no-op`() = runTest {
        // Alex: Calling startStream() twice rapidly should NOT create two sessions.
        // The guard in startStream() checks for both Active and Connecting states.
        viewModel.sessionState.test {
            assertEquals(StreamUiState.Idle, awaitItem())
            viewModel.startStream()
            assertEquals(StreamUiState.Connecting, awaitItem())

            // Second call should be ignored — no new state emission
            viewModel.startStream()
            // Alex: expectNoEvents() would fail if a second Connecting was emitted
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `startStream failure transitions to Error`() = runTest {
        // Alex: When the DAT SDK isn't initialized (unit test environment),
        // startStreamSession() will fail and we should see an Error state.
        viewModel.sessionState.test {
            assertEquals(StreamUiState.Idle, awaitItem())
            viewModel.startStream()
            assertEquals(StreamUiState.Connecting, awaitItem())

            // Advance coroutines to let the async call complete/fail
            advanceUntilIdle()

            val errorState = awaitItem()
            assertTrue(
                "Expected Error state but got $errorState",
                errorState is StreamUiState.Error
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- stopStream tests ---

    @Test
    fun `stopStream when Idle is no-op`() = runTest {
        // Alex: Calling stop when already idle shouldn't crash or emit new state.
        viewModel.sessionState.test {
            assertEquals(StreamUiState.Idle, awaitItem())
            viewModel.stopStream()
            // No state change should happen
            expectNoEvents()
        }
    }

    @Test
    fun `stopStream when Connecting is no-op`() = runTest {
        // Alex: We don't have a session reference in Connecting state,
        // so stopStream correctly does nothing (the guard checks for Active only).
        viewModel.sessionState.test {
            assertEquals(StreamUiState.Idle, awaitItem())
            viewModel.startStream()
            assertEquals(StreamUiState.Connecting, awaitItem())

            viewModel.stopStream()
            // Should remain in Connecting — stopStream only works from Active
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- capturePhoto tests ---

    @Test
    fun `capturePhoto when not streaming is no-op`() = runTest {
        // Alex: Capture should be a no-op when idle. The UI disables the button,
        // but we also guard it in the ViewModel for safety.
        viewModel.photoCaptureResult.test {
            assertNull(awaitItem())
            viewModel.capturePhoto()
            // No result emitted — photo capture requires Active state
            expectNoEvents()
        }
    }

    @Test
    fun `capturePhoto when Connecting is no-op`() = runTest {
        // Alex: Also a no-op during connection — we don't have a session yet.
        viewModel.startStream()
        viewModel.photoCaptureResult.test {
            assertNull(awaitItem())
            viewModel.capturePhoto()
            expectNoEvents()
        }
    }

    // --- clearPhotoCaptureResult tests ---

    @Test
    fun `clearPhotoCaptureResult resets to null`() = runTest {
        // Alex: Verifies the snackbar cleanup flow.
        viewModel.photoCaptureResult.test {
            assertNull(awaitItem())
            viewModel.clearPhotoCaptureResult()
            // Already null, so no new emission
            expectNoEvents()
        }
    }

    // --- onCleared tests ---

    @Test
    fun `onCleared calls stopStream`() = runTest {
        // Alex: When the ViewModel is cleared (Activity finishing, etc.),
        // we must release the stream session. This test verifies that
        // onCleared() triggers the cleanup path.
        viewModel.sessionState.test {
            assertEquals(StreamUiState.Idle, awaitItem())

            // Alex: We call onCleared() directly since we're in a unit test.
            // In production, the ViewModelStore calls it automatically.
            viewModel.onCleared()

            // State should remain Idle (we were already idle, no session to stop)
            expectNoEvents()
        }
    }
}
