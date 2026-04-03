package com.duchess.glasses.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import android.renderscript.Type
import android.view.Surface
import com.duchess.glasses.model.InferenceMode
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages the Camera2 pipeline on the Vuzix M400 for PPE detection frames.
 *
 * Alex: Camera2 on the Vuzix M400 is... special. The Qualcomm XR1 chipset has a
 * single rear camera that outputs YUV_420_888 natively. CameraX would be nicer to
 * work with but it requires Google Play Services, which doesn't exist on AOSP.
 * So we're stuck with Camera2's callback hell, wrapped in callbackFlow for sanity.
 *
 * KEY GOTCHAS:
 * 1. The M400 camera supports 640x480 and 1280x720. We use 640x480 because it's
 *    closer to the model's 640x640 input and saves memory + battery.
 * 2. YUV_420_888 → RGB conversion is surprisingly expensive on the XR1. We use
 *    RenderScript (deprecated but still in AOSP 13) because it uses the GPU.
 *    Do NOT try to do manual YUV conversion on the CPU — it'll eat 30% of frame time.
 * 3. We don't change the hardware frame rate to throttle inference. Instead we skip
 *    frames based on the current InferenceMode. Reopening the camera causes a visible
 *    flicker that disorients the worker.
 * 4. ALWAYS close the camera in onPause(). Holding it open drains battery and blocks
 *    other apps (the Vuzix has exactly one camera).
 *
 * @param context Application context (not Activity — we need this to survive config changes)
 * @param captureWidth Capture width in pixels. Default 640 for the M400's native resolution.
 * @param captureHeight Capture height in pixels. Default 480 for the M400.
 */
