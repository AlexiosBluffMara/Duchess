package com.duchess.glasses.ble

import com.duchess.glasses.model.SafetyAlert

/**
 * Deserializes BLE alert payloads into the glasses-side SafetyAlert.
 *
 * ALEX: This is the glasses-side mirror of the phone's AlertSerializer. The phone
 * serializes with AlertSerializer.serialize(), sends bytes over BLE GATT, and the
 * glasses deserialize with this class. The pipe-delimited format and escape scheme
 * MUST match exactly:
 *
 *   id|violationType|severity|zoneId|timestamp|messageEn|messageEs
 *
 * Escaping: `\` as `\\`, `|` as `\|` — same as phone side.
 *
 * THE CONTRACT: phone's AlertSerializer.serialize(alert).let { AlertDeserializer.deserialize(it) }
 * MUST produce an equivalent SafetyAlert (modulo message truncation if payload > 240 bytes).
 * If you change the format here, you MUST change AlertSerializer.kt in app-phone too.
 *
 * PRIVACY: This deserializer never produces a SafetyAlert with worker identity data
 * because the wire format has no such fields. Defense in depth — even if someone modified
 * the phone's serializer to add PII fields, this parser wouldn't know how to extract them.
 *
 * NOTE: The glasses-side SafetyAlert has runtime require() checks (severity 0-5, non-blank
 * messages). If the payload violates those constraints, we return null instead of crashing.
 */
object AlertDeserializer {

    /**
     * Deserialize BLE payload bytes into a glasses-side SafetyAlert.
     *
     * ALEX: Returns null if:
     *   - Fewer than 7 pipe-delimited fields
     *   - severity isn't a valid int
     *   - timestamp isn't a valid long
     *   - severity is outside 0-5 (glasses SafetyAlert.init require)
     *   - messageEn or messageEs is blank (glasses SafetyAlert.init require)
     *
     * @param bytes Raw bytes from the GATT notification characteristic
     * @return Parsed SafetyAlert, or null if malformed
     */
    fun deserialize(bytes: ByteArray): SafetyAlert? {
        return try {
            val str = String(bytes, Charsets.UTF_8)
            val parts = splitEscaped(str)
            if (parts.size < 7) return null

            val severity = parts[2].toIntOrNull() ?: return null
            val timestamp = parts[4].toLongOrNull() ?: return null
            val messageEn = unescapeField(parts[5])
            val messageEs = unescapeField(parts[6])

            // ALEX: The glasses SafetyAlert has require(severity in 0..5) and
            // require(messageEn.isNotBlank()). Rather than letting the constructor
            // throw, we validate here and return null for invalid payloads.
            if (severity !in 0..5) return null
            if (messageEn.isBlank() || messageEs.isBlank()) return null

            SafetyAlert(
                id = unescapeField(parts[0]),
                violationType = unescapeField(parts[1]),
                severity = severity,
                zoneId = unescapeField(parts[3]),
                timestamp = timestamp,
                messageEn = messageEn,
                messageEs = messageEs
            )
        } catch (_: Exception) {
            // ALEX: Malformed payload — don't crash the glasses. A null return
            // means the HUD simply won't display this alert. Better than a crash
            // that blacks out the worker's display mid-task.
            null
        }
    }

    // ALEX: Unescape a field value. Identical logic to phone's AlertSerializer.unescapeField().
    // Duplicated here (not shared) because glasses and phone have NO shared module dependency —
    // they communicate exclusively over BLE. The wire format IS the shared contract.
    private fun unescapeField(value: String): String {
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

    // ALEX: Split string by unescaped pipe delimiters. Identical to phone's AlertSerializer.splitEscaped().
    private fun splitEscaped(input: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < input.length) {
            when {
                input[i] == '\\' && i + 1 < input.length -> {
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
}
