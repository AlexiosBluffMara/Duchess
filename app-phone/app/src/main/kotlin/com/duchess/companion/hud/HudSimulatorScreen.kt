@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.duchess.companion.hud

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duchess.companion.R
import kotlin.math.roundToInt

// Glasses display palette
private val HudGreen = Color(0xFF4CAF50)
private val HudRed = Color(0xFFF44336)
private val HudYellow = Color(0xFFFFC107)
private val HudCyan = Color(0xFF00BCD4)
private val HudWhite = Color(0xFFE0E0E0)
private val GlassesBackground = Color(0xFF0A0A0A)
private val GlassesBorder = Color(0xFF333333)

/**
 * Alex: Simulates the Vuzix M400 640x360 HUD display. Uses Canvas drawing to replicate
 * exactly what HudRenderer would show on the glasses. The controls below let you toggle
 * between normal and violation states and adjust parameters.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HudSimulatorScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isViolation by remember { mutableStateOf(false) }
    var simulatedFps by remember { mutableFloatStateOf(10f) }
    var bleConnected by remember { mutableStateOf(true) }
    var batteryPercent by remember { mutableFloatStateOf(75f) }

    val infiniteTransition = rememberInfiniteTransition(label = "hud_anim")
    val violationPulse by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "violation_pulse",
    )

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val allClearEn = stringResource(R.string.hud_all_clear)
    val allClearEs = stringResource(R.string.hud_all_clear_es)
    val ppeAlertEn = stringResource(R.string.hud_ppe_alert)
    val ppeAlertEs = stringResource(R.string.hud_ppe_alert_es)
    val noGlassesLabel = stringResource(R.string.hud_no_glasses_detected)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.hud_sim_title),
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF121212),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
        containerColor = Color(0xFF121212),
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.hud_sim_description),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
            )

            Spacer(modifier = Modifier.height(20.dp))

            // --- Glasses Display Preview ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(640f / 360f)
                    .border(
                        width = 2.dp,
                        color = GlassesBorder,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .background(
                        color = GlassesBackground,
                        shape = RoundedCornerShape(8.dp),
                    ),
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                    drawHudContent(
                        textMeasurer = textMeasurer,
                        density = density,
                        isViolation = isViolation,
                        violationPulse = violationPulse,
                        fps = simulatedFps.roundToInt(),
                        bleConnected = bleConnected,
                        batteryPercent = batteryPercent.roundToInt(),
                        allClearEn = allClearEn,
                        allClearEs = allClearEs,
                        ppeAlertEn = ppeAlertEn,
                        ppeAlertEs = ppeAlertEs,
                        noGlassesLabel = noGlassesLabel,
                    )
                }
            }

            // "640 x 360" label below display
            Text(
                text = "640 \u00D7 360",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(modifier = Modifier.height(28.dp))

            // --- Controls ---
            ControlSection(
                isViolation = isViolation,
                onViolationToggle = { isViolation = it },
                simulatedFps = simulatedFps,
                onFpsChange = { simulatedFps = it },
                bleConnected = bleConnected,
                onBleToggle = { bleConnected = it },
                batteryPercent = batteryPercent,
                onBatteryChange = { batteryPercent = it },
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ControlSection(
    isViolation: Boolean,
    onViolationToggle: (Boolean) -> Unit,
    simulatedFps: Float,
    onFpsChange: (Float) -> Unit,
    bleConnected: Boolean,
    onBleToggle: (Boolean) -> Unit,
    batteryPercent: Float,
    onBatteryChange: (Float) -> Unit,
) {
    val controlBg = Color.White.copy(alpha = 0.06f)
    val controlShape = RoundedCornerShape(12.dp)

    // Mode toggle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(controlBg, controlShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = if (isViolation) {
                    stringResource(R.string.hud_violation_mode)
                } else {
                    stringResource(R.string.hud_normal_mode)
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isViolation) HudRed else HudGreen,
            )
        }
        Switch(
            checked = isViolation,
            onCheckedChange = onViolationToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = HudRed,
                checkedTrackColor = HudRed.copy(alpha = 0.3f),
                uncheckedThumbColor = HudGreen,
                uncheckedTrackColor = HudGreen.copy(alpha = 0.3f),
            ),
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // FPS slider
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(controlBg, controlShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.hud_fps_label),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            Text(
                text = "${simulatedFps.roundToInt()} FPS",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = HudCyan,
            )
        }
        Slider(
            value = simulatedFps,
            onValueChange = onFpsChange,
            valueRange = 2f..30f,
            steps = 27,
            colors = SliderDefaults.colors(
                thumbColor = HudCyan,
                activeTrackColor = HudCyan,
                inactiveTrackColor = HudCyan.copy(alpha = 0.2f),
            ),
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // BLE toggle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(controlBg, controlShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.hud_ble_connected),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
        )
        Switch(
            checked = bleConnected,
            onCheckedChange = onBleToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = HudGreen,
                checkedTrackColor = HudGreen.copy(alpha = 0.3f),
            ),
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Battery slider
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(controlBg, controlShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.hud_battery),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            Text(
                text = "${batteryPercent.roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = when {
                    batteryPercent > 50 -> HudGreen
                    batteryPercent > 20 -> HudYellow
                    else -> HudRed
                },
            )
        }
        Slider(
            value = batteryPercent,
            onValueChange = onBatteryChange,
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = HudGreen,
                activeTrackColor = HudGreen,
                inactiveTrackColor = HudGreen.copy(alpha = 0.2f),
            ),
        )
    }
}

// region Canvas HUD Drawing

private fun DrawScope.drawHudContent(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    density: Density,
    isViolation: Boolean,
    violationPulse: Float,
    fps: Int,
    bleConnected: Boolean,
    batteryPercent: Int,
    allClearEn: String,
    allClearEs: String,
    ppeAlertEn: String,
    ppeAlertEs: String,
    noGlassesLabel: String,
) {
    val w = size.width
    val h = size.height
    val pad = density.run { 8.dp.toPx() }

    // --- Top bar ---
    val topBarHeight = h * 0.14f
    val topBarColor = if (isViolation) HudRed.copy(alpha = violationPulse * 0.85f) else HudGreen.copy(alpha = 0.7f)

    drawRoundRect(
        color = topBarColor,
        topLeft = Offset(pad, pad),
        size = Size(w - pad * 2, topBarHeight),
        cornerRadius = CornerRadius(density.run { 4.dp.toPx() }),
    )

    val topText = if (isViolation) "$ppeAlertEn / $ppeAlertEs" else "$allClearEn / $allClearEs"
    val topStyle = TextStyle(
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
    )
    val topMeasured = textMeasurer.measure(topText, topStyle)
    drawText(
        textLayoutResult = topMeasured,
        topLeft = Offset(
            (w - topMeasured.size.width) / 2f,
            pad + (topBarHeight - topMeasured.size.height) / 2f,
        ),
    )

    // --- Detection bounding boxes ---
    val boxStroke = density.run { 2.dp.toPx() }
    val boxCorner = density.run { 3.dp.toPx() }

    // Green boxes (confirmed PPE)
    drawHudBox(
        textMeasurer = textMeasurer,
        density = density,
        label = "HARDHAT 0.94",
        color = HudGreen,
        alpha = 0.9f,
        left = w * 0.08f,
        top = h * 0.22f,
        boxWidth = w * 0.20f,
        boxHeight = h * 0.18f,
        strokeWidth = boxStroke,
        cornerRadius = boxCorner,
    )

    drawHudBox(
        textMeasurer = textMeasurer,
        density = density,
        label = "VEST 0.88",
        color = HudGreen,
        alpha = 0.9f,
        left = w * 0.06f,
        top = h * 0.42f,
        boxWidth = w * 0.24f,
        boxHeight = h * 0.28f,
        strokeWidth = boxStroke,
        cornerRadius = boxCorner,
    )

    if (isViolation) {
        // Red violation box — pulsing
        drawHudBox(
            textMeasurer = textMeasurer,
            density = density,
            label = noGlassesLabel,
            color = HudRed,
            alpha = violationPulse,
            left = w * 0.55f,
            top = h * 0.20f,
            boxWidth = w * 0.30f,
            boxHeight = h * 0.22f,
            strokeWidth = boxStroke * 1.5f,
            cornerRadius = boxCorner,
        )
    } else {
        drawHudBox(
            textMeasurer = textMeasurer,
            density = density,
            label = "GLASSES 0.89",
            color = HudGreen,
            alpha = 0.9f,
            left = w * 0.55f,
            top = h * 0.20f,
            boxWidth = w * 0.30f,
            boxHeight = h * 0.22f,
            strokeWidth = boxStroke,
            cornerRadius = boxCorner,
        )
    }

    // --- Bottom diagnostic bar ---
    val bottomBarHeight = h * 0.12f
    val bottomBarTop = h - pad - bottomBarHeight

    drawRoundRect(
        color = Color.Black.copy(alpha = 0.7f),
        topLeft = Offset(pad, bottomBarTop),
        size = Size(w - pad * 2, bottomBarHeight),
        cornerRadius = CornerRadius(density.run { 4.dp.toPx() }),
    )

    // Battery bar visualization
    val batteryBlocks = (batteryPercent / 20f).roundToInt().coerceIn(0, 5)
    val batteryBar = "\u25A0".repeat(batteryBlocks) + "\u25A1".repeat(5 - batteryBlocks)
    val batteryColor = when {
        batteryPercent > 50 -> HudGreen
        batteryPercent > 20 -> HudYellow
        else -> HudRed
    }

    val diagText = "$fps FPS | GPU | 18ms | $batteryBar $batteryPercent%"
    val diagStyle = TextStyle(
        color = HudWhite,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
    )
    val diagMeasured = textMeasurer.measure(diagText, diagStyle)
    drawText(
        textLayoutResult = diagMeasured,
        topLeft = Offset(
            (w - diagMeasured.size.width) / 2f,
            bottomBarTop + (bottomBarHeight - diagMeasured.size.height) / 2f,
        ),
    )

    // --- BLE connection indicator (top-right) ---
    val bleDotRadius = density.run { 4.dp.toPx() }
    val bleDotColor = if (bleConnected) HudGreen else HudRed
    drawCircle(
        color = bleDotColor,
        radius = bleDotRadius,
        center = Offset(w - pad - bleDotRadius - density.run { 30.dp.toPx() }, pad + topBarHeight / 2f),
    )

    val bleLabel = if (bleConnected) "BLE" else " - "
    val bleStyle = TextStyle(
        color = bleDotColor,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
    )
    val bleMeasured = textMeasurer.measure(bleLabel, bleStyle)
    drawText(
        textLayoutResult = bleMeasured,
        topLeft = Offset(
            w - pad - bleMeasured.size.width,
            pad + (topBarHeight - bleMeasured.size.height) / 2f,
        ),
    )
}

private fun DrawScope.drawHudBox(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    density: Density,
    label: String,
    color: Color,
    alpha: Float,
    left: Float,
    top: Float,
    boxWidth: Float,
    boxHeight: Float,
    strokeWidth: Float,
    cornerRadius: Float,
) {
    drawRoundRect(
        color = color.copy(alpha = alpha),
        topLeft = Offset(left, top),
        size = Size(boxWidth, boxHeight),
        cornerRadius = CornerRadius(cornerRadius),
        style = Stroke(width = strokeWidth),
    )

    val labelStyle = TextStyle(
        color = Color.White,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
    )
    val measured = textMeasurer.measure(label, labelStyle)
    val padH = density.run { 4.dp.toPx() }
    val padV = density.run { 2.dp.toPx() }
    val bgWidth = measured.size.width + padH * 2
    val bgHeight = measured.size.height + padV * 2

    drawRoundRect(
        color = color.copy(alpha = alpha * 0.8f),
        topLeft = Offset(left, top - bgHeight),
        size = Size(bgWidth, bgHeight),
        cornerRadius = CornerRadius(cornerRadius),
    )

    drawText(
        textLayoutResult = measured,
        topLeft = Offset(left + padH, top - bgHeight + padV),
    )
}

// endregion
