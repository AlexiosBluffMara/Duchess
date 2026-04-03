package com.duchess.companion.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for BleGattServer.
 *
 * Alex: BLE testing on Android is notoriously hard because BluetoothGattServer
 * requires a real Bluetooth stack. We mock the Android framework classes to
 * test our state machine and logic WITHOUT needing actual Bluetooth hardware.
 *
 * These tests verify:
 *   1. Server state transitions (Stopped → Starting → Running → Stopped)
 *   2. Idempotent start (double-start doesn't crash)
 *   3. sendAlert behavior with no connected devices
 *   4. Error handling when BluetoothManager is unavailable
 *
 * For actual BLE integration tests (send alert → glasses receive it),
 * see the androidTest/ instrumented tests with MockDeviceKit.
 */
class BleGattServerTest {

    private lateinit var mockContext: Context
    private lateinit var mockBluetoothManager: BluetoothManager
    private lateinit var mockGattServer: BluetoothGattServer
    private lateinit var gattServer: BleGattServer

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)
        mockBluetoothManager = mockk(relaxed = true)
        mockGattServer = mockk(relaxed = true)

        // Alex: Wire up the mock chain: context → BluetoothManager → GattServer.
        // relaxed = true means any unmocked method returns a sensible default
        // (null for objects, 0 for ints, false for booleans).
        every {
            mockContext.getSystemService(Context.BLUETOOTH_SERVICE)
        } returns mockBluetoothManager

        // Stub BLUETOOTH_CONNECT permission as granted for test
        every {
            mockContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } returns PackageManager.PERMISSION_GRANTED

        every {
            mockBluetoothManager.openGattServer(any(), any())
        } returns mockGattServer

        // Alex: Mock the service and characteristic chain for sendAlert tests.
        // Without this, getService() returns null and sendAlert bails out early.
        val mockService = mockk<android.bluetooth.BluetoothGattService>(relaxed = true)
        val mockCharacteristic = mockk<android.bluetooth.BluetoothGattCharacteristic>(relaxed = true)
        every { mockGattServer.getService(BleGattServer.DUCHESS_SERVICE_UUID) } returns mockService
        every { mockService.getCharacteristic(BleGattServer.ALERT_CHARACTERISTIC_UUID) } returns mockCharacteristic

        gattServer = BleGattServer(mockContext)
    }

    // --- State machine tests ---

    @Test
    fun `initial state is Stopped`() = runTest {
        gattServer.state.test {
            assertEquals(BleGattServer.ServerState.Stopped, awaitItem())
        }
    }

    @Test
    fun `start transitions to Running`() = runTest {
        // Alex: start() goes Stopped → Starting → Running synchronously.
        // We only observe the final Running state because StateFlow conflates
        // rapid emissions — Starting is transient and may be skipped.
        gattServer.state.test {
            assertEquals(BleGattServer.ServerState.Stopped, awaitItem())
            gattServer.start()
            // Alex: StateFlow conflation may skip Starting, so we accept either
            val state = awaitItem()
            assert(
                state is BleGattServer.ServerState.Running ||
                state is BleGattServer.ServerState.Starting
            ) { "Expected Starting or Running, got $state" }
            if (state is BleGattServer.ServerState.Starting) {
                assertEquals(BleGattServer.ServerState.Running, awaitItem())
            }
        }
    }

    @Test
    fun `stop transitions to Stopped`() = runTest {
        gattServer.state.test {
            assertEquals(BleGattServer.ServerState.Stopped, awaitItem())
            gattServer.start()
            // Consume the Running state
            cancelAndIgnoreRemainingEvents()
        }

        gattServer.stop()

        gattServer.state.test {
            assertEquals(BleGattServer.ServerState.Stopped, awaitItem())
        }
    }

    @Test
    fun `double start is idempotent`() = runTest {
        // Alex: Calling start() when already Running should be a no-op.
        // The server should remain in Running state, no error.
        gattServer.start()

        gattServer.state.test {
            val currentState = awaitItem()
            // Alex: Should be Running from the first start()
            assertEquals(BleGattServer.ServerState.Running, currentState)

            // Second start should be silently ignored
            gattServer.start()
            // No new state emission expected
            expectNoEvents()
        }
    }

    @Test
    fun `stop when already stopped is safe`() = runTest {
        // Alex: stop() on an already-stopped server should not crash.
        // gattServer is null, close() is skipped, state stays Stopped.
        gattServer.state.test {
            assertEquals(BleGattServer.ServerState.Stopped, awaitItem())
            gattServer.stop()
            // State was already Stopped, so StateFlow won't emit (same value)
            expectNoEvents()
        }
    }

    // --- sendAlert tests ---

    @Test
    fun `sendAlert with no connected devices returns false`() {
        // Alex: sendAlert should return false when no devices are connected.
        // The characteristic gets updated but no notifications are sent.
        gattServer.start()
        val result = gattServer.sendAlert("test alert".toByteArray())
        assertFalse(result)
    }

    @Test
    fun `sendAlert when server not started returns false`() {
        // Alex: Without a running server, sendAlert should gracefully return false.
        val result = gattServer.sendAlert("test alert".toByteArray())
        assertFalse(result)
    }

    // --- Connected device count tests ---

    @Test
    fun `initial connected device count is zero`() = runTest {
        gattServer.connectedDeviceCount.test {
            assertEquals(0, awaitItem())
        }
    }

    // --- Error handling tests ---

    @Test
    fun `start with null BluetoothManager transitions to Error`() = runTest {
        // Alex: On devices where Bluetooth is unavailable, getSystemService returns null.
        every {
            mockContext.getSystemService(Context.BLUETOOTH_SERVICE)
        } returns null

        val serverNoBlue = BleGattServer(mockContext)

        serverNoBlue.state.test {
            assertEquals(BleGattServer.ServerState.Stopped, awaitItem())
            serverNoBlue.start()
            // Should skip Starting and go straight to Error (StateFlow conflation)
            val state = awaitItem()
            if (state is BleGattServer.ServerState.Starting) {
                val errorState = awaitItem()
                assert(errorState is BleGattServer.ServerState.Error)
            } else {
                assert(state is BleGattServer.ServerState.Error)
            }
        }
    }

    @Test
    fun `start with null GATT server transitions to Error`() = runTest {
        // Alex: openGattServer can return null if BT adapter is off or bonked.
        every {
            mockBluetoothManager.openGattServer(any(), any())
        } returns null

        val serverNoGatt = BleGattServer(mockContext)

        serverNoGatt.state.test {
            assertEquals(BleGattServer.ServerState.Stopped, awaitItem())
            serverNoGatt.start()
            val state = awaitItem()
            if (state is BleGattServer.ServerState.Starting) {
                val errorState = awaitItem()
                assert(errorState is BleGattServer.ServerState.Error)
            } else {
                assert(state is BleGattServer.ServerState.Error)
            }
        }
    }
}
