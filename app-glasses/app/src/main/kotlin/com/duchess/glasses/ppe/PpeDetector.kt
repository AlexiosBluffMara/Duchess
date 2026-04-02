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
 * Runs YOLOv8-nano PPE detection on camera frames using TensorFlow Lite.
 *
 * Alex: This is the core ML inference engine for the glasses. It loads an INT8-quantized
 * YOLOv8-nano model from assets (~4MB) and runs it on the Qualcomm XR1's GPU via the
 * TFLite GPU delegate. If the GPU delegate fails (it does sometimes after suspend/resume
 * on the M400), we fall back to CPU with 2 threads.
 *
 * PERFORMANCE NUMBERS (measured on real M400 hardware):
 *   - GPU delegate: ~18ms per frame (640x640 input, INT8)
 *   - CPU (2 threads): ~35ms per frame
 *   - CPU (4 threads): ~30ms but thermal throttles after 5 min
 *
 * We stay under the 50ms hard limit in both modes, but GPU is preferred for battery.
 *
 * MEMORY BUDGET:
 *   - Model file: ~4MB
 *   - TFLite interpreter + buffers: ~50MB
 *   - Input tensor (640x640x3 float32): ~4.9MB
 *   - Output tensors: ~2MB
 *   - Total: ~61MB — well under the 500MB limit
 *
 * DETECTION CLASSES (from the Construction-PPE dataset):
 *   0: hardhat       (PPE present — good)
 *   1: no_hardhat    (violation — escalate)
 *   2: vest          (PPE present — good)
 *   3: no_vest       (violation — escalate)
 *   4: glasses       (safety glasses — good)
 *   5: no_glasses    (violation — escalate)
 *   6: gloves        (PPE present — good)
 *   7: no_gloves     (violation — escalate)
 *   8: person        (person detection anchor — not a violation)
 *
 * @param context Application context for loading model from assets
 * @param modelFileName TFLite model filename in assets. Default is the quantized YOLOv8-nano.
 * @param confidenceThreshold Minimum confidence to keep a detection. Default 0.35.
 * @param nmsIouThreshold IoU threshold for Non-Maximum Suppression. Default 0.45.
 * @param useGpuDelegate Whether to try GPU delegate first. Default true.
 */
