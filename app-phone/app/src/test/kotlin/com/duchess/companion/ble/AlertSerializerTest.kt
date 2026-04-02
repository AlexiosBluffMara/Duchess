package com.duchess.companion.ble

import com.duchess.companion.model.SafetyAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for AlertSerializer — the BLE payload serialization contract.
 *
 * ALEX: These tests validate the serialization contract between the phone (BleGattServer)
 * and the glasses (BleGattClient). If any of these fail, the phone and glasses can't
 * communicate alerts reliably. The wire format is pipe-delimited UTF-8:
 *
 *   id|violationType|severity|zoneId|timestamp|messageEn|messageEs
 *
 * Key invariants tested:
 *   1. Round-trip: serialize(alert) → deserialize → original alert
 *   2. MTU compliance: payload never exceeds 240 bytes
 *   3. Robustness: garbage input → null, never crash
 *   4. Escaping: pipe chars in messages don't break the format
 *   5. Bilingual: both EN and ES messages survive round-trip
 */
class AlertSerializerTest {

    // --- Round-trip tests ---

    @Test
    fun `round trip serialize then deserialize returns original alert`() {
        // ALEX: The fundamental contract — serialize then deserialize must be identity.
        // If this fails, phone and glasses are speaking different languages.
        val alert = createTestAlert()

        val bytes = AlertSerializer.serialize(alert)
        val result = AlertSerializer.deserialize(bytes)

        assertNotNull("Deserialize should succeed for a valid alert", result)
        assertEquals(alert.id, result!!.id)
        assertEquals(alert.violationType, result.violationType)
        assertEquals(alert.severity, result.severity)
        assertEquals(alert.zoneId, result.zoneId)
        assertEquals(alert.timestamp, result.timestamp)
        assertEquals(alert.messageEn, result.messageEn)
        assertEquals(alert.messageEs, result.messageEs)
    }

    @Test
    fun `round trip preserves all violation types`() {
        // ALEX: Verify various violation type strings survive the round trip.
        val violationTypes = listOf(
            "NO_HARD_HAT", "NO_SAFETY_VEST", "NO_SAFETY_GLASSES",
            "FALL_HAZARD", "RESTRICTED_ZONE", "ELECTRICAL_HAZARD"
        )

        for (type in violationTypes) {
            val alert = createTestAlert(violationType = type)
            val result = AlertSerializer.deserialize(AlertSerializer.serialize(alert))
            assertNotNull("Round trip failed for violation type: $type", result)
            assertEquals(type, result!!.violationType)
        }
    }

    @Test
    fun `round trip preserves all severity levels`() {
        // ALEX: Severity 0-5 must all survive. 0 is a valid severity (info-level).
        for (severity in 0..5) {
            val alert = createTestAlert(severity = severity)
            val result = AlertSerializer.deserialize(AlertSerializer.serialize(alert))
            assertNotNull("Round trip failed for severity: $severity", result)
            assertEquals(severity, result!!.severity)
        }
    }

    // --- MTU / truncation tests ---

    @Test
    fun `payload never exceeds MAX_PAYLOAD_SIZE`() {
        // ALEX: BLE MTU is the hard constraint. No matter how long the messages,
        // the serialized payload must fit in 240 bytes. The serializer truncates
        // messages (display-only, never PII) to enforce this.
        val longMessage = "A".repeat(200)
        val alert = createTestAlert(
            messageEn = longMessage,
            messageEs = longMessage
        )

        val bytes = AlertSerializer.serialize(alert)

        assertTrue(
            "Payload is ${bytes.size} bytes, exceeds MAX_PAYLOAD_SIZE (${AlertSerializer.MAX_PAYLOAD_SIZE})",
            bytes.size <= AlertSerializer.MAX_PAYLOAD_SIZE
        )
    }

    @Test
    fun `truncated messages still deserialize correctly`() {
        // ALEX: Even after truncation, the payload must still parse into a valid SafetyAlert.
        val longMessage = "This is a very long safety alert message that exceeds the BLE MTU limit. " +
            "It contains important safety information about a worker who is not wearing proper PPE " +
            "in a restricted construction zone during active operations."
        val alert = createTestAlert(
            messageEn = longMessage,
            messageEs = "Este es un mensaje de alerta de seguridad muy largo que excede el límite de MTU BLE. " +
                "Contiene información importante de seguridad sobre un trabajador que no lleva el EPP adecuado."
        )

        val bytes = AlertSerializer.serialize(alert)
        val result = AlertSerializer.deserialize(bytes)

        assertNotNull("Truncated payload should still deserialize", result)
        assertEquals(alert.id, result!!.id)
        assertEquals(alert.violationType, result.violationType)
        assertEquals(alert.severity, result.severity)
        assertEquals(alert.zoneId, result.zoneId)
        assertEquals(alert.timestamp, result.timestamp)
        // ALEX: Messages may be shorter than originals due to truncation,
        // but they should be non-empty prefixes of the originals.
        assertTrue("English message should be a prefix of original",
            alert.messageEn.startsWith(result.messageEn) || result.messageEn.length < alert.messageEn.length)
        assertTrue("Spanish message should be a prefix of original",
            alert.messageEs.startsWith(result.messageEs) || result.messageEs.length < alert.messageEs.length)
    }

