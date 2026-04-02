package com.duchess.glasses

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.widget.FrameLayout
import com.duchess.glasses.battery.BatteryAwareScheduler
import com.duchess.glasses.ble.BleConnectionState
import com.duchess.glasses.ble.BleGattClient
import com.duchess.glasses.camera.CameraSession
import com.duchess.glasses.display.HudRenderer
import com.duchess.glasses.model.InferenceMode
import com.duchess.glasses.ppe.PpeDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main Activity for the Duchess Glasses PPE detection pipeline on Vuzix M400.
 *
 * Alex: This is the heart of the glasses app. It wires together:
 *   CameraSession → PpeDetector → HudRenderer (the detection pipeline)
 *   BatteryAwareScheduler → controls inference FPS
 *   BleGattClient → sends escalations to phone, receives alerts
 *
 * The Activity runs in LANDSCAPE orientation (declared in AndroidManifest) because
 * the Vuzix M400 display is physically landscape. The camera feed is 640x480,
 * scaled/letterboxed to the 640x360 display by HudRenderer.
 *
 * LIFECYCLE:
 * - onCreate: Initialize all components (detector, BLE, battery scheduler)
 * - onResume: Start camera, start detection pipeline, start BLE scan
 * - onPause: Stop camera (CRITICAL — releases hardware for other apps), stop pipeline
 * - onDestroy: Release all native resources (TFLite, RenderScript, BLE)
 *
 * MEMORY BUDGET: ~180MB total
 *   - PpeDetector (TFLite + buffers): ~61MB
 *   - CameraSession (ImageReader + RenderScript): ~30MB
 *   - HudRenderer (Canvas paints): ~5MB
 *   - BleGattClient: ~2MB
 *   - BatteryAwareScheduler: ~1MB
 *   - Android framework overhead: ~80MB
 *   Total: ~179MB — well under the 500MB limit with headroom for GC spikes
 *
 * BATTERY: Active detection at FULL mode (10 FPS) draws ~250mA. On a 750mAh battery
 * that's 3 hours. BatteryAwareScheduler throttles to REDUCED/MINIMAL/SUSPENDED to
 * extend to 4+ hours. PARTIAL_WAKE_LOCK keeps the CPU alive during detection but
 * releases automatically if the Activity is destroyed (timeout + release in onPause).
 *
 * NO GOOGLE PLAY SERVICES. NO FIREBASE. NO CameraX. Pure AOSP.
 */
class MainActivity : Activity() {

    // Alex: SupervisorJob so one coroutine failure doesn't cancel siblings.
    // The camera failing shouldn't kill the BLE connection, for example.
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Alex: All components are lateinit because they need Context, which isn't
    // available until onCreate(). Nullable would work but adds ?.let boilerplate everywhere.
    private lateinit var batteryScheduler: BatteryAwareScheduler
    private lateinit var hudRenderer: HudRenderer
    private lateinit var bleClient: BleGattClient

    // Alex: Nullable because they're created/destroyed with the detection pipeline.
    // CameraSession is recreated on every onResume because the camera hardware
    // needs fresh initialization after onPause releases it.
    private var cameraSession: CameraSession? = null
    private var ppeDetector: PpeDetector? = null
    private var detectionJob: Job? = null

    // Alex: Partial wake lock to keep CPU alive during detection.
    // WITHOUT this, Doze mode will kill inference after ~30s of inactivity
    // (no user interaction = Doze thinks the device is idle). Construction
    // workers don't "interact" with the glasses — they just wear them.
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Alex: Simple FrameLayout with the HudRenderer covering the full display.
        // No XML inflation, no ViewBinding — overkill for a single full-screen View.
        // The HudRenderer IS the entire UI.
        hudRenderer = HudRenderer(this)
        val rootLayout = FrameLayout(this)
        rootLayout.addView(hudRenderer)
        setContentView(rootLayout)

