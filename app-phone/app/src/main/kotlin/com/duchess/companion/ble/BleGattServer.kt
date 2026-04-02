package com.duchess.companion.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BLE GATT server that runs on the companion phone.
 *
 * Alex: This is the phone side of the glasses <-> phone BLE link.
 * The Vuzix M400 glasses run a GATT client that connects here. We expose
 * two characteristics:
 *
 *   1. ALERT_CHARACTERISTIC — read + notify. When a safety alert fires,
 *      we write the alert payload here and notify all connected glasses.
 *      Workers see the alert on their HUD instantly via BLE push.
 *
 *   2. STATUS_CHARACTERISTIC — read + write. Glasses write frame metadata
 *      and escalation requests here. Phone reads it to decide if Gemma 3n
 *      inference is needed.
 *
 * GATT server lifecycle on Android is finicky:
 *   - openGattServer() can return null if BT is off or the adapter is bonked
 *   - addService() is async internally even though the API looks sync
 *   - The server survives Activity rotation but NOT app process death
 *   - We keep it as a @Singleton because only one GATT server per app is allowed
 *
 * For NOTIFY to work, clients must write to the CCCD (Client Characteristic
 * Configuration Descriptor) on the alert characteristic. The 0x2902 UUID.
 * Without that, notifyCharacteristicChanged() silently does nothing. I burned
 * two days debugging this once because the glasses client wasn't subscribing.
 */