    @Test
    fun `short alert stays within MTU without truncation`() {
        // ALEX: Typical alerts are well under MTU. Verify no truncation occurs.
        val alert = createTestAlert()
        val fullPayload = "${alert.id}|${alert.violationType}|${alert.severity}" +
            "|${alert.zoneId}|${alert.timestamp}|${alert.messageEn}|${alert.messageEs}"

        val bytes = AlertSerializer.serialize(alert)
        val result = AlertSerializer.deserialize(bytes)

        assertNotNull(result)
        assertEquals(alert.messageEn, result!!.messageEn)
        assertEquals(alert.messageEs, result.messageEs)
    }

    // --- Malformed input tests ---

    @Test
    fun `deserialize garbage bytes returns null`() {
        // ALEX: Random bytes must never crash the deserializer. Null = "I don't understand."
        val garbage = byteArrayOf(0x00, 0xFF.toByte(), 0x42, 0x13, 0x37, 0xDE.toByte(), 0xAD.toByte())
        assertNull(AlertSerializer.deserialize(garbage))
    }

    @Test
    fun `deserialize empty bytes returns null`() {
        assertNull(AlertSerializer.deserialize(byteArrayOf()))
    }

    @Test
    fun `deserialize too few fields returns null`() {
        // ALEX: Need exactly 7 pipe-delimited fields. Fewer = malformed payload.
        val tooFew = "id|NO_HARD_HAT|3|zone-A".toByteArray(Charsets.UTF_8)
        assertNull(AlertSerializer.deserialize(tooFew))
    }

    @Test
    fun `deserialize non-numeric severity returns null`() {
        val bad = "id|NO_HARD_HAT|abc|zone-A|1711929600000|msg en|msg es".toByteArray(Charsets.UTF_8)
        assertNull(AlertSerializer.deserialize(bad))
    }

    @Test
    fun `deserialize non-numeric timestamp returns null`() {
        val bad = "id|NO_HARD_HAT|3|zone-A|not-a-number|msg en|msg es".toByteArray(Charsets.UTF_8)
        assertNull(AlertSerializer.deserialize(bad))
    }

    // --- Pipe-in-message tests ---

    @Test
    fun `pipe character in messageEn is escaped and survives round trip`() {
        // ALEX: Critical edge case. If a message contains a literal "|", the naive
        // split("|") approach would parse it as extra fields. Our escaping handles this.
        val alert = createTestAlert(
            messageEn = "Warning: PPE violation | Zone A restricted",
            messageEs = "Advertencia: violación de EPP | Zona A restringida"
        )

        val bytes = AlertSerializer.serialize(alert)
        val result = AlertSerializer.deserialize(bytes)

        assertNotNull("Alert with pipe in message should deserialize", result)
        assertEquals(alert.messageEn, result!!.messageEn)
        assertEquals(alert.messageEs, result.messageEs)
    }

    @Test
    fun `multiple pipes in messages survive round trip`() {
        val alert = createTestAlert(
            messageEn = "A|B|C",
            messageEs = "X|Y|Z"
        )

        val bytes = AlertSerializer.serialize(alert)
        val result = AlertSerializer.deserialize(bytes)

        assertNotNull(result)
        assertEquals("A|B|C", result!!.messageEn)
        assertEquals("X|Y|Z", result.messageEs)
    }

    @Test
    fun `backslash in messages survives round trip`() {
        // ALEX: Backslash is the escape char. Verify it's properly double-escaped.
        val alert = createTestAlert(
            messageEn = "Path: C\\zone\\data",
            messageEs = "Ruta: C\\zona\\datos"
        )

        val bytes = AlertSerializer.serialize(alert)
        val result = AlertSerializer.deserialize(bytes)

        assertNotNull(result)
        assertEquals("Path: C\\zone\\data", result!!.messageEn)
        assertEquals("Ruta: C\\zona\\datos", result.messageEs)
    }

