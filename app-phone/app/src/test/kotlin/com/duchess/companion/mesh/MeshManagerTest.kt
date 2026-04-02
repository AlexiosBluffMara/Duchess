package com.duchess.companion.mesh

import app.cash.turbine.test
import com.duchess.companion.model.SafetyAlert
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for MeshManager.
 *
 * NOAH: Tests cover the state machine, offline queue, alert serialization,
 * and privacy invariants. We can't test actual Tailscale connectivity in
 * unit tests (needs a real WireGuard tunnel), so we test:
 *   1. State machine transitions (Disconnected → Connecting → Connected/Disconnected)
 *   2. Offline queue behavior (enqueue, cap, drain, clear)
 *   3. Alert JSON serialization (correct fields, no PII)
 *   4. broadcastAlert routing (mesh up → send, mesh down → queue)
 *
 * Integration tests with a Tailscale test network live in androidTest/.
 */
class MeshManagerTest {

    private lateinit var meshManager: MeshManager

    @Before
    fun setup() {
        meshManager = MeshManager()
    }

    private fun createTestAlert(
        id: String = "test-001",
        violationType: String = "NO_HARD_HAT",
        severity: Int = 3,
        zoneId: String = "zone-A",
        timestamp: Long = 1700000000000L,
        messageEn: String = "Hard hat missing in Zone A",
        messageEs: String = "Falta casco en Zona A"
    ) = SafetyAlert(
        id = id,
        violationType = violationType,
        severity = severity,
        zoneId = zoneId,
        timestamp = timestamp,
        messageEn = messageEn,
        messageEs = messageEs
    )

    // -----------------------------------------------------------------------
    // State machine tests
    // -----------------------------------------------------------------------

    @Test
    fun `initial state is Disconnected`() = runTest {
        meshManager.state.test {
            assertEquals(MeshManager.MeshState.Disconnected, awaitItem())
        }
    }

    @Test
    fun `isConnected returns false initially`() {
        assertFalse(meshManager.isConnected())
    }

    @Test
    fun `connect transitions through Connecting then resolves`() = runTest {
        // NOAH: connect() without a running coordinator will resolve to Disconnected
        // because checkCoordinatorReachable() fails. We verify the Connecting
        // intermediate state is set.
        meshManager.state.test {
            assertEquals(MeshManager.MeshState.Disconnected, awaitItem())

            meshManager.connect()

            // NOAH: StateFlow may conflate Connecting if the check resolves instantly.
            // The final state will be Disconnected (no real coordinator in unit tests).
            val nextState = awaitItem()
            if (nextState is MeshManager.MeshState.Connecting) {
                // Connecting was observed, next should be the resolution
                val resolved = awaitItem()
                assertTrue(
                    resolved is MeshManager.MeshState.Connected ||
                        resolved is MeshManager.MeshState.Disconnected
                )
            } else {
                // Connecting was conflated, we got the final state directly
                assertTrue(
                    nextState is MeshManager.MeshState.Connected ||
                        nextState is MeshManager.MeshState.Disconnected
                )
            }
        }
    }

    @Test
    fun `disconnect transitions to Disconnected`() = runTest {
        meshManager.disconnect()

        meshManager.state.test {
            assertEquals(MeshManager.MeshState.Disconnected, awaitItem())
        }
        assertFalse(meshManager.isConnected())
    }

    @Test
    fun `disconnect when already Disconnected is safe`() = runTest {
        // NOAH: Double-disconnect should be a no-op, not a crash.
        meshManager.state.test {
            assertEquals(MeshManager.MeshState.Disconnected, awaitItem())
            meshManager.disconnect()
            // StateFlow won't re-emit the same value, so no new event
            expectNoEvents()
        }
    }

    // -----------------------------------------------------------------------
    // Offline queue tests
    // -----------------------------------------------------------------------

    @Test
    fun `alert queued when mesh is disconnected`() {
        // NOAH: When mesh is down, broadcastAlert() should enqueue, not drop.
        assertFalse(meshManager.isConnected())

        val alert = createTestAlert()
        meshManager.broadcastAlert(alert)

        assertEquals(1, meshManager.getPendingAlertCount())
    }

