package com.duchess.companion.stream

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var viewModel: StreamViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = StreamViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Idle`() = runTest {
        viewModel.sessionState.test {
            assertEquals(StreamSessionState.Idle, awaitItem())
        }
    }

    @Test
    fun `startStream updates state to Connecting`() = runTest {
        viewModel.sessionState.test {
            assertEquals(StreamSessionState.Idle, awaitItem())
            viewModel.startStream()
            assertEquals(StreamSessionState.Connecting, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stopStream returns to Idle`() = runTest {
        viewModel.sessionState.test {
            assertEquals(StreamSessionState.Idle, awaitItem())
            viewModel.stopStream()
            // Should remain Idle since no active session
            expectNoEvents()
        }
    }

    @Test
    fun `latestFrame is null initially`() = runTest {
        viewModel.latestFrame.test {
            assertEquals(null, awaitItem())
        }
    }

    @Test
    fun `photoCaptureResult is null initially`() = runTest {
        viewModel.photoCaptureResult.test {
            assertEquals(null, awaitItem())
        }
    }

    @Test
    fun `clearPhotoCaptureResult resets to null`() = runTest {
        viewModel.photoCaptureResult.test {
            assertEquals(null, awaitItem())
            viewModel.clearPhotoCaptureResult()
            expectNoEvents()
        }
    }
}
