package com.duchess.companion.model

/**
 * Safety alert broadcast across the Duchess mesh network.
 *
 * Alex: This data class is the SINGLE source of truth for alert structure
 * across the entire platform. It flows through:
 *   - Gemma 3n inference output → SafetyAlert
 *   - Mesh broadcast payload → SafetyAlert serialized to JSON
 *   - BLE GATT notification → SafetyAlert serialized to bytes
 *   - Cloud escalation API → SafetyAlert in request body
 *   - Push notification content → SafetyAlert.messageEn / messageEs
 *
 * PRIVACY (NON-NEGOTIABLE):
 *   This class must NEVER contain worker identity fields.
 *   NO name, face ID, badge number, employee ID, or exact GPS coordinates.
 *   Only zone-level location (zoneId), violation type, severity, and
 *   bilingual message text.
 *
 *   If you need to add a field, run SafetyAlertTest first — it has a PII
 *   field detector that will catch suspicious field names. This is by design.
 *
 * BILINGUAL (NON-NEGOTIABLE):
 *   Every alert MUST have both messageEn and messageEs. Construction sites
 *   have bilingual workers. A missing translation = a worker who can't read
 *   the safety alert. That's a liability issue, not just a UX issue.
 *
 * @property id Unique alert identifier (UUID format)
 * @property violationType Machine-readable violation code (e.g., "NO_HARD_HAT")
 * @property severity 0-5 scale where 0=info, 3=warning, 5=critical
 * @property zoneId Site zone identifier (e.g., "zone-A") — NOT exact GPS
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
)