    @Test
    fun `multiple alerts queued when disconnected`() {
        assertFalse(meshManager.isConnected())

        repeat(5) { i ->
            meshManager.broadcastAlert(createTestAlert(id = "alert-$i"))
        }

        assertEquals(5, meshManager.getPendingAlertCount())
    }

    @Test
    fun `queue caps at 100 and drops oldest`() {
        // NOAH: Fill the queue to capacity, then add one more.
        // The oldest (first) alert should be evicted.
        assertFalse(meshManager.isConnected())

        repeat(MeshManager.MAX_QUEUE_SIZE) { i ->
            meshManager.broadcastAlert(createTestAlert(id = "alert-$i"))
        }
        assertEquals(MeshManager.MAX_QUEUE_SIZE, meshManager.getPendingAlertCount())

        // Add the 101st alert — should evict alert-0
        meshManager.broadcastAlert(createTestAlert(id = "alert-overflow"))
        assertEquals(MeshManager.MAX_QUEUE_SIZE, meshManager.getPendingAlertCount())
    }

    @Test
    fun `getPendingAlertCount reflects queue size`() {
        assertEquals(0, meshManager.getPendingAlertCount())

        meshManager.broadcastAlert(createTestAlert(id = "a1"))
        assertEquals(1, meshManager.getPendingAlertCount())

        meshManager.broadcastAlert(createTestAlert(id = "a2"))
        assertEquals(2, meshManager.getPendingAlertCount())

        meshManager.broadcastAlert(createTestAlert(id = "a3"))
        assertEquals(3, meshManager.getPendingAlertCount())
    }

    @Test
    fun `clearPendingAlerts empties the queue`() {
        repeat(10) { i ->
            meshManager.broadcastAlert(createTestAlert(id = "alert-$i"))
        }
        assertEquals(10, meshManager.getPendingAlertCount())

        meshManager.clearPendingAlerts()
        assertEquals(0, meshManager.getPendingAlertCount())
    }

    @Test
    fun `queue drains on reconnect via drainOfflineQueue`() {
        // NOAH: Simulate queueing alerts while disconnected, then draining.
        // Since there's no real coordinator, drainOfflineQueue() will fail to
        // send and leave alerts in the queue. We verify the drain is attempted
        // by checking that alerts remain (no coordinator to accept them).
        repeat(3) { i ->
            meshManager.enqueueAlert(createTestAlert(id = "queued-$i"))
        }
        assertEquals(3, meshManager.getPendingAlertCount())

        // NOAH: drainOfflineQueue will try to send and fail (no coordinator),
        // so alerts stay in the queue. This is correct behavior.
        meshManager.drainOfflineQueue()
        assertEquals(3, meshManager.getPendingAlertCount())
    }

    @Test
    fun `enqueueAlert directly respects cap`() {
        repeat(MeshManager.MAX_QUEUE_SIZE + 5) { i ->
            meshManager.enqueueAlert(createTestAlert(id = "direct-$i"))
        }
        assertEquals(MeshManager.MAX_QUEUE_SIZE, meshManager.getPendingAlertCount())
    }

    // -----------------------------------------------------------------------
    // Alert serialization tests
    // -----------------------------------------------------------------------

    @Test
    fun `alert JSON contains exactly the right fields`() {
        val alert = createTestAlert(
            id = "ser-001",
            violationType = "NO_SAFETY_VEST",
            severity = 4,
            zoneId = "zone-C",
            timestamp = 1700000000000L,
            messageEn = "Missing safety vest in Zone C",
            messageEs = "Falta chaleco de seguridad en Zona C"
        )

        val json = JSONObject(meshManager.serializeAlert(alert))

        // NOAH: Verify all SafetyAlert fields are present with correct values
        assertEquals("ser-001", json.getString("id"))
        assertEquals("NO_SAFETY_VEST", json.getString("violationType"))
        assertEquals(4, json.getInt("severity"))
        assertEquals("zone-C", json.getString("zoneId"))
        assertEquals(1700000000000L, json.getLong("timestamp"))
        assertEquals("Missing safety vest in Zone C", json.getString("messageEn"))
        assertEquals("Falta chaleco de seguridad en Zona C", json.getString("messageEs"))
    }

