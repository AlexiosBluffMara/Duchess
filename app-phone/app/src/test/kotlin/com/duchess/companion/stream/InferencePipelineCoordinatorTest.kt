package com.duchess.companion.stream

import app.cash.turbine.test
import com.duchess.companion.ble.BleGattServer
import com.duchess.companion.gemma.GemmaAnalysisResult
import com.duchess.companion.gemma.GemmaInferenceEngine
import com.duchess.companion.model.SafetyAlert
import com.meta.wearable.dat.camera.types.VideoFrame
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for InferencePipelineCoordinator.
 *
 * Alex: The coordinator has 4 behaviors to test:
 *   1. Throttling — at most 1 inference per THROTTLE_MS
 *   2. Confidence filtering — results < MIN_CONFIDENCE are dropped
 *   3. Alert emission — violations emit on alertFlow
 *   4. BLE routing — severity >= BLE_SEVERITY_THRESHOLD triggers sendAlert()
 *   5. toSafetyAlert() — extension function maps result to SafetyAlert correctly
 *
 * We mock GemmaInferenceEngine (to control what analyze() returns) and BleGattServer
 * (to verify sendAlert() is/isn't called without needing real Bluetooth).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InferencePipelineCoordinatorTest {

    private lateinit var engine: GemmaInferenceEngine
    private lateinit var bleServer: BleGattServer
    private lateinit var coordinator: InferencePipelineCoordinator
    private lateinit var mockFrame: VideoFrame

    @Before
    fun setup() {
        engine = mockk(relaxed = true)
        bleServer = mockk(relaxed = true) {
            every { sendAlert(any()) } returns true
        }
        coordinator = InferencePipelineCoordinator(engine, bleServer)
        mockFrame = mockk(relaxed = true)
    }

    // --- Throttling tests ---

    @Test
    fun `processFrame skips second call within throttle window`() = runTest {
        val violation = violationResult(severity = 3, confidence = 0.9)
        coEvery { engine.analyze(any()) } returns violation

        // First call — should analyze
        coordinator.processFrame(mockFrame, "zone-A")
        // Second call immediately — throttle not elapsed, should skip
        coordinator.processFrame(mockFrame, "zone-A")

        // Alex: analyze() should only be called once despite two processFrame() calls.
        // The throttle window is 1000ms. Both calls happen in the same test tick (0ms apart).
        coVerify(exactly = 1) { engine.analyze(any()) }
    }

    @Test
    fun `processFrame processes frame after throttle window`() = runTest {
        val violation = violationResult(severity = 3, confidence = 0.9)
        coEvery { engine.analyze(any()) } returns violation

        coordinator.processFrame(mockFrame, "zone-A")

        // Alex: Simulate time passing by directly manipulating lastInferenceMs via reflection.
        // TestCoroutineDispatcher advances virtual time but `lastInferenceMs` uses
        // System.currentTimeMillis() — we need to age the last inference time.
        advanceLastInferenceTime(coordinator, InferencePipelineCoordinator.THROTTLE_MS + 1)

        coordinator.processFrame(mockFrame, "zone-A")

        coVerify(exactly = 2) { engine.analyze(any()) }
    }

    // --- Confidence filter tests ---

    @Test
    fun `processFrame drops result below confidence threshold`() = runTest {
        val lowConfidence = violationResult(severity = 5, confidence = 0.3) // below 0.5
        coEvery { engine.analyze(any()) } returns lowConfidence

        coordinator.alertFlow.test {
            coordinator.processFrame(mockFrame, "zone-A")
            // Alex: No alert should be emitted for low-confidence results.
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `processFrame drops result with violation_detected = false`() = runTest {
        val noViolation = noViolationResult()
        coEvery { engine.analyze(any()) } returns noViolation

        coordinator.alertFlow.test {
            coordinator.processFrame(mockFrame, "zone-A")
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Alert emission tests ---

    @Test
    fun `processFrame emits alert for high-confidence violation`() = runTest {
        val violation = violationResult(
            violationType = "NO_HARD_HAT",
            severity = 4,
            confidence = 0.88,
            descriptionEn = "Hard hat missing",
            descriptionEs = "Falta casco"
        )
        coEvery { engine.analyze(any()) } returns violation

        coordinator.alertFlow.test {
            coordinator.processFrame(mockFrame, "zone-B-excavation")

            val alert = awaitItem()
            assertEquals("NO_HARD_HAT", alert.violationType)
            assertEquals(4, alert.severity)
            assertEquals("zone-B-excavation", alert.zoneId)
            assertEquals("Hard hat missing", alert.messageEn)
            assertEquals("Falta casco", alert.messageEs)
            assertNotNull(alert.id)
            assertTrue(alert.timestamp > 0)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `processFrame sets zoneId correctly on emitted alert`() = runTest {
        val violation = violationResult(severity = 3, confidence = 0.8)
        coEvery { engine.analyze(any()) } returns violation

        coordinator.alertFlow.test {
            coordinator.processFrame(mockFrame, "zone-D-roofing")

            val alert = awaitItem()
            assertEquals("zone-D-roofing", alert.zoneId)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `processFrame generates unique IDs for successive alerts`() = runTest {
        val violation = violationResult(severity = 3, confidence = 0.85)
        coEvery { engine.analyze(any()) } returns violation

        val ids = mutableListOf<String>()
        coordinator.alertFlow.test {
            // First alert
            coordinator.processFrame(mockFrame, "zone-A")
            ids.add(awaitItem().id)

            // Age the throttle window
            advanceLastInferenceTime(coordinator, InferencePipelineCoordinator.THROTTLE_MS + 1)

            // Second alert
            coordinator.processFrame(mockFrame, "zone-A")
            ids.add(awaitItem().id)

            cancelAndIgnoreRemainingEvents()
        }

        // Alex: UUIDs must be unique. Same violation type in same zone should still
        // get distinct IDs so the cloud can distinguish separate events.
        assertEquals(2, ids.distinct().size)
    }

    // --- BLE routing tests ---

    @Test
    fun `processFrame calls sendAlert for severity at threshold`() = runTest {
        val violation = violationResult(severity = InferencePipelineCoordinator.BLE_SEVERITY_THRESHOLD, confidence = 0.9)
        coEvery { engine.analyze(any()) } returns violation

        coordinator.processFrame(mockFrame, "zone-A")

        verify(exactly = 1) { bleServer.sendAlert(any()) }
    }

    @Test
    fun `processFrame calls sendAlert for severity above threshold`() = runTest {
        val violation = violationResult(severity = 5, confidence = 0.95) // critical
        coEvery { engine.analyze(any()) } returns violation

        coordinator.processFrame(mockFrame, "zone-A")

        verify(exactly = 1) { bleServer.sendAlert(any()) }
    }

    @Test
    fun `processFrame does NOT call sendAlert for severity below threshold`() = runTest {
        // Alex: Minor violations (severity 1-2) go to the app notification only.
        // We don't want minor housekeeping issues buzzing workers' glasses HUD constantly.
        val minor = violationResult(
            severity = InferencePipelineCoordinator.BLE_SEVERITY_THRESHOLD - 1,
            confidence = 0.8
        )
        coEvery { engine.analyze(any()) } returns minor

        coordinator.processFrame(mockFrame, "zone-A")

        verify(exactly = 0) { bleServer.sendAlert(any()) }
    }

    @Test
    fun `processFrame does NOT call sendAlert when no violation detected`() = runTest {
        coEvery { engine.analyze(any()) } returns noViolationResult()

        coordinator.processFrame(mockFrame, "zone-A")

        verify(exactly = 0) { bleServer.sendAlert(any()) }
    }

    // --- emitManualAlert tests ---

    @Test
    fun `emitManualAlert emits alert on alertFlow`() = runTest {
        val manual = SafetyAlert(
            id = "manual-001",
            violationType = "FALL_HAZARD",
            severity = 5,
            zoneId = "zone-D-roofing",
            timestamp = System.currentTimeMillis(),
            messageEn = "Unprotected edge reported",
            messageEs = "Borde sin protección reportado"
        )

        coordinator.alertFlow.test {
            coordinator.emitManualAlert(manual)

            val received = awaitItem()
            assertEquals("manual-001", received.id)
            assertEquals("FALL_HAZARD", received.violationType)
            assertEquals(5, received.severity)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `emitManualAlert calls sendAlert for high severity manual reports`() = runTest {
        val critical = SafetyAlert(
            id = "manual-002",
            violationType = "RESTRICTED_ZONE",
            severity = 4,
            zoneId = "zone-C-electrical",
            timestamp = System.currentTimeMillis(),
            messageEn = "Unauthorized entry",
            messageEs = "Entrada no autorizada"
        )

        coordinator.emitManualAlert(critical)

        verify(exactly = 1) { bleServer.sendAlert(any()) }
    }

    // --- toSafetyAlert() extension function tests ---

    @Test
    fun `toSafetyAlert maps all fields correctly`() {
        val result = GemmaAnalysisResult(
            violationDetected = true,
            violationType = "NO_SAFETY_VEST",
            severity = 3,
            descriptionEn = "Safety vest missing",
            descriptionEs = "Falta chaleco",
            confidence = 0.75
        )

        val alert = result.toSafetyAlert("zone-B")

        assertEquals("NO_SAFETY_VEST", alert.violationType)
        assertEquals(3, alert.severity)
        assertEquals("zone-B", alert.zoneId)
        assertEquals("Safety vest missing", alert.messageEn)
        assertEquals("Falta chaleco", alert.messageEs)
        assertNotNull(alert.id)
        assertTrue(alert.timestamp > 0)
    }

    @Test
    fun `toSafetyAlert uses UNKNOWN for null violation type`() {
        val result = GemmaAnalysisResult(
            violationDetected = true,
            violationType = null,  // model didn't specify
            severity = 2,
            descriptionEn = "Unclassified hazard",
            descriptionEs = "Peligro sin clasificar",
            confidence = 0.6
        )

        val alert = result.toSafetyAlert("zone-A")

        assertEquals("UNKNOWN", alert.violationType)
    }

    @Test
    fun `toSafetyAlert generated alert passes PII scan`() {
        val result = violationResult(severity = 3, confidence = 0.8)
        val alert = result.toSafetyAlert("zone-A")

        val piiKeywords = listOf(
            "worker", "name", "face", "gps", "latitude",
            "longitude", "email", "badge", "identity"
        )
        val fieldNames = SafetyAlert::class.java.declaredFields
            .filter { !it.isSynthetic }
            .map { it.name.lowercase() }

        for (keyword in piiKeywords) {
            val hits = fieldNames.filter { it.contains(keyword) }
            assertTrue(
                "SafetyAlert from toSafetyAlert() has PII field '$hits' (keyword '$keyword')",
                hits.isEmpty()
            )
        }
    }

    // --- Coordinator constants ---

    @Test
    fun `THROTTLE_MS is 1 second`() {
        assertEquals(1_000L, InferencePipelineCoordinator.THROTTLE_MS)
    }

    @Test
    fun `BLE_SEVERITY_THRESHOLD is 3`() {
        assertEquals(3, InferencePipelineCoordinator.BLE_SEVERITY_THRESHOLD)
    }

    @Test
    fun `MIN_CONFIDENCE is 0_5`() {
        assertEquals(0.5, InferencePipelineCoordinator.MIN_CONFIDENCE, 0.001)
    }

    // --- Helpers ---

    private fun violationResult(
        violationType: String = "NO_HARD_HAT",
        severity: Int = 3,
        confidence: Double = 0.85,
        descriptionEn: String = "Hard hat missing",
        descriptionEs: String = "Falta casco"
    ) = GemmaAnalysisResult(
        violationDetected = true,
        violationType = violationType,
        severity = severity,
        descriptionEn = descriptionEn,
        descriptionEs = descriptionEs,
        confidence = confidence
    )

    private fun noViolationResult() = GemmaAnalysisResult(
        violationDetected = false,
        violationType = null,
        severity = 0,
        descriptionEn = "Scene clear",
        descriptionEs = "Escena despejada",
        confidence = 0.95
    )

    /**
     * Age the coordinator's lastInferenceMs by the given amount via reflection.
     * Needed because lastInferenceMs uses System.currentTimeMillis() (real time),
     * not the coroutine test dispatcher's virtual time.
     */
    private fun advanceLastInferenceTime(
        coordinator: InferencePipelineCoordinator,
        advanceByMs: Long
    ) {
        val field = InferencePipelineCoordinator::class.java
            .getDeclaredField("lastInferenceMs")
        field.isAccessible = true
        val current = field.getLong(coordinator)
        field.setLong(coordinator, current - advanceByMs)
    }
}
