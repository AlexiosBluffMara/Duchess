package com.duchess.companion.mesh

import com.duchess.companion.model.SafetyAlert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Tailscale mesh network connectivity for the Duchess site network.
 * Handles peer discovery, alert broadcasting, and connectivity status.
 *
 * Graceful degradation: if mesh is down, alerts fall back to BLE,
 * then local-only mode.
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
     * Falls back gracefully if mesh is unavailable.
     */
    fun broadcastAlert(alert: SafetyAlert) {
        if (!isConnected()) {
            // TODO: Fall back to BLE broadcast, then local-only
            return
        }
        // TODO: Serialize alert and send via Tailscale mesh to all peers
        // - POST to mesh coordinator endpoint
        // - Include zoneId for geospatial routing
        // - bilingual messageEn/messageEs included in payload
    }

    /**
     * Connect to the Tailscale mesh network.
     */
    suspend fun connect() {
        _state.value = MeshState.Connecting
        // TODO: Initialize Tailscale VPN connection
        // - Authenticate via site-specific auth key
        // - Register device on mesh
        // - Start GPS broadcasting (every 30s)
        _state.value = MeshState.Connected
    }

    /**
     * Disconnect from the mesh network.
     */
    fun disconnect() {
        // TODO: Tear down Tailscale connection
        _state.value = MeshState.Disconnected
    }
}