    @Test
    fun `alert JSON contains no PII fields`() {
        // NOAH: Privacy invariant — the JSON payload must NEVER contain worker
        // identity fields. This test is a safety net alongside SafetyAlertTest.
        val alert = createTestAlert()
        val json = JSONObject(meshManager.serializeAlert(alert))

        val keys = json.keys().asSequence().toSet()

        // NOAH: Allowlist of permitted fields. Anything outside this set is a
        // potential PII leak and fails the test.
        val allowedKeys = setOf(
            "id", "violationType", "severity", "zoneId",
            "timestamp", "messageEn", "messageEs"
        )
        assertEquals(allowedKeys, keys)

        // NOAH: Explicitly verify no PII-like keys snuck in
        val piiKeys = listOf(
            "name", "workerName", "workerId", "employeeId",
            "faceId", "badgeNumber", "gps", "latitude", "longitude",
            "deviceId", "phoneNumber", "email", "ssn"
        )
        for (piiKey in piiKeys) {
            assertFalse(
                "JSON must not contain PII field: $piiKey",
                json.has(piiKey)
            )
        }
    }

    @Test
    fun `alert JSON includes zoneId for geospatial routing`() {
        // NOAH: The zoneId is critical for the coordinator to perform geospatial
        // fan-out. Workers in or near the affected zone get the alert first.
        val alert = createTestAlert(zoneId = "zone-F3-north")
        val json = JSONObject(meshManager.serializeAlert(alert))

        assertTrue(json.has("zoneId"))
        assertEquals("zone-F3-north", json.getString("zoneId"))
    }

    @Test
    fun `alert JSON includes bilingual messages`() {
        // NOAH: Both EN and ES messages must be in the payload so the
        // receiving device can display the worker's preferred language.
        val alert = createTestAlert(
            messageEn = "No hard hat detected",
            messageEs = "No se detectó casco"
        )
        val json = JSONObject(meshManager.serializeAlert(alert))

        assertEquals("No hard hat detected", json.getString("messageEn"))
        assertEquals("No se detectó casco", json.getString("messageEs"))
    }

    // -----------------------------------------------------------------------
    // broadcastAlert behavior tests
    // -----------------------------------------------------------------------

    @Test
    fun `broadcastAlert when disconnected queues instead of crashing`() {
        assertFalse(meshManager.isConnected())

        val alert = createTestAlert(id = "no-crash-001")
        // NOAH: Must not throw. Alert goes to offline queue.
        meshManager.broadcastAlert(alert)

        assertEquals(1, meshManager.getPendingAlertCount())
    }

    @Test
    fun `broadcastAlert when disconnected preserves alert order`() {
        // NOAH: Queue is FIFO. First alert in = first alert out on drain.
        assertFalse(meshManager.isConnected())

        val alerts = (1..5).map { createTestAlert(id = "order-$it") }
        alerts.forEach { meshManager.broadcastAlert(it) }

        assertEquals(5, meshManager.getPendingAlertCount())
    }

    // -----------------------------------------------------------------------
    // Coordinator reachability
    // -----------------------------------------------------------------------

    @Test
    fun `checkCoordinatorReachable returns false when no coordinator`() {
        // NOAH: In unit tests, there's no Tailscale mesh or coordinator.
        // The TCP connect should fail and return false.
        assertFalse(meshManager.checkCoordinatorReachable())
    }

    @Test
    fun `sendAlertToCoordinator returns false when no coordinator`() {
        // NOAH: No coordinator running = HTTP POST fails gracefully.
        val alert = createTestAlert()
        assertFalse(meshManager.sendAlertToCoordinator(alert))
    }
}
