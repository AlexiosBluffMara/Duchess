package com.duchess.companion.mesh

import app.cash.turbine.test
import com.duchess.companion.model.SafetyAlert
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MeshManager.
 *
 * Alex: The MeshManager wraps the Tailscale VPN connection logic.
 * In unit tests we can't test actual Tailscale connectivity (that needs
 * the Tailscale daemon running), so we focus on:
 *   1. State machine transitions (Disconnected → Connecting → Connected)
 *   2. broadcastAlert behavior in different states
 *   3. disconnect cleanup
 *
 * For actual mesh connectivity tests, you'd need an integration test
 * with a Tailscale test network. That's a CI/CD thing, not a unit test.
 */
class MeshManagerTest {

    private lateinit var meshManager: MeshManager

    @Before
    fun setup() {
        meshManager = MeshManager()
    }

    // --- State machine tests ---

    @Test
    fun `initial state is Disconnected`() = runTest {
        // Alex: MeshManager starts disconnected. Workers must explicitly connect
        // when they arrive on-site and their phone detects the site's WiFi.
        meshManager.state.test {
            assertEquals(MeshManager.MeshState.Disconnected, awaitItem())
        }
    }

    @Test
    fun `isConnected returns false initially`() {
        assertFalse(meshManager.isConnected())
    }

    @Test
    fun `connect transitions through Connecting to Connected`() = runTest {
        meshManager.state.test {
            assertEquals(MeshManager.MeshState.Disconnected, awaitItem())

            meshManager.connect()

            // Alex: connect() sets Connecting first, then Connected.
            // StateFlow might conflate Connecting if the TODO stub resolves
            // instantly, so we handle both orderings.
            val nextState = awaitItem()
            if (nextState is MeshManager.MeshState.Connecting) {
                assertEquals(MeshManager.MeshState.Connected, awaitItem())
            } else {
                assertEquals(MeshManager.MeshState.Connected, nextState)
            }
        }
    }

    @Test
    fun `isConnected returns true after connect`() = runTest {
        meshManager.connect()
        assertTrue(meshManager.isConnected())
    }

    @Test
    fun `disconnect transitions to Disconnected`() = runTest {
        // First connect
        meshManager.connect()
        assertTrue(meshManager.isConnected())

        // Then disconnect
        meshManager.disconnect()

        meshManager.state.test {
            assertEquals(MeshManager.MeshState.Disconnected, awaitItem())
        }
        assertFalse(meshManager.isConnected())
    }

    @Test
    fun `disconnect when already Disconnected is safe`() = runTest {
        // Alex: Double-disconnect should be a no-op, not a crash.
        meshManager.state.test {
            assertEquals(MeshManager.MeshState.Disconnected, awaitItem())
            meshManager.disconnect()
            // StateFlow won't re-emit the same value, so no new event
            expectNoEvents()
        }
    }

    // --- broadcastAlert tests ---

    @Test
    fun `broadcastAlert when disconnected does not crash`() {
        // Alex: Workers might trigger an alert before mesh is connected.
        // The manager should gracefully skip the broadcast, not crash.
        // In production, the fallback chain is: mesh → BLE → local-only.
        val alert = SafetyAlert(
            id = "test-001",
            violationType = "NO_HARD_HAT",
            severity = 3,
            zoneId = "zone-A",
            timestamp = System.currentTimeMillis(),
            messageEn = "Hard hat missing in Zone A",
            messageEs = "Falta casco en Zona A"
        )

        // Alex: Should not throw. Internally returns early because isConnected() is false.
        meshManager.broadcastAlert(alert)
    }

    @Test
    fun `broadcastAlert when connected does not crash`() = runTest {
        // Alex: Connect first, then broadcast. The TODO stub inside broadcastAlert
        // doesn't actually send anything yet, but it should not throw.
        meshManager.connect()

        val alert = SafetyAlert(
            id = "test-002",
            violationType = "NO_SAFETY_VEST",
            severity = 2,
            zoneId = "zone-B",
            timestamp = System.currentTimeMillis(),
            messageEn = "Safety vest missing in Zone B",
            messageEs = "Falta chaleco de seguridad en Zona B"
        )

        meshManager.broadcastAlert(alert)
        // Alex: No crash = test passes. When the TODO is implemented,
        // we'll add assertions for actual mesh traffic.
    }
}
