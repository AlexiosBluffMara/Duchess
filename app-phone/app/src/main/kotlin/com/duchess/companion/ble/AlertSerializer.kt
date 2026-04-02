package com.duchess.companion.ble

import com.duchess.companion.model.SafetyAlert

/**
 * Serializes/deserializes SafetyAlert to/from pipe-delimited BLE payload bytes.
 *
 * ALEX: This is the SINGLE AUTHORITATIVE serialization contract for safety alerts sent
 * over BLE GATT from phone (server) to glasses (client). The wire format is:
 *
 *   id|violationType|severity|zoneId|timestamp|messageEn|messageEs
 *
 * Why pipe-delimited and not JSON? BLE MTU is ~247 bytes after ATT header negotiation.
 * JSON overhead (braces, colons, quotes, key names) costs ~80 extra bytes. Pipe-delimited
 * keeps a typical alert at ~120 bytes vs ~200 for JSON. That 80-byte headroom matters
 * on the Qualcomm XR1 where the BLE radio is shared with WiFi.
 *
 * ESCAPING: Field values may contain pipe chars (unlikely for IDs, possible for messages).
 * We escape `\` as `\\` and `|` as `\|`. The glasses-side AlertDeserializer uses the
 * same escaping scheme — they MUST stay in sync.
 *
 * PRIVACY: This serializer NEVER includes worker identity, face data, badge numbers,
 * or exact GPS coordinates. Only zone-level location and violation metadata. This is
 * enforced by the SafetyAlert data class design and verified by SafetyAlertTest's PII scanner.
 */
object AlertSerializer {

    // ALEX: 240 bytes safe max payload. BLE MTU negotiates to ~247 after ATT overhead,
    // but device firmware varies. Some budget Android phones negotiate 244. The Vuzix M400
    // sometimes gets 251. We use 240 as a conservative floor that works everywhere.
    const val MAX_PAYLOAD_SIZE = 240

    /**
     * Serialize a SafetyAlert into BLE-ready bytes.
     *
     * ALEX: If the UTF-8 payload exceeds MAX_PAYLOAD_SIZE, messageEn and messageEs are
     * truncated symmetrically. Messages are display-only — truncation doesn't lose critical
     * safety data. The id, violationType, severity, zoneId, and timestamp are ALWAYS
     * preserved in full because downstream systems key on these fields.
     *
     * @return UTF-8 encoded pipe-delimited payload, guaranteed <= MAX_PAYLOAD_SIZE bytes
     */
    fun serialize(alert: SafetyAlert): ByteArray {
        val fullPayload = buildPayload(
            alert.id, alert.violationType, alert.severity,
            alert.zoneId, alert.timestamp, alert.messageEn, alert.messageEs
        )
        val fullBytes = fullPayload.toByteArray(Charsets.UTF_8)
        if (fullBytes.size <= MAX_PAYLOAD_SIZE) return fullBytes

        // ALEX: Payload too big — truncate messages. Build the fixed prefix first
        // to calculate how many bytes remain for the two message fields.
        val prefix = buildPrefix(
            alert.id, alert.violationType, alert.severity,
            alert.zoneId, alert.timestamp
        )
        val prefixByteSize = prefix.toByteArray(Charsets.UTF_8).size
        // -1 for the pipe delimiter between messageEn and messageEs
        val budgetForMessages = MAX_PAYLOAD_SIZE - prefixByteSize - 1

        if (budgetForMessages <= 0) {
            // ALEX: Even the prefix exceeds MTU (astronomically unlikely — would need
            // a ~200-char violation type). Send empty messages as last resort.
            return "$prefix|".toByteArray(Charsets.UTF_8)
        }

        // ALEX: Split budget evenly between EN and ES. Construction sites are bilingual —
        // neither language gets preferential space allocation.
        val perMessageBudget = budgetForMessages / 2
        val escapedEn = escapeField(alert.messageEn)
        val escapedEs = escapeField(alert.messageEs)
        val truncEn = truncateUtf8(escapedEn, perMessageBudget)
        // ALEX: Give any remaining bytes from EN's allocation to ES
        val truncEs = truncateUtf8(
            escapedEs,
            budgetForMessages - truncEn.toByteArray(Charsets.UTF_8).size
        )

        return "$prefix$truncEn|$truncEs".toByteArray(Charsets.UTF_8)
    }

