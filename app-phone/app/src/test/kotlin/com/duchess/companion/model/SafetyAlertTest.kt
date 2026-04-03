package com.duchess.companion.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the SafetyAlert data class.
 *
 * Alex: These tests are deceptively important. The SafetyAlert is the core data
 * structure that flows through the ENTIRE alert pipeline — from Gemma 4 inference
 * to mesh broadcast to cloud escalation. If someone adds a field with PII
 * (worker name, face ID, GPS coordinates), these tests catch it.
 *
 * The "no PII fields" test is the canary in the coal mine. It verifies that the
 * data class ONLY has the expected anonymized fields. If a new field is added,
 * the test will fail, forcing the developer to either:
 *   a) Add the new field to the expected set (if it's safe)
 *   b) Remove the PII field they accidentally added
 *
 * This is a privacy-by-design pattern: make violations impossible to merge.
 */
class SafetyAlertTest {

    // --- Construction tests ---

    @Test
    fun `alert can be created with all fields`() {
        // Alex: Basic construction test. Verifies all fields are accessible
        // and have the values we set. Kotlin data classes generate equals()
        // based on all constructor params, so this also implicitly tests that.
        val alert = SafetyAlert(
            id = "alert-001",
            violationType = "NO_HARD_HAT",
            severity = 3,
            zoneId = "zone-A",
            timestamp = 1711929600000L,
            messageEn = "Hard hat missing in Zone A",
            messageEs = "Falta casco en Zona A"
        )

        assertEquals("alert-001", alert.id)
        assertEquals("NO_HARD_HAT", alert.violationType)
        assertEquals(3, alert.severity)
        assertEquals("zone-A", alert.zoneId)
        assertEquals(1711929600000L, alert.timestamp)
        assertEquals("Hard hat missing in Zone A", alert.messageEn)
        assertEquals("Falta casco en Zona A", alert.messageEs)
    }

    @Test
    fun `alert has bilingual messages`() {
        // Alex: EVERY SafetyAlert must have both English and Spanish messages.
        // This is a non-negotiable requirement for construction sites with
        // bilingual workers. Missing translations = workers can't read the alert.
        val alert = createTestAlert()

        assertTrue(
            "English message should not be empty",
            alert.messageEn.isNotBlank()
        )
        assertTrue(
            "Spanish message should not be empty",
            alert.messageEs.isNotBlank()
        )
    }

    // --- Privacy tests (CRITICAL) ---

    @Test
    fun `alert has no PII fields`() {
        // Alex: THIS IS THE MOST IMPORTANT TEST IN THE ENTIRE FILE.
        //
        // SafetyAlert must NEVER contain personally identifiable information.
        // No worker names, no face IDs, no badge numbers, no exact GPS coordinates.
        // Only zone-level location (zoneId), violation type, and bilingual messages.
        //
        // We verify this by checking the data class only has the expected fields.
        // If someone adds a "workerName" or "faceId" field, this test FAILS.
        //
        // This is enforced by checking the Kotlin data class component functions,
        // which correspond 1:1 with constructor parameters.
        val expectedFieldNames = setOf(
            "id",
            "violationType",
            "severity",
            "zoneId",
            "timestamp",
            "messageEn",
            "messageEs"
        )

        // Alex: Kotlin data classes generate componentN() functions for each
        // constructor parameter. We use reflection to get the actual field names
        // and verify they match our expected set exactly.
        val actualFieldNames = SafetyAlert::class.java.declaredFields
            .filter { !it.isSynthetic && !it.name.startsWith("\$") }
            .map { it.name }
            .toSet()

        assertEquals(
            "SafetyAlert has unexpected fields! " +
            "If you added a new field, verify it contains NO PII " +
            "(no worker name, face ID, badge number, or exact GPS). " +
            "Expected: $expectedFieldNames, Actual: $actualFieldNames",
            expectedFieldNames,
            actualFieldNames
        )
    }

    @Test
    fun `alert does not contain worker identity keywords in field names`() {
        // Alex: Belt-and-suspenders check. Even if someone adds a field with
        // a creative name, we scan for PII-related keywords.
        val piiKeywords = listOf(
            "worker", "name", "face", "badge", "employee",
            "person", "identity", "ssn", "phone", "email",
            "gps", "latitude", "longitude", "address"
        )

        val fieldNames = SafetyAlert::class.java.declaredFields
            .filter { !it.isSynthetic && !it.name.startsWith("\$") }
            .map { it.name.lowercase() }

        for (keyword in piiKeywords) {
            val matchingFields = fieldNames.filter { it.contains(keyword) }
            assertTrue(
                "SafetyAlert contains PII-suspicious field(s): $matchingFields " +
                "(matched keyword: '$keyword'). This violates privacy requirements.",
                matchingFields.isEmpty()
            )
        }
    }

    // --- Equality tests ---

    @Test
    fun `alerts with same fields are equal`() {
        // Alex: Kotlin data class equals() is based on all constructor params.
        val alert1 = createTestAlert()
        val alert2 = createTestAlert()
        assertEquals(alert1, alert2)
    }

    @Test
    fun `alerts with different ids are not equal`() {
        val alert1 = createTestAlert(id = "alert-001")
        val alert2 = createTestAlert(id = "alert-002")
        assertNotEquals(alert1, alert2)
    }

    @Test
    fun `alerts with different severity are not equal`() {
        val alert1 = createTestAlert(severity = 1)
        val alert2 = createTestAlert(severity = 3)
        assertNotEquals(alert1, alert2)
    }

    @Test
    fun `alert copy preserves other fields`() {
        // Alex: data class copy() is used heavily in state updates.
        // Verify it doesn't lose fields.
        val original = createTestAlert()
        val copied = original.copy(severity = 5)

        assertEquals(original.id, copied.id)
        assertEquals(original.violationType, copied.violationType)
        assertEquals(original.zoneId, copied.zoneId)
        assertEquals(5, copied.severity)
        assertEquals(original.messageEn, copied.messageEn)
        assertEquals(original.messageEs, copied.messageEs)
    }

    @Test
    fun `alert hashCode is consistent with equals`() {
        // Alex: If two objects are equal, they MUST have the same hashCode.
        // This is a Java/Kotlin contract that data classes satisfy automatically,
        // but it's good to verify in case someone overrides equals/hashCode.
        val alert1 = createTestAlert()
        val alert2 = createTestAlert()
        assertEquals(alert1.hashCode(), alert2.hashCode())
    }

    // --- Helper ---

    private fun createTestAlert(
        id: String = "alert-001",
        severity: Int = 3
    ) = SafetyAlert(
        id = id,
        violationType = "NO_HARD_HAT",
        severity = severity,
        zoneId = "zone-A",
        timestamp = 1711929600000L,
        messageEn = "Hard hat missing in Zone A",
        messageEs = "Falta casco en Zona A"
    )
}
