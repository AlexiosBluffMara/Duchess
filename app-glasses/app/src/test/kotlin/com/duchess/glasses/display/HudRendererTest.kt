package com.duchess.glasses.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for HudRenderer's static/companion methods.
 *
 * Alex: HudRenderer is a custom View, which makes it hard to unit test without
 * Robolectric (and Robolectric doesn't match the Vuzix M400's OLED behavior).
 * So we test the LOGIC methods in the companion object:
 *   - labelToDisplay: label → bilingual display string
 *   - batteryColor: percentage → color int
 *
 * The actual rendering (onDraw, Canvas operations) is tested visually on real
 * hardware and in screenshot tests (TODO: add screenshot test infrastructure).
 *
 * These tests also verify our 4-word-max constraint for HUD labels, which is
 * critical for readability on the 640x360 display.
 */
class HudRendererTest {

    // ===== labelToDisplay TESTS =====

    @Test
    fun `hardhat label displays as Hardhat`() {
        assertEquals("Hardhat", HudRenderer.labelToDisplay("hardhat"))
    }

    @Test
    fun `no_hardhat label displays as NO HARDHAT`() {
        // Alex: Violations are UPPERCASE for maximum visibility on the tiny display.
        assertEquals("NO HARDHAT", HudRenderer.labelToDisplay("no_hardhat"))
    }

    @Test
    fun `vest label displays as Vest`() {
        assertEquals("Vest", HudRenderer.labelToDisplay("vest"))
    }

    @Test
    fun `no_vest label displays as NO VEST`() {
        assertEquals("NO VEST", HudRenderer.labelToDisplay("no_vest"))
    }

    @Test
    fun `glasses label displays as Glasses`() {
        assertEquals("Glasses", HudRenderer.labelToDisplay("glasses"))
    }

    @Test
    fun `no_glasses label displays as NO GLASSES`() {
        assertEquals("NO GLASSES", HudRenderer.labelToDisplay("no_glasses"))
    }

    @Test
    fun `gloves label displays as Gloves`() {
        assertEquals("Gloves", HudRenderer.labelToDisplay("gloves"))
    }

    @Test
    fun `no_gloves label displays as NO GLOVES`() {
        assertEquals("NO GLOVES", HudRenderer.labelToDisplay("no_gloves"))
    }

    @Test
    fun `person label displays as empty string`() {
        // Alex: "person" is a model anchor class, not useful info for the worker.
        // Showing "PERSON" on the HUD would be confusing and waste screen space.
        assertEquals("", HudRenderer.labelToDisplay("person"))
    }

    @Test
    fun `unknown label displays as uppercase`() {
        // Alex: Future-proofing. If the ML team adds a new class, we uppercase it
        // by default rather than crashing or showing nothing.
        assertEquals("UNKNOWN_CLASS", HudRenderer.labelToDisplay("unknown_class"))
    }

    // ===== 4-WORD MAX CONSTRAINT =====

    @Test
    fun `all known labels are 4 words or fewer`() {
        // Alex: The M400's 640x360 display can show about 4 words per line at our
        // font size before text wraps or gets cut off. Every label must respect this.
        val labels = listOf("hardhat", "no_hardhat", "vest", "no_vest",
            "glasses", "no_glasses", "gloves", "no_gloves", "person")

        for (label in labels) {
            val display = HudRenderer.labelToDisplay(label)
            if (display.isNotEmpty()) {
                val wordCount = display.trim().split("\\s+".toRegex()).size
                assertTrue(
                    "Label '$label' → '$display' has $wordCount words (max 4)",
                    wordCount <= 4
                )
            }
        }
    }

    @Test
    fun `violation labels are all uppercase`() {
        // Alex: Violations should be SHOUTING — all caps for instant recognition.
        val violations = listOf("no_hardhat", "no_vest", "no_glasses", "no_gloves")

        for (label in violations) {
            val display = HudRenderer.labelToDisplay(label)
            assertEquals(
                "Violation label '$label' should be all uppercase",
                display, display.uppercase()
            )
        }
    }

    // ===== batteryColor TESTS =====

