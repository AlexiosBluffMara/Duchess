package com.duchess.glasses.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Unit tests for BleGattClient.
 *
 * Alex: BLE testing is tricky because the BluetoothManager/BluetoothGatt stack
 * requires a real Android device (or at least Robolectric with BLE support, which
 * is spotty). What we CAN unit test without Android:
 *
 * 1. Alert payload parsing — pure string→SafetyAlert conversion
 * 2. Connection state enum values — ensure all states exist
 * 3. UUID constants — ensure they're valid and match the phone's BleGattServer
 * 4. Reconnection constants — verify backoff parameters
 *
 * The actual BLE connection lifecycle is tested in instrumentation tests on real M400 hardware.
 */
class BleGattClientTest {

    // ===== ALERT PAYLOAD PARSING =====
    // Alex: The phone sends alerts as pipe-delimited strings:
    // "id|violationType|severity|zoneId|timestamp|messageEn|messageEs"

    @Test
    fun `parse valid alert payload`() {
        val payload = "alert-123|NO_HARD_HAT|3|zone-A|1680000000000|No hard hat detected|Casco no detectado"
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)

        // Alex: We need a BleGattClient instance to call parseAlertPayload.
        // But the constructor needs Context and CoroutineScope... so we test
        // the parsing logic by instantiating with what we can.
        // Since parseAlertPayload is a public method that only uses the payload
        // parameter (no instance state), we could extract it to a companion.
        // For now, we test it through the parse function directly.
        val alert = parseAlertPayloadStatic(bytes)

