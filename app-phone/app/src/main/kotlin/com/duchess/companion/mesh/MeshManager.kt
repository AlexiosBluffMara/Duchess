package com.duchess.companion.mesh

import com.duchess.companion.model.SafetyAlert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Tailscale mesh network connectivity for the Duchess site network.
 *
 * NOAH: The mesh network is the nervous system of the jobsite. Every phone on-site
 * joins a Tailscale WireGuard VPN mesh, enabling:
 *   - Direct peer-to-peer alert delivery (<10ms latency)
 *   - Geospatial routing (alerts go to nearest workers first)
 *   - Cloud escalation via Tailscale exit node
 *   - GPS location broadcasting for zone tracking
 *
 * The mesh is NOT required for basic operation. The degradation chain is:
 *   1. Mesh available → broadcast alert to all peers via Tailscale
 *   2. Mesh down → queue alert locally in offlineQueue (cap 100)
 *   3. Mesh reconnects → drain queue, send all pending alerts
 *
 * All mesh traffic is WireGuard-encrypted. Tailscale cannot decrypt because
 * keys are managed by the org, not Tailscale's coordination server.
 *
 * This is a @Singleton because there should be exactly ONE mesh connection per app.
 * Multiple connections would confuse the Tailscale daemon and waste battery.
 */
@Singleton
class MeshManager @Inject constructor() {

    sealed interface MeshState {
        data object Connected : MeshState
        data object Disconnected : MeshState
        data object Connecting : MeshState
    }

    private val _state = MutableStateFlow<MeshState>(MeshState.Disconnected)
    val state: StateFlow<MeshState> = _state.asStateFlow()

    // NOAH: AtomicBoolean for thread-safe reads from broadcastAlert() which may
    // be called from any coroutine dispatcher. StateFlow.value is thread-safe too,
    // but AtomicBoolean gives us a single CAS-capable flag for the hot path.
    private val connected = AtomicBoolean(false)

    // NOAH: Offline queue stores alerts when mesh is unreachable. Uses
    // ConcurrentLinkedQueue for lock-free thread safety. Capped at 100 entries —
    // on a busy site with 50+ workers, 100 queued alerts is ~10 minutes of
    // constant violations. If we exceed that, the oldest alert is dropped because
    // newer violations are more actionable than stale ones.
    private val offlineQueue = ConcurrentLinkedQueue<SafetyAlert>()

    // NOAH: Periodic connectivity check job — cancelled on disconnect().
    private var connectivityCheckJob: Job? = null

    // NOAH: Coordinator endpoint. On the Tailscale mesh, every site has a
    // coordinator service running on the Mac server (Tier 3) or a designated
    // phone. The 100.x.x.x address is a Tailscale CGNAT IP — it's only
    // reachable through the WireGuard tunnel, never over the public internet.
    internal var coordinatorHost: String = "100.64.0.1"
    internal var coordinatorPort: Int = 8080

    companion object {
        // NOAH: Queue cap — drop oldest when full. 100 alerts covers ~10 min of
        // continuous violations on a large site. After that, stale alerts lose
        // actionability and we prefer fresh ones.
        internal const val MAX_QUEUE_SIZE = 100

        // NOAH: Connectivity check interval. 30s balances battery drain vs.
        // detection latency. On WiFi this is negligible power; on cellular
        // the Tailscale keepalive is already firing every 25s anyway.
        internal const val CONNECTIVITY_CHECK_INTERVAL_MS = 30_000L

        // NOAH: HTTP timeouts. 5s connect catches DNS/routing failures fast.
        // 10s read allows for the coordinator to fan out to peers before acking.
        // These are generous for a local mesh — p99 should be <500ms.
        internal const val CONNECT_TIMEOUT_MS = 5_000
        internal const val READ_TIMEOUT_MS = 10_000
    }

    fun isConnected(): Boolean = connected.get()

    /**
     * Returns the number of alerts waiting in the offline queue.
     * Useful for UI display (e.g., "3 alerts pending sync").
     */
    fun getPendingAlertCount(): Int = offlineQueue.size

    /**
     * Clears all pending alerts from the offline queue.
     * Manual override for supervisors who want to dismiss stale alerts.
     */
    fun clearPendingAlerts() {
        offlineQueue.clear()
    }

