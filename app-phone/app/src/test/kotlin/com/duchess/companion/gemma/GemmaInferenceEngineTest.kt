package com.duchess.companion.gemma

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for GemmaInferenceEngine.
 *
 * Alex: GemmaInferenceEngine is a plain @Singleton — no Android Service lifecycle
 * complications. We can test parse/extract logic directly with real Context mocks.
 *
 * Tests are organized by method:
 *   1. Constants (temperature, timeout, max images)
 *   2. parseGemmaOutput() — JSON parsing + safe defaults
 *   3. extractJson() — markdown fence stripping
 *   4. SAFETY_PROMPT — content requirements (bilingual, PPE taxonomy)
 */
class GemmaInferenceEngineTest {

    // --- Constants ---

    @Test
    fun `inference temperature is 0_1 for deterministic output`() {
        assertEquals(0.1f, GemmaInferenceEngine.INFERENCE_TEMPERATURE)
    }

    @Test
    fun `inactivity timeout is 5 minutes`() {
        assertEquals(300_000L, GemmaInferenceEngine.INACTIVITY_TIMEOUT_MS)
    }

    @Test
    fun `max images is 1 for single-frame analysis`() {
        assertEquals(1, GemmaInferenceEngine.MAX_IMAGES)
    }

    // --- parseGemmaOutput tests ---

    @Test
    fun `parseGemmaOutput parses violation with all fields`() {
        val result = engine().parseGemmaOutput("""
            {
                "violation_detected": true,
                "violation_type": "NO_HARD_HAT",
                "severity": 4,
                "description_en": "Worker without hard hat in Zone A",
                "description_es": "Trabajador sin casco en Zona A",
                "confidence": 0.91
            }
        """.trimIndent())

        assertTrue(result.violationDetected)
        assertEquals("NO_HARD_HAT", result.violationType)
        assertEquals(4, result.severity)
        assertEquals("Worker without hard hat in Zone A", result.descriptionEn)
        assertEquals("Trabajador sin casco en Zona A", result.descriptionEs)
        assertEquals(0.91, result.confidence, 0.001)
    }

    @Test
    fun `parseGemmaOutput parses no-violation response`() {
        val result = engine().parseGemmaOutput("""
            {
                "violation_detected": false,
                "violation_type": null,
                "severity": 0,
                "description_en": "Scene clear",
                "description_es": "Escena despejada",
                "confidence": 0.97
            }
        """.trimIndent())

        assertFalse(result.violationDetected)
        assertNull(result.violationType)
        assertEquals(0, result.severity)
    }

    @Test
    fun `parseGemmaOutput handles missing fields with safe defaults`() {
        // Alex: LLMs sometimes omit fields. Partial JSON should not crash the pipeline.
        val result = engine().parseGemmaOutput("""{ "violation_detected": true }""")

        assertTrue(result.violationDetected)
        assertNull(result.violationType)   // not present → null
        assertEquals(0, result.severity)   // not present → 0
        assertTrue(result.descriptionEn.isNotBlank())
        assertTrue(result.descriptionEs.isNotBlank())
        assertEquals(0.0, result.confidence, 0.001)
    }

    @Test
    fun `parseGemmaOutput handles empty JSON object`() {
        val result = engine().parseGemmaOutput("{}")

        assertFalse(result.violationDetected)
        assertNull(result.violationType)
        assertEquals(0, result.severity)
    }

    @Test
    fun `parseGemmaOutput handles malformed JSON without crashing`() {
        // Alex: Model output garbage = return safe "no violation" default.
        // The pipeline MUST NOT crash just because the LLM had a bad day.
        val result = engine().parseGemmaOutput("I think there might be a problem {{{")

        assertFalse(result.violationDetected)
        assertNull(result.violationType)
        assertEquals(0, result.severity)
        assertTrue(result.descriptionEn.isNotBlank())  // has a human-readable error message
    }

    @Test
    fun `parseGemmaOutput handles empty string`() {
        val result = engine().parseGemmaOutput("")

        assertFalse(result.violationDetected)
        assertEquals(0, result.severity)
    }

    @Test
    fun `parseGemmaOutput clamps severity to 0-5 range`() {
        // Alex: coerceIn(0, 5) guards against model hallucinating severity=99.
        // Safety logic downstream keys on severity levels — out-of-range = undefined behavior.
        val resultHigh = engine().parseGemmaOutput("""{"severity": 99, "violation_detected": true}""")
        val resultNeg = engine().parseGemmaOutput("""{"severity": -3, "violation_detected": true}""")

        assertEquals(5, resultHigh.severity)  // clamped from 99 to 5
        assertEquals(0, resultNeg.severity)   // clamped from -3 to 0
    }

    @Test
    fun `parseGemmaOutput clamps confidence to 0_0-1_0 range`() {
        val resultHigh = engine().parseGemmaOutput("""{"confidence": 2.5, "violation_detected": false}""")
        val resultNeg = engine().parseGemmaOutput("""{"confidence": -0.1, "violation_detected": false}""")

        assertEquals(1.0, resultHigh.confidence, 0.001)
        assertEquals(0.0, resultNeg.confidence, 0.001)
    }

    @Test
    fun `parseGemmaOutput returns null violation_type for null JSON value`() {
        val result = engine().parseGemmaOutput("""{"violation_detected": true, "violation_type": null}""")
        assertNull(result.violationType)
    }

    @Test
    fun `parseGemmaOutput returns null violation_type for literal string null`() {
        // Alex: Some models output "null" as a string value instead of JSON null.
        val result = engine().parseGemmaOutput("""{"violation_detected": false, "violation_type": "null"}""")
        assertNull(result.violationType)
    }

