package com.duchess.companion.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import com.duchess.companion.demo.ConnectionStatus
import com.duchess.companion.demo.DemoDataProvider
import com.duchess.companion.demo.ZoneStatus
import com.duchess.companion.model.SafetyAlert
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
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
}