    @Test
    fun `battery at 100 is green`() {
        assertEquals(HudRenderer.COLOR_OK, HudRenderer.batteryColor(100))
    }

    @Test
    fun `battery at 50 is green (at threshold)`() {
        assertEquals(HudRenderer.COLOR_OK, HudRenderer.batteryColor(50))
    }

    @Test
    fun `battery at 49 is yellow (just below green threshold)`() {
        assertEquals(HudRenderer.COLOR_WARNING, HudRenderer.batteryColor(49))
    }

    @Test
    fun `battery at 20 is yellow (at yellow threshold)`() {
        assertEquals(HudRenderer.COLOR_WARNING, HudRenderer.batteryColor(20))
    }

    @Test
    fun `battery at 19 is red (just below yellow threshold)`() {
        assertEquals(HudRenderer.COLOR_VIOLATION, HudRenderer.batteryColor(19))
    }

    @Test
    fun `battery at 0 is red`() {
        assertEquals(HudRenderer.COLOR_VIOLATION, HudRenderer.batteryColor(0))
    }

    @Test
    fun `battery at 10 is red`() {
        assertEquals(HudRenderer.COLOR_VIOLATION, HudRenderer.batteryColor(10))
    }

    @Test
    fun `battery at 35 is yellow`() {
        assertEquals(HudRenderer.COLOR_WARNING, HudRenderer.batteryColor(35))
    }

    @Test
    fun `battery at 75 is green`() {
        assertEquals(HudRenderer.COLOR_OK, HudRenderer.batteryColor(75))
    }

    // ===== COLOR CONSTANTS TESTS =====

    @Test
    fun `color constants are distinct`() {
        // Alex: Green, red, and yellow must be distinct or the HUD is useless
        // for communicating safety status at a glance.
        val colors = setOf(HudRenderer.COLOR_OK, HudRenderer.COLOR_VIOLATION, HudRenderer.COLOR_WARNING)
        assertEquals("Safety colors must be distinct", 3, colors.size)
    }

    @Test
    fun `OK color is green-ish`() {
        // Alex: Extract green channel and verify it's dominant
        val green = (HudRenderer.COLOR_OK shr 8) and 0xFF
        assertTrue("OK color green channel should be high: $green", green > 150)
    }

    @Test
    fun `violation color is red-ish`() {
        val red = (HudRenderer.COLOR_VIOLATION shr 16) and 0xFF
        assertTrue("Violation color red channel should be high: $red", red > 200)
    }

    @Test
    fun `warning color is yellow-ish`() {
        val red = (HudRenderer.COLOR_WARNING shr 16) and 0xFF
        val green = (HudRenderer.COLOR_WARNING shr 8) and 0xFF
        assertTrue("Warning color should have high red: $red", red > 200)
        assertTrue("Warning color should have high green: $green", green > 200)
    }

    // ===== DIMENSION CONSTANTS =====

    @Test
    fun `status bar height is reasonable for 360px display`() {
        // Alex: Status bar shouldn't take more than 20% of the 360px height
        assertTrue(
            "Status bar too tall for 640x360 display",
            HudRenderer.STATUS_BAR_HEIGHT <= 72f // 20% of 360
        )
    }

    @Test
    fun `diagnostic bar height is reasonable`() {
        assertTrue(
            "Diagnostic bar too tall",
            HudRenderer.DIAG_BAR_HEIGHT <= 40f
        )
    }

    @Test
    fun `status text size is readable`() {
        // Alex: Minimum readable text on the M400's 640x360 display is about 18sp.
        // Our status text (the most important info) should be at least 24sp.
        assertTrue(
            "Status text too small for M400 display",
            HudRenderer.STATUS_TEXT_SIZE >= 24f
        )
    }

    @Test
    fun `violation box is thicker than ok box`() {
        // Alex: Violations need to stand out more than confirmed PPE.
        // Thicker stroke = more visible = worker notices faster.
        assertTrue(
            "Violation box should be thicker than OK box",
            HudRenderer.BOX_STROKE_WIDTH_VIOLATION > HudRenderer.BOX_STROKE_WIDTH
        )
    }
}
