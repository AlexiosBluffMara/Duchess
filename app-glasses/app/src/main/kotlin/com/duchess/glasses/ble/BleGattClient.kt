package com.duchess.glasses.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.duchess.glasses.model.SafetyAlert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * BLE GATT client that connects to the companion phone's GATT server.
 *
 * Alex: The glasses are the BLE CLIENT. The phone runs a GATT SERVER (see BleGattServer
 * in app-phone/). This is the reverse of what most people expect — usually the "peripheral"
 * (small device) is the server. But in our architecture, the phone is the hub that multiple
 * glasses can connect to, and the phone pushes alerts DOWN to the glasses via GATT notifications.
 *
 * BLE 5.0 on the Vuzix M400 is... finicky. The Qualcomm XR1 has a single BLE radio shared
 * with WiFi. Scanning while connected causes packet loss. The GATT stack has a 512-byte MTU
 * limit but the actual negotiated MTU is usually 247 bytes. We split large payloads.
 *
 * RECONNECTION STRATEGY:
 * BLE connections on construction sites drop ALL THE TIME — steel structures, concrete walls,
 * worker walking to a different floor, pocket blocking the signal. We implement exponential
 * backoff reconnection:
 *   - 1st retry: 1s
 *   - 2nd retry: 2s
 *   - 3rd retry: 4s
 *   - 4th retry: 8s
 *   - 5th retry: 16s
 *   - After 5 retries: scan timeout, wait for manual reconnect
 *
 * Max backoff is 16s because if BLE is down for longer than that on a construction site,
 * the worker has probably walked to a different zone and needs a fresh scan anyway.
 *
 * PRIVACY: The glasses send detection metadata (label, confidence, zone) to the phone.
 * NEVER send raw video frames over BLE. The phone handles all cloud communication.
 *
 * @param context Application context for BluetoothManager access
 * @param scope CoroutineScope for reconnection coroutines (tied to activity lifecycle)
 */
