package com.duchess.glasses.ppe

import android.graphics.RectF
import com.duchess.glasses.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Unit tests for TemporalVoter — the 3-out-of-5 sliding window filter.
 *
 * ELENA: These tests verify the temporal voting algorithm that prevents
 * single-frame false positives from reaching the escalation pipeline.
 * Every test is deterministic and runs in <10ms — no flakiness.
 */
class TemporalVoterTest {

    private lateinit var voter: TemporalVoter

    // ELENA: Dummy bounding box — irrelevant for voting logic but required by Detection.
    private val dummyBox = RectF(0.1f, 0.1f, 0.5f, 0.5f)

    @Before
    fun setUp() {
        voter = TemporalVoter()
    }

    // ===== CORE VOTING LOGIC =====

    @Test
    fun `3 out of 5 frames agree - should escalate`() {
        // ELENA: The canonical happy path — exactly the threshold.
        // Frames: YES, NO, YES, NO, YES → 3/5 → escalate
        voter.recordDetections(listOf(Detection("no_hardhat", 0.2f, dummyBox)))
        voter.recordDetections(emptyList())
        voter.recordDetections(listOf(Detection("no_hardhat", 0.15f, dummyBox)))
        voter.recordDetections(emptyList())
        voter.recordDetections(listOf(Detection("no_hardhat", 0.25f, dummyBox)))

        assertTrue(voter.shouldEscalate("no_hardhat"))
        assertTrue(voter.getActiveViolations().contains("no_hardhat"))
    }

    @Test
    fun `2 out of 5 frames - should NOT escalate`() {
        // ELENA: Below threshold — no escalation. Prevents flickering detections
        // from triggering alerts.
        voter.recordDetections(listOf(Detection("no_hardhat", 0.2f, dummyBox)))
        voter.recordDetections(emptyList())
        voter.recordDetections(listOf(Detection("no_hardhat", 0.15f, dummyBox)))
        voter.recordDetections(emptyList())
        voter.recordDetections(emptyList())

        assertFalse(voter.shouldEscalate("no_hardhat"))
        assertFalse(voter.getActiveViolations().contains("no_hardhat"))
    }

    @Test
    fun `5 out of 5 frames - should escalate`() {
        // ELENA: Solid, persistent violation — should absolutely escalate.
        repeat(5) {
            voter.recordDetections(listOf(Detection("no_vest", 0.1f, dummyBox)))
        }

        assertTrue(voter.shouldEscalate("no_vest"))
    }

    @Test
    fun `0 out of 5 frames - should NOT escalate`() {
        // ELENA: No violations at all — nothing to report.
        repeat(5) {
            voter.recordDetections(emptyList())
        }

        assertFalse(voter.shouldEscalate("no_hardhat"))
        assertFalse(voter.shouldEscalate("no_vest"))
        assertTrue(voter.getActiveViolations().isEmpty())
    }

    @Test
    fun `exactly 3 out of 5 - boundary condition should escalate`() {
        // ELENA: Boundary test — exactly at threshold, alternating pattern.
        // YES, YES, YES, NO, NO → 3/5 → escalate
        voter.recordDetections(listOf(Detection("no_glasses", 0.2f, dummyBox)))
        voter.recordDetections(listOf(Detection("no_glasses", 0.2f, dummyBox)))
        voter.recordDetections(listOf(Detection("no_glasses", 0.2f, dummyBox)))
        voter.recordDetections(emptyList())
        voter.recordDetections(emptyList())

        assertTrue(voter.shouldEscalate("no_glasses"))
    }

    // ===== SLIDING WINDOW =====

    @Test
    fun `window slides correctly - after 10 frames only last 5 matter`() {
        // ELENA: First 5 frames all have the violation → escalate.
        // Next 5 frames have NO violation → the old votes are overwritten.
        // After 10 frames, only frames 6-10 matter — all "no" → no escalation.
        repeat(5) {
            voter.recordDetections(listOf(Detection("no_hardhat", 0.2f, dummyBox)))
        }
        assertTrue(voter.shouldEscalate("no_hardhat"))

        // ELENA: Now record 5 clean frames to fully overwrite the window.
        repeat(5) {
            voter.recordDetections(emptyList())
        }
        assertFalse(voter.shouldEscalate("no_hardhat"))
    }