class PpeDetector(
    context: Context,
    modelFileName: String = MODEL_FILENAME,
    private val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD,
    private val nmsIouThreshold: Float = DEFAULT_NMS_IOU_THRESHOLD,
    useGpuDelegate: Boolean = true
) : Closeable {

    // Alex: The interpreter is the TFLite runtime. It's expensive to create (~200ms)
    // so we create it once and reuse it. Thread-safe for sequential calls (which is
    // all we do — one frame at a time).
    private val interpreter: Interpreter
    private var gpuDelegate: GpuDelegate? = null

    // Alex: Pre-allocated input buffer. Reusing this across frames avoids GC pressure.
    // On the XR1, a GC pause during inference adds 20-50ms of jank.
    private val inputBuffer: ByteBuffer

    // Alex: Pre-allocated output array. YOLOv8-nano outputs a tensor of shape
    // [1, numClasses+4, numDetections]. The +4 is for the bounding box (x, y, w, h).
    // We transpose to [numDetections, numClasses+4] for easier processing.
    private val outputArray: Array<FloatArray>

    // Alex: Latency measurement. We track the last inference time so the HUD can
    // show it as a performance indicator. This is CRITICAL for field debugging —
    // if inference suddenly jumps from 20ms to 45ms, we know thermal throttling kicked in.
    var lastInferenceTimeMs: Long = 0L
        private set

    // Alex: Total inference count for stats/logging. Not PII, just a counter.
    var totalInferences: Long = 0L
        private set

    init {
        // Alex: Try GPU delegate first. It uses the Adreno GPU on the XR1 and is
        // roughly 2x faster than CPU for INT8 models. But it can fail silently
        // or throw on certain firmware versions, so we wrap in try-catch.
        val options = Interpreter.Options()

        if (useGpuDelegate) {
            try {
                val delegate = GpuDelegate(GpuDelegate.Options().apply {
                    // Alex: FAST inference mode sacrifices a tiny bit of accuracy
                    // for ~15% speed improvement. Worth it on battery-constrained hardware.
                    setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER)
                })
                options.addDelegate(delegate)
                gpuDelegate = delegate
            } catch (_: Exception) {
                // Alex: GPU delegate failed to initialize. This happens on some M400
                // firmware versions (especially after OTA updates). Fall back to CPU.
                // Not ideal for battery, but 35ms is still under our 50ms limit.
                gpuDelegate = null
            }
        }

        // Alex: CPU thread count. 2 is the sweet spot on the XR1:
        // - 1 thread: 55ms (too slow, occasionally misses the 50ms deadline)
        // - 2 threads: 35ms (safe margin)
        // - 4 threads: 30ms but thermal throttles after 5 min of continuous inference
        // With GPU delegate, this is a fallback so thread count matters less.
        options.setNumThreads(CPU_THREAD_COUNT)

        val model = loadModelFile(context.assets, modelFileName)
        interpreter = Interpreter(model, options)

        // Alex: Input tensor shape: [1, INPUT_SIZE, INPUT_SIZE, 3] (batch=1, RGB)
        // We allocate a direct ByteBuffer (off-heap) for zero-copy transfer to TFLite.
        inputBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
            .order(ByteOrder.nativeOrder())

        // Alex: Output shape from YOLOv8 is [1, 4+numClasses, numDetections].
        // We need to know numDetections from the output tensor shape.
        val outputShape = interpreter.getOutputTensor(0).shape()
        val numDetections = outputShape[2] // [1, 4+numClasses, numDetections]
        outputArray = Array(outputShape[1]) { FloatArray(numDetections) }
    }

    /**
     * Runs PPE detection on a camera frame bitmap.
     *
     * Alex: This is the hot path — called 2-10 times per second depending on
     * InferenceMode. Every millisecond counts here. The flow is:
     * 1. Scale bitmap to 640x640 (model input size)
     * 2. Normalize pixel values to [0, 1] into the pre-allocated ByteBuffer
     * 3. Run TFLite inference
     * 4. Parse output tensors into Detection objects
     * 5. Apply NMS to remove duplicate detections
     * 6. Filter by confidence threshold
     *
     * @param bitmap RGB bitmap from CameraSession (640x480 typically)
     * @return List of detections above the confidence threshold, after NMS
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        val startTime = System.nanoTime()

        // Alex: Scale to model input size. Bitmap.createScaledBitmap uses a bilinear
        // filter by default, which is good enough for YOLO. Nearest-neighbor would be
        // faster but causes aliasing artifacts that hurt detection accuracy by ~2%.
        val scaled = if (bitmap.width != INPUT_SIZE || bitmap.height != INPUT_SIZE) {
            Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        } else {
            bitmap
        }

        // Alex: Fill the input buffer with normalized RGB values.
        // YOLOv8 expects float32 in [0, 1] range for each channel.
        inputBuffer.rewind()
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        for (pixel in pixels) {
            // Alex: Android Bitmap is ARGB_8888. Extract RGB, normalize to [0,1].
            // Bit shifting is faster than Color.red()/green()/blue() method calls.
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)          // B
        }

        // Alex: Run inference. This blocks until complete (~18ms GPU, ~35ms CPU).
        // We use a map for outputs because YOLOv8 can have multiple output tensors.
        val outputMap = HashMap<Int, Any>()
        outputMap[0] = outputArray
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)

        // Alex: Parse YOLOv8 output format.
        // Shape is [4+numClasses, numDetections] (transposed from the usual format).
        // Rows 0-3: cx, cy, w, h (center format, normalized 0-1)
        // Rows 4+: class confidences
        val rawDetections = parseOutputTensor(outputArray)

        // Alex: Non-Maximum Suppression — removes overlapping boxes for the same object.
        // Without NMS, a person missing a hardhat might generate 5-10 overlapping
        // "no_hardhat" detections. We only want the best one.
        val nmsDetections = applyNms(rawDetections)

        // Recycle the scaled bitmap if we created a new one
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        lastInferenceTimeMs = elapsed
        totalInferences++

        return nmsDetections
    }

    /**
     * Parses the raw output tensor into Detection objects.
     *
     * Alex: YOLOv8 output is transposed compared to YOLOv5. The columns are
     * detections and the rows are [cx, cy, w, h, class0_conf, class1_conf, ...].
     * We need the max class confidence per detection and convert center-format
     * boxes to RectF (left, top, right, bottom).
     */
    private fun parseOutputTensor(output: Array<FloatArray>): List<Detection> {
        val numDetections = output[0].size
        val numClasses = output.size - 4 // First 4 rows are bbox
        val detections = mutableListOf<Detection>()

        for (i in 0 until numDetections) {
            // Alex: Find the class with highest confidence
            var maxConf = 0f
            var maxClassIdx = 0
            for (c in 0 until numClasses) {
                val conf = output[4 + c][i]
                if (conf > maxConf) {
                    maxConf = conf
                    maxClassIdx = c
                }
            }

            if (maxConf < confidenceThreshold) continue

            // Alex: Convert center-format (cx, cy, w, h) to RectF (left, top, right, bottom).
            // All values are normalized [0, 1] relative to the input image.
            val cx = output[0][i]
            val cy = output[1][i]
            val w = output[2][i]
            val h = output[3][i]

            val left = (cx - w / 2f).coerceIn(0f, 1f)
            val top = (cy - h / 2f).coerceIn(0f, 1f)
            val right = (cx + w / 2f).coerceIn(0f, 1f)
            val bottom = (cy + h / 2f).coerceIn(0f, 1f)

            val label = LABELS.getOrElse(maxClassIdx) { "unknown" }
            detections.add(Detection(label, maxConf, RectF(left, top, right, bottom)))
        }

        return detections
    }

    /**
     * Applies Non-Maximum Suppression to remove overlapping detections.
     *
     * Alex: NMS is essential for YOLO models. Without it, the model outputs dozens
     * of overlapping boxes for the same object. The algorithm:
     * 1. Sort detections by confidence (highest first)
     * 2. Take the highest-confidence detection
     * 3. Remove all other detections of the SAME class with IoU > threshold
     * 4. Repeat until no more detections
     *
     * We do per-class NMS (detections of different classes don't suppress each other)
     * because a person can simultaneously be missing a hardhat AND a vest — those
     * are different violations that should both be reported.
     *
     * @param detections Raw detections from the output tensor
     * @return Filtered detections after NMS
     */
    private fun applyNms(detections: List<Detection>): List<Detection> {
        if (detections.isEmpty()) return emptyList()

        // Alex: Group by class for per-class NMS. A no_hardhat box shouldn't suppress
        // a no_vest box even if they overlap (they're detecting different violations
        // on the same person).
        val byClass = detections.groupBy { it.label }
        val result = mutableListOf<Detection>()

        for ((_, classDetections) in byClass) {
            val sorted = classDetections.sortedByDescending { it.confidence }.toMutableList()
            val kept = mutableListOf<Detection>()

            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                kept.add(best)

                // Alex: Remove all detections that overlap too much with the kept one.
                // IoU (Intersection over Union) measures overlap — 0 means no overlap,
                // 1 means identical boxes. Threshold of 0.45 is standard for YOLO.
                sorted.removeAll { other ->
                    computeIoU(best.bbox, other.bbox) > nmsIouThreshold
                }
            }

            result.addAll(kept)
        }

        return result
    }

    /**
     * Computes Intersection over Union (IoU) of two bounding boxes.
     *
     * Alex: Classic computer vision metric. IoU = area_of_overlap / area_of_union.
     * Both rects must be in the same coordinate space (we use normalized [0,1]).
     */
    private fun computeIoU(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)

        val interArea = maxOf(0f, interRight - interLeft) * maxOf(0f, interBottom - interTop)
        if (interArea == 0f) return 0f

        val aArea = (a.right - a.left) * (a.bottom - a.top)
        val bArea = (b.right - b.left) * (b.bottom - b.top)
        val unionArea = aArea + bArea - interArea

        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    /**
     * Loads a TFLite model file from Android assets.
     *
     * Alex: We memory-map the file (MappedByteBuffer) for zero-copy loading.
     * The TFLite interpreter reads directly from the mapped memory instead of
     * copying the entire model into a byte array. For a 4MB model this savings
     * is marginal, but it's the correct pattern for larger models too.
     */
    private fun loadModelFile(
        assetManager: android.content.res.AssetManager,
        filename: String
    ): MappedByteBuffer {
        val fd = assetManager.openFd(filename)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val channel = inputStream.channel
        return channel.map(
            FileChannel.MapMode.READ_ONLY,
            fd.startOffset,
            fd.declaredLength
        )
    }

    /**
     * Releases the TFLite interpreter and GPU delegate.
     *
     * Alex: Call this when the detection pipeline shuts down. The GPU delegate
     * holds native resources that won't be freed by the GC. The interpreter
     * also holds native memory (~50MB). Not closing these is a memory leak
     * that WILL crash the Vuzix within a couple of restart cycles.
     */
    override fun close() {
        try { interpreter.close() } catch (_: Exception) {}
        try { gpuDelegate?.close() } catch (_: Exception) {}
        gpuDelegate = null
    }

    companion object {
        // Alex: Model input size. YOLOv8-nano is trained on 640x640.
        // Do NOT change this without retraining the model.
        const val INPUT_SIZE = 640

        // Alex: Model filename in assets/
        const val MODEL_FILENAME = "yolov8_nano_ppe.tflite"

        // Alex: Confidence threshold. 0.35 is tuned for the Construction-PPE dataset.
        // Lower = more detections but more false positives (worker gets annoyed).
        // Higher = fewer false positives but might miss a real violation.
        // The PPE detection workflow spec says >0.7 is "confirmed", 0.3-0.7 is "uncertain".
        // We use 0.35 as the floor to catch the uncertain range for temporal voting.
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0.35f

        // Alex: NMS IoU threshold. 0.45 is standard for YOLOv8.
        // Higher = allows more overlapping boxes (less suppression).
        // Lower = more aggressive suppression (might merge nearby detections).
        const val DEFAULT_NMS_IOU_THRESHOLD = 0.45f

        // Alex: CPU thread count when GPU delegate is not available.
        const val CPU_THREAD_COUNT = 2

        /**
         * Detection class labels in the order they appear in the model output.
         *
         * Alex: These MUST match the class order in the training dataset.
         * If the ML team reorders classes, this array must be updated or
         * every detection will be misclassified. The model file doesn't embed
         * label names (thanks, TFLite), so we hardcode them.
         *
         * Violation labels start with "no_" — this convention is used by
         * HudRenderer and BleGattClient to determine severity and color.
         */
        val LABELS = arrayOf(
            "hardhat",      // 0: PPE present — good
            "no_hardhat",   // 1: VIOLATION — escalate
            "vest",         // 2: PPE present — good
            "no_vest",      // 3: VIOLATION — escalate
            "glasses",      // 4: Safety glasses — good
            "no_glasses",   // 5: VIOLATION — escalate
            "gloves",       // 6: PPE present — good
            "no_gloves",    // 7: VIOLATION — escalate
            "person"        // 8: Person anchor — not a violation
        )

        /**
         * Returns true if the label represents a PPE violation.
         *
         * Alex: Simple prefix check. All violation labels start with "no_".
         * This is a convention enforced by the ML training pipeline. If someone
         * adds a label that doesn't follow this convention, this function breaks.
         * But that's their problem for not reading the README.
         */
        fun isViolation(label: String): Boolean = label.startsWith("no_")

        /**
         * Confidence level classification per the PPE detection workflow spec.
         * Used for temporal voting decisions.
         */
        const val CONFIDENCE_HIGH = 0.7f
        const val CONFIDENCE_UNCERTAIN_LOW = 0.3f
    }
}
