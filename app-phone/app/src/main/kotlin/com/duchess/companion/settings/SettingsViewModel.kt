package com.duchess.companion.settings

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import com.duchess.companion.demo.ConnectionStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: SharedPreferences,
) : ViewModel() {

    private val _notificationsEnabled = MutableStateFlow(prefs.getBoolean("notifications", true))
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _nightlyUploadEnabled = MutableStateFlow(prefs.getBoolean("nightly_upload", true))
    val nightlyUploadEnabled: StateFlow<Boolean> = _nightlyUploadEnabled.asStateFlow()

    private val _alertSoundEnabled = MutableStateFlow(prefs.getBoolean("alert_sound", true))
    val alertSoundEnabled: StateFlow<Boolean> = _alertSoundEnabled.asStateFlow()

    private val _selectedLanguage = MutableStateFlow(prefs.getString("language", "en") ?: "en")
    val selectedLanguage: StateFlow<String> = _selectedLanguage.asStateFlow()

    private val _detectionSensitivity = MutableStateFlow(prefs.getFloat("sensitivity", 0.5f))
    val detectionSensitivity: StateFlow<Float> = _detectionSensitivity.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DEMO_MODE)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _demoMode = MutableStateFlow(prefs.getBoolean("demo_mode", true))
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    fun toggleNotifications() {
        val new = !_notificationsEnabled.value
        _notificationsEnabled.value = new
        prefs.edit().putBoolean("notifications", new).apply()
    }

    fun toggleNightlyUpload() {
        val new = !_nightlyUploadEnabled.value
        _nightlyUploadEnabled.value = new
        prefs.edit().putBoolean("nightly_upload", new).apply()
    }

    fun toggleAlertSound() {
        val new = !_alertSoundEnabled.value
        _alertSoundEnabled.value = new
        prefs.edit().putBoolean("alert_sound", new).apply()
    }

    fun setLanguage(lang: String) {
        _selectedLanguage.value = lang
        prefs.edit().putString("language", lang).apply()
    }

    fun setDetectionSensitivity(value: Float) {
        _detectionSensitivity.value = value
        prefs.edit().putFloat("sensitivity", value).apply()
    }

    fun setDemoMode(enabled: Boolean) {
        _demoMode.value = enabled
        prefs.edit().putBoolean("demo_mode", enabled).apply()
    }
}
