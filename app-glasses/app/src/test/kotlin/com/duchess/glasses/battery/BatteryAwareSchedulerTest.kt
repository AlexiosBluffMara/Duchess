package com.duchess.glasses.battery

import com.duchess.glasses.model.InferenceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for BatteryAwareScheduler.
 *
 * Alex: These tests are the MOST IMPORTANT unit tests in the glasses app.
 * If the battery thresholds are wrong, either:
 *   a) The glasses die mid-shift because we ran inference too aggressively, or
 *   b) We suspend inference too early and the worker loses safety protection
 *
 * Both are safety hazards on a construction site. Get these right.
 *
 * We test the pure function modeForBatteryLevel() which has no Android dependencies.
 * The BroadcastReceiver plumbing is tested in instrumentation tests on real hardware.
 */
class BatteryAwareSchedulerTest {

    // ===== THRESHOLD BOUNDARY TESTS =====
    // Alex: Test EXACTLY at the boundary values. Off-by-one errors in threshold
    // comparisons are the #1 cause of "it worked in development but not in the field."

    @Test
    fun `100 percent battery returns FULL mode`() {
        assertEquals(InferenceMode.FULL, BatteryAwareScheduler.modeForBatteryLevel(100))
    }

    @Test
    fun `50 percent battery returns FULL mode (at threshold)`() {
        // Alex: EXACTLY at the FULL threshold. >= 50 means 50 IS full.
        assertEquals(InferenceMode.FULL, BatteryAwareScheduler.modeForBatteryLevel(50))
    }

    @Test
    fun `49 percent battery returns REDUCED mode (just below FULL threshold)`() {
        // Alex: One percent below FULL threshold. This is where REDUCED kicks in.
        assertEquals(InferenceMode.REDUCED, BatteryAwareScheduler.modeForBatteryLevel(49))
    }

    @Test
    fun `30 percent battery returns REDUCED mode (at threshold)`() {
        assertEquals(InferenceMode.REDUCED, BatteryAwareScheduler.modeForBatteryLevel(30))
    }

    @Test
    fun `29 percent battery returns MINIMAL mode (just below REDUCED threshold)`() {
        assertEquals(InferenceMode.MINIMAL, BatteryAwareScheduler.modeForBatteryLevel(29))
    }

    @Test
    fun `15 percent battery returns MINIMAL mode (at threshold)`() {
        assertEquals(InferenceMode.MINIMAL, BatteryAwareScheduler.modeForBatteryLevel(15))
    }

    @Test
    fun `14 percent battery returns SUSPENDED mode (just below MINIMAL threshold)`() {
        // Alex: Below 15% — kill inference entirely. BLE alerts only.
        assertEquals(InferenceMode.SUSPENDED, BatteryAwareScheduler.modeForBatteryLevel(14))
    }

    @Test
    fun `0 percent battery returns SUSPENDED mode`() {
        assertEquals(InferenceMode.SUSPENDED, BatteryAwareScheduler.modeForBatteryLevel(0))
    }

    // ===== RANGE TESTS =====
    // Alex: Test representative values within each range to confirm the entire
    // range maps correctly, not just the boundaries.

    @Test
    fun `75 percent battery returns FULL mode`() {
        assertEquals(InferenceMode.FULL, BatteryAwareScheduler.modeForBatteryLevel(75))
    }

    @Test
    fun `40 percent battery returns REDUCED mode`() {
        assertEquals(InferenceMode.REDUCED, BatteryAwareScheduler.modeForBatteryLevel(40))
    }

    @Test
    fun `20 percent battery returns MINIMAL mode`() {
        assertEquals(InferenceMode.MINIMAL, BatteryAwareScheduler.modeForBatteryLevel(20))
    }

    @Test
    fun `5 percent battery returns SUSPENDED mode`() {
        assertEquals(InferenceMode.SUSPENDED, BatteryAwareScheduler.modeForBatteryLevel(5))
    }

