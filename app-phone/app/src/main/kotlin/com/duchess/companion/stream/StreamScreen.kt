@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.duchess.companion.stream

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
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
import androidx.hilt.navigation.compose.hiltViewModel
import com.duchess.companion.MainActivity
import com.duchess.companion.R

// Alex: Detection box colors — industry standard for ML visualization.
private val DetectionGreen = Color(0xFF4CAF50)
private val DetectionRed = Color(0xFFF44336)
private val DetectionYellow = Color(0xFFFFC107)
private val FeedBackground = Color(0xFF1A1A1A)
private val OverlayBackground = Color(0xFF000000)

/**
 * Main streaming screen. When DEMO_MODE is on, renders an impressive simulated PPE
 * detection feed using Canvas primitives. When off, shows real DAT SDK stream states.
 */
@Composable
fun StreamScreen(
    modifier: Modifier = Modifier,
    viewModel: StreamViewModel = hiltViewModel(),
) {
    if (MainActivity.DEMO_MODE) {
        DemoStreamContent(modifier = modifier)
    } else {
        RealStreamContent(modifier = modifier, viewModel = viewModel)
    }
}

// region Demo Mode

@Composable
private fun DemoStreamContent(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "demo_anim")

    val shimmerOffset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer",
    )

    val violationAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "violation_pulse",
    )

    val violationGlow by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "violation_glow",
    )

    var isStreaming by remember { mutableStateOf(true) }

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val demoTitle = stringResource(R.string.stream_demo_title)
    val demoBadge = stringResource(R.string.stream_demo_badge)
    val connectHint = stringResource(R.string.stream_demo_connect_hint)
    val fpsText = stringResource(R.string.stream_fps, 24)
    val inferenceText = stringResource(R.string.stream_inference_time, 18)
    val batteryText = stringResource(R.string.stream_battery, 87)

    Box(modifier = modifier.fillMaxSize().background(OverlayBackground)) {
        // --- Header ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = demoTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = demoBadge,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.Black,
                modifier = Modifier
                    .background(
                        color = DetectionYellow,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            if (isStreaming) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(DetectionGreen),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "LIVE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = DetectionGreen,
                )
            }
        }

        // --- Canvas: Simulated detection feed ---
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp, bottom = 100.dp)
                .background(FeedBackground),
        ) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            // Shimmer gradient overlay
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.03f),
                        Color.Transparent,
                    ),
                    start = Offset(canvasWidth * shimmerOffset, 0f),
                    end = Offset(canvasWidth * (shimmerOffset + 0.5f), canvasHeight),
                ),
            )

            drawSceneHints(canvasWidth, canvasHeight)

            val strokePx = density.run { 3.dp.toPx() }
            val cornerPx = density.run { 6.dp.toPx() }

            // Box 1: HARDHAT ✓
            drawDetectionBox(
                textMeasurer = textMeasurer,
                label = "HARDHAT \u2713 0.94",
                color = DetectionGreen,
                alpha = 1f,
                left = canvasWidth * 0.05f,
                top = canvasHeight * 0.12f,
                boxWidth = canvasWidth * 0.25f,
                boxHeight = canvasHeight * 0.18f,
                strokeWidth = strokePx,
                cornerRadius = cornerPx,
                density = density,
            )

            // Box 2: VEST ✓
            drawDetectionBox(
                textMeasurer = textMeasurer,
                label = "VEST \u2713 0.88",
                color = DetectionGreen,
                alpha = 1f,
                left = canvasWidth * 0.08f,
                top = canvasHeight * 0.32f,
                boxWidth = canvasWidth * 0.22f,
                boxHeight = canvasHeight * 0.30f,
                strokeWidth = strokePx,
                cornerRadius = cornerPx,
                density = density,
            )

            // Box 3: NO GLASSES ✗ — VIOLATION (pulsing)
            drawDetectionBox(
                textMeasurer = textMeasurer,
                label = "NO GLASSES \u2717 0.91",
                color = DetectionRed,
                alpha = violationAlpha,
                left = canvasWidth * 0.52f,
                top = canvasHeight * 0.08f,
                boxWidth = canvasWidth * 0.28f,
                boxHeight = canvasHeight * 0.22f,
                strokeWidth = strokePx + violationGlow,
                cornerRadius = cornerPx,
                density = density,
                isViolation = true,
            )

            // Box 4: PERSON (anchor)
            drawDetectionBox(
                textMeasurer = textMeasurer,
                label = "PERSON 0.76",
                color = DetectionYellow,
                alpha = 0.85f,
                left = canvasWidth * 0.45f,
                top = canvasHeight * 0.05f,
                boxWidth = canvasWidth * 0.42f,
                boxHeight = canvasHeight * 0.75f,
                strokeWidth = strokePx,
                cornerRadius = cornerPx,
                density = density,
            )
        }

        // --- Bottom control bar ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, OverlayBackground.copy(alpha = 0.95f)),
                    ),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatBadge(label = fpsText, color = DetectionGreen)
                StatBadge(label = inferenceText, color = Color.Cyan)
                StatBadge(label = batteryText, color = Color.White)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { isStreaming = !isStreaming },
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = if (isStreaming) DetectionRed.copy(alpha = 0.2f) else DetectionGreen.copy(alpha = 0.2f),
                            shape = CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = if (isStreaming) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = if (isStreaming) {
                            stringResource(R.string.stop_stream)
                        } else {
                            stringResource(R.string.start_stream)
                        },
                        tint = if (isStreaming) DetectionRed else DetectionGreen,
                    )
                }

                // Capture button — white ring shutter
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .border(width = 4.dp, color = Color.White, shape = CircleShape)
                        .padding(6.dp)
                        .background(color = Color.White.copy(alpha = 0.3f), shape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.CameraAlt,
                        contentDescription = stringResource(R.string.capture_photo),
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }

                Spacer(modifier = Modifier.size(48.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = connectHint,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun StatBadge(label: String, color: Color) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = Modifier
            .background(color = color.copy(alpha = 0.1f), shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun DrawScope.drawDetectionBox(
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    label: String,
    color: Color,
    alpha: Float,
    left: Float,
    top: Float,
    boxWidth: Float,
    boxHeight: Float,
    strokeWidth: Float,
    cornerRadius: Float,
    density: Density,
    isViolation: Boolean = false,
) {
    if (isViolation) {
        drawRoundRect(
            color = color.copy(alpha = alpha * 0.15f),
            topLeft = Offset(left - 4f, top - 4f),
            size = Size(boxWidth + 8f, boxHeight + 8f),
            cornerRadius = CornerRadius(cornerRadius + 2f),
        )
    }

    drawRoundRect(
        color = color.copy(alpha = alpha),
        topLeft = Offset(left, top),
        size = Size(boxWidth, boxHeight),
        cornerRadius = CornerRadius(cornerRadius),
        style = Stroke(width = strokeWidth),
    )

    val labelStyle = TextStyle(
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
    )
    val measured = textMeasurer.measure(label, labelStyle)
    val labelPadH = density.run { 6.dp.toPx() }
    val labelPadV = density.run { 3.dp.toPx() }
    val labelBgWidth = measured.size.width + labelPadH * 2
    val labelBgHeight = measured.size.height + labelPadV * 2

    drawRoundRect(
        color = color.copy(alpha = alpha * 0.85f),
        topLeft = Offset(left, top - labelBgHeight),
        size = Size(labelBgWidth, labelBgHeight),
        cornerRadius = CornerRadius(cornerRadius, cornerRadius),
    )

    drawText(
        textLayoutResult = measured,
        topLeft = Offset(left + labelPadH, top - labelBgHeight + labelPadV),
    )
}

private fun DrawScope.drawSceneHints(canvasWidth: Float, canvasHeight: Float) {
    val hintColor = Color.White.copy(alpha = 0.04f)
    drawLine(hintColor, Offset(0f, canvasHeight * 0.4f), Offset(canvasWidth, canvasHeight * 0.38f), 2f)
    drawLine(hintColor, Offset(canvasWidth * 0.35f, canvasHeight * 0.1f), Offset(canvasWidth * 0.35f, canvasHeight * 0.9f), 1.5f)
    drawLine(hintColor, Offset(canvasWidth * 0.75f, canvasHeight * 0.05f), Offset(canvasWidth * 0.78f, canvasHeight * 0.85f), 1.5f)
    drawLine(hintColor, Offset(0f, canvasHeight * 0.82f), Offset(canvasWidth, canvasHeight * 0.80f), 3f)
}

// endregion

// region Real Stream Mode

@Composable
private fun RealStreamContent(
    modifier: Modifier = Modifier,
    viewModel: StreamViewModel,
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val latestFrame by viewModel.latestFrame.collectAsState()
    val photoCaptureResult by viewModel.photoCaptureResult.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val capturedMsg = stringResource(R.string.photo_captured)
    val failedMsg = stringResource(R.string.photo_capture_failed)

    LaunchedEffect(photoCaptureResult) {
        when (photoCaptureResult) {
            is PhotoCaptureResult.Success -> {
                snackbarHostState.showSnackbar(capturedMsg)
                viewModel.clearPhotoCaptureResult()
            }
            is PhotoCaptureResult.Failure -> {
                snackbarHostState.showSnackbar(failedMsg)
                viewModel.clearPhotoCaptureResult()
            }
            null -> { /* Initial state */ }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (val state = sessionState) {
            is StreamUiState.Idle -> IdleContent(onStartStream = { viewModel.startStream() })
            is StreamUiState.Connecting -> ConnectingContent()
            is StreamUiState.Active -> ActiveStreamContent(
                frameBitmap = latestFrame?.toBitmap(),
                onCapturePhoto = { viewModel.capturePhoto() },
                onStopStream = { viewModel.stopStream() },
            )
            is StreamUiState.Error -> ErrorContent(
                message = state.message,
                onRetry = { viewModel.startStream() },
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun IdleContent(onStartStream: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.stream_disconnected),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onStartStream) {
            Text(text = stringResource(R.string.start_stream))
        }
    }
}

@Composable
private fun ConnectingContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.stream_connecting),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ActiveStreamContent(
    frameBitmap: Bitmap?,
    onCapturePhoto: () -> Unit,
    onStopStream: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (frameBitmap != null) {
            Image(
                bitmap = frameBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.stream_active),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(12.dp)
                .clip(CircleShape)
                .background(Color.Green),
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onCapturePhoto) {
                Text(text = stringResource(R.string.capture_photo))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = onStopStream,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(text = stringResource(R.string.stop_stream))
            }
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.stream_error),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(text = stringResource(R.string.retry_button))
        }
    }
}

// endregion