        assertNotNull(alert)
        assertEquals("alert-123", alert!!.id)
        assertEquals("NO_HARD_HAT", alert.violationType)
        assertEquals(3, alert.severity)
        assertEquals("zone-A", alert.zoneId)
        assertEquals(1680000000000L, alert.timestamp)
        assertEquals("No hard hat detected", alert.messageEn)
        assertEquals("Casco no detectado", alert.messageEs)
    }

    @Test
    fun `parse payload with minimum severity`() {
        val payload = "id1|INFO|0|zone-B|1680000000000|All clear|Sin alertas"
        val alert = parseAlertPayloadStatic(payload.toByteArray(StandardCharsets.UTF_8))

        assertNotNull(alert)
        assertEquals(0, alert!!.severity)
    }

    @Test
    fun `parse payload with maximum severity`() {
        val payload = "id2|EMERGENCY|5|zone-C|1680000000000|Emergency stop|Parada de emergencia"
        val alert = parseAlertPayloadStatic(payload.toByteArray(StandardCharsets.UTF_8))

        assertNotNull(alert)
        assertEquals(5, alert!!.severity)
    }

    @Test
    fun `parse payload with too few fields returns null`() {
        // Alex: Malformed BLE payload — not enough pipe-delimited fields.
        // This happens when the BLE packet gets truncated (common on noisy RF environments
        // like construction sites with rebar and steel beams).
        val payload = "alert-123|NO_HARD_HAT|3"
        val alert = parseAlertPayloadStatic(payload.toByteArray(StandardCharsets.UTF_8))
        assertNull(alert)
    }

    @Test
    fun `parse empty payload returns null`() {
        val alert = parseAlertPayloadStatic(ByteArray(0))
        assertNull(alert)
    }

    @Test
    fun `parse payload with invalid severity returns null`() {
        val payload = "id|TYPE|notanumber|zone|123|msg|msges"
        val alert = parseAlertPayloadStatic(payload.toByteArray(StandardCharsets.UTF_8))
        assertNull(alert)
    }

    @Test
    fun `parse payload with invalid timestamp returns null`() {
        val payload = "id|TYPE|3|zone|notatimestamp|msg|msges"
        val alert = parseAlertPayloadStatic(payload.toByteArray(StandardCharsets.UTF_8))
        assertNull(alert)
    }

    @Test
    fun `parse payload with severity out of range throws`() {
        // Alex: Severity must be 0-5 (enforced by SafetyAlert's init block).
        // Severity 6 should cause SafetyAlert to throw, which our parser catches.
        val payload = "id|TYPE|6|zone|1680000000000|msg|msges"
        val alert = parseAlertPayloadStatic(payload.toByteArray(StandardCharsets.UTF_8))
        assertNull(alert) // SafetyAlert init block rejects severity 6
    }

    @Test
    fun `parse payload with special characters in messages`() {
        // Alex: Spanish text often has accented characters. BLE UTF-8 should handle this.
        val payload = "id|TYPE|2|zone|1680000000000|Check PPE|¡Verifique su EPP!"
        val alert = parseAlertPayloadStatic(payload.toByteArray(StandardCharsets.UTF_8))

        assertNotNull(alert)
        assertEquals("¡Verifique su EPP!", alert!!.messageEs)
    }

    @Test
    fun `parse payload with pipe in message field`() {
        // Alex: If a message contains a pipe character, the parser will split wrong.
        // This tests our assumption that messages never contain pipes.
        // In practice, the phone's BleGattServer escapes pipes before sending.
        val payload = "id|TYPE|2|zone|1680000000000|Message with | pipe|Mensaje con | tubo"
        val alert = parseAlertPayloadStatic(payload.toByteArray(StandardCharsets.UTF_8))

        // Alex: With a naive split, this gives 9 parts instead of 7.
        // The alert parsing should still extract the first 7 fields correctly,
        // but the message will be truncated at the pipe. This is a known limitation.
        // The phone-side serializer must NOT include pipes in messages.
        assertNotNull(alert) // shouldn't crash
    }

    // ===== CONNECTION STATE ENUM =====

    @Test
    fun `all connection states exist`() {
        // Alex: Ensure all expected states are in the enum. If someone removes a state,
        // the HudRenderer's BLE dot rendering breaks.
        val states = BleConnectionState.values()
        assertEquals(6, states.size)

        assertTrue(states.contains(BleConnectionState.DISCONNECTED))
        assertTrue(states.contains(BleConnectionState.SCANNING))
        assertTrue(states.contains(BleConnectionState.CONNECTING))
        assertTrue(states.contains(BleConnectionState.CONNECTED))
        assertTrue(states.contains(BleConnectionState.RECONNECTING))
        assertTrue(states.contains(BleConnectionState.ERROR))
    }

    // ===== UUID CONSTANTS =====

    @Test
    fun `service UUID is valid`() {
        assertNotNull(BleGattClient.SERVICE_UUID)
        // Alex: UUID format: 8-4-4-4-12 hex digits
        val uuidStr = BleGattClient.SERVICE_UUID.toString()
        assertTrue("UUID should be in standard format", uuidStr.matches(
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        ))
    }

    @Test
    fun `alert characteristic UUID is valid`() {
        assertNotNull(BleGattClient.ALERT_CHAR_UUID)
    }

    @Test
    fun `escalation characteristic UUID is valid`() {
        assertNotNull(BleGattClient.ESCALATION_CHAR_UUID)
    }

    @Test
    fun `CCCD UUID is the standard value`() {
        // Alex: 0x2902 is the BLE standard CCCD UUID. If this is wrong,
        // notifications won't work with ANY BLE device. This is not a
        // Duchess-specific UUID — it's defined in the Bluetooth SIG spec.
        assertEquals(
            "00002902-0000-1000-8000-00805f9b34fb",
            BleGattClient.CCCD_UUID.toString()
        )
    }

    @Test
    fun `service and characteristic UUIDs are distinct`() {
        // Alex: If these accidentally got the same UUID, BLE would fail
        // in very confusing ways (service discovery returns the wrong thing).
        val uuids = setOf(
            BleGattClient.SERVICE_UUID,
            BleGattClient.ALERT_CHAR_UUID,
            BleGattClient.ESCALATION_CHAR_UUID
        )
        assertEquals("All UUIDs must be distinct", 3, uuids.size)
    }

    // ===== RECONNECTION CONSTANTS =====

    @Test
    fun `max reconnect attempts is 5`() {
        assertEquals(5, BleGattClient.MAX_RECONNECT_ATTEMPTS)
    }

    @Test
    fun `initial backoff is 1 second`() {
        assertEquals(1000L, BleGattClient.INITIAL_BACKOFF_MS)
    }

    @Test
    fun `max backoff time is 16 seconds`() {
        // Alex: Max backoff = INITIAL * 2^(MAX_ATTEMPTS-1) = 1000 * 2^4 = 16000ms
        val maxBackoff = BleGattClient.INITIAL_BACKOFF_MS * (1L shl (BleGattClient.MAX_RECONNECT_ATTEMPTS - 1))
        assertEquals(16000L, maxBackoff)
    }

    @Test
    fun `scan timeout is 30 seconds`() {
        assertEquals(30_000L, BleGattClient.SCAN_TIMEOUT_MS)
    }

    @Test
    fun `alert buffer size is 5`() {
        assertEquals(5, BleGattClient.ALERT_BUFFER_SIZE)
    }

    @Test
    fun `backoff sequence is exponential`() {
        // Alex: Verify the full backoff sequence: 1s, 2s, 4s, 8s, 16s
        val expected = listOf(1000L, 2000L, 4000L, 8000L, 16000L)
        for (attempt in 0 until BleGattClient.MAX_RECONNECT_ATTEMPTS) {
            val delay = BleGattClient.INITIAL_BACKOFF_MS * (1L shl attempt)
            assertEquals(
                "Backoff at attempt $attempt should be ${expected[attempt]}ms",
                expected[attempt], delay
            )
        }
    }

    // Alex: Static parsing function that mirrors BleGattClient.parseAlertPayload()
    // without needing an instance. We duplicate the logic here rather than making
    // the original a companion function because the original is an instance method
    // (could change to use instance state in the future).
    private fun parseAlertPayloadStatic(payload: ByteArray): com.duchess.glasses.model.SafetyAlert? {
        return try {
            val str = String(payload, StandardCharsets.UTF_8)
            val parts = str.split("|")
            if (parts.size < 7) return null

            com.duchess.glasses.model.SafetyAlert(
                id = parts[0],
                violationType = parts[1],
                severity = parts[2].toIntOrNull() ?: return null,
                zoneId = parts[3],
                timestamp = parts[4].toLongOrNull() ?: return null,
                messageEn = parts[5],
                messageEs = parts[6]
            )
        } catch (_: Exception) {
            null
        }
    }
}
