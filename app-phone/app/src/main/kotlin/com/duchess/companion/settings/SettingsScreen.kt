package com.duchess.companion.settings

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.duchess.companion.R
import com.duchess.companion.demo.ConnectionStatus

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onNavigateToHudSim: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsState()
    val nightlyUploadEnabled by viewModel.nightlyUploadEnabled.collectAsState()
    val alertSoundEnabled by viewModel.alertSoundEnabled.collectAsState()
    val selectedLanguage by viewModel.selectedLanguage.collectAsState()
    val detectionSensitivity by viewModel.detectionSensitivity.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val demoMode by viewModel.demoMode.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // Title
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        // --- Device Section ---
        SectionHeader(title = stringResource(R.string.settings_device))

        GlassesConnectionRow(connectionStatus = connectionStatus)
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        SettingsRow(
            icon = Icons.Filled.DevicesOther,
            title = stringResource(R.string.device_name),
            subtitle = stringResource(R.string.no_device_paired),
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        ChevronSettingsRow(
            icon = Icons.Filled.Visibility,
            title = stringResource(R.string.view_hud_sim),
            onClick = onNavigateToHudSim,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- Preferences Section ---
        SectionHeader(title = stringResource(R.string.settings_preferences))

        LanguageRow(
            selectedLanguage = selectedLanguage,
            onLanguageSelected = { viewModel.setLanguage(it) },
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        SwitchSettingsRow(
            icon = Icons.Filled.Notifications,
            title = stringResource(R.string.notifications),
            subtitle = stringResource(R.string.notifications_desc),
            checked = notificationsEnabled,
            onCheckedChange = { viewModel.toggleNotifications() },
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        SwitchSettingsRow(
            icon = Icons.Filled.CloudUpload,
            title = stringResource(R.string.nightly_upload),
            subtitle = stringResource(R.string.nightly_upload_desc),
            checked = nightlyUploadEnabled,
            onCheckedChange = { viewModel.toggleNightlyUpload() },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- Safety Section ---
        SectionHeader(title = stringResource(R.string.settings_safety))

        SensitivityRow(
            sensitivity = detectionSensitivity,
            onSensitivityChange = { viewModel.setDetectionSensitivity(it) },
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        SwitchSettingsRow(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            title = stringResource(R.string.alert_sound),
            subtitle = null,
            checked = alertSoundEnabled,
            onCheckedChange = { viewModel.toggleAlertSound() },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- App Mode Section ---
        SectionHeader(title = stringResource(R.string.settings_mode))

        SwitchSettingsRow(
            icon = Icons.Filled.Tune,
            title = stringResource(R.string.demo_mode_label),
            subtitle = if (demoMode) stringResource(R.string.demo_mode_desc) else stringResource(R.string.live_mode_desc),
            checked = demoMode,
            onCheckedChange = { enabled ->
                viewModel.setDemoMode(enabled)
                // Recreate the Activity so MainActivity re-reads the pref and
                // switches between demo and live composable trees.
                (context as? Activity)?.recreate()
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- About Section ---
        SectionHeader(title = stringResource(R.string.settings_about))

        SettingsRow(
            icon = Icons.Filled.Info,
            title = stringResource(R.string.version),
            subtitle = "0.1.0 (Demo)",
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        ChevronSettingsRow(
            icon = Icons.Filled.Policy,
            title = stringResource(R.string.privacy_policy),
            onClick = { /* Placeholder */ },
        )
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        ChevronSettingsRow(
            icon = Icons.Filled.Description,
            title = stringResource(R.string.licenses),
            onClick = { /* Placeholder */ },
        )

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// --- Section Header ---

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing * 1.2,
    )
}

// --- Glasses Connection Row ---

@Composable
private fun GlassesConnectionRow(connectionStatus: ConnectionStatus) {
    val (dotColor, statusText) = when (connectionStatus) {
        ConnectionStatus.CONNECTED -> Color(0xFF4CAF50) to stringResource(R.string.connection_connected)
        ConnectionStatus.DISCONNECTED -> Color(0xFFD32F2F) to stringResource(R.string.connection_disconnected)
        ConnectionStatus.DEMO_MODE -> Color(0xFFFFD600) to stringResource(R.string.connection_demo)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Bluetooth,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.glasses_connection),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Standard Settings Row ---

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// --- Switch Settings Row ---

@Composable
private fun SwitchSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
    }
}

// --- Chevron Row (navigation-style) ---

@Composable
private fun ChevronSettingsRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- Language Dropdown Row ---

@Composable
private fun LanguageRow(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val displayLanguage = if (selectedLanguage == "en") "English" else "Español"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Language,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = stringResource(R.string.language),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = displayLanguage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("English") },
                onClick = {
                    onLanguageSelected("en")
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Español") },
                onClick = {
                    onLanguageSelected("es")
                    expanded = false
                },
            )
        }
    }
}

// --- Sensitivity Slider Row ---

@Composable
private fun SensitivityRow(
    sensitivity: Float,
    onSensitivityChange: (Float) -> Unit,
) {
    val label = when {
        sensitivity < 0.33f -> stringResource(R.string.sensitivity_low)
        sensitivity < 0.66f -> stringResource(R.string.sensitivity_medium)
        else -> stringResource(R.string.sensitivity_high)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = stringResource(R.string.detection_sensitivity),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = sensitivity,
            onValueChange = onSensitivityChange,
            modifier = Modifier.padding(start = 40.dp),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.sensitivity_low),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.sensitivity_medium),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.sensitivity_high),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