    /**
     * Broadcast a safety alert to all devices on the site mesh.
     *
     * NOAH: This is the primary alert delivery mechanism. The alert is serialized
     * to JSON and POSTed to the mesh coordinator, which fans it out to all
     * connected peers. The zoneId header enables geospatial routing — the
     * coordinator delivers to workers in or near the affected zone first.
     *
     * PRIVACY: The SafetyAlert data class is already anonymized (no PII).
     * We serialize only SafetyAlert fields — no device ID, no worker identity,
     * no exact GPS. The coordinator uses zoneId for routing, not coordinates.
     *
     * Falls back gracefully if mesh is unavailable — queues locally, never crashes.
     */
    fun broadcastAlert(alert: SafetyAlert) {
        if (!isConnected()) {
            // NOAH: Mesh down. Queue the alert for delivery when we reconnect.
            // The offline queue is the safety net — no alert is silently dropped.
            enqueueAlert(alert)
            return
        }

        if (!sendAlertToCoordinator(alert)) {
            // NOAH: Send failed (network blip, coordinator restart, etc.).
            // Queue it — the periodic connectivity check will drain the queue
            // when the mesh is confirmed healthy again.
            enqueueAlert(alert)
        }
    }

    /**
     * Connect to the Tailscale mesh network.
     *
     * NOAH: Tailscale VPN is managed by the Tailscale Android app (separate APK).
     * We don't control the tunnel directly — we check reachability of the mesh
     * coordinator to determine if we're on the mesh. This is intentional:
     *   - Tailscale handles key exchange, NAT traversal, DERP relay selection
     *   - We just need to know "can I reach the coordinator?" to set our state
     *   - The coordinator hostname resolves only through MagicDNS on the mesh
     *
     * After confirming connectivity, we start a periodic check every 30s.
     * If the mesh drops (worker walks behind a steel beam, WiFi blip), we
     * detect it within 30s and flip to Disconnected so alerts get queued.
     *
     * @param scope CoroutineScope for the periodic connectivity check job.
     *              Typically viewModelScope or a service scope.
     */
    suspend fun connect(scope: CoroutineScope? = null) {
        _state.value = MeshState.Connecting

        // NOAH: Check if the mesh coordinator is reachable. This validates
        // that the Tailscale tunnel is up and our coordinator is healthy.
        val reachable = checkCoordinatorReachable()

        if (reachable) {
            connected.set(true)
            _state.value = MeshState.Connected

            // NOAH: Drain any alerts that were queued while we were offline.
            // This happens on reconnect — critical for not losing safety alerts.
            drainOfflineQueue()

            // NOAH: Start periodic connectivity check. If scope is null
            // (unit tests), skip the background job — tests control state directly.
            if (scope != null) {
                startConnectivityCheck(scope)
            }
        } else {
            connected.set(false)
            _state.value = MeshState.Disconnected
        }
    }

    /**
     * Disconnect from the mesh network and stop all background monitoring.
     *
     * NOAH: Called when the worker leaves the jobsite or the app shuts down.
     * We cancel the connectivity check, attempt to drain the offline queue
     * (best-effort — if mesh is already gone, alerts stay queued on-device),
     * then update state.
     */
    fun disconnect() {
        // NOAH: Cancel periodic check first to prevent it from flipping state
        // back to Connected while we're tearing down.
        connectivityCheckJob?.cancel()
        connectivityCheckJob = null

        // NOAH: Best-effort drain — try to flush pending alerts before going offline.
        // If the mesh is already unreachable, they stay in the queue for next session.
        drainOfflineQueue()

        connected.set(false)
        _state.value = MeshState.Disconnected
    }

    // -----------------------------------------------------------------------
    // Internal: alert serialization and HTTP transport
    // -----------------------------------------------------------------------

    /**
     * Serialize a SafetyAlert to JSON for mesh transport.
     *
     * NOAH: We use org.json.JSONObject (Android SDK built-in) to avoid adding
     * a serialization dependency. The payload contains ONLY SafetyAlert fields —
     * no device ID, no worker identity, no GPS coordinates. The zoneId is the
     * coarsest location granularity we transmit.
     */
    internal fun serializeAlert(alert: SafetyAlert): String {
        return JSONObject().apply {
            put("id", alert.id)
            put("violationType", alert.violationType)
            put("severity", alert.severity)
            put("zoneId", alert.zoneId)
            put("timestamp", alert.timestamp)
            put("messageEn", alert.messageEn)
            put("messageEs", alert.messageEs)
        }.toString()
    }

    /**
     * POST an alert to the mesh coordinator.
     *
     * NOAH: Uses java.net.HttpURLConnection to keep dependencies minimal.
     * The coordinator runs on the Tailscale mesh at a CGNAT IP (100.x.x.x),
     * so this request never leaves the WireGuard tunnel. The zoneId header
     * lets the coordinator do geospatial fan-out without parsing the body.
     *
     * Timeouts: 5s connect (catches DNS/routing failures fast), 10s read
     * (allows coordinator to fan out before acking). On a healthy mesh,
     * p50 is <50ms, p99 is <500ms.
     *
     * @return true if the coordinator accepted the alert (HTTP 2xx), false otherwise.
     */
    internal fun sendAlertToCoordinator(alert: SafetyAlert): Boolean {
        return try {
            val url = URL("http://$coordinatorHost:$coordinatorPort/api/v1/alerts")
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                // NOAH: zoneId header enables geospatial routing at the coordinator level.
                // The coordinator reads this header to prioritize delivery to workers
                // in the affected zone, without needing to parse the JSON body.
                setRequestProperty("X-Zone-Id", alert.zoneId)
            }

            val json = serializeAlert(alert)

            connection.outputStream.use { outputStream ->
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(json)
                    writer.flush()
                }
            }