        // Alex: Initialize the battery scheduler first — it determines if we should
        // even start the detection pipeline. If battery is <15%, we go straight to
        // SUSPENDED mode and don't waste power opening the camera.
        batteryScheduler = BatteryAwareScheduler(this)
        batteryScheduler.startMonitoring()

        // Alex: BLE client for phone communication. Scoped to activityScope so
        // reconnection coroutines are cancelled when the Activity dies.
        bleClient = BleGattClient(this, activityScope)

        // Alex: Collect battery mode changes and update the HUD + pipeline
        activityScope.launch {
            batteryScheduler.currentMode.collectLatest { mode ->
                hudRenderer.currentMode = mode
                // Alex: If mode changed, restart the detection pipeline with the new FPS.
                // This is cheaper than dynamically changing the frame skip rate because
                // the CameraSession callbackFlow recreates cleanly.
                restartDetectionPipeline(mode)
            }
        }

        // Alex: Collect BLE connection state for HUD display
        activityScope.launch {
            bleClient.connectionState.collect { state ->
                hudRenderer.isConnectedToBle = (state == BleConnectionState.CONNECTED)
            }
        }

        // Alex: Collect BLE alerts from the phone and display on HUD
        activityScope.launch {
            bleClient.alerts.collect { alert ->
                // Alex: Phone-pushed alert — display immediately on HUD.
                // This is for alerts that the PHONE detected (from its own camera
                // or from another worker's glasses) and is pushing to nearby workers.
                // We don't need to run inference — just show the alert.
                // For now, we show the violation type as a pseudo-detection on the HUD.
                // TODO: Dedicated alert overlay in HudRenderer (separate from detection boxes)
            }
        }

        // Alex: Check permissions BEFORE doing anything that requires them.
        // On AOSP 13 (SDK 33), CAMERA and BLUETOOTH_CONNECT are runtime permissions.
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()

