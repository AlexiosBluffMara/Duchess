package com.duchess.glasses.camera

import android.content.Context
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.Closeable

/**
 * Camera2-based capture session for the Vuzix M400.
 * Captures frames at 15 FPS, 640x480, emits Bitmaps via [frames] Flow.
 *
 * Camera2 uses executor/callback pattern — wrapped in callbackFlow
 * per project conventions.
 *
 * Battery: camera is released in [close] and should be closed in onPause/onDestroy.
 */
class CameraSession(private val context: Context) : Closeable {

    companion object {
        const val FRAME_WIDTH = 640
        const val FRAME_HEIGHT = 480
        const val TARGET_FPS = 15
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    val frames: Flow<Bitmap> = callbackFlow {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        imageReader = ImageReader.newInstance(
            FRAME_WIDTH, FRAME_HEIGHT, ImageFormat.YUV_420_888, 2
        )

        imageReader!!.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bitmap = imageToBitmap(image)
                trySend(bitmap)
            } finally {
                image.close()
            }
        }, backgroundHandler)

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = findBackCamera(cameraManager)

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startRepeatingCapture(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
                close()
            }
        }, backgroundHandler)

        awaitClose {
            close()
        }
    }

    private fun startRepeatingCapture(camera: CameraDevice) {
        val reader = imageReader ?: return
        val handler = backgroundHandler ?: return

        val captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(reader.surface)
            // Target 15 FPS for battery efficiency on Vuzix
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(TARGET_FPS, TARGET_FPS))
        }

        camera.createCaptureSession(
            listOf(reader.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    session.setRepeatingRequest(
                        captureRequestBuilder.build(),
                        null,
                        handler
                    )
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                }
            },
            handler
        )
    }

    private fun findBackCamera(manager: CameraManager): String {
        for (id in manager.cameraIdList) {
            val characteristics = manager.getCameraCharacteristics(id)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id
            }
        }
        // Fallback to first camera if no back camera found (Vuzix has a single camera)
        return manager.cameraIdList.first()
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        // Convert YUV_420_888 to ARGB Bitmap
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(
            nv21, ImageFormat.NV21, image.width, image.height, null
        )
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 80, out)
        val bytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    override fun close() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }
}