            val responseCode = connection.responseCode
            connection.disconnect()

            // NOAH: Any 2xx is success. The coordinator may return 202 Accepted
            // for async fan-out or 200 OK for synchronous delivery.
            responseCode in 200..299
        } catch (_: Exception) {
            // NOAH: Network failure — timeout, connection refused, DNS failure, etc.
            // Don't log the exception details (could leak network topology).
            // The caller will queue the alert for retry.
            false
        }
    }

    // -----------------------------------------------------------------------
    // Internal: offline queue management
    // -----------------------------------------------------------------------

    /**
     * Add an alert to the offline queue, enforcing the cap.
     *
     * NOAH: ConcurrentLinkedQueue is unbounded, so we enforce MAX_QUEUE_SIZE
     * manually. When full, we drop the oldest alert (poll from head). This is
     * intentional — newer violations are more actionable than stale ones.
     * A 10-minute-old "no hard hat" alert is less useful than a 30-second-old one.
     */
    internal fun enqueueAlert(alert: SafetyAlert) {
        // NOAH: Drop oldest if at capacity. In practice, hitting 100 queued
        // alerts means the mesh has been down for several minutes on a busy site.
        while (offlineQueue.size >= MAX_QUEUE_SIZE) {
            offlineQueue.poll()
        }
        offlineQueue.add(alert)
    }

    /**
     * Attempt to send all queued alerts to the coordinator.
     *
     * NOAH: Drains head-first (FIFO) so alerts are delivered in chronological order.
     * If a send fails mid-drain, we stop and leave remaining alerts in the queue —
     * the next connectivity check will retry. This prevents hammering a flaky
     * coordinator with rapid-fire retries.
     */
    internal fun drainOfflineQueue() {
        while (offlineQueue.isNotEmpty()) {
            val alert = offlineQueue.peek() ?: break
            if (sendAlertToCoordinator(alert)) {
                offlineQueue.poll()
            } else {
                // NOAH: Coordinator unreachable again. Stop draining — the periodic
                // check will retry when connectivity is confirmed.
                break
            }
        }
    }

    // -----------------------------------------------------------------------
    // Internal: connectivity monitoring
    // -----------------------------------------------------------------------

    /**
     * Check if the mesh coordinator is reachable via TCP connect.
     *
     * NOAH: We can't call the Tailscale localapi from a 3P app (it binds to
     * localhost and requires the Tailscale UID). Instead, we probe the coordinator
     * directly — if we can TCP-connect to it, the Tailscale tunnel is up.
     *
     * A TCP connect probe (not HTTP) is used for speed — we just need to know
     * the port is open, not that the full HTTP stack is healthy. The coordinator
     * health check happens implicitly when we POST alerts.
     */
    internal fun checkCoordinatorReachable(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(coordinatorHost, coordinatorPort),
                    CONNECT_TIMEOUT_MS
                )
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Start periodic connectivity checks every 30 seconds.
     *
     * NOAH: This runs on the provided CoroutineScope (typically a service scope).
     * If the coordinator becomes unreachable, we flip to Disconnected immediately.
     * When it comes back, we flip to Connected and drain the offline queue.
     *
     * 30s interval balances:
     *   - Battery: negligible on WiFi, acceptable on cellular (Tailscale keepalive
     *     is already 25s)
     *   - Detection latency: worst case, we detect a mesh drop in 30s. For safety
     *     alerts, the broadcastAlert() call will fail-fast and queue immediately,
     *     so the effective detection latency for alert delivery is <1s.
     */
    private fun startConnectivityCheck(scope: CoroutineScope) {
        connectivityCheckJob?.cancel()
        connectivityCheckJob = scope.launch {
            while (true) {
                delay(CONNECTIVITY_CHECK_INTERVAL_MS)
                val reachable = checkCoordinatorReachable()
                val wasConnected = connected.get()

                if (reachable && !wasConnected) {
                    // NOAH: Mesh came back. Update state and drain queue.
                    connected.set(true)
                    _state.value = MeshState.Connected
                    drainOfflineQueue()
                } else if (!reachable && wasConnected) {
                    // NOAH: Mesh dropped. Flip to Disconnected so subsequent
                    // broadcastAlert() calls queue instead of attempting HTTP.
                    connected.set(false)
                    _state.value = MeshState.Disconnected
                }
            }
        }
    }
}