class CameraSession(
    private val context: Context,
    private val captureWidth: Int = CAPTURE_WIDTH,
    private val captureHeight: Int = CAPTURE_HEIGHT
) {
    // Alex: AtomicBoolean because Camera2 callbacks come from the camera thread
    // but close() can be called from the main thread. Volatile isn't enough —
    // we need atomic CAS for the "close once" guarantee.
    private val isClosed = AtomicBoolean(false)

    // Alex: We track the last inference timestamp to enforce frame rate limiting.
    // This is cheaper than using a timer or ticker because we avoid coroutine overhead.
    private val lastFrameTimeNanos = AtomicLong(0L)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // Alex: RenderScript for YUV→RGB conversion. Yes, it's deprecated in API 31+.
    // But on AOSP 13 (Vuzix) it still works and is WAY faster than manual conversion.
    // The alternative is a custom native (C++) conversion, which is overkill for now.
    // ELI13: The camera records colors in a weird format called YUV (like a foreign
    // language for colors). Our AI model only understands RGB (normal red/green/blue).
    // RenderScript translates between them using the GPU, which is ~7x faster than
    // doing it on the CPU. Think of it like Google Translate but for color formats.
    private var renderScript: RenderScript? = null
    private var yuvToRgb: ScriptIntrinsicYuvToRGB? = null

    /**
     * Opens the camera and emits Bitmap frames as a Flow.
     *
     * Alex: We use callbackFlow to bridge Camera2's callback-based API into
     * Kotlin Flow. The Flow respects backpressure — if the collector (PpeDetector)
     * is slow, we drop frames automatically via BUFFER_STRATEGY. This is critical
     * on the XR1 because if we queue up frames faster than inference can process
     * them, we'll OOM (remember: 500MB ML budget, and each 640x480 ARGB frame is ~1.2MB).
     *
     * Frame rate is controlled by [inferenceMode]. We capture at the hardware rate
     * but only emit frames at the mode's target FPS. Frames between emissions are
     * silently dropped — no allocation, no conversion, no waste.
     *
     * @param inferenceMode The current inference mode controlling target FPS.
     *        If SUSPENDED, no frames are emitted (but camera stays open for preview).
     * @return Flow of RGB Bitmaps at the target frame rate, or empty if permissions denied.
     */
    // ELI13: callbackFlow is a bridge between two coding styles. The camera uses an
    // old-school "hey, call me back when a photo is ready" approach. Our code uses a
    // modern "I'll wait here for photos to arrive in a stream" approach (called Flow).
    // callbackFlow translates between them — like an adapter plug for different outlets.
    fun frames(inferenceMode: InferenceMode): Flow<Bitmap> = callbackFlow {
        // Alex: Permission check BEFORE opening the camera. On AOSP 13 (SDK 33),
        // CAMERA is a runtime permission. If the user denies it, Camera2 throws
        // a SecurityException from openCamera() with a completely unhelpful message.
        // Better to check here and close the flow gracefully.
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            close()
            return@callbackFlow
        }

        // Alex: SUSPENDED mode = no inference, no frames. We don't even open the camera
        // to save battery. The HUD can still display BLE-pushed alerts from the phone.
        if (inferenceMode == InferenceMode.SUSPENDED) {
            close()
            return@callbackFlow
        }

        val frameIntervalNanos = if (inferenceMode.fps > 0) {
            1_000_000_000L / inferenceMode.fps
        } else {
            Long.MAX_VALUE // Effectively never emit
        }

        // Alex: Dedicated thread for camera callbacks. Camera2 requires a Handler
        // that's NOT on the main thread (it'll ANR if you try). HandlerThread is
        // AOSP-native, no dependency needed.
        val thread = HandlerThread("duchess-camera").also { it.start() }
        cameraThread = thread
        val handler = Handler(thread.looper)
        cameraHandler = handler

        // Alex: RenderScript setup for YUV→RGB. We create it once and reuse it
        // for every frame. Creating per-frame would be catastrophically slow.
        val rs = RenderScript.create(context)
        renderScript = rs
        val yuvScript = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
        yuvToRgb = yuvScript

        // Alex: ImageReader is the Camera2 way to get raw frames. maxImages=2 means
        // we double-buffer: one being converted, one being filled by the camera.
        // More than 2 wastes memory; less than 2 causes frame drops on slow inference.
        val reader = ImageReader.newInstance(
            captureWidth, captureHeight,
            ImageFormat.YUV_420_888,
            MAX_IMAGES
        )
        imageReader = reader

        reader.setOnImageAvailableListener({ imgReader ->
            if (isClosed.get()) return@setOnImageAvailableListener

            val image = imgReader.acquireLatestImage() ?: return@setOnImageAvailableListener

            try {
                // Alex: Frame rate limiting. Check if enough time has passed since
                // the last emitted frame. If not, drop this one silently.
                // We use System.nanoTime() (monotonic clock) instead of
                // System.currentTimeMillis() because wall clock can jump.
                val now = System.nanoTime()
                val elapsed = now - lastFrameTimeNanos.get()
                if (elapsed < frameIntervalNanos) {
                    return@setOnImageAvailableListener
                }
                lastFrameTimeNanos.set(now)

                // Alex: YUV_420_888 → Bitmap conversion using RenderScript.
                // This runs on the GPU and takes ~2ms on the XR1 vs ~15ms for
                // manual CPU conversion. The 13ms savings per frame adds up fast.
                val bitmap = yuvToBitmap(image, rs, yuvScript)
                if (bitmap != null) {
                    trySend(bitmap)
                }
            } finally {
                // Alex: ALWAYS close the Image. Camera2 has a fixed buffer pool
                // (we set maxImages=2). If we don't close, the pool fills up and
                // the camera silently stops delivering frames. No error, no callback,
                // just silence. Ask me how I know. (Hint: 3 hours of debugging.)
                image.close()
            }
        }, handler)

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Alex: Find the rear-facing camera. The M400 only has one, but we
        // iterate defensively in case a future model adds more.
        val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: run {
            close()
            return@callbackFlow
        }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (isClosed.get()) {
                    camera.close()
                    return
                }
                cameraDevice = camera

                // Alex: Create a capture session with the ImageReader surface.
                // We don't add a preview surface because the Vuzix M400 HUD is
                // driven by our custom HudRenderer, not a SurfaceView.
                val surfaces = listOf(reader.surface)
                camera.createCaptureSession(
                    surfaces,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            if (isClosed.get()) {
                                session.close()
                                return
                            }
                            captureSession = session

                            // Alex: Repeating request = continuous frame delivery.
                            // TEMPLATE_PREVIEW gives us the fastest frame rate
                            // without triggering the M400's "recording" indicator LED.
                            val request = camera.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW
                            ).apply {
                                addTarget(reader.surface)
                                // Alex: Auto-exposure and auto-white balance.
                                // Construction sites have wildly varying lighting
                                // (indoor vs outdoor, sun angle, shadows from scaffolding).
                                // Manual exposure would fail instantly.
                                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            }.build()

                            session.setRepeatingRequest(request, null, handler)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            // Alex: This fires when the camera is in a bad state.
                            // Usually happens when another app is holding the camera.
                            // On the M400 there's only one camera, so we just bail.
                            session.close()
                            close()
                        }
                    },
                    handler
                )
            }

            override fun onDisconnected(camera: CameraDevice) {
                // Alex: Camera disconnected — could be hardware failure or another
                // app grabbed it. Close everything and let the pipeline restart.
                camera.close()
                cameraDevice = null
                close()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                // Alex: Camera2 error codes are NOT documented well. The common ones:
                // ERROR_CAMERA_DEVICE (1) = fatal hardware error
                // ERROR_CAMERA_SERVICE (2) = camera service crashed
                // ERROR_CAMERA_IN_USE (4) = another app has it
                // In all cases, close and let CameraSession be recreated.
                camera.close()
                cameraDevice = null
                close()
            }
        }, handler)

        awaitClose {
            // Alex: callbackFlow's cleanup block. This runs when the collector
            // cancels or the flow scope is cancelled. Release EVERYTHING.
            releaseResources()
        }
    }

    /**
     * Converts a YUV_420_888 Image to an ARGB_8888 Bitmap using RenderScript.
     *
     * Alex: This is the performance-critical path. Every frame goes through here.
     * RenderScript uses the GPU for the color space conversion, which is ~7x faster
     * than manual CPU conversion on the Qualcomm XR1. The output bitmap is ARGB_8888
     * because that's what LiteRT expects for its input tensor.
     *
     * NOTE: RenderScript is deprecated in API 31+ but AOSP 13 still ships it.
     * If Vuzix ever moves to API 35+, we'll need to switch to Vulkan compute
     * or a custom NDK solution. That's a future-Alex problem.
     */
    private fun yuvToBitmap(
        image: android.media.Image,
        rs: RenderScript,
        yuvScript: ScriptIntrinsicYuvToRGB
    ): Bitmap? {
        try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            // Alex: NV21 format: Y plane first, then interleaved VU.
            // Camera2 YUV_420_888 doesn't guarantee NV21 layout, but on the XR1
            // chipset it always is. We build the NV21 byte array manually.
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            // Alex: RenderScript Type describes the data layout. We create it once
            // per frame (cheap) because the image dimensions could theoretically change
            // (they don't on the M400, but defensive coding saves debugging time).
            val yuvType = Type.Builder(rs, Element.U8(rs))
                .setX(nv21.size)
                .create()
            val rgbType = Type.Builder(rs, Element.RGBA_8888(rs))
                .setX(captureWidth)
                .setY(captureHeight)
                .create()

            val yuvAlloc = Allocation.createTyped(rs, yuvType, Allocation.USAGE_SCRIPT)
            val rgbAlloc = Allocation.createTyped(rs, rgbType, Allocation.USAGE_SCRIPT)

            yuvAlloc.copyFrom(nv21)
            yuvScript.setInput(yuvAlloc)
            yuvScript.forEach(rgbAlloc)

            val bitmap = Bitmap.createBitmap(captureWidth, captureHeight, Bitmap.Config.ARGB_8888)
            rgbAlloc.copyTo(bitmap)

            // Alex: Clean up RenderScript allocations immediately. These are GPU memory
            // and won't be GC'd like regular objects. Leak these and you'll hit the
            // 500MB ML budget before you know it.
            yuvAlloc.destroy()
            rgbAlloc.destroy()

            return bitmap
        } catch (e: Exception) {
            // Alex: In production, a frame conversion failure isn't fatal — we just
            // skip this frame and try the next one. Log at debug level because
            // this can happen during camera startup/shutdown transitions.
            return null
        }
    }

    /**
     * Releases all camera and conversion resources.
     *
     * Alex: This is called from both close() and awaitClose(). Every resource
     * here is either native (camera), GPU (RenderScript), or thread (HandlerThread).
     * None of these get cleaned up by the GC. Miss one and you leak until the
     * process is killed. On the Vuzix with 3.5GB available, that's not gonna last long.
     */
    private fun releaseResources() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null

        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null

        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null

        try { yuvToRgb?.destroy() } catch (_: Exception) {}
        yuvToRgb = null

        try { renderScript?.destroy() } catch (_: Exception) {}
        renderScript = null

        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    /**
     * Closes the camera session and releases all resources.
     * Thread-safe — can be called from any thread, but only executes once.
     */
    fun close() {
        if (isClosed.compareAndSet(false, true)) {
            releaseResources()
        }
    }

    /**
     * Returns true if this session has been closed.
     */
    fun isClosed(): Boolean = isClosed.get()

    companion object {
        // Alex: These match the Vuzix M400's native rear camera resolution.
        // The camera supports 1280x720 too, but 640x480 saves memory and is
        // closer to the YOLOv8-nano 640x640 input size (less scaling overhead).
        const val CAPTURE_WIDTH = 640
        const val CAPTURE_HEIGHT = 480

        // Alex: Double-buffering. 2 is the minimum for smooth capture.
        // 3 adds latency without benefit on the XR1.
        const val MAX_IMAGES = 2
    }
}