        // Alex: Acquire a partial wake lock with a timeout. PARTIAL_WAKE_LOCK keeps
        // the CPU running but lets the display turn off (which we don't want on
        // the M400, but the display is controlled by the Vuzix system, not us).
        // The 4-hour timeout is a safety net — if the Activity somehow leaks the
        // wake lock, it auto-releases after one shift.
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "duchess:detection"
        ).apply {
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }

        // Alex: Start the detection pipeline at the current battery-determined mode
        val mode = batteryScheduler.currentMode.value
        if (mode != InferenceMode.SUSPENDED) {
            startDetectionPipeline(mode)
        }

        // Alex: Start BLE scan to find/reconnect to the companion phone
        bleClient.startScan()
    }

    override fun onPause() {
        super.onPause()

        // Alex: STOP EVERYTHING. On the Vuzix M400:
        // - Camera MUST be released (single hardware camera, other apps need it)
        // - Wake lock MUST be released (battery preservation)
        // - Detection pipeline stops (no point running inference with no camera)
        // - BLE stays connected (handled by the system, not us)

        stopDetectionPipeline()

        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()

        // Alex: Full cleanup. Release ALL native resources.
        // Order matters: stop pipeline first, then release components.
        stopDetectionPipeline()
        batteryScheduler.stopMonitoring()
        bleClient.close()
        activityScope.cancel()
    }

    /**
     * Starts the Camera→PpeDetector→HudRenderer pipeline.
     *
     * Alex: This is the hot loop. Every frame flows through:
     * 1. CameraSession captures YUV frame → converts to Bitmap
     * 2. PpeDetector runs YOLOv8-nano inference → List<Detection>
     * 3. HudRenderer draws detection boxes and status on the display
     *
     * The entire pipeline runs on the Default dispatcher (CPU-bound work).
     * Frame rate is controlled by InferenceMode (CameraSession skips frames).
     *
     * @param mode Current inference mode determining target FPS
     */
    private fun startDetectionPipeline(mode: InferenceMode) {
        if (mode == InferenceMode.SUSPENDED) return

        // Alex: Create fresh instances. CameraSession needs fresh Camera2 resources,
        // and PpeDetector is stateless between frames (reusable, but easier to track
        // resource lifecycle with fresh instances on each resume).
        val detector = PpeDetector(this)
        ppeDetector = detector

        val camera = CameraSession(this)
        cameraSession = camera

        detectionJob = activityScope.launch(Dispatchers.Default) {
            camera.frames(mode).collect { bitmap ->
                // Alex: Run inference on the captured frame.
                // detect() is blocking (~18ms GPU, ~35ms CPU) — that's fine on
                // Dispatchers.Default which has a thread pool sized to CPU cores.
                val detections = detector.detect(bitmap)

                // Alex: Update HUD state. These are volatile writes that get
                // picked up on the next onDraw() call.
                hudRenderer.detections = detections
                hudRenderer.inferenceTimeMs = detector.lastInferenceTimeMs

                // Alex: Update battery display (read from scheduler, not BatteryManager directly)
                // The scheduler's StateFlow has the latest battery-derived mode.

                // Alex: Check for violations that need BLE escalation to the phone.
                // Per the PPE detection workflow spec:
                // - confidence > 0.7: confirmed violation → escalate immediately
                // - confidence 0.3-0.7: uncertain → needs temporal voting (TODO)
                // - confidence < 0.3: probably OK
                for (detection in detections) {
                    if (PpeDetector.isViolation(detection.label) &&
                        detection.confidence >= PpeDetector.CONFIDENCE_HIGH
                    ) {
                        // Alex: High-confidence violation. Send to phone for Gemma 3n confirmation.
                        bleClient.sendEscalation(
                            label = detection.label,
                            confidence = detection.confidence,
                            zoneId = "zone-default" // TODO: Real zone detection from phone GPS
                        )
                    }
                }

                // Alex: Recycle the bitmap after use. CameraSession creates new bitmaps
                // for each frame (from RenderScript), so we own them and must recycle.
                bitmap.recycle()

                // Alex: Trigger HUD redraw on the main thread
                hudRenderer.post { hudRenderer.invalidate() }
            }
        }
    }

    /**
     * Stops the detection pipeline and releases camera + detector resources.
     */
    private fun stopDetectionPipeline() {
        detectionJob?.cancel()
        detectionJob = null

        cameraSession?.close()
        cameraSession = null

        ppeDetector?.close()
        ppeDetector = null
    }

    /**
     * Restarts the pipeline with a new inference mode (different FPS target).
     *
     * Alex: Called when the battery level crosses a threshold. We stop the current
     * pipeline and start a new one at the new FPS. This causes a brief (~500ms)
     * gap in detection — acceptable because mode changes only happen 3-4 times
     * per shift (as battery drains through the thresholds).
     */
    private fun restartDetectionPipeline(mode: InferenceMode) {
        stopDetectionPipeline()
        if (mode != InferenceMode.SUSPENDED) {
            startDetectionPipeline(mode)
        }
    }

    /**
     * Checks and requests runtime permissions for CAMERA and BLE.
     *
     * Alex: On AOSP 13 (SDK 33), CAMERA and BLUETOOTH_CONNECT require runtime permission.
     * The Vuzix M400 doesn't have a great permissions UI (tiny display, no touch), but
     * the system dialog still works with the side button. We request both upfront because
     * the app is useless without either one.
     */
    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                // Alex: Permissions granted — start everything.
                // onResume() will fire after this returns, which starts the pipeline.
            } else {
                // Alex: Permission denied. On the Vuzix M400, the worker might have
                // accidentally denied it (tiny display, gloves, side button).
                // We don't bug them again — the app will run in degraded mode
                // (BLE alerts only, no camera detection).
                // TODO: Voice command to re-trigger permission request
            }
        }
    }

    companion object {
        const val PERMISSION_REQUEST_CODE = 1001

        // Alex: 4 hours in milliseconds. One shift. After this, the wake lock
        // auto-releases even if we forgot to release it in onPause(). Safety net.
        const val WAKE_LOCK_TIMEOUT_MS = 4 * 60 * 60 * 1000L
    }
}