class BleGattClient(
    private val context: Context,
    private val scope: CoroutineScope
) : Closeable {

    /**
     * Connection state observable. Collectors (MainActivity, HudRenderer) use this
     * to show BLE status on the HUD and gate escalation attempts.
     */
    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    /**
     * Alert flow. Emits SafetyAlert objects parsed from GATT notifications.
     *
     * Alex: SharedFlow (not StateFlow) because alerts are EVENTS, not STATE.
     * We never want to replay an old alert to a new collector. replay=0,
     * extraBufferCapacity=5 to handle bursts without dropping alerts.
     */
    private val _alerts = MutableSharedFlow<SafetyAlert>(
        replay = 0,
        extraBufferCapacity = ALERT_BUFFER_SIZE
    )
    val alerts: SharedFlow<SafetyAlert> = _alerts.asSharedFlow()

    // Alex: BLE stack references. nullability tracks lifecycle (null = not connected).
    private var bluetoothGatt: BluetoothGatt? = null
    private var scanner: BluetoothLeScanner? = null
    private var reconnectJob: Job? = null
    private var scanTimeoutJob: Job? = null

    // Alex: Reconnection state
    private var reconnectAttempt = 0
    private var isScanning = false

    /**
     * Starts scanning for the companion phone's GATT server.
     *
     * Alex: We scan with a filter for our custom service UUID so we don't waste
     * battery processing irrelevant BLE advertisements. The Vuzix M400 BLE radio
     * uses shared resources with WiFi — scanning without a filter hammers both.
     *
     * Scan timeout is 30 seconds. If we haven't found the phone by then, the worker
     * probably isn't near their phone. We stop scanning to save battery and can be
     * restarted manually or by the reconnection backoff logic.
     */
    fun startScan() {
        if (isScanning) return

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: return
        if (!adapter.isEnabled) {
            _connectionState.value = BleConnectionState.ERROR
            return
        }

        scanner = adapter.bluetoothLeScanner ?: return
        _connectionState.value = BleConnectionState.SCANNING

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            // Alex: LOW_LATENCY scan mode uses more battery but finds the phone faster.
            // On a construction site, the phone is usually within 10 meters of the glasses.
            // We'll find it in <2 seconds. Once connected, we stop scanning immediately.
            .build()

        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            isScanning = true

            // Alex: Scan timeout — don't scan forever. 30 seconds is enough to find
            // a nearby phone. After that, stop to save battery.
            scanTimeoutJob?.cancel()
            scanTimeoutJob = scope.launch {
                delay(SCAN_TIMEOUT_MS)
                stopScan()
                if (_connectionState.value == BleConnectionState.SCANNING) {
                    _connectionState.value = BleConnectionState.DISCONNECTED
                }
            }
        } catch (e: SecurityException) {
            // Alex: Missing BLUETOOTH_SCAN permission. This shouldn't happen because
            // we declare it in the manifest and it's granted at install on API 33
            // for AOSP (no runtime prompt for BLE on the M400). But just in case.
            _connectionState.value = BleConnectionState.ERROR
        }
    }

    /**
     * Stops BLE scanning. Called after connection or on timeout.
     */
    fun stopScan() {
        if (!isScanning) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) { }
        isScanning = false
        scanTimeoutJob?.cancel()
    }

    /**
     * Sends an escalation payload to the companion phone.
     *
     * Alex: This is how the glasses tell the phone "I detected a PPE violation,
     * please run Gemma 4 for confirmation." The payload is a JSON string with
     * the detection label, confidence, and zone. We keep it small because BLE
     * MTU is limited (typically 247 bytes after negotiation).
     *
     * PRIVACY: We send the detection type and confidence, NOT the camera frame.
     * Raw video never leaves the glasses except through the phone's explicit
     * escalation pipeline (which anonymizes before cloud upload).
     *
     * @param label Detection label (e.g., "no_hardhat")
     * @param confidence Detection confidence [0, 1]
     * @param zoneId Current zone identifier
     * @return true if the write was queued successfully, false if not connected
     */
    fun sendEscalation(label: String, confidence: Float, zoneId: String): Boolean {
        val gatt = bluetoothGatt ?: return false
        if (_connectionState.value != BleConnectionState.CONNECTED) return false

        val service = gatt.getService(SERVICE_UUID) ?: return false
        val characteristic = service.getCharacteristic(ESCALATION_CHAR_UUID) ?: return false

        // Alex: JSON payload. Keep it minimal — BLE MTU is small.
        // No PII fields. No raw image data. Just the detection metadata.
        val payload = """{"label":"$label","confidence":$confidence,"zone":"$zoneId","ts":${System.currentTimeMillis()}}"""
        characteristic.value = payload.toByteArray(StandardCharsets.UTF_8)

        return try {
            gatt.writeCharacteristic(characteristic)
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Initiates reconnection with exponential backoff.
     *
     * Alex: Called when the BLE connection drops (which happens A LOT on construction sites).
     * We don't immediately reconnect because:
     * 1. The phone might be temporarily out of range (worker is on a different floor)
     * 2. The BLE stack might be in a bad state and needs a moment to reset
     * 3. Hammering reconnect burns battery needlessly
     *
     * Exponential backoff: 1s, 2s, 4s, 8s, 16s, then give up.
     * The give-up isn't permanent — startScan() can be called again.
     */
    private fun scheduleReconnect() {
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            _connectionState.value = BleConnectionState.DISCONNECTED
            reconnectAttempt = 0
            return
        }

        val delayMs = INITIAL_BACKOFF_MS * (1L shl reconnectAttempt)
        reconnectAttempt++

        _connectionState.value = BleConnectionState.RECONNECTING

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            startScan()
        }
    }

    // Alex: BLE scan callback. Fires when we find a device advertising our service UUID.
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            // Alex: Found the phone! Stop scanning immediately to save battery
            // and initiate GATT connection.
            stopScan()
            connectToDevice(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            // Alex: Scan failed. Common causes:
            // SCAN_FAILED_ALREADY_STARTED (1) — we're already scanning
            // SCAN_FAILED_APPLICATION_REGISTRATION_FAILED (2) — BLE stack borked
            // SCAN_FAILED_INTERNAL_ERROR (3) — hardware issue
            // SCAN_FAILED_FEATURE_UNSUPPORTED (4) — shouldn't happen on M400
            isScanning = false
            _connectionState.value = BleConnectionState.ERROR
        }
    }

    /**
     * Connects to the discovered companion phone device.
     */
    private fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = BleConnectionState.CONNECTING

        try {
            // Alex: TRANSPORT_LE forces BLE (not BR/EDR). autoConnect=false for fast
            // initial connection. autoConnect=true would try BLE classic first, which
            // is slower and not what we want for a GATT connection.
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            _connectionState.value = BleConnectionState.ERROR
        }
    }

    // Alex: GATT callback handles the entire connection lifecycle.
    // This is Camera2-level callback hell, but it's the only way on AOSP.
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    // Alex: Connected! Now discover services to find our custom
                    // service with the alert and escalation characteristics.
                    reconnectAttempt = 0 // Reset backoff on successful connection
                    try {
                        gatt.discoverServices()
                    } catch (_: SecurityException) {
                        _connectionState.value = BleConnectionState.ERROR
                    }
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    // Alex: Connection lost. This is NORMAL on a construction site.
                    // Steel beams, concrete walls, moving into an elevator — all cause drops.
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    gatt.close()
                    bluetoothGatt = null
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = BleConnectionState.ERROR
                return
            }

            // Alex: Subscribe to the alert characteristic for push notifications.
            // The phone pushes SafetyAlert data DOWN to the glasses via GATT notifications.
            val service = gatt.getService(SERVICE_UUID)
            val alertChar = service?.getCharacteristic(ALERT_CHAR_UUID)

            if (alertChar != null) {
                try {
                    gatt.setCharacteristicNotification(alertChar, true)
                    // Alex: Write to the CCCD (Client Characteristic Configuration Descriptor)
                    // to enable server-side notifications. Without this, the phone won't
                    // push data even though we registered locally.
                    val descriptor = alertChar.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                } catch (_: SecurityException) {
                    _connectionState.value = BleConnectionState.ERROR
                    return
                }
            }

            _connectionState.value = BleConnectionState.CONNECTED
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Alex: This fires when the phone pushes an alert notification.
            // Parse the payload into a SafetyAlert and emit it on the alerts flow.
            if (characteristic.uuid == ALERT_CHAR_UUID) {
                val payload = characteristic.value
                if (payload != null) {
                    val alert = parseAlertPayload(payload)
                    if (alert != null) {
                        _alerts.tryEmit(alert)
                    }
                }
            }
        }
    }

    /**
     * Parses a BLE notification payload into a SafetyAlert.
     *
     * Alex: The phone sends alerts as a pipe-delimited string to minimize payload size:
     * "id|violationType|severity|zoneId|timestamp|messageEn|messageEs"
     *
     * Why pipe-delimited instead of JSON? BLE MTU is ~247 bytes after negotiation.
     * A JSON-encoded SafetyAlert is ~200 bytes. Pipe-delimited is ~120 bytes.
     * That headroom matters when the BLE radio is shared with WiFi on the XR1.
     *
     * @param payload Raw bytes from the GATT notification
     * @return Parsed SafetyAlert, or null if the payload is malformed
     */
    fun parseAlertPayload(payload: ByteArray): SafetyAlert? {
        return try {
            val str = String(payload, StandardCharsets.UTF_8)
            val parts = str.split("|")
            if (parts.size < 7) return null

            SafetyAlert(
                id = parts[0],
                violationType = parts[1],
                severity = parts[2].toIntOrNull() ?: return null,
                zoneId = parts[3],
                timestamp = parts[4].toLongOrNull() ?: return null,
                messageEn = parts[5],
                messageEs = parts[6]
            )
        } catch (_: Exception) {
            // Alex: Malformed payload. Don't crash — just drop it and log nothing
            // (no PII in logs, remember?). The phone will re-send if it was important.
            null
        }
    }

    /**
     * Disconnects from the phone and releases all BLE resources.
     */
    override fun close() {
        reconnectJob?.cancel()
        scanTimeoutJob?.cancel()
        stopScan()

        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: SecurityException) { }
        bluetoothGatt = null

        _connectionState.value = BleConnectionState.DISCONNECTED
    }

    companion object {
        // Duke: CRITICAL FIX — UUIDs were mismatched with the phone's BleGattServer.
        // The original values ("0000DCSS-...") contained non-hex chars and didn't match
        // the phone side at all. These MUST be identical to BleGattServer.kt in app-phone.
        // Alex: If you change these, you MUST update BleGattServer.kt simultaneously.
        val SERVICE_UUID: UUID = UUID.fromString("d0c5e550-0001-4b6e-a5a0-b0b0b0b0b0b0")
        val ALERT_CHAR_UUID: UUID = UUID.fromString("d0c5e550-0002-4b6e-a5a0-b0b0b0b0b0b0")
        // Duke: "ESCALATION" from the glasses' perspective = "STATUS" from the phone's
        // perspective. Same characteristic, different names. The glasses write escalation
        // data here; the phone reads it as status updates. UUID must match phone's STATUS_CHARACTERISTIC_UUID.
        val ESCALATION_CHAR_UUID: UUID = UUID.fromString("d0c5e550-0003-4b6e-a5a0-b0b0b0b0b0b0")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Alex: Reconnection parameters
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val INITIAL_BACKOFF_MS = 1000L // 1 second
        // Alex: Max backoff = INITIAL * 2^(MAX-1) = 1000 * 16 = 16 seconds

        // Alex: Scan timeout. 30 seconds is enough to find a phone within 10 meters.
        const val SCAN_TIMEOUT_MS = 30_000L

        // Alex: Alert buffer size for SharedFlow. 5 is enough for burst alerts
        // (e.g., 3 workers simultaneously violating PPE near the glasses wearer).
        const val ALERT_BUFFER_SIZE = 5
    }
}

/**
 * BLE connection states for the GATT client.
 *
 * Alex: These map roughly to the BluetoothGatt.STATE_* constants but with
 * our own SCANNING, RECONNECTING, and ERROR states for richer HUD feedback.
 * The HudRenderer uses these to show the connection dot color.
 */
enum class BleConnectionState {
    /** Not connected, not scanning. Idle. */
    DISCONNECTED,
    /** Actively scanning for the companion phone's GATT server. */
    SCANNING,
    /** Found the phone, establishing GATT connection. */
    CONNECTING,
    /** GATT connected, services discovered, notifications enabled. Ready. */
    CONNECTED,
    /** Connection lost, waiting to retry with exponential backoff. */
    RECONNECTING,
    /** Unrecoverable error (permissions, hardware failure, etc.) */
    ERROR
}
