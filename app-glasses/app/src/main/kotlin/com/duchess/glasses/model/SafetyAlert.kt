package com.duchess.glasses.model

/**
 * Safety alert received from the companion phone via BLE, or generated locally
 * when the glasses detect a high-confidence PPE violation.
 *
 * Alex: This is the GLASSES-SIDE mirror of the phone's SafetyAlert data class.
 * Same fields, same privacy guarantees, different package. We keep them in sync
 * manually because the glasses app has NO shared module dependency with the phone
 * app — they communicate exclusively over BLE GATT. If you change a field here,
 * change it on the phone side too, or the BLE payload parsing will break silently.
 *
 * PRIVACY (NON-NEGOTIABLE):
 *   NO worker name, face ID, badge number, employee ID, or exact GPS.
 *   Only zone-level location, violation type, severity, and bilingual messages.
 *   The Vuzix M400 runs fully offline on AOSP — no data leaves the device except
 *   through the BLE escalation path to the companion phone. But even so, we don't
 *   collect PII in the first place. Defense in depth.
 *
 * BILINGUAL (NON-NEGOTIABLE):
 *   Construction sites are bilingual (EN/ES). Every alert MUST populate both
 *   messageEn and messageEs. A missing translation = a worker who can't read
 *   the safety alert on their 640x360 HUD. That's a liability issue.
 *
 * @property id Unique alert identifier (UUID format)
 * @property violationType Machine-readable violation code (e.g., "NO_HARD_HAT", "NO_VEST")
 * @property severity 0-5 scale: 0=info, 1=low, 2=medium, 3=warning, 4=high, 5=critical
 * @property zoneId Site zone identifier (e.g., "zone-A") — zone granularity, NOT exact GPS
 * @property timestamp Unix epoch millis when the violation was detected
 * @property messageEn Human-readable alert message in English
 * @property messageEs Human-readable alert message in Spanish
 */
data class SafetyAlert(
    val id: String,
    val violationType: String,
    val severity: Int,
    val zoneId: String,
    val timestamp: Long,
    val messageEn: String,
    val messageEs: String
) {
    init {
        // Alex: Runtime guard — severity must be 0-5. If this fires, someone is sending
        // garbage over BLE and we need to know about it immediately, not silently clip.
        require(severity in 0..5) { "Severity must be 0-5, got $severity" }
        // Alex: Bilingual is non-negotiable. Crash early in dev, not silently in the field.
        require(messageEn.isNotBlank()) { "English message is required" }
        require(messageEs.isNotBlank()) { "Spanish message is required" }
    }
}