@Singleton
class BleGattServer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Alex: Custom UUIDs for the Duchess service. The d0c5e550 prefix is a
        // quasi-namespace to avoid collisions with standard BLE profiles.
        // DO NOT change these without updating the glasses BleGattClient too.
        val DUCHESS_SERVICE_UUID: UUID = UUID.fromString("d0c5e550-0001-4b6e-a5a0-b0b0b0b0b0b0")
        val ALERT_CHARACTERISTIC_UUID: UUID = UUID.fromString("d0c5e550-0002-4b6e-a5a0-b0b0b0b0b0b0")
        val STATUS_CHARACTERISTIC_UUID: UUID = UUID.fromString("d0c5e550-0003-4b6e-a5a0-b0b0b0b0b0b0")

        // Alex: The standard Client Characteristic Configuration Descriptor UUID.
        // Every BLE characteristic that supports NOTIFY needs this descriptor.
        // Clients write 0x0001 here to enable notifications, 0x0000 to disable.
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    sealed interface ServerState {
        data object Stopped : ServerState
        data object Starting : ServerState
        data object Running : ServerState
        data class Error(val message: String) : ServerState
    }

    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    // Alex: Track connected devices so we can notify all of them when an alert fires.
    // Using a mutable set internally but exposing a snapshot via StateFlow.
    // ConcurrentHashMap.newKeySet() would also work but StateFlow gives us
    // reactive observation for the UI layer.
    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private val _connectedDeviceCount = MutableStateFlow(0)
    val connectedDeviceCount: StateFlow<Int> = _connectedDeviceCount.asStateFlow()

    private var gattServer: BluetoothGattServer? = null

    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            // Alex: This callback fires on a Binder thread, NOT the main thread.
            // StateFlow is thread-safe by design, but if we ever do UI work here
            // we'd need to dispatch to Main. For now, just track the device set.
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    synchronized(connectedDevices) {
                        connectedDevices.add(device)
                        _connectedDeviceCount.value = connectedDevices.size
                    }
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    synchronized(connectedDevices) {
                        connectedDevices.remove(device)
                        _connectedDeviceCount.value = connectedDevices.size
                    }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Alex: Always respond to read requests or the client times out after 30s
            // and disconnects. The characteristic.value may be null if we haven't
            // written anything yet — that's fine, send an empty response.
            gattServer?.sendResponse(
                device, requestId, BluetoothGatt.GATT_SUCCESS,
                offset, characteristic.value ?: byteArrayOf()
            )
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            // Alex: Glasses write escalation requests and frame metadata here.
            // We ACK the write immediately, then process the data async.
            // Never block in a GATT callback — it'll stall the entire BLE stack.
            if (value != null) {
                characteristic.value = value
            }
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
                )
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            // Alex: This is where clients subscribe to notifications by writing
            // to the CCCD descriptor. We MUST respond or the subscription fails.
            // The glasses BleGattClient writes ENABLE_NOTIFICATION_VALUE (0x0001)
            // here when it wants to receive alert pushes.
            if (value != null) {
                descriptor.value = value
            }
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value
                )
            }
        }
    }

    /**
     * Start the GATT server and advertise the Duchess service.
     *
     * Alex: Idempotent — calling start() when already Running is a no-op.
     * This avoids double-server issues if the Activity recreates (rotation, etc.).
     * GattServer.openGattServer() can return null if Bluetooth is disabled.
     * We don't force-enable BT here because that requires BLUETOOTH_ADMIN and a
     * user prompt — that's the caller's responsibility.
     */
    fun start() {
        if (_state.value is ServerState.Running) return
        _state.value = ServerState.Starting

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (bluetoothManager == null) {
            _state.value = ServerState.Error("BluetoothManager not available")
            return
        }

        gattServer = bluetoothManager.openGattServer(context, gattCallback)
        if (gattServer == null) {
            _state.value = ServerState.Error("Failed to open GATT server")
            return
        }

        val service = BluetoothGattService(
            DUCHESS_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // Alex: Alert characteristic — read + notify.
        // NOTIFY lets us push alerts to glasses without them polling.
        // The CCCD descriptor is REQUIRED for notifications to work.
        val alertCharacteristic = BluetoothGattCharacteristic(
            ALERT_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        // Alex: Add the CCCD descriptor so clients can subscribe to notifications.
        // Without this, notifyCharacteristicChanged() is silently ignored. I cannot
        // stress enough how often this is missed in BLE GATT server implementations.
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        alertCharacteristic.addDescriptor(cccd)

        // Alex: Status characteristic — read + write. Glasses write here, phone reads.
        val statusCharacteristic = BluetoothGattCharacteristic(
            STATUS_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(alertCharacteristic)
        service.addCharacteristic(statusCharacteristic)
        gattServer?.addService(service)

        _state.value = ServerState.Running
    }

    /**
     * Stop the GATT server and release all BLE resources.
     *
     * Alex: close() disconnects all clients and releases the server reference.
     * We also clear the connected devices set. After stop(), calling sendAlert()
     * is a no-op (returns false). The server can be start()ed again if needed.
     */
    fun stop() {
        gattServer?.close()
        gattServer = null
        synchronized(connectedDevices) {
            connectedDevices.clear()
            _connectedDeviceCount.value = 0
        }
        _state.value = ServerState.Stopped
    }

    /**
     * Send a safety alert to ALL connected glasses devices via BLE notification.
     *
     * Alex: This writes the alert payload to the ALERT characteristic and then
     * calls notifyCharacteristicChanged() for each connected device. The notification
     * is pushed over BLE — no polling needed on the glasses side.
     *
     * Returns true if the alert was sent to at least one device, false otherwise.
     * False can mean: no server running, no devices connected, or no service found.
     *
     * PRIVACY: The alertPayload must be pre-anonymized. No worker identity data.
     * Only violation_type, zone_id, severity, and bilingual message text.
     *
     * @param alertPayload Serialized alert bytes (JSON encoded SafetyAlert minus PII)
     * @return true if notification was sent to at least one device
     */
    fun sendAlert(alertPayload: ByteArray): Boolean {
        val server = gattServer ?: return false
        val service = server.getService(DUCHESS_SERVICE_UUID) ?: return false
        val characteristic = service.getCharacteristic(ALERT_CHARACTERISTIC_UUID) ?: return false

        characteristic.value = alertPayload

        // Alex: Snapshot the connected devices under the lock, then notify outside it.
        // We don't want to hold the lock while doing BLE I/O — that would block
        // onConnectionStateChange callbacks and potentially deadlock.
        val devices: List<BluetoothDevice>
        synchronized(connectedDevices) {
            devices = connectedDevices.toList()
        }

        if (devices.isEmpty()) return false

        // Alex: notifyCharacteristicChanged() queues the notification in the BLE stack.
        // The `false` param means "no confirmation required" (unacknowledged notification).
        // Using `true` would require the client to ACK each notification, which adds
        // latency we can't afford for safety alerts.
        var sentToAny = false
        for (device in devices) {
            val sent = server.notifyCharacteristicChanged(device, characteristic, false)
            if (sent) sentToAny = true
        }
        return sentToAny
    }
}
