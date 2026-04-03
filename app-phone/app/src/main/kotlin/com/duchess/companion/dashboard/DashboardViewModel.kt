package com.duchess.companion.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duchess.companion.demo.ConnectionStatus
import com.duchess.companion.demo.DemoDataProvider
import com.duchess.companion.demo.ZoneStatus
import com.duchess.companion.mesh.MeshManager
import com.duchess.companion.model.SafetyAlert
import com.duchess.companion.stream.InferencePipelineCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val coordinator: InferencePipelineCoordinator,
    private val meshManager: MeshManager,
) : ViewModel() {

    private val _safetyScore = MutableStateFlow(DemoDataProvider.getSafetyScore())
    val safetyScore: StateFlow<Int> = _safetyScore.asStateFlow()

    private val _zones = MutableStateFlow(DemoDataProvider.getZoneStatuses())
    val zones: StateFlow<List<ZoneStatus>> = _zones.asStateFlow()

    private val _activeWorkers = MutableStateFlow(DemoDataProvider.getActiveWorkerCount())
    val activeWorkers: StateFlow<Int> = _activeWorkers.asStateFlow()

    private val _connectionStatus = MutableStateFlow(DemoDataProvider.getConnectionStatus())
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    /** Live mesh connectivity state exposed to DashboardScreen. */
    // ELI13: stateIn() turns a Flow (a stream of data) into a StateFlow (a stream that
    // always remembers its latest value). WhileSubscribed(5_000) means "keep the data
    // stream alive for 5 seconds after the last screen stops watching." Why 5 seconds?
    // If you rotate your phone, the screen briefly dies and rebuilds — without this
    // grace period, we'd restart all data loading on every rotation. 5 seconds covers that.
    val meshState: StateFlow<MeshManager.MeshState> = meshManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeshManager.MeshState.Disconnected)

    private val _recentAlerts = MutableStateFlow(DemoDataProvider.getSampleAlerts())
    val recentAlerts: StateFlow<List<SafetyAlert>> = _recentAlerts.asStateFlow()

    val activeAlertCount: Int
        get() = _recentAlerts.value.count { it.severity >= 3 }

    init {
        // Subscribe to live inference alerts. In demo mode the coordinator never
        // emits, so demo seed data stays unchanged. In live mode each real
        // violation prepends to the feed and recalculates the safety score.
        viewModelScope.launch {
            coordinator.alertFlow.collect { alert ->
                val updated = (listOf(alert) + _recentAlerts.value).take(50)
                _recentAlerts.value = updated
                val criticalCount = updated.count { it.severity >= 4 }
                // ELI13: Safety score starts at 100 (perfect) and loses 8 points per critical
                // alert. So 1 critical = 92, 5 criticals = 60, 12 criticals = 4... but we cap
                // the minimum at 10 so the score never hits zero (zero would look like "no data"
                // rather than "very unsafe"). Why 8? Because ~11 criticals drops you to the
                // floor, which is roughly the "things are really bad, stop work" threshold.
                _safetyScore.value = maxOf(10, 100 - (criticalCount * 8))
            }
        }
        // Connect mesh and start offline-queue drain loop.
        viewModelScope.launch {
            meshManager.connect(viewModelScope)
        }
    }
}
