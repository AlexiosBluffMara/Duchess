package com.duchess.glasses.display

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.SurfaceHolder
import com.duchess.glasses.model.Detection

/**
 * Canvas-based overlay renderer for the Vuzix M400 640x360 OLED display.
 * Draws bounding boxes around detected PPE violations and bilingual alert text.
 *
 * Design constraints (from maya/instructions):
 * - Max 4 words per alert (glanceable at arm's length)
 * - Large icons, dark background (saves OLED power)
 * - No touch interaction (gloves) — display only
 */
class HudRenderer {

    companion object {
        const val DISPLAY_WIDTH = 640
        const val DISPLAY_HEIGHT = 360

        // Violation colors
        val COLOR_VIOLATION = Color.RED
        val COLOR_COMPLIANT = Color.GREEN
        val COLOR_TEXT_BG = Color.argb(180, 0, 0, 0)
    }

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
        isFakeBoldText = true
    }

    private val textBgPaint = Paint().apply {
        color = COLOR_TEXT_BG
        style = Paint.Style.FILL
    }

    /**
     * Render detection results onto the HUD surface.
     * Called per-frame from the detection pipeline.
     */
    fun render(surfaceHolder: SurfaceHolder, detections: List<Detection>) {
        val canvas = surfaceHolder.lockCanvas() ?: return
        try {
            // Dark background (OLED power saving)
            canvas.drawColor(Color.BLACK)

            for (detection in detections) {
                drawDetection(canvas, detection)
            }

            // Status bar at top
            if (detections.isEmpty()) {
                drawStatusText(canvas, "OK", "Sin alertas", Color.GREEN)
            } else {
                val violations = detections.count { it.label.startsWith("no_") }
                if (violations > 0) {
                    drawStatusText(canvas, "PPE ALERT", "ALERTA EPP", Color.RED)
                }
            }
        } finally {
            surfaceHolder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawDetection(canvas: Canvas, detection: Detection) {
        val isViolation = detection.label.startsWith("no_")
        boxPaint.color = if (isViolation) COLOR_VIOLATION else COLOR_COMPLIANT

        // Scale bbox from camera coords (640x480) to display coords (640x360)
        val scaleX = DISPLAY_WIDTH.toFloat() / 640f
        val scaleY = DISPLAY_HEIGHT.toFloat() / 480f
        val scaledBox = RectF(
            detection.bbox.left * scaleX,
            detection.bbox.top * scaleY,
            detection.bbox.right * scaleX,
            detection.bbox.bottom * scaleY
        )

        canvas.drawRect(scaledBox, boxPaint)

        // Label text — bilingual, max 4 words
        val label = labelToDisplay(detection.label)
        val confidence = "${(detection.confidence * 100).toInt()}%"
        val displayText = "$label $confidence"

        val textX = scaledBox.left
        val textY = scaledBox.top - 8f

        // Background behind text for readability
        val textWidth = textPaint.measureText(displayText)
        canvas.drawRect(textX - 2f, textY - 28f, textX + textWidth + 4f, textY + 4f, textBgPaint)
        canvas.drawText(displayText, textX, textY, textPaint)
    }

    private fun drawStatusText(canvas: Canvas, textEn: String, textEs: String, color: Int) {
        val statusPaint = Paint(textPaint).apply {
            this.color = color
            textSize = 32f
        }

        // English on left, Spanish on right
        canvas.drawText(textEn, 16f, 36f, statusPaint)
        val esWidth = statusPaint.measureText(textEs)
        canvas.drawText(textEs, DISPLAY_WIDTH - esWidth - 16f, 36f, statusPaint)
    }

    /**
     * Map model label to bilingual short display text (max 4 words).
     */
    private fun labelToDisplay(label: String): String = when (label) {
        "hardhat" -> "Hardhat/Casco"
        "vest" -> "Vest/Chaleco"
        "no_hardhat" -> "NO HARDHAT"
        "no_vest" -> "NO VEST"
        else -> label
    }
}
