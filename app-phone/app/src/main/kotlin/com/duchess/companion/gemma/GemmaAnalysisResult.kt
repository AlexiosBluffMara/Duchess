package com.duchess.companion.gemma

/**
 * Typed result of a Gemma 4 E2B multimodal analysis of a construction site video frame.
 *
 * PRIVACY: Contains ONLY violation metadata — no image data, no worker identity,
 * no exact GPS coordinates. Safe to log (severity/type only, no PII) and to
 * transmit through the PPE escalation pipeline to cloud reviewers.
 *
 * @param violationDetected Whether any safety violation or hazard was detected.
 * @param violationType     Violation category (e.g. "NO_HARD_HAT") or null if none detected.
 * @param severity          0 = none, 1 = minor, 2 = moderate, 3 = serious,
 *                          4 = severe, 5 = critical/life-threatening.
 * @param descriptionEn     Human-readable English description for supervisor review.
 * @param descriptionEs     Human-readable Spanish description for bilingual field workers.
 * @param confidence        Model confidence in this result, clamped to [0.0, 1.0].
 */
data class GemmaAnalysisResult(
    val violationDetected: Boolean,
    val violationType: String?,
    val severity: Int,
    val descriptionEn: String,
    val descriptionEs: String,
    val confidence: Double,
)