    @Test
    fun `1 percent battery returns SUSPENDED mode`() {
        assertEquals(InferenceMode.SUSPENDED, BatteryAwareScheduler.modeForBatteryLevel(1))
    }

    // ===== EDGE CASES =====

    @Test
    fun `negative battery level returns SUSPENDED (defensive)`() {
        // Alex: Negative battery level shouldn't happen, but hardware is weird.
        // BatteryManager returns -1 when it can't read the battery. If our
        // percentage calculation goes negative, we should suspend (safe default).
        assertEquals(InferenceMode.SUSPENDED, BatteryAwareScheduler.modeForBatteryLevel(-1))
    }

    @Test
    fun `battery level above 100 returns FULL`() {
        // Alex: Some battery managers report over 100% during certain charging states.
        // Not our problem, but we should handle it gracefully.
        assertEquals(InferenceMode.FULL, BatteryAwareScheduler.modeForBatteryLevel(110))
    }

    // ===== THRESHOLD CONSTANTS TESTS =====

    @Test
    fun `thresholds are in descending order`() {
        // Alex: FULL > REDUCED > MINIMAL. If someone rearranges these, the
        // when-clause in modeForBatteryLevel would produce wrong results.
        assertTrue(
            "Thresholds must be FULL > REDUCED > MINIMAL",
            BatteryAwareScheduler.THRESHOLD_FULL >
                BatteryAwareScheduler.THRESHOLD_REDUCED &&
                BatteryAwareScheduler.THRESHOLD_REDUCED >
                BatteryAwareScheduler.THRESHOLD_MINIMAL
        )
    }

    @Test
    fun `FULL threshold is 50`() {
        assertEquals(50, BatteryAwareScheduler.THRESHOLD_FULL)
    }

    @Test
    fun `REDUCED threshold is 30`() {
        assertEquals(30, BatteryAwareScheduler.THRESHOLD_REDUCED)
    }

    @Test
    fun `MINIMAL threshold is 15`() {
        assertEquals(15, BatteryAwareScheduler.THRESHOLD_MINIMAL)
    }

    // ===== INFERENCE MODE FPS VALUES =====

    @Test
    fun `FULL mode is 10 FPS`() {
        assertEquals(10, InferenceMode.FULL.fps)
    }

    @Test
    fun `REDUCED mode is 5 FPS`() {
        assertEquals(5, InferenceMode.REDUCED.fps)
    }

    @Test
    fun `MINIMAL mode is 2 FPS`() {
        assertEquals(2, InferenceMode.MINIMAL.fps)
    }

    @Test
    fun `SUSPENDED mode is 0 FPS`() {
        assertEquals(0, InferenceMode.SUSPENDED.fps)
    }

    @Test
    fun `FPS values are strictly decreasing with modes`() {
        // Alex: FULL > REDUCED > MINIMAL > SUSPENDED. This ordering is critical
        // for battery drain calculations.
        assertTrue(InferenceMode.FULL.fps > InferenceMode.REDUCED.fps)
        assertTrue(InferenceMode.REDUCED.fps > InferenceMode.MINIMAL.fps)
        assertTrue(InferenceMode.MINIMAL.fps > InferenceMode.SUSPENDED.fps)
    }

    // ===== MONOTONICITY TEST =====

    @Test
    fun `mode degrades monotonically as battery decreases`() {
        // Alex: As battery goes from 100% to 0%, the mode should only go
        // FULL → REDUCED → MINIMAL → SUSPENDED, never jump backwards.
        // This is a regression test for threshold ordering bugs.
        var previousMode = InferenceMode.FULL
        for (level in 100 downTo 0) {
            val mode = BatteryAwareScheduler.modeForBatteryLevel(level)
            assertTrue(
                "Mode at ${level}% should be <= previous mode, but got $mode after $previousMode",
                mode.fps <= previousMode.fps
            )
            previousMode = mode
        }
    }

    // Alex: Helper for threshold ordering assertion
    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }
}
