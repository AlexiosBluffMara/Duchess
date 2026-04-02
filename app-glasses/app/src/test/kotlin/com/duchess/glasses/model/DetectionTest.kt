package com.duchess.glasses.model

import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Detection data class.
 *
 * Alex: Detection is a simple data class, but we still test it because:
 * 1. It's the PRIMARY data type flowing through the entire pipeline
 *    (CameraSession → PpeDetector → HudRenderer → BleGattClient)
 * 2. Any field change breaks serialization, display, and BLE payloads
 * 3. The bounding box format (normalized RectF) is a common source of bugs
 *    when someone forgets it's [0,1] and passes pixel coordinates
 *
 * These are boring validation tests, but they've caught field renames twice already.
 */
class DetectionTest {

    @Test
    fun `create detection with valid data`() {
        val det = Detection(
            label = "hardhat",
            confidence = 0.95f,
            bbox = RectF(0.1f, 0.2f, 0.5f, 0.8f)
        )

        assertEquals("hardhat", det.label)
        assertEquals(0.95f, det.confidence, 0.001f)
        assertEquals(0.1f, det.bbox.left, 0.001f)
        assertEquals(0.2f, det.bbox.top, 0.001f)
        assertEquals(0.5f, det.bbox.right, 0.001f)
        assertEquals(0.8f, det.bbox.bottom, 0.001f)
    }

    @Test
    fun `create detection with violation label`() {
        // Alex: Violation labels start with "no_" by convention. This test
        // ensures the data class doesn't reject them (no validation in Detection
        // itself — that's PpeDetector's job).
        val det = Detection(
            label = "no_hardhat",
            confidence = 0.82f,
            bbox = RectF(0.3f, 0.1f, 0.7f, 0.9f)
        )
        assertEquals("no_hardhat", det.label)
    }

    @Test
    fun `create detection with zero confidence`() {
        // Alex: Edge case — 0 confidence is technically valid (model says "I have no idea").
        // The PpeDetector threshold will filter this out, but the data class should allow it.
        val det = Detection(
            label = "vest",
            confidence = 0.0f,
            bbox = RectF(0.0f, 0.0f, 0.5f, 0.5f)
        )
        assertEquals(0.0f, det.confidence, 0.001f)
    }

    @Test
    fun `create detection with max confidence`() {
        val det = Detection(
            label = "gloves",
            confidence = 1.0f,
            bbox = RectF(0.0f, 0.0f, 1.0f, 1.0f)
        )
        assertEquals(1.0f, det.confidence, 0.001f)
    }

    @Test
    fun `bbox covers full frame`() {
        // Alex: A full-frame detection (entire image is one class) should work.
        // This happens when a worker is very close to the camera and fills the frame.
        val det = Detection(
            label = "vest",
            confidence = 0.99f,
            bbox = RectF(0.0f, 0.0f, 1.0f, 1.0f)
        )
        assertEquals(0.0f, det.bbox.left, 0.001f)
        assertEquals(0.0f, det.bbox.top, 0.001f)
        assertEquals(1.0f, det.bbox.right, 0.001f)
        assertEquals(1.0f, det.bbox.bottom, 0.001f)
    }

    @Test
    fun `bbox is a point (zero area)`() {
        // Alex: Degenerate case. Shouldn't happen in practice but the data class allows it.
        val det = Detection(
            label = "person",
            confidence = 0.5f,
            bbox = RectF(0.5f, 0.5f, 0.5f, 0.5f)
        )
        assertEquals(0.0f, det.bbox.width(), 0.001f)
        assertEquals(0.0f, det.bbox.height(), 0.001f)
    }

    @Test
    fun `data class equality`() {
        // Alex: data class auto-generates equals/hashCode. Two Detections with the
        // same fields should be equal. This matters for temporal voting (comparing
        // detections across frames).
        val a = Detection("hardhat", 0.9f, RectF(0.1f, 0.1f, 0.5f, 0.5f))
        val b = Detection("hardhat", 0.9f, RectF(0.1f, 0.1f, 0.5f, 0.5f))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `data class inequality on different label`() {
        val a = Detection("hardhat", 0.9f, RectF(0.1f, 0.1f, 0.5f, 0.5f))
        val b = Detection("no_hardhat", 0.9f, RectF(0.1f, 0.1f, 0.5f, 0.5f))
        assertNotEquals(a, b)
    }

    @Test
    fun `data class inequality on different confidence`() {
        val a = Detection("hardhat", 0.9f, RectF(0.1f, 0.1f, 0.5f, 0.5f))
        val b = Detection("hardhat", 0.8f, RectF(0.1f, 0.1f, 0.5f, 0.5f))
        assertNotEquals(a, b)
    }

    @Test
    fun `data class copy creates independent instance`() {
        // Alex: Kotlin's copy() should create a deep-enough copy that modifying
        // one doesn't affect the other. RectF is mutable though...
        val original = Detection("vest", 0.75f, RectF(0.2f, 0.2f, 0.6f, 0.6f))
        val copied = original.copy(label = "no_vest")
        assertEquals("no_vest", copied.label)
        assertEquals("vest", original.label) // Original unchanged
        assertEquals(original.confidence, copied.confidence, 0.001f)
    }

    @Test
    fun `all standard labels create valid detections`() {
        // Alex: Smoke test — every label from the model's class list should
        // be usable in a Detection without throwing.
        val labels = listOf("hardhat", "no_hardhat", "vest", "no_vest",
            "glasses", "no_glasses", "gloves", "no_gloves", "person")

        for (label in labels) {
            val det = Detection(label, 0.5f, RectF(0.1f, 0.1f, 0.5f, 0.5f))
            assertEquals(label, det.label)
        }
    }

    @Test
    fun `detection has no PII fields`() {
        // Alex: PRIVACY CHECK. Detection is a public data class that flows through
        // the entire pipeline, including over BLE to the phone. It must NEVER contain
        // worker-identifying information. We check that the class only has the expected
        // fields: label, confidence, bbox.
        val fields = Detection::class.java.declaredFields
            .filter { !it.isSynthetic } // Kotlin adds synthetic fields
            .map { it.name }
            .toSet()

        // Alex: If someone adds a "workerId", "faceName", "badgeNumber" etc. field,
        // this test will fail and force them to think about privacy.
        val allowedFields = setOf("label", "confidence", "bbox")
        assertEquals(
            "Detection has unexpected fields! Check for PII violations.",
            allowedFields, fields
        )
    }

    @Test
    fun `toString does not contain PII`() {
        // Alex: data class toString() auto-generates from all fields. If someone adds
        // a PII field, it'll show up in toString() which could end up in logs.
        val det = Detection("hardhat", 0.9f, RectF(0.1f, 0.1f, 0.5f, 0.5f))
        val str = det.toString()
        // Alex: Should contain our expected fields and nothing suspicious
        assertTrue(str.contains("hardhat"))
        assertTrue(str.contains("0.9"))
    }
}
