package com.duchess.companion.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duchess.companion.demo.ConnectionStatus
import com.duchess.companion.demo.DemoDataProvider
import com.duchess.companion.demo.ZoneStatus
import com.duchess.companion.model.SafetyAlert
import com.duchess.companion.stream.InferencePipelineCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val coordinator: InferencePipelineCoordinator,
) : ViewModel() {

    private val _safetyScore = MutableStateFlow(DemoDataProvider.getSafetyScore())
    val safetyScore: StateFlow<Int> = _safetyScore.asStateFlow()

    private val _zones = MutableStateFlow(DemoDataProvider.getZoneStatuses())
    val zones: StateFlow<List<ZoneStatus>> = _zones.asStateFlow()

    private val _activeWorkers = MutableStateFlow(DemoDataProvider.getActiveWorkerCount())
    val activeWorkers: StateFlow<Int> = _activeWorkers.asStateFlow()

    private val _connectionStatus = MutableStateFlow(DemoDataProvider.getConnectionStatus())
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

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
                _safetyScore.value = maxOf(10, 100 - (criticalCount * 8))
            }
        }
    }
}