    /**
     * Deserialize BLE payload bytes back to a SafetyAlert.
     *
     * ALEX: This is the phone-side deserializer (same model package). The glasses side
     * has its own AlertDeserializer that produces the glasses-side SafetyAlert. Both use
     * identical parsing logic — the wire format is the contract between them.
     *
     * @return SafetyAlert if valid, null if malformed (wrong field count, unparseable numbers, etc.)
     */
    fun deserialize(bytes: ByteArray): SafetyAlert? {
        return try {
            val str = String(bytes, Charsets.UTF_8)
            val parts = splitEscaped(str)
            if (parts.size < 7) return null

            SafetyAlert(
                id = unescapeField(parts[0]),
                violationType = unescapeField(parts[1]),
                severity = parts[2].toIntOrNull() ?: return null,
                zoneId = unescapeField(parts[3]),
                timestamp = parts[4].toLongOrNull() ?: return null,
                messageEn = unescapeField(parts[5]),
                messageEs = unescapeField(parts[6])
            )
        } catch (_: Exception) {
            // ALEX: Malformed payload. Return null — caller decides what to do.
            // Don't log the raw bytes (could theoretically contain PII if another
            // app wrote garbage to the GATT characteristic).
            null
        }
    }

    // --- Internal helpers ---

    private fun buildPayload(
        id: String, violationType: String, severity: Int,
        zoneId: String, timestamp: Long, messageEn: String, messageEs: String
    ): String {
        return "${escapeField(id)}|${escapeField(violationType)}|$severity" +
            "|${escapeField(zoneId)}|$timestamp" +
            "|${escapeField(messageEn)}|${escapeField(messageEs)}"
    }

    // ALEX: Builds the prefix portion (everything up to and including the pipe before messageEn).
    // The trailing pipe is included so callers can append truncated messages directly.
    private fun buildPrefix(
        id: String, violationType: String, severity: Int,
        zoneId: String, timestamp: Long
    ): String {
        return "${escapeField(id)}|${escapeField(violationType)}|$severity" +
            "|${escapeField(zoneId)}|$timestamp|"
    }

    // ALEX: Escape pipe and backslash so they don't break the delimiter format.
    // Order matters: escape backslash FIRST (so we don't double-escape the pipe's backslash),
    // then pipe.
    internal fun escapeField(value: String): String =
        value.replace("\\", "\\\\").replace("|", "\\|")

    // ALEX: Unescape a field value. Walks char by char to handle escape sequences correctly.
    // Can't just do replace("\\|", "|") because that would mishandle "\\|" (escaped backslash
    // followed by pipe delimiter).
    internal fun unescapeField(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            if (value[i] == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '|' -> { sb.append('|'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(value[i]); i++ }
                }
            } else {
                sb.append(value[i])
                i++
            }
        }
        return sb.toString()
    }

    // ALEX: Split string by UNESCAPED pipe delimiters. String.split("|") won't work
    // because escaped \| must NOT be treated as a delimiter boundary.
    internal fun splitEscaped(input: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < input.length) {
            when {
                input[i] == '\\' && i + 1 < input.length -> {
                    // ALEX: Escape sequence — keep both chars, they'll be unescaped later
                    current.append(input[i])
                    current.append(input[i + 1])
                    i += 2
                }
                input[i] == '|' -> {
                    parts.add(current.toString())
                    current.clear()
                    i++
                }
                else -> {
                    current.append(input[i])
                    i++
                }
            }
        }
        parts.add(current.toString())
        return parts
    }

    // ALEX: Truncate a string so its UTF-8 byte representation fits in maxBytes.
    // Iterates character-by-character to avoid cutting multi-byte codepoints mid-sequence.
    // Spanish messages often have multi-byte characters (á, é, í, ñ, ¡, etc.).
    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val fullBytes = value.toByteArray(Charsets.UTF_8)
        if (fullBytes.size <= maxBytes) return value

        var byteCount = 0
        val sb = StringBuilder()
        for (char in value) {
            val charByteSize = char.toString().toByteArray(Charsets.UTF_8).size
            if (byteCount + charByteSize > maxBytes) break
            sb.append(char)
            byteCount += charByteSize
        }
        return sb.toString()
    }
}
