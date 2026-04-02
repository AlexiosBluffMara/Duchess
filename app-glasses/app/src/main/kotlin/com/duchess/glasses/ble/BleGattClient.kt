package com.duchess.glasses.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.duchess.glasses.model.Detection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * BLE GATT client connecting to the companion phone's GATT server.
 * Receives alert payloads from phone, sends escalation requests when
 * PPE violations are detected on-glasses.
 *
 * Battery: stops scanning once connected. Reconnects on disconnect.
 */
class BleGattClient(private val context: Context) {

    companion object {
        // Must match BleGattServer UUIDs in app-phone
        val DUCHESS_SERVICE_UUID: UUID = UUID.fromString("d0c5e550-0001-4b6e-a5a0-b0b0b0b0b0b0")
        val ALERT_CHARACTERISTIC_UUID: UUID = UUID.fromString("d0c5e550-0002-4b6e-a5a0-b0b0b0b0b0b0")
        val STATUS_CHARACTERISTIC_UUID: UUID = UUID.fromString("d0c5e550-0003-4b6e-a5a0-b0b0b0b0b0b0")
    }

    sealed interface ClientState {
        data object Disconnected : ClientState
        data object Scanning : ClientState
        data object Connecting : ClientState
        data object Connected : ClientState
        data class Error(val message: String) : ClientState
    }

    private val _state = MutableStateFlow<ClientState>(ClientState.Disconnected)
    val state: StateFlow<ClientState> = _state.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    _state.value = ClientState.Connected
                    gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    _state.value = ClientState.Disconnected
                    bluetoothGatt = null
                    // Auto-reconnect
                    connect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(DUCHESS_SERVICE_UUID) ?: return
                val alertChar = service.getCharacteristic(ALERT_CHARACTERISTIC_UUID)
                if (alertChar != null) {
                    // Subscribe to alert notifications from phone
                    gatt.setCharacteristicNotification(alertChar, true)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == ALERT_CHARACTERISTIC_UUID) {
                // TODO: Parse alert payload and display on HUD
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Found the Duchess companion phone — stop scan and connect
            stopScan()
            _state.value = ClientState.Connecting
            bluetoothGatt = result.device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = ClientState.Error("BLE scan failed: $errorCode")
        }
    }

    fun connect() {
        if (_state.value is ClientState.Connected || _state.value is ClientState.Scanning) return

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = ClientState.Error("Bluetooth not available")
            return
        }

        _state.value = ClientState.Scanning

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(DUCHESS_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        adapter.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
    }

    fun disconnect() {
        stopScan()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _state.value = ClientState.Disconnected
    }

    /**
     * Send a PPE violation escalation to the companion phone.
     */
    fun sendEscalation(detection: Detection) {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(DUCHESS_SERVICE_UUID) ?: return
        val statusChar = service.getCharacteristic(STATUS_CHARACTERISTIC_UUID) ?: return

        // Encode detection as compact payload: label|confidence|bbox
        val payload = "${detection.label}|${detection.confidence}|${detection.bbox.toShortString()}"
        statusChar.value = payload.toByteArray(Charsets.UTF_8)
        gatt.writeCharacteristic(statusChar)
    }

    private fun stopScan() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }
}
