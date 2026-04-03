package com.duchess.companion.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.duchess.companion.R
import com.duchess.companion.demo.ConnectionStatus
import com.duchess.companion.demo.ZoneStatus
import com.duchess.companion.mesh.MeshManager

private val SafetyGreen = Color(0xFF4CAF50)
private val SafetyYellow = Color(0xFFFFD600)
private val SafetyRed = Color(0xFFE53935)
private val CardShape = RoundedCornerShape(16.dp)

private fun scoreColor(score: Int): Color = when {
    score > 80 -> SafetyGreen
    score > 50 -> SafetyYellow
    else -> SafetyRed
}

@Composable
fun DashboardScreen(
    modifier: Modifier = Modifier,
    onNavigateToHudSim: () -> Unit = {},
    onZoneClick: (String) -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val safetyScore by viewModel.safetyScore.collectAsState()
    val zones by viewModel.zones.collectAsState()
    val activeWorkers by viewModel.activeWorkers.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val recentAlerts by viewModel.recentAlerts.collectAsState()
    val meshState by viewModel.meshState.collectAsState()

    val activeAlertCount = recentAlerts.count { it.severity >= 3 }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // --- Mesh connectivity status ---
        MeshStatusRow(meshState = meshState)
        Spacer(modifier = Modifier.height(8.dp))

        // --- Safety Score ---
        SafetyScoreSection(score = safetyScore)

        Spacer(modifier = Modifier.height(24.dp))

        // --- Zone Cards ---
        SectionHeader(text = stringResource(R.string.zones))
        Spacer(modifier = Modifier.height(12.dp))
        zones.forEach { zone ->
            ZoneCard(zone = zone, onClick = { onZoneClick(zone.zoneId) })
            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- Quick Stats ---
        QuickStatsRow(
            workerCount = activeWorkers,
            alertCount = activeAlertCount,
            zoneCount = zones.size,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- Connection Status ---
        ConnectionChip(status = connectionStatus)

        Spacer(modifier = Modifier.height(16.dp))

        // --- HUD Simulator card ---
        HudSimCard(onClick = onNavigateToHudSim)

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// region Safety Score

@Composable
private fun SafetyScoreSection(score: Int) {
    val animatedProgress by animateFloatAsState(
        targetValue = score / 100f,
        animationSpec = tween(durationMillis = 1200),
        label = "score",
    )
    val color = scoreColor(score)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp)) {
            Canvas(modifier = Modifier.size(180.dp)) {
                val strokeWidth = 14.dp.toPx()
                val arcSize = size.minDimension - strokeWidth
                val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

                // Track
                drawArc(
                    color = color.copy(alpha = 0.15f),
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(arcSize, arcSize),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
                // Progress
                drawArc(
                    color = color,
                    startAngle = 135f,
                    sweepAngle = 270f * animatedProgress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(arcSize, arcSize),
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "$score",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
                Text(
                    text = "/100",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.safety_score),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// endregion

// region Zone Card

@Composable
private fun ZoneCard(zone: ZoneStatus, onClick: () -> Unit = {}) {
    val zoneColor = scoreColor(zone.safetyScore)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = CardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = zone.zoneName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = zone.zoneNameEs,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${zone.safetyScore}",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = zoneColor,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { zone.safetyScore / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = zoneColor,
                trackColor = zoneColor.copy(alpha = 0.15f),
                strokeCap = StrokeCap.Round,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                IconStat(
                    icon = Icons.Filled.Person,
                    value = "${zone.workerCount}",
                    label = stringResource(R.string.workers_label),
                )
                if (zone.activeAlerts > 0) {
                    IconStat(
                        icon = Icons.Filled.Warning,
                        value = "${zone.activeAlerts}",
                        label = stringResource(R.string.active_alerts),
                        tint = SafetyRed,
                    )
                }
            }
        }
    }
}

@Composable
private fun IconStat(
    icon: ImageVector,
    value: String,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// endregion

// region Quick Stats

@Composable
private fun QuickStatsRow(workerCount: Int, alertCount: Int, zoneCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        StatPill(icon = Icons.Filled.Groups, value = "$workerCount", label = stringResource(R.string.active_workers))
        StatPill(icon = Icons.Filled.Warning, value = "$alertCount", label = stringResource(R.string.active_alerts), valueColor = if (alertCount > 0) SafetyRed else SafetyGreen)
        StatPill(icon = Icons.Filled.Map, value = "$zoneCount", label = stringResource(R.string.zones))
    }
}

@Composable
private fun StatPill(
    icon: ImageVector,
    value: String,
    label: String,
    valueColor: Color = MaterialTheme.colorScheme.primary,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = valueColor,
            modifier = Modifier.size(28.dp),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// endregion

// region Connection Chip

@Composable
private fun ConnectionChip(status: ConnectionStatus) {
    val (label, chipColor) = when (status) {
        ConnectionStatus.CONNECTED -> stringResource(R.string.connection_connected) to SafetyGreen
        ConnectionStatus.DISCONNECTED -> stringResource(R.string.connection_disconnected) to SafetyRed
        ConnectionStatus.DEMO_MODE -> stringResource(R.string.connection_demo) to SafetyYellow
    }

    AssistChip(
        onClick = { },
        label = { Text(label, fontWeight = FontWeight.Medium) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = chipColor.copy(alpha = 0.15f),
            labelColor = chipColor,
        ),
        border = AssistChipDefaults.assistChipBorder(
            enabled = true,
            borderColor = chipColor.copy(alpha = 0.4f),
        ),
    )
}

// endregion

// region HUD Simulator Card

@Composable
private fun HudSimCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = CardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Visibility,
                contentDescription = null,
                tint = SafetyYellow,
                modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.view_hud_sim),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.hud_sim_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "\u203A",
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// endregion

// region Mesh Status Row

/** One-line mesh connectivity indicator: colour-coded dot + label text. */
@Composable
private fun MeshStatusRow(meshState: MeshManager.MeshState) {
    val (dotColor, labelRes) = when (meshState) {
        MeshManager.MeshState.Connected    -> SafetyGreen  to R.string.mesh_status_connected
        MeshManager.MeshState.Connecting   -> SafetyYellow to R.string.mesh_status_connecting
        MeshManager.MeshState.Disconnected -> SafetyRed    to R.string.mesh_status_offline
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(10.dp)) {
            drawCircle(color = dotColor)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.mesh_status_label, stringResource(labelRes)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// endregion

// region Section Header

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth(),
    )
}

// endregion
