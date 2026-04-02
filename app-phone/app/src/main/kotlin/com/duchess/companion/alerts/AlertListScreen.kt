package com.duchess.companion.alerts

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.duchess.companion.R
import com.duchess.companion.model.SafetyAlert

private val CriticalRed = Color(0xFFD32F2F)
private val WarningOrange = Color(0xFFE65100)
private val InfoYellow = Color(0xFFF9A825)
private val CriticalRedBg = Color(0xFFFFEBEE)
private val WarningOrangeBg = Color(0xFFFFF3E0)
private val InfoYellowBg = Color(0xFFFFFDE7)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertListScreen(
    onAlertClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlertsViewModel = hiltViewModel(),
) {
    val filteredAlerts by viewModel.filteredAlerts.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        // Header
        Text(
            text = stringResource(R.string.alerts_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        // Filter chips
        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AlertFilter.entries.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { viewModel.setFilter(filter) },
                    label = {
                        Text(
                            text = when (filter) {
                                AlertFilter.ALL -> stringResource(R.string.filter_all)
                                AlertFilter.CRITICAL -> stringResource(R.string.filter_critical)
                                AlertFilter.WARNING -> stringResource(R.string.filter_warning)
                                AlertFilter.INFO -> stringResource(R.string.filter_info)
                            }
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = when (filter) {
                            AlertFilter.ALL -> MaterialTheme.colorScheme.primaryContainer
                            AlertFilter.CRITICAL -> CriticalRedBg
                            AlertFilter.WARNING -> WarningOrangeBg
                            AlertFilter.INFO -> InfoYellowBg
                        },
                        selectedLabelColor = when (filter) {
                            AlertFilter.ALL -> MaterialTheme.colorScheme.onPrimaryContainer
                            AlertFilter.CRITICAL -> CriticalRed
                            AlertFilter.WARNING -> WarningOrange
                            AlertFilter.INFO -> Color(0xFF6D4C00)
                        },
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Alert list with animated content switching
        AnimatedContent(
            targetState = filteredAlerts,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "alertList",
        ) { alerts ->
            if (alerts.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    items(alerts, key = { it.id }) { alert ->
                        AlertCard(
                            alert = alert,
                            onClick = { onAlertClick(alert.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = Color(0xFF4CAF50),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.all_clear),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.all_clear_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlertCard(
    alert: SafetyAlert,
    onClick: () -> Unit,
) {
    val severityColor = severityColor(alert.severity)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Severity stripe
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                    .background(severityColor),
            )

            // Card content
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = violationIcon(alert.violationType),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = severityColor,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = violationDisplayName(alert.violationType),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = timeAgoText(alert.timestamp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Zone badge + severity badge row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Zone chip
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = zoneDisplayName(alert.zoneId),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Severity badge
                    SeverityBadge(severity = alert.severity)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // English message
                Text(
                    text = alert.messageEn,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                // Spanish message
                Text(
                    text = alert.messageEs,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun SeverityBadge(severity: Int) {
    val (label, bgColor, textColor) = when {
        severity >= 4 -> Triple("CRITICAL", CriticalRedBg, CriticalRed)
        severity in 2..3 -> Triple("WARNING", WarningOrangeBg, WarningOrange)
        else -> Triple("INFO", InfoYellowBg, Color(0xFF6D4C00))
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// --- Helpers ---

private fun severityColor(severity: Int): Color = when {
    severity >= 4 -> CriticalRed
    severity in 2..3 -> WarningOrange
    else -> InfoYellow
}

private fun violationIcon(type: String): ImageVector = when (type) {
    "NO_HARD_HAT" -> Icons.Filled.Engineering
    "NO_SAFETY_VEST" -> Icons.Filled.VisibilityOff
    "NO_SAFETY_GLASSES" -> Icons.Filled.RemoveRedEye
    "FALL_HAZARD" -> Icons.Filled.Warning
    "RESTRICTED_ZONE" -> Icons.Filled.Shield
    "IMPROPER_SCAFFOLDING" -> Icons.Filled.Construction
    "HOUSEKEEPING" -> Icons.Filled.ReportProblem
    else -> Icons.Filled.Warning
}

internal fun violationDisplayName(type: String): String = when (type) {
    "NO_HARD_HAT" -> "Missing Hard Hat"
    "NO_SAFETY_VEST" -> "Missing Safety Vest"
    "NO_SAFETY_GLASSES" -> "Missing Safety Glasses"
    "FALL_HAZARD" -> "Fall Hazard"
    "RESTRICTED_ZONE" -> "Restricted Zone Entry"
    "IMPROPER_SCAFFOLDING" -> "Improper Scaffolding"
    "HOUSEKEEPING" -> "Housekeeping Hazard"
    "MULTIPLE_VIOLATIONS" -> "Multiple Violations"
    else -> type.replace("_", " ")
}

internal fun zoneDisplayName(zoneId: String): String {
    return zoneId
        .removePrefix("zone-")
        .split("-")
        .joinToString(" — ") { segment ->
            segment.replaceFirstChar { it.uppercase() }
        }
}

internal fun timeAgoText(timestamp: Long): String {
    val diffMs = System.currentTimeMillis() - timestamp
    val diffMin = (diffMs / 60_000).toInt()
    return when {
        diffMin < 60 -> "${diffMin}m ago"
        diffMin < 1440 -> "${diffMin / 60}h ago"
        else -> "${diffMin / 1440}d ago"
    }
}
