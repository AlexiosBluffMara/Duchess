package com.duchess.companion.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GATT server for glasses <-> phone communication.
 * Advertises Duchess service, accepts connections from paired Vuzix glasses.
 * Sends: alert commands, status updates.
 * Receives: frame metadata, escalation requests.
 */
@Singleton
class BleGattServer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val DUCHESS_SERVICE_UUID: UUID = UUID.fromString("d0c5e550-0001-4b6e-a5a0-b0b0b0b0b0b0")
        val ALERT_CHARACTERISTIC_UUID: UUID = UUID.fromString("d0c5e550-0002-4b6e-a5a0-b0b0b0b0b0b0")
        val STATUS_CHARACTERISTIC_UUID: UUID = UUID.fromString("d0c5e550-0003-4b6e-a5a0-b0b0b0b0b0b0")
    }

    sealed interface ServerState {
        data object Stopped : ServerState
        data object Starting : ServerState
        data object Running : ServerState
        data class Error(val message: String) : ServerState
    }

    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private var gattServer: BluetoothGattServer? = null

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    // Glasses connected
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    // Glasses disconnected
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.value)
        }
    }

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

        val service = BluetoothGattService(DUCHESS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val alertCharacteristic = BluetoothGattCharacteristic(
            ALERT_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

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

    fun stop() {
        gattServer?.close()
        gattServer = null
        _state.value = ServerState.Stopped
    }

    fun sendAlert(alertPayload: ByteArray) {
        val server = gattServer ?: return
        val service = server.getService(DUCHESS_SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(ALERT_CHARACTERISTIC_UUID) ?: return
        characteristic.value = alertPayload
    }
}
