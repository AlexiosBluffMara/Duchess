package com.duchess.glasses.ppe

import com.duchess.glasses.model.Detection

/**
 * Temporal voting filter for PPE violation detection on the Vuzix M400.
 *
 * ELENA: The core problem — a single YOLOv8-nano frame can produce a false positive
 * due to camera motion blur, momentary occlusion, or head tilt. On a construction site,
 * a false PPE violation alert erodes trust fast. Temporal voting requires 3 out of 5
 * consecutive frames to agree before we escalate, which filters transient noise while
 * keeping latency low (~500ms at 10 FPS for 5 frames).
 *
 * ELENA: Memory design — the Vuzix M400 has only 500MB ML budget. Each violation label
 * gets a fixed BooleanArray(5) window plus an Int cursor. With ~4 violation classes
 * (no_hardhat, no_vest, no_glasses, no_gloves), that's ~24 bytes per label × 4 = ~96 bytes.
 * Well under the 1KB budget even with overhead.
 *
 * ELENA: Thread safety — camera callbacks arrive on the camera thread while the main
 * thread reads active violations for BLE escalation. We use @Synchronized rather than
 * locks or atomics because the critical sections are tiny (<1μs) and contention is
 * near-zero in practice (10 FPS camera thread vs. occasional main-thread reads).
 */
class TemporalVoter(
    // ELENA: 5-frame window at 10 FPS = 500ms lookback. Configurable for tuning,
    // but 5 is the spec default. Smaller windows are noisier; larger windows add latency.
    private val windowSize: Int = DEFAULT_WINDOW_SIZE,
    // ELENA: 3/5 majority. At 10 FPS this means a real violation persists for ≥300ms
    // before escalation — fast enough for safety, slow enough to filter glitches.
    private val threshold: Int = DEFAULT_THRESHOLD
) {

    // ELENA: Known violation labels in the YOLOv8-nano PPE model.
    // Only "no_*" prefixed labels are violations. Positive labels like "hardhat",
    // "vest", "person" are NOT violations and must never enter the voting pipeline.
    companion object {
        const val VIOLATION_PREFIX = "no_"
        const val DEFAULT_WINDOW_SIZE = 5
        const val DEFAULT_THRESHOLD = 3

        // ELENA: All violation labels the model can produce.
        // Fixed set — if a new violation class is added to the model, add it here.
        val VIOLATION_LABELS = setOf("no_hardhat", "no_vest", "no_glasses", "no_gloves")
    }

    // ELENA: Per-label sliding window state. Fixed-size circular buffer using BooleanArray.
    // The cursor wraps around via modulo — no allocations after init.
    private class VoteWindow(size: Int) {
        val votes = BooleanArray(size)  // circular buffer of frame votes
        var cursor: Int = 0             // next write position
        var count: Int = 0              // how many slots have been written (up to size)

        fun record(detected: Boolean) {
            votes[cursor] = detected
            cursor = (cursor + 1) % votes.size
            if (count < votes.size) count++
        }

        fun positiveCount(): Int {
            // ELENA: Only count slots that have actually been written to.
            // Before the window is full, we only look at [0, count) entries.
            var sum = 0
            for (i in 0 until count) {
                if (votes[i]) sum++
            }
            return sum
        }

        fun reset() {
            votes.fill(false)
            cursor = 0
            count = 0
        }
    }

    // ELENA: Pre-allocate windows for all known violation labels at construction time.
    // This avoids any runtime allocation — critical on the M400 to avoid GC pauses.
    private val windows: Map<String, VoteWindow> = VIOLATION_LABELS.associateWith { VoteWindow(windowSize) }

    /**
     * Record one frame's detection results into the temporal voting windows.
     *
     * ELENA: This is called once per frame with ALL detections from YOLOv8-nano.
     * For each known violation label, we check if it appears in this frame's detections.
     * If it does → vote YES. If it doesn't → vote NO. Both advance the sliding window.
     * This means absence of a detection is actively counted as evidence against violation,
     * which is exactly what we want for temporal smoothing.
     */
    @Synchronized
    fun recordDetections(detections: List<Detection>) {
        // ELENA: Build a set of violation labels detected in this frame.
        // We filter to only violation labels to avoid polluting the windows.
        val detectedViolations = HashSet<String>(detections.size)
        for (d in detections) {
            if (d.label in VIOLATION_LABELS) {
                detectedViolations.add(d.label)
            }
        }

        // ELENA: Every known violation label gets a vote — detected or not.
        // This is key: if "no_hardhat" was seen in 3 frames but not in the next 2,
        // those 2 "NO" votes push it below threshold and prevent stale escalation.
        for ((label, window) in windows) {
            window.record(label in detectedViolations)
        }
    }

    /**
     * Check if a specific violation label meets the temporal escalation threshold.
     *
     * @return true if [threshold] or more of the last [windowSize] frames had this violation.
     */
    @Synchronized
    fun shouldEscalate(violationLabel: String): Boolean {
        val window = windows[violationLabel] ?: return false
        return window.positiveCount() >= threshold
    }

    /**
     * Get all violation labels currently meeting the temporal escalation threshold.
     *
     * ELENA: This is the primary API for the escalation pipeline — called after
     * recordDetections() to determine if any violations warrant BLE escalation to Tier 2.
     */
    @Synchronized
    fun getActiveViolations(): List<String> {
        val active = mutableListOf<String>()
        for ((label, window) in windows) {
            if (window.positiveCount() >= threshold) {
                active.add(label)
            }
        }
        return active
    }

    /**
     * Reset all voting state. Call on pipeline restart or when the worker changes.
     */
    @Synchronized
    fun reset() {
        for ((_, window) in windows) {
            window.reset()
        }
    }
}
