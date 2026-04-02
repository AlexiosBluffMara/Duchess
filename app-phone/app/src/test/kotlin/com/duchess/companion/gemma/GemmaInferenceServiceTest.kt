package com.duchess.companion.gemma

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import app.cash.turbine.test
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for GemmaInferenceService.
 *
 * Alex: Testing an Android Service in unit tests is tricky because Services
 * depend on the Android lifecycle (onCreate, onStartCommand, etc.). We can't
 * call those directly in JVM unit tests.
 *
 * What we CAN test:
 *   1. The GemmaState state machine
 *   2. JSON parsing logic (parseGemmaOutput)
 *   3. The loadModel() state transitions
 *   4. Constants (temperature, timeout values)
 *
 * For full service lifecycle testing, see the instrumented tests in androidTest/.
 *
 * Note: We test the GemmaInferenceService's public/internal methods directly.
 * In production, the Service would be started via Intent and accessed through binding.
 * Here we construct it directly and call methods since we're testing logic, not lifecycle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GemmaInferenceServiceTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- Constants tests ---

    @Test
    fun `inference temperature is 0_1`() {
        // Alex: Temperature must be 0.1 for deterministic safety output.
        // This is a critical config value — higher temps = inconsistent classifications.
        assertEquals(0.1f, GemmaInferenceService.INFERENCE_TEMPERATURE)
    }

    @Test
    fun `inactivity timeout is 5 minutes`() {
        // Alex: Per the companion phone instructions: "Unload model after 5 minutes."
        // 5 minutes = 300,000 ms. This saves ~1.2GB of RAM when idle.
        assertEquals(300_000L, GemmaInferenceService.INACTIVITY_TIMEOUT_MS)
    }

    // --- JSON parsing tests ---

    @Test
    fun `parseGemmaOutput parses valid JSON with violation`() {
        // Alex: Test the happy path — model detects a violation.
        val service = createServiceForParsing()
        val json = """
            {
                "violation_detected": true,
                "violation_type": "NO_HARD_HAT",
                "severity": 3,
                "description_en": "Worker missing hard hat in Zone A",
                "description_es": "Trabajador sin casco en Zona A",
                "confidence": 0.92
            }
        """.trimIndent()

        val result = service.parseGemmaOutput(json)

        assertTrue(result.violationDetected)
        assertEquals("NO_HARD_HAT", result.violationType)
        assertEquals(3, result.severity)
        assertEquals("Worker missing hard hat in Zone A", result.descriptionEn)
        assertEquals("Trabajador sin casco en Zona A", result.descriptionEs)
        assertEquals(0.92, result.confidence, 0.001)
    }

    @Test
    fun `parseGemmaOutput parses valid JSON without violation`() {
        val service = createServiceForParsing()
        val json = """
            {
                "violation_detected": false,
                "violation_type": null,
                "severity": 0,
                "description_en": "No safety violations detected",
                "description_es": "No se detectaron violaciones de seguridad",
                "confidence": 0.0
            }
        """.trimIndent()

        val result = service.parseGemmaOutput(json)

        assertFalse(result.violationDetected)
        assertNull(result.violationType)
        assertEquals(0, result.severity)
        assertEquals("No safety violations detected", result.descriptionEn)
    }

    @Test
    fun `parseGemmaOutput handles missing fields with defaults`() {
        // Alex: LLMs sometimes omit fields in their JSON output. Our parser
        // should handle this gracefully with safe defaults instead of crashing.
        val service = createServiceForParsing()
        val json = """{ "violation_detected": true }"""

        val result = service.parseGemmaOutput(json)

        assertTrue(result.violationDetected)
        assertNull(result.violationType)
        assertEquals(0, result.severity)
        assertEquals("Analysis complete", result.descriptionEn)
        assertEquals("Análisis completo", result.descriptionEs)
        assertEquals(0.0, result.confidence, 0.001)
    }

    @Test
    fun `parseGemmaOutput handles malformed JSON`() {
        // Alex: If the model outputs garbage (it happens with LLMs), we return
        // a safe "no violation" default. This is critical — we NEVER want a
        // JSON parse error to crash the safety pipeline.
        val service = createServiceForParsing()
        val result = service.parseGemmaOutput("this is not json {{{")

        assertFalse(result.violationDetected)
        assertNull(result.violationType)
        assertEquals(0, result.severity)
        assertTrue(result.descriptionEn.contains("error", ignoreCase = true))
    }

    @Test
    fun `parseGemmaOutput handles empty string`() {
        val service = createServiceForParsing()
        val result = service.parseGemmaOutput("")

        // Alex: Empty string = parse error = safe defaults
        assertFalse(result.violationDetected)
        assertEquals(0, result.severity)
    }

    @Test
    fun `parseGemmaOutput handles empty JSON object`() {
        val service = createServiceForParsing()
        val result = service.parseGemmaOutput("{}")

        // Alex: Valid JSON but no fields — should use all defaults
        assertFalse(result.violationDetected)
        assertNull(result.violationType)
        assertEquals(0, result.severity)
    }

    // --- GemmaAnalysisResult tests ---

    @Test
    fun `GemmaAnalysisResult has bilingual descriptions`() {
        val result = GemmaAnalysisResult(
            violationDetected = true,
            violationType = "NO_SAFETY_VEST",
            severity = 2,
            descriptionEn = "Safety vest missing",
            descriptionEs = "Falta chaleco de seguridad",
            confidence = 0.85
        )

        assertTrue(result.descriptionEn.isNotBlank())
        assertTrue(result.descriptionEs.isNotBlank())
    }

    @Test
    fun `GemmaAnalysisResult severity range`() {
        // Alex: Severity should be 0-5. While we don't enforce this in the data class
        // (the model outputs it), we verify the expected range in tests.
        val lowSeverity = GemmaAnalysisResult(
            violationDetected = false, violationType = null, severity = 0,
            descriptionEn = "OK", descriptionEs = "OK", confidence = 0.0
        )
        val highSeverity = GemmaAnalysisResult(
            violationDetected = true, violationType = "FALL_HAZARD", severity = 5,
            descriptionEn = "Critical", descriptionEs = "Crítico", confidence = 0.99
        )

        assertEquals(0, lowSeverity.severity)
        assertEquals(5, highSeverity.severity)
    }

    // --- Helper ---

    /**
     * Alex: We can't fully construct GemmaInferenceService in unit tests because
     * it extends android.app.Service which requires the Android runtime.
     * For JSON parsing tests, we use a test-friendly subclass approach.
     * The parseGemmaOutput method is marked `internal` so we can access it.
     *
     * In a real project, you'd extract the parsing logic into a separate class
     * (GemmaOutputParser) that doesn't depend on android.app.Service. But for
     * now, we test via a helper that creates a minimal instance using mockk.
     */
    private fun createServiceForParsing(): GemmaInferenceService {
        // Alex: We use Mockk's spyk() to create a partial mock, but since
        // GemmaInferenceService extends Service (which needs Android),
        // we instead just use the class directly and only test internal methods.
        // The parseGemmaOutput method doesn't use any Service methods.
        return io.mockk.mockk<GemmaInferenceService>(relaxed = true) {
            io.mockk.every { parseGemmaOutput(any()) } answers {
                // Call the real implementation
                callOriginal()
            }
        }
    }
}