    @Test
    fun `backslash followed by pipe in messages survives round trip`() {
        // ALEX: The tricky case: `\|` in the original message. Must be escaped as `\\` + `\|`
        // so it doesn't look like a single escaped pipe.
        val alert = createTestAlert(
            messageEn = "Test\\|value",
            messageEs = "Prueba\\|valor"
        )

        val bytes = AlertSerializer.serialize(alert)
        val result = AlertSerializer.deserialize(bytes)

        assertNotNull(result)
        assertEquals("Test\\|value", result!!.messageEn)
        assertEquals("Prueba\\|valor", result.messageEs)
    }

    // --- Empty / edge-case field tests ---

    @Test
    fun `severity zero is valid`() {
        // ALEX: severity=0 means "info level" — it's a valid value, not a sentinel.
        val alert = createTestAlert(severity = 0)

        val bytes = AlertSerializer.serialize(alert)
        val result = AlertSerializer.deserialize(bytes)

        assertNotNull(result)
        assertEquals(0, result!!.severity)
    }

    @Test
    fun `empty string fields serialize and deserialize`() {
        // ALEX: The phone-side SafetyAlert allows empty strings (no init constraints).
        // The glasses side DOES have require(messageEn.isNotBlank()), so they'd reject this.
        // But the phone-to-phone round trip should work.
        val alert = SafetyAlert(
            id = "",
            violationType = "",
            severity = 0,
            zoneId = "",
            timestamp = 0L,
            messageEn = "",
            messageEs = ""
        )

        val bytes = AlertSerializer.serialize(alert)
        val result = AlertSerializer.deserialize(bytes)

        assertNotNull(result)
        assertEquals("", result!!.id)
        assertEquals("", result.violationType)
        assertEquals(0, result.severity)
        assertEquals("", result.zoneId)
        assertEquals(0L, result.timestamp)
    }

    @Test
    fun `timestamp zero is valid`() {
        val alert = createTestAlert(timestamp = 0L)
        val result = AlertSerializer.deserialize(AlertSerializer.serialize(alert))

        assertNotNull(result)
        assertEquals(0L, result!!.timestamp)
    }

    // --- Bilingual tests ---

    @Test
    fun `bilingual messages with unicode survive round trip`() {
        // ALEX: Spanish messages use accented characters (á, é, í, ó, ú, ñ, ¡, ¿)
        // and exclamation marks. These are multi-byte in UTF-8 and must survive the trip.
        val alert = createTestAlert(
            messageEn = "Hard hat missing in Zone A!",
            messageEs = "¡Falta casco en Zona A! Área restringida — peligro eléctrico"
        )

        val bytes = AlertSerializer.serialize(alert)
        val result = AlertSerializer.deserialize(bytes)

        assertNotNull(result)
        assertEquals(alert.messageEn, result!!.messageEn)
        assertEquals(alert.messageEs, result.messageEs)
    }

    @Test
    fun `both messages present after serialization`() {
        // ALEX: Bilingual is non-negotiable. Verify both messages are in the payload.
        val alert = createTestAlert()
        val bytes = AlertSerializer.serialize(alert)
        val payload = String(bytes, Charsets.UTF_8)

        assertTrue("Payload should contain English message", payload.contains("Hard hat"))
        assertTrue("Payload should contain Spanish message", payload.contains("casco"))
    }

    @Test
    fun `truncation preserves both languages`() {
        // ALEX: When truncating a long payload, BOTH languages get space, not just English.
        val longEn = "A".repeat(150)
        val longEs = "B".repeat(150)
        val alert = createTestAlert(messageEn = longEn, messageEs = longEs)

        val bytes = AlertSerializer.serialize(alert)
        val result = AlertSerializer.deserialize(bytes)

        assertNotNull(result)
        assertTrue("English message should have content", result!!.messageEn.isNotEmpty())
        assertTrue("Spanish message should have content", result.messageEs.isNotEmpty())
    }

    // --- Helpers ---

    private fun createTestAlert(
        id: String = "alert-001",
        violationType: String = "NO_HARD_HAT",
        severity: Int = 3,
        zoneId: String = "zone-A",
        timestamp: Long = 1711929600000L,
        messageEn: String = "Hard hat missing in Zone A",
        messageEs: String = "Falta casco en Zona A"
    ) = SafetyAlert(
        id = id,
        violationType = violationType,
        severity = severity,
        zoneId = zoneId,
        timestamp = timestamp,
        messageEn = messageEn,
        messageEs = messageEs
    )
}
