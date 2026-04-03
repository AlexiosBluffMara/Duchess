package com.duchess.companion.stream

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.types.VideoFrame

/**
 * Convert a raw [VideoFrame] buffer to an Android [Bitmap] for display or ML inference.
 *
 * The Meta DAT SDK delivers frames as raw RGBA ByteBuffer data. Bitmap.copyPixelsFromBuffer
 * reads the buffer in RGBA order, which matches the SDK's output format.
 *
 * Returns null on any conversion failure (e.g., buffer underflow, invalid dimensions).
 * Callers (GemmaInferenceEngine, StreamScreen preview) handle null gracefully.
 */
fun VideoFrame.toBitmap(): Bitmap? {
    return try {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val copy = buffer.duplicate().also { it.rewind() }
        bitmap.copyPixelsFromBuffer(copy)
        bitmap
    } catch (e: Exception) {
        null
    }
}
