package com.duchess.companion.stream

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.duchess.companion.R

@Composable
fun StreamScreen(
    modifier: Modifier = Modifier,
    viewModel: StreamViewModel = hiltViewModel()
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
            null -> { /* no-op */ }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (val state = sessionState) {
            is StreamSessionState.Idle -> {
                IdleContent(onStartStream = { viewModel.startStream() })
            }
            is StreamSessionState.Connecting -> {
                ConnectingContent()
            }
            is StreamSessionState.Active -> {
                ActiveStreamContent(
                    frameBitmap = latestFrame?.bitmap,
                    onCapturePhoto = { viewModel.capturePhoto() },
                    onStopStream = { viewModel.stopStream() }
                )
            }
            is StreamSessionState.Error -> {
                ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.startStream() }
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun IdleContent(onStartStream: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.stream_disconnected),
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onStartStream) {
            Text(text = stringResource(R.string.stream_active))
        }
    }
}

@Composable
private fun ConnectingContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.stream_connecting),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun ActiveStreamContent(
    frameBitmap: Bitmap?,
    onCapturePhoto: () -> Unit,
    onStopStream: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Video frame display
        if (frameBitmap != null) {
            Image(
                bitmap = frameBitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.stream_active),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // Session state indicator
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(12.dp)
                .clip(CircleShape)
                .background(Color.Green)
        )

        // Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(onClick = onCapturePhoto) {
                Text(text = stringResource(R.string.capture_photo))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onStopStream) {
                Text(text = stringResource(R.string.stream_disconnected))
            }
        }
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.stream_error),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Text(text = stringResource(R.string.register_button))
        }
    }
}
