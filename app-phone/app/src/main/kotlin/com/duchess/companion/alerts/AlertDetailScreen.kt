package com.duchess.companion.alerts

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.duchess.companion.R
import com.duchess.companion.model.SafetyAlert
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CriticalRed = Color(0xFFD32F2F)
private val WarningOrange = Color(0xFFE65100)
private val InfoYellow = Color(0xFFF9A825)
private val CriticalRedBg = Color(0xFFFFEBEE)
private val WarningOrangeBg = Color(0xFFFFF3E0)
private val InfoYellowBg = Color(0xFFFFFDE7)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertDetailScreen(
    alertId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlertsViewModel = hiltViewModel(),
) {
    val alert = viewModel.getAlertById(alertId)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.alert_details)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        if (alert == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Alert not found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Header — large severity badge with icon and name
            AlertDetailHeader(alert)

            Spacer(modifier = Modifier.height(24.dp))

            // Description section (English)
            SectionHeader(title = stringResource(R.string.alert_description))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = alert.messageEn,
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Descripción section (Spanish)
            SectionHeader(title = "Descripción")
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = alert.messageEs,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Details section
            SectionHeader(title = stringResource(R.string.alert_details))
            Spacer(modifier = Modifier.height(8.dp))

            DetailRow(
                icon = Icons.Filled.LocationOn,
                label = stringResource(R.string.alert_zone),
                value = zoneDisplayName(alert.zoneId),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            DetailRow(
                icon = Icons.Filled.AccessTime,
                label = stringResource(R.string.alert_time),
                value = formatTimestamp(alert.timestamp),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            DetailRow(
                icon = severityIcon(alert.severity),
                label = stringResource(R.string.alert_severity),
                value = "${alert.severity} — ${severityLabel(alert.severity)}",
                valueColor = severityColor(alert.severity),
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            DetailRow(
                icon = violationDetailIcon(alert.violationType),
                label = stringResource(R.string.alert_violation_type),
                value = alert.violationType,
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Actions section
            SectionHeader(title = "Actions")
            Spacer(modifier = Modifier.height(12.dp))

            // Acknowledge button (primary, orange)
            Button(
                onClick = { /* Placeholder */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.alert_acknowledge),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Escalate button (outlined, red)
            OutlinedButton(
                onClick = { /* Placeholder */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = CriticalRed,
                ),
            ) {
                Text(
                    text = stringResource(R.string.alert_escalate),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Dismiss button (text only, gray)
            TextButton(
                onClick = { onBack() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(
                    text = stringResource(R.string.alert_dismiss),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AlertDetailHeader(alert: SafetyAlert) {
    val bgColor = when {
        alert.severity >= 4 -> CriticalRedBg
        alert.severity in 2..3 -> WarningOrangeBg
        else -> InfoYellowBg
    }
    val tintColor = severityColor(alert.severity)

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = violationDetailIcon(alert.violationType),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = tintColor,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = violationDisplayName(alert.violationType),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = tintColor,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = tintColor.copy(alpha = 0.15f),
                ) {
                    Text(
                        text = severityBadgeLabel(alert.severity),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = tintColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = valueColor,
            )
        }
    }
}

// --- Helpers ---

private fun severityColor(severity: Int): Color = when {
    severity >= 4 -> CriticalRed
    severity in 2..3 -> WarningOrange
    else -> InfoYellow
}

private fun severityLabel(severity: Int): String = when {
    severity >= 4 -> "Severe"
    severity in 2..3 -> "Moderate"
    else -> "Low"
}

private fun severityBadgeLabel(severity: Int): String = when {
    severity >= 4 -> "CRITICAL"
    severity in 2..3 -> "WARNING"
    else -> "INFO"
}

private fun severityIcon(severity: Int): ImageVector = when {
    severity >= 4 -> Icons.Filled.Warning
    severity in 2..3 -> Icons.Filled.ReportProblem
    else -> Icons.Filled.ErrorOutline
}

private fun violationDetailIcon(type: String): ImageVector = when (type) {
    "NO_HARD_HAT" -> Icons.Filled.Engineering
    "NO_SAFETY_VEST" -> Icons.Filled.VisibilityOff
    "NO_SAFETY_GLASSES" -> Icons.Filled.RemoveRedEye
    "FALL_HAZARD" -> Icons.Filled.Warning
    "RESTRICTED_ZONE" -> Icons.Filled.Shield
    "IMPROPER_SCAFFOLDING" -> Icons.Filled.Construction
    "HOUSEKEEPING" -> Icons.Filled.ReportProblem
    else -> Icons.Filled.Warning
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
