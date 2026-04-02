package com.meta.wearable.dat.camera.types

import android.graphics.Bitmap

/**
 * Stub for DAT SDK VideoFrame.
 * In real SDK, this wraps a Bitmap from the glasses camera.
 */
data class VideoFrame(
    val bitmap: Bitmap? = null,
    val timestampMs: Long = System.currentTimeMillis(),
)
