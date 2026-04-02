package com.duchess.glasses

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.duchess.glasses.ble.BleGattClient
import com.duchess.glasses.camera.CameraSession
import com.duchess.glasses.display.HudRenderer
import com.duchess.glasses.ppe.PpeDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main activity for the Vuzix M400 glasses app.
 * Boots camera capture + BLE client, renders HUD overlay on 640x360 display.
 * No touch UI — workers wear gloves. Voice/head gesture only.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var cameraSession: CameraSession
    private lateinit var ppeDetector: PpeDetector
    private lateinit var bleClient: BleGattClient
    private lateinit var hudRenderer: HudRenderer
    private lateinit var hudOverlay: SurfaceView

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) {
            startPipeline()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on during active detection
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Set up 640x360 landscape layout
        val rootLayout = FrameLayout(this)
        hudOverlay = SurfaceView(this)
        rootLayout.addView(hudOverlay)
        setContentView(rootLayout)

        cameraSession = CameraSession(this)
        ppeDetector = PpeDetector(this)
        bleClient = BleGattClient(this)
        hudRenderer = HudRenderer()

        if (hasAllPermissions()) {
            startPipeline()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startPipeline() {
        // Start BLE client to connect to phone GATT server
        bleClient.connect()

        // Start camera capture → PPE detection → HUD rendering pipeline
        lifecycleScope.launch(Dispatchers.Default) {
            cameraSession.frames.collectLatest { bitmap ->
                val detections = ppeDetector.detect(bitmap)
                hudRenderer.render(hudOverlay.holder, detections)

                // If violations detected, notify phone via BLE
                detections.filter { it.confidence > 0.7f }.forEach { detection ->
                    bleClient.sendEscalation(detection)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraSession.close()
        bleClient.disconnect()
        ppeDetector.close()
    }
}
