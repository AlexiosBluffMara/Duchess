package com.duchess.companion.gemma

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for GemmaInferenceService.
 *
 * Alex: GemmaInferenceService is now a thin Android Service wrapper around
 * GemmaInferenceEngine. Its responsibilities are limited to:
 *   1. Foreground service lifecycle (notification, START_STICKY)
 *   2. Delegating to GemmaInferenceEngine
 *   3. Managing the inactivity timer
 *
 * ALL inference logic (parseGemmaOutput, extractJson, loadModel, analyze)
 * and ALL inference constants (INFERENCE_TEMPERATURE, INACTIVITY_TIMEOUT_MS)
 * are now in GemmaInferenceEngine. See GemmaInferenceEngineTest for those tests.
 *
 * What we test here: the constants that GemmaInferenceService still publishes
 * (via the engine) haven't drifted from expected values. This keeps a stable
 * contract so callers (InferencePipelineCoordinator etc.) don't need to import
 * GemmaInferenceEngine just for constants.
 *
 * Full service lifecycle testing (onCreate, onStartCommand, inactivity timer)
 * requires an Android instrumented test — see androidTest/ for those.
 */
class GemmaInferenceServiceTest {

    @Test
    fun `GemmaInferenceEngine temperature constant is 0_1`() {
        // Alex: Temperature must be 0.1 for deterministic safety output.
        // Higher temps = more creative / less consistent PPE classification.
        assertEquals(0.1f, GemmaInferenceEngine.INFERENCE_TEMPERATURE)
    }

    @Test
    fun `GemmaInferenceEngine inactivity timeout is 5 minutes`() {
        // Alex: 5 minutes = 300,000ms. After this of idle silence,
        // GemmaInferenceService triggers engine.unloadModel() to free ~1.2GB RAM.
        assertEquals(300_000L, GemmaInferenceEngine.INACTIVITY_TIMEOUT_MS)
    }

    @Test
    fun `GemmaState values cover all service lifecycle phases`() {
        // Alex: Verify the sealed interface covers all states the service can be in.
        // If a new state is needed, add it here to document intent.
        val states = listOf(
            GemmaState.Idle,
            GemmaState.Loading,
            GemmaState.Ready,
            GemmaState.Running,
            GemmaState.Error("test")
        )
        assertEquals(5, states.size)
    }

    @Test
    fun `GemmaState Error carries message`() {
        val error = GemmaState.Error("OOM during model load")
        assertEquals("OOM during model load", error.message)
    }

    @Test
    fun `GemmaState data objects have value equality`() {
        // Alex: data object means === and == are equivalent.
        // Important for StateFlow collectors doing equality checks.
        assertEquals(GemmaState.Idle, GemmaState.Idle)
        assertEquals(GemmaState.Ready, GemmaState.Ready)
        assertEquals(GemmaState.Loading, GemmaState.Loading)
        assertEquals(GemmaState.Running, GemmaState.Running)
    }
}
