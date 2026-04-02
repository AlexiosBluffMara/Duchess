package com.duchess.companion.mesh

import com.duchess.companion.model.SafetyAlert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Tailscale mesh network connectivity for the Duchess site network.
 *
 * Alex: The mesh network is the nervous system of the jobsite. Every phone on-site
 * joins a Tailscale WireGuard VPN mesh, enabling:
 *   - Direct peer-to-peer alert delivery (<10ms latency)
 *   - Geospatial routing (alerts go to nearest workers first)
 *   - Cloud escalation via Tailscale exit node
 *   - GPS location broadcasting for zone tracking
 *
 * The mesh is NOT required for basic operation. The degradation chain is:
 *   1. Mesh available → broadcast alert to all peers via Tailscale
 *   2. Mesh down, BLE up → send alert to nearby glasses via BLE GATT
 *   3. Everything down → store alert locally, sync when connectivity returns
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

    fun isConnected(): Boolean = _state.value is MeshState.Connected

    /**
     * Broadcast a safety alert to all devices on the site mesh.
     *
     * Alex: This is the primary alert delivery mechanism. The alert is serialized
     * to JSON and sent to the mesh coordinator endpoint, which fans it out to
     * all connected peers. The zoneId field enables geospatial routing — workers
     * in or near the affected zone get the alert first.
     *
     * PRIVACY: The SafetyAlert data class is already anonymized (no PII).
     * We double-check this at the model level with SafetyAlertTest.
     *
     * Falls back gracefully if mesh is unavailable — no crash, no exception.
     */
    fun broadcastAlert(alert: SafetyAlert) {
        if (!isConnected()) {
            // Alex: Mesh down. In production, the caller (AlertDispatcher) would
            // try BLE broadcast next, then queue for later sync. We don't handle
            // the fallback here — single responsibility. MeshManager only does mesh.
            return
        }
        // TODO: Serialize alert and send via Tailscale mesh to all peers
        // - POST to mesh coordinator endpoint (http://100.x.x.x:8080/alert)
        // - Include zoneId for geospatial routing
        // - bilingual messageEn/messageEs included in payload
        // - Use OkHttp/Ktor client with exponential backoff
    }

    /**
     * Connect to the Tailscale mesh network.
     *
     * Alex: This initializes the Tailscale VPN tunnel using a site-specific
     * auth key. The auth key is stored in Android Keystore (NOT SharedPreferences)
     * and rotates per shift. After connection, we start broadcasting GPS location
     * every 30 seconds so the mesh coordinator knows where each device is.
     *
     * connect() is a suspend function because the Tailscale handshake involves
     * network I/O (key exchange with coordination server).
     */
    suspend fun connect() {
        _state.value = MeshState.Connecting
        // TODO: Initialize Tailscale VPN connection
        // - Authenticate via site-specific auth key from Keystore
        // - Register device on mesh with anonymous device pseudonym
        // - Start GPS broadcasting (every 30s, zone-level only — not exact coords)
        _state.value = MeshState.Connected
    }

    /**
     * Disconnect from the mesh network and stop all background broadcasting.
     *
     * Alex: Called when the worker leaves the jobsite or the app is being shut down.
     * This tears down the VPN tunnel and stops GPS broadcasting. The Tailscale
     * daemon on the device handles cleanup of kernel-level WireGuard interfaces.
     */
    fun disconnect() {
        // TODO: Tear down Tailscale connection
        // - Stop GPS broadcasting
        // - Close VPN tunnel
        // - Unregister from mesh coordinator
        _state.value = MeshState.Disconnected
    }
}
