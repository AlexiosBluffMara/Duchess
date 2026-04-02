package com.duchess.companion.alerts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.duchess.companion.demo.DemoDataProvider
import com.duchess.companion.model.SafetyAlert
import com.duchess.companion.stream.InferencePipelineCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AlertFilter { ALL, CRITICAL, WARNING, INFO }

/**
 * ViewModel managing the safety alert list for AlertListScreen and DashboardScreen.
 *
 * Alex: Two alert sources feed into this ViewModel:
 *
 *   1. DEMO DATA (initial state): DemoDataProvider.getSampleAlerts() seeds the list at
 *      startup so the app looks populated in demo mode or before live inference warms up.
 *      This is intentional UX — a blank alerts list on first launch looks broken.
 *
 *   2. LIVE INFERENCE (runtime): The InferencePipelineCoordinator emits SafetyAlerts
 *      on its alertFlow whenever Gemma 4 detects a violation with confidence > 50%.
 *      observeInferenceAlerts() subscribes in init{} and prepends each new alert to
 *      the list in real time.
 *
 * The separation of concerns:
 *   - InferencePipelineCoordinator owns the detection logic (Gemma + BLE routing)
 *   - AlertsViewModel owns the UI state (list ordering, filtering, navigation)
 *   - AlertListScreen just observes the filteredAlerts StateFlow
 *
 * PRIVACY: SafetyAlert contains no worker identity fields (enforced by SafetyAlertTest).
 * This ViewModel never adds PII to alerts — it only receives and filters them.
 */
@HiltViewModel
class AlertsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val coordinator: InferencePipelineCoordinator,
) : ViewModel() {

    // Alex: Demo alerts as initial state. The list grows prepended with live inference
    // alerts during an active session. We keep demo alerts at the tail to avoid them
    // dominating the view once real detections start flowing.
    private val _alerts = MutableStateFlow(DemoDataProvider.getSampleAlerts())
    val alerts: StateFlow<List<SafetyAlert>> = _alerts.asStateFlow()

    private val _selectedFilter = MutableStateFlow(AlertFilter.ALL)
    val selectedFilter: StateFlow<AlertFilter> = _selectedFilter.asStateFlow()

    private val _filteredAlerts = MutableStateFlow(DemoDataProvider.getSampleAlerts())
    val filteredAlerts: StateFlow<List<SafetyAlert>> = _filteredAlerts.asStateFlow()

    init {
        applyFilter()
        observeInferenceAlerts()
    }

    /**
     * Set the active filter and re-apply it to the current alert list.
     */
    fun setFilter(filter: AlertFilter) {
        _selectedFilter.value = filter
        applyFilter()
    }

    /**
     * Look up a specific alert by ID for the detail screen.
     */
    fun getAlertById(id: String): SafetyAlert? {
        return _alerts.value.find { it.id == id }
    }

    /**
     * Add a new alert from live inference, prepended to the top of the list.
     *
     * Alex: Prepending keeps the most recent alert at the top — standard pattern
     * for real-time safety feeds. Workers scrolling the list see the latest hazard first.
     *
     * We also cap the list at MAX_ALERTS_IN_MEMORY to prevent unbounded growth
     * during a long shift (construction shifts can be 10+ hours at 1 detection/second
     * = tens of thousands of alerts if we don't cap). We keep the newest MAX_ALERTS_IN_MEMORY
     * and silently drop the oldest. The cloud DynamoDB table retains the full history.
     */
    fun addAlert(alert: SafetyAlert) {
        val updated = (listOf(alert) + _alerts.value).take(MAX_ALERTS_IN_MEMORY)
        _alerts.value = updated
        applyFilter()
    }

    /**
     * Subscribe to the InferencePipelineCoordinator's alertFlow.
     *
     * Alex: This is the live wiring. Every time Gemma detects a violation and the
     * coordinator emits a SafetyAlert, we get it here and prepend it to the UI list.
     * The viewModelScope coroutine lives as long as the ViewModel (Activity lifecycle).
     *
     * We use viewModelScope.launch{} (not GlobalScope) so this subscription is
     * automatically cancelled when the Activity is destroyed. No memory leak.
     */
    private fun observeInferenceAlerts() {
        viewModelScope.launch {
            coordinator.alertFlow.collect { alert ->
                addAlert(alert)
            }
        }
    }

    private fun applyFilter() {
        val all = _alerts.value
        _filteredAlerts.value = when (_selectedFilter.value) {
            AlertFilter.ALL -> all
            AlertFilter.CRITICAL -> all.filter { it.severity >= 4 }
            AlertFilter.WARNING -> all.filter { it.severity in 2..3 }
            AlertFilter.INFO -> all.filter { it.severity <= 1 }
        }
    }

    companion object {
        // Alex: Cap at 500 alerts in memory. A 10-hour shift at 1 detection/5s max
        // = ~7,200 potential alerts. Keeping 500 gives a 40+ minute rolling window
        // which is plenty for the supervisor dashboard. Full history is in DynamoDB.
        const val MAX_ALERTS_IN_MEMORY = 500
    }
}