    @Test
    fun `parseGemmaOutput returns null violation_type for blank string`() {
        val result = engine().parseGemmaOutput("""{"violation_detected": false, "violation_type": ""}""")
        assertNull(result.violationType)
    }

    // --- extractJson tests ---

    @Test
    fun `extractJson returns clean JSON unchanged`() {
        val input = """{"violation_detected": true, "severity": 3}"""
        val result = engine().extractJson(input)
        assertEquals(input, result)
    }

    @Test
    fun `extractJson strips json code fence`() {
        val input = "```json\n{\"violation_detected\": true}\n```"
        val result = engine().extractJson(input)
        assertEquals("""{"violation_detected": true}""", result)
    }

    @Test
    fun `extractJson strips generic code fence`() {
        val input = "```\n{\"severity\": 4}\n```"
        val result = engine().extractJson(input)
        assertEquals("""{"severity": 4}""", result)
    }

    @Test
    fun `extractJson extracts JSON from surrounding prose`() {
        // Alex: Gemma sometimes produces "Here is the analysis: {...} Hope this helps!"
        val input = "Based on my analysis: {\"violation_detected\": false} That's my assessment."
        val result = engine().extractJson(input)
        assertEquals("""{"violation_detected": false}""", result)
    }

    @Test
    fun `extractJson handles multiple JSON objects by taking first to last brace`() {
        // Alex: Outer { ... } extraction handles nested objects correctly.
        val input = """{"outer": {"inner": 1}, "value": 2}"""
        val result = engine().extractJson(input)
        assertEquals(input, result)
    }

    @Test
    fun `extractJson returns original string if no braces found`() {
        val input = "no json here at all"
        val result = engine().extractJson(input)
        assertEquals(input, result)
    }

    // --- SAFETY_PROMPT content tests ---

    @Test
    fun `SAFETY_PROMPT requests JSON-only output`() {
        assertTrue(GemmaInferenceEngine.SAFETY_PROMPT.contains("JSON", ignoreCase = true))
    }

    @Test
    fun `SAFETY_PROMPT requires bilingual descriptions`() {
        assertTrue(GemmaInferenceEngine.SAFETY_PROMPT.contains("description_en"))
        assertTrue(GemmaInferenceEngine.SAFETY_PROMPT.contains("description_es"))
    }

    @Test
    fun `SAFETY_PROMPT includes PPE violation taxonomy`() {
        val prompt = GemmaInferenceEngine.SAFETY_PROMPT
        assertTrue(prompt.contains("NO_HARD_HAT"))
        assertTrue(prompt.contains("NO_SAFETY_VEST"))
        assertTrue(prompt.contains("FALL_HAZARD"))
        assertTrue(prompt.contains("RESTRICTED_ZONE"))
    }

    @Test
    fun `SAFETY_PROMPT includes severity scale`() {
        // Alex: The model must understand severity 0-5 to produce useful output.
        assertTrue(GemmaInferenceEngine.SAFETY_PROMPT.contains("0"))
        assertTrue(GemmaInferenceEngine.SAFETY_PROMPT.contains("5"))
    }

    @Test
    fun `SAFETY_PROMPT mentions construction context`() {
        assertTrue(
            GemmaInferenceEngine.SAFETY_PROMPT.contains("construction", ignoreCase = true)
        )
    }

    // --- getModelPath test ---

    @Test
    fun `getModelPath returns path ending with gemma4-e2b_bin`() {
        val tempDir = createTempDir()
        // Pre-create file so the copy-from-assets branch is not triggered
        java.io.File(tempDir, "gemma4-e2b.bin").createNewFile()

        val mockContext = mockk<android.content.Context>(relaxed = true) {
            every { filesDir } returns tempDir
        }

        val engine = GemmaInferenceEngine(mockContext)
        val path = engine.getModelPath()

        assertTrue("Model path must end in gemma4-e2b.bin", path.endsWith("gemma4-e2b.bin"))
        tempDir.deleteRecursively()
    }

    // --- PII guardrail for GemmaAnalysisResult ---

    @Test
    fun `GemmaAnalysisResult contains no PII field names`() {
        val piiKeywords = listOf(
            "worker", "name", "face", "gps", "location",
            "latitude", "longitude", "phone", "email",
            "address", "identity", "badge", "photo"
        )
        val fieldNames = GemmaAnalysisResult::class.java.declaredFields
            .filter { !it.isSynthetic }
            .map { it.name.lowercase() }

        for (keyword in piiKeywords) {
            val hits = fieldNames.filter { it.contains(keyword) }
            assertTrue(
                "GemmaAnalysisResult has PII-suspicious field(s) $hits (keyword: '$keyword'). " +
                "This data flows to cloud, BLE, and mesh — no PII allowed.",
                hits.isEmpty()
            )
        }
    }

    @Test
    fun `GemmaAnalysisResult has only the expected 6 fields`() {
        val expected = setOf(
            "violationDetected", "violationType", "severity",
            "descriptionEn", "descriptionEs", "confidence"
        )
        val actual = GemmaAnalysisResult::class.java.declaredFields
            .filter { !it.isSynthetic }
            .map { it.name }
            .toSet()

        assertEquals(
            "Unexpected fields in GemmaAnalysisResult. New fields require PII review. " +
            "Expected: $expected",
            expected, actual
        )
    }

    // --- Helper ---

    private fun createTempDir(): java.io.File {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "duchess_engine_test_${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun engine(): GemmaInferenceEngine {
        val tempDir = createTempDir()
        val mockContext = mockk<android.content.Context>(relaxed = true) {
            every { filesDir } returns tempDir
        }
        return GemmaInferenceEngine(mockContext)
    }
}
