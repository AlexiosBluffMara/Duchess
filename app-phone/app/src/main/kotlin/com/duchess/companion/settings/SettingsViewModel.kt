package com.duchess.companion.settings

import androidx.lifecycle.ViewModel
import com.duchess.companion.demo.ConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {

    private val _notificationsEnabled = MutableStateFlow(true)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _nightlyUploadEnabled = MutableStateFlow(true)
    val nightlyUploadEnabled: StateFlow<Boolean> = _nightlyUploadEnabled.asStateFlow()

    private val _alertSoundEnabled = MutableStateFlow(true)
    val alertSoundEnabled: StateFlow<Boolean> = _alertSoundEnabled.asStateFlow()

    private val _selectedLanguage = MutableStateFlow("en")
    val selectedLanguage: StateFlow<String> = _selectedLanguage.asStateFlow()

    private val _detectionSensitivity = MutableStateFlow(0.5f)
    val detectionSensitivity: StateFlow<Float> = _detectionSensitivity.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DEMO_MODE)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    fun toggleNotifications() {
        _notificationsEnabled.value = !_notificationsEnabled.value
    }

    fun toggleNightlyUpload() {
        _nightlyUploadEnabled.value = !_nightlyUploadEnabled.value
    }

    fun toggleAlertSound() {
        _alertSoundEnabled.value = !_alertSoundEnabled.value
    }

    fun setLanguage(lang: String) {
        _selectedLanguage.value = lang
    }

    fun setDetectionSensitivity(value: Float) {
        _detectionSensitivity.value = value
    }
}
