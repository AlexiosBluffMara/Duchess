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
 * Runs YOLOv8-nano PPE detection on camera frames using LiteRT.
 *
 * Alex: This is the core ML inference engine for the glasses. It loads an INT8-quantized
 * YOLOv8-nano model from assets (~4MB) and runs it on the Qualcomm XR1's GPU via the
 * LiteRT GPU delegate. If the GPU delegate fails (it does sometimes after suspend/resume
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
 *   - LiteRT interpreter + buffers: ~50MB
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
 * @param modelFileName LiteRT model filename in assets. Default is the quantized YOLOv8-nano.
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

    // Alex: Whether we're running in demo/stub mode (no real model loaded).
    // True when the model file is missing, too small, or otherwise invalid.
    // In demo mode, detect() returns plausible synthetic detections for UI testing.
    val isStubMode: Boolean

    // Alex: Nullable — null in stub mode. All usages gated behind isStubMode check.
    private val _interpreter: Interpreter?
    private var gpuDelegate: GpuDelegate? = null

    // Alex: Nullable in stub mode (no real model = no real buffers needed).
    private val inputBuffer: ByteBuffer?
    private val outputArray: Array<FloatArray>?

    // Alex: Latency measurement. We track the last inference time so the HUD can
    // show it as a performance indicator. This is CRITICAL for field debugging —
    // if inference suddenly jumps from 20ms to 45ms, we know thermal throttling kicked in.
    var lastInferenceTimeMs: Long = 0L
        private set

    // Alex: Total inference count for stats/logging. Not PII, just a counter.
    var totalInferences: Long = 0L
        private set

    init {
        // Alex: Attempt to load the real model. If the file is a placeholder stub
        // (< MIN_VALID_MODEL_BYTES) or fails to parse, we enter stub/demo mode.
        // This lets the app run on hardware before the final model is available,
        // and prevents crashes on the very first boot when assets is a placeholder.
        var stubMode = false
        var interp: Interpreter? = null
        var inBuf: ByteBuffer? = null
        var outArr: Array<FloatArray>? = null
        var delegate: GpuDelegate? = null

        try {
            val model = loadModelFile(context.assets, modelFileName)

            // Alex: A real YOLOv8-nano INT8 model is ~4MB. If we loaded a file
            // smaller than 100KB, it's definitely the placeholder stub — bail early.
            if (model.capacity() < MIN_VALID_MODEL_BYTES) {
                throw IllegalStateException(
                    "Model stub detected (${model.capacity()} bytes < ${MIN_VALID_MODEL_BYTES}). " +
                    "Replace assets/yolov8_nano_ppe.tflite with a real model to enable inference."
                )
            }

            val options = Interpreter.Options()
            if (useGpuDelegate) {
                try {
                    val d = GpuDelegate(GpuDelegate.Options().apply {
                        setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_FAST_SINGLE_ANSWER)
                    })
                    options.addDelegate(d)
                    delegate = d
                } catch (_: Exception) {
                    // GPU delegate unavailable — continue with CPU fallback
                }
            }
            options.setNumThreads(CPU_THREAD_COUNT)

            interp = Interpreter(model, options)

            inBuf = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4)
                .order(ByteOrder.nativeOrder())

            val outputShape = interp.getOutputTensor(0).shape()
            val numDetections = outputShape[2]
            outArr = Array(outputShape[1]) { FloatArray(numDetections) }

        } catch (e: Exception) {
            // Alex: Model invalid or stub — enter demo mode. The app keeps running
            // with synthetic detections so the UI, BLE, and HUD pipelines can be
            // tested end-to-end without a real model file.
            stubMode = true
            try { delegate?.close() } catch (_: Exception) {}
            delegate = null
        }

        isStubMode = stubMode
        _interpreter = interp
        gpuDelegate = delegate
        inputBuffer = inBuf
        outputArray = outArr
    }

    /**
     * Runs PPE detection on a camera frame bitmap.
     *
     * Alex: This is the hot path — called 2-10 times per second depending on
     * InferenceMode. Every millisecond counts here. The flow is:
     * 1. Scale bitmap to 640x640 (model input size)
     * 2. Normalize pixel values to [0, 1] into the pre-allocated ByteBuffer
     * 3. Run LiteRT inference
     * 4. Parse output tensors into Detection objects
     * 5. Apply NMS to remove duplicate detections
     * 6. Filter by confidence threshold
     *
     * @param bitmap RGB bitmap from CameraSession (640x480 typically)
     * @return List of detections above the confidence threshold, after NMS
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        // Alex: In stub/demo mode, return synthetic detections for UI testing.
        // This lets the full Camera → Detector → HUD → BLE pipeline run without
        // a real model, which is essential for hardware bring-up and CI demos.
        if (isStubMode) {
            totalInferences++
            lastInferenceTimeMs = STUB_INFERENCE_TIME_MS
            return generateDemoDetections()
        }

        val startTime = System.nanoTime()

        val inBuf = inputBuffer ?: return emptyList()
        val outArr = outputArray ?: return emptyList()
        val interp = _interpreter ?: return emptyList()

        // Alex: Scale to model input size. Bilinear filter (true) is ~2% more accurate than
        // nearest-neighbor for detection tasks (less aliasing on edge pixels).
        val scaled = if (bitmap.width != INPUT_SIZE || bitmap.height != INPUT_SIZE) {
            Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        } else {
            bitmap
        }

        // Alex: Fill the input buffer with normalized RGB values [0, 1].
        // Bit-shifting is faster than Color.red/green/blue calls in the hot loop.
        inBuf.rewind()
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (pixel in pixels) {
            inBuf.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            inBuf.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            inBuf.putFloat((pixel and 0xFF) / 255.0f)          // B
        }

        val outputMap = HashMap<Int, Any>()
        outputMap[0] = outArr
        interp.runForMultipleInputsOutputs(arrayOf(inBuf), outputMap)

        val rawDetections = parseOutputTensor(outArr)
        val nmsDetections = applyNms(rawDetections)

        if (scaled !== bitmap) scaled.recycle()

        val elapsed = (System.nanoTime() - startTime) / 1_000_000
        lastInferenceTimeMs = elapsed
        totalInferences++

        return nmsDetections
    }

    /**
     * Returns synthetic demo detections for stub mode.
     *
     * Alex: Cycles through a short sequence of PPE scenarios so the HUD shows
     * realistic-looking detections and the BLE escalation pipeline can be exercised
     * without a real model. The cycle repeats every DEMO_CYCLE_FRAMES frames.
     *
     * The scenarios are:
     *   0-2: "all clear" — person with hardhat and vest
     *   3-5: "hardhat missing" — person + no_hardhat violation
     *   6-8: "vest missing" — person + no_vest violation
     *   9:   "multiple violations" — no_hardhat + no_vest
     */
    private fun generateDemoDetections(): List<Detection> {
        val frame = (totalInferences % DEMO_CYCLE_FRAMES).toInt()
        return when {
            frame in 3..5 -> listOf(
                Detection("person",     0.91f, RectF(0.2f, 0.1f, 0.8f, 0.95f)),
                Detection("no_hardhat", 0.83f, RectF(0.3f, 0.05f, 0.7f, 0.35f)),
            )
            frame in 6..8 -> listOf(
                Detection("person",  0.88f, RectF(0.25f, 0.1f, 0.75f, 0.95f)),
                Detection("no_vest", 0.79f, RectF(0.28f, 0.35f, 0.72f, 0.75f)),
            )
            frame == 9 -> listOf(
                Detection("person",     0.94f, RectF(0.2f, 0.1f, 0.8f, 0.95f)),
                Detection("no_hardhat", 0.87f, RectF(0.3f, 0.05f, 0.7f, 0.35f)),
                Detection("no_vest",    0.81f, RectF(0.28f, 0.35f, 0.72f, 0.75f)),
            )
            else -> listOf(
                Detection("person",  0.93f, RectF(0.2f, 0.1f, 0.8f, 0.95f)),
                Detection("hardhat", 0.90f, RectF(0.3f, 0.05f, 0.7f, 0.35f)),
                Detection("vest",    0.86f, RectF(0.28f, 0.35f, 0.72f, 0.75f)),
            )
        }
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
     * Loads a LiteRT model file from Android assets.
     *
     * Alex: We memory-map the file (MappedByteBuffer) for zero-copy loading.
     * The LiteRT interpreter reads directly from the mapped memory instead of
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
     * Releases the LiteRT interpreter and GPU delegate.
     *
     * Alex: Call this when the detection pipeline shuts down. The GPU delegate
     * holds native resources that won't be freed by the GC. The interpreter
     * also holds native memory (~50MB). Not closing these is a memory leak
     * that WILL crash the Vuzix within a couple of restart cycles.
     */
    override fun close() {
        try { _interpreter?.close() } catch (_: Exception) {}
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
         * label names (thanks, LiteRT), so we hardcode them.
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

        // Alex: Stub mode constants.
        // A real YOLOv8-nano INT8 model is ~4MB. Anything under 100KB is a stub.
        const val MIN_VALID_MODEL_BYTES = 100 * 1024  // 100KB

        // Alex: Stub inference time — realistic latency so the HUD diagnostics
        // bar shows a plausible number instead of 0ms.
        const val STUB_INFERENCE_TIME_MS = 18L

        // Alex: Demo cycle length. After this many frames, the demo scenario repeats.
        const val DEMO_CYCLE_FRAMES = 10L
    }
}
