package com.duchess.glasses.model

import android.graphics.RectF

/**
 * A single object detection result from the PPE detector.
 *
 * @param label The detected class (e.g., "hardhat", "vest", "no_hardhat", "no_vest")
 * @param confidence Detection confidence score in [0, 1]
 * @param bbox Bounding box in normalized coordinates relative to the input image
 */
data class Detection(
    val label: String,
    val confidence: Float,
    val bbox: RectF
)