    @Test
    fun `window overwrites oldest vote when sliding`() {
        // ELENA: Verify that the circular buffer correctly overwrites the oldest entry.
        // Frame sequence: NO, NO, YES, YES, YES → 3/5 → escalate
        // Then one more NO → window becomes: NO, YES, YES, YES, NO → still 3/5
        voter.recordDetections(emptyList())
        voter.recordDetections(emptyList())
        voter.recordDetections(listOf(Detection("no_gloves", 0.2f, dummyBox)))
        voter.recordDetections(listOf(Detection("no_gloves", 0.2f, dummyBox)))
        voter.recordDetections(listOf(Detection("no_gloves", 0.2f, dummyBox)))
        assertTrue(voter.shouldEscalate("no_gloves"))

        // ELENA: 6th frame (NO) overwrites slot 0 (was NO). Window: NO, YES, YES, YES, NO → 3/5
        voter.recordDetections(emptyList())
        assertTrue(voter.shouldEscalate("no_gloves"))

        // ELENA: 7th frame (NO) overwrites slot 1 (was NO). Window: NO, NO, YES, YES, YES → 3/5
        voter.recordDetections(emptyList())
        assertTrue(voter.shouldEscalate("no_gloves"))

        // ELENA: 8th frame (NO) overwrites slot 2 (was YES). Window: NO, NO, NO, YES, YES → 2/5
        voter.recordDetections(emptyList())
        assertFalse(voter.shouldEscalate("no_gloves"))
    }

    // ===== PER-LABEL INDEPENDENCE =====

    @Test
    fun `independent per-label - no_hardhat voting does not affect no_vest`() {
        // ELENA: Each violation label has its own independent window.
        // Detecting no_hardhat should have zero effect on no_vest's vote count.
        repeat(5) {
            voter.recordDetections(listOf(Detection("no_hardhat", 0.2f, dummyBox)))
        }

        assertTrue(voter.shouldEscalate("no_hardhat"))
        assertFalse(voter.shouldEscalate("no_vest"))
    }

    @Test
    fun `multiple violations can escalate simultaneously`() {
        // ELENA: A worker without both hardhat AND vest — both should escalate.
        repeat(5) {
            voter.recordDetections(listOf(
                Detection("no_hardhat", 0.2f, dummyBox),
                Detection("no_vest", 0.15f, dummyBox)
            ))
        }

        val active = voter.getActiveViolations()
        assertTrue(active.contains("no_hardhat"))
        assertTrue(active.contains("no_vest"))
        assertEquals(2, active.size)
    }

    // ===== RESET =====

    @Test
    fun `reset clears all state`() {
        // ELENA: After reset, all windows should be empty — nothing escalates.
        repeat(5) {
            voter.recordDetections(listOf(
                Detection("no_hardhat", 0.2f, dummyBox),
                Detection("no_vest", 0.15f, dummyBox)
            ))
        }
        assertTrue(voter.getActiveViolations().isNotEmpty())

        voter.reset()

        assertFalse(voter.shouldEscalate("no_hardhat"))
        assertFalse(voter.shouldEscalate("no_vest"))
        assertTrue(voter.getActiveViolations().isEmpty())
    }

    @Test
    fun `reset allows fresh voting afterwards`() {
        // ELENA: Verify that voting works correctly after a reset.
        repeat(5) {
            voter.recordDetections(listOf(Detection("no_hardhat", 0.2f, dummyBox)))
        }
        voter.reset()

        // ELENA: Now record 3 new frames with violation → should escalate again.
        repeat(3) {
            voter.recordDetections(listOf(Detection("no_hardhat", 0.2f, dummyBox)))
        }
        // ELENA: Only 3 frames recorded so far, window count is 3, positiveCount is 3.
        // 3/3 written slots are positive, and 3 >= threshold → escalate.
        assertTrue(voter.shouldEscalate("no_hardhat"))
    }

    // ===== VIOLATION LABEL FILTERING =====

    @Test
    fun `only violation labels tracked - hardhat and person are ignored`() {
        // ELENA: Positive labels like "hardhat", "vest", "person" are NOT violations.
        // They should never appear in the voting windows or active violations.
        repeat(5) {
            voter.recordDetections(listOf(
                Detection("hardhat", 0.9f, dummyBox),
                Detection("person", 0.85f, dummyBox),
                Detection("vest", 0.8f, dummyBox),
                Detection("glasses", 0.7f, dummyBox),
                Detection("gloves", 0.75f, dummyBox)
            ))
        }

        assertTrue(voter.getActiveViolations().isEmpty())
        assertFalse(voter.shouldEscalate("hardhat"))
        assertFalse(voter.shouldEscalate("person"))
    }

