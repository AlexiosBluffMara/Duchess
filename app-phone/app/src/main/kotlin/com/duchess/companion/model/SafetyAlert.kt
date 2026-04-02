package com.duchess.companion.model

/**
 * Safety alert broadcast across the Duchess mesh network.
 *
 * PRIVACY: This class must NEVER contain worker identity fields
 * (no name, face ID, badge number, or exact GPS coordinates).
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
