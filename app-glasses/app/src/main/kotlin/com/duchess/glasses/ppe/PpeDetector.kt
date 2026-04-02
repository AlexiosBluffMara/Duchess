package com.duchess.glasses.ppe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.duchess.glasses.model.Detection
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TFLite interpreter wrapper for YOLOv8-nano PPE detection.
 * Loads `yolov8_nano_ppe.tflite` from assets.
 *
 * Model: INT8 quantized, GPU delegate with NNAPI fallback.
 * Input: 640x480 bitmap scaled to model input size (320x320).
 * Output: List of [Detection] with bounding boxes and labels.
 */
class PpeDetector(private val context: Context) : Closeable {

    companion object {
        private const val MODEL_FILE = "yolov8_nano_ppe.tflite"
        private const val INPUT_SIZE = 320
        private const val NUM_CLASSES = 4
        private const val CONFIDENCE_THRESHOLD = 0.5f

        val LABELS = listOf("hardhat", "vest", "no_hardhat", "no_vest")
    }

    private val interpreter: Interpreter by lazy { createInterpreter() }

    private fun createInterpreter(): Interpreter {
        val model = loadModelFile()
        val options = Interpreter.Options().apply {
            numThreads = 2
            // Try GPU delegate first, fall back to NNAPI, then CPU
            try {
                addDelegate(GpuDelegate())
            } catch (_: Exception) {
                useNNAPI = true
            }
        }
        return Interpreter(model, options)
    }

    private fun loadModelFile(): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(MODEL_FILE)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Run PPE detection on a camera frame.
     *
     * @param bitmap Camera frame (640x480 from CameraSession)
     * @return List of detections above confidence threshold
     */
    suspend fun detect(bitmap: Bitmap): List<Detection> {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val inputBuffer = bitmapToByteBuffer(scaledBitmap)

        // TODO: Replace with actual model output parsing when real model is loaded
        // Output shape depends on YOLOv8-nano export format
        // Typical: [1, num_detections, 4 + num_classes]
        val outputBuffer = Array(1) { Array(100) { FloatArray(NUM_CLASSES + 4) } }

        try {
            interpreter.run(inputBuffer, outputBuffer)
        } catch (_: Exception) {
            // Model file is a placeholder — return empty detections
            return emptyList()
        }

        return parseDetections(outputBuffer[0], bitmap.width, bitmap.height)
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            buffer.putFloat((pixel and 0xFF) / 255.0f)           // B
        }

        buffer.rewind()
        return buffer
    }

    private fun parseDetections(
        output: Array<FloatArray>,
        imageWidth: Int,
        imageHeight: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        for (row in output) {
            // row format: [x_center, y_center, width, height, class_0, class_1, ...]
            val classScores = row.drop(4)
            val maxScore = classScores.maxOrNull() ?: 0f
            if (maxScore < CONFIDENCE_THRESHOLD) continue

            val classIndex = classScores.indexOf(maxScore)
            val label = LABELS.getOrElse(classIndex) { "unknown" }

            val cx = row[0] * imageWidth
            val cy = row[1] * imageHeight
            val w = row[2] * imageWidth
            val h = row[3] * imageHeight

            val bbox = RectF(
                cx - w / 2f,
                cy - h / 2f,
                cx + w / 2f,
                cy + h / 2f
            )

            detections.add(Detection(label, maxScore, bbox))
        }

        return detections
    }

    override fun close() {
        interpreter.close()
    }
}