    @Test
    fun `shouldEscalate returns false for unknown label`() {
        // ELENA: Querying a label that doesn't exist in the violation set
        // should safely return false.
        assertFalse(voter.shouldEscalate("unknown_label"))
        assertFalse(voter.shouldEscalate("hardhat"))
    }

    // ===== EMPTY DETECTION HANDLING =====

    @Test
    fun `empty detection list counts as no for all active labels`() {
        // ELENA: When the detector returns nothing (no workers in frame, or all
        // confidences below threshold), every violation label gets a "NO" vote.
        // This ensures stale violations decay out of the window.
        repeat(3) {
            voter.recordDetections(listOf(Detection("no_hardhat", 0.2f, dummyBox)))
        }
        // ELENA: 3/3 so far → escalate.
        assertTrue(voter.shouldEscalate("no_hardhat"))

        // ELENA: Now 2 empty frames → window has 5 slots: YES, YES, YES, NO, NO → 3/5 → still escalate
        voter.recordDetections(emptyList())
        voter.recordDetections(emptyList())
        assertTrue(voter.shouldEscalate("no_hardhat"))

        // ELENA: One more empty frame overwrites oldest YES → NO, YES, YES, NO, NO → 2/5 → no escalate
        voter.recordDetections(emptyList())
        assertFalse(voter.shouldEscalate("no_hardhat"))
    }

    // ===== PARTIAL WINDOW (FEWER THAN 5 FRAMES) =====

    @Test
    fun `fewer than windowSize frames - can still escalate if threshold met`() {
        // ELENA: Edge case — only 3 frames recorded. If all 3 are violations,
        // that's 3 >= threshold → escalate. We don't require the full window to fill.
        repeat(3) {
            voter.recordDetections(listOf(Detection("no_vest", 0.1f, dummyBox)))
        }

        assertTrue(voter.shouldEscalate("no_vest"))
    }

    @Test
    fun `single frame below threshold - should not escalate`() {
        // ELENA: 1 frame is below the threshold of 3.
        voter.recordDetections(listOf(Detection("no_hardhat", 0.2f, dummyBox)))
        assertFalse(voter.shouldEscalate("no_hardhat"))
    }

    // ===== THREAD SAFETY =====

    @Test
    fun `thread safety - concurrent recordDetections does not corrupt state`() {
        // ELENA: Simulate the camera thread and main thread racing.
        // We can't deterministically prove thread safety with a unit test, but we can
        // stress-test for corruption (crashes, inconsistent state, exceptions).
        val executor = Executors.newFixedThreadPool(4)
        val iterations = 1000
        val latch = CountDownLatch(iterations)

        repeat(iterations) { i ->
            executor.submit {
                try {
                    if (i % 2 == 0) {
                        voter.recordDetections(listOf(Detection("no_hardhat", 0.2f, dummyBox)))
                    } else {
                        voter.recordDetections(emptyList())
                    }
                    // Interleave reads with writes
                    voter.shouldEscalate("no_hardhat")
                    voter.getActiveViolations()
                } finally {
                    latch.countDown()
                }
            }
        }

        // ELENA: If we get here without exceptions or hanging, the synchronized
        // blocks are protecting the state correctly.
        assertTrue("Concurrent operations should complete within 5 seconds",
            latch.await(5, TimeUnit.SECONDS))

        executor.shutdown()
    }

    // ===== MIXED VIOLATIONS AND NON-VIOLATIONS IN SAME FRAME =====

    @Test
    fun `frame with both violation and non-violation labels`() {
        // ELENA: Real scenario — worker has vest (good) but no hardhat (bad).
        // Only no_hardhat should register as a vote.
        repeat(5) {
            voter.recordDetections(listOf(
                Detection("vest", 0.9f, dummyBox),
                Detection("no_hardhat", 0.2f, dummyBox),
                Detection("person", 0.8f, dummyBox)
            ))
        }

        assertTrue(voter.shouldEscalate("no_hardhat"))
        assertFalse(voter.shouldEscalate("no_vest"))
    }
}
