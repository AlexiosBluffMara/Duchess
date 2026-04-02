package com.duchess.companion.alerts

import android.content.Context
import androidx.lifecycle.ViewModel
import com.duchess.companion.demo.DemoDataProvider
import com.duchess.companion.model.SafetyAlert
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class AlertFilter { ALL, CRITICAL, WARNING, INFO }

@HiltViewModel
class AlertsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _alerts = MutableStateFlow(DemoDataProvider.getSampleAlerts())
    val alerts: StateFlow<List<SafetyAlert>> = _alerts.asStateFlow()

    private val _selectedFilter = MutableStateFlow(AlertFilter.ALL)
    val selectedFilter: StateFlow<AlertFilter> = _selectedFilter.asStateFlow()

    private val _filteredAlerts = MutableStateFlow(DemoDataProvider.getSampleAlerts())
    val filteredAlerts: StateFlow<List<SafetyAlert>> = _filteredAlerts.asStateFlow()

    init {
        applyFilter()
    }

    fun setFilter(filter: AlertFilter) {
        _selectedFilter.value = filter
        applyFilter()
    }

    fun getAlertById(id: String): SafetyAlert? {
        return _alerts.value.find { it.id == id }
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
}
