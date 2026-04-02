package com.duchess.companion.upload

/**
 * Anonymized metadata attached to each nightly batch video upload.
 *
 * Alex: This is the ONLY metadata that leaves the jobsite with video uploads.
 * Every field here has been vetted against the Duchess privacy requirements:
 *   - No worker identity (no name, badge, face ID, employee ID)
 *   - No exact GPS (zone-level only, e.g. "zone-A")
 *   - No device identifiers (pseudonymId is a rotating UUID, not hardware ID)
 *   - No file names (could leak shift timing patterns)
 *
 * The cloud receives this metadata alongside the encrypted video file.
 * It's enough for retrospective analysis (which zone, when, how long)
 * without being able to identify any individual worker.
 *
 * @property zoneId Site zone identifier (e.g., "zone-A") — NOT lat/lon coordinates
 * @property timestamp Unix epoch millis when video segment was recorded
 * @property durationMs Duration of the video segment in milliseconds
 * @property fileSizeBytes Size of the video file in bytes
 * @property pseudonymId Rotating anonymous device identifier (UUID format, NOT real device ID)
 */
data class UploadMetadata(
    val zoneId: String,
    val timestamp: Long,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val pseudonymId: String
)
