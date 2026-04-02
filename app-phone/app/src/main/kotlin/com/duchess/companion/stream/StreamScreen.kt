package com.duchess.companion.stream

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.duchess.companion.R

/**
 * Main streaming screen for the Duchess companion app.
 *
 * Alex: This is the screen workers see 90% of the time. It shows the live video feed
 * from their Meta glasses, with controls to start/stop streaming and capture photos.
 * The screen reacts to StreamUiState changes from the ViewModel — Compose recomposition
 * handles all the UI transitions for us. No manual view invalidation, no RecyclerView
 * adapter.notifyDataSetChanged(). Just reactive state. I love Compose.
 *
 * All user-visible text uses stringResource() for bilingual EN/ES support.
 * The strings.xml and strings-es.xml files must stay in sync.
 */
@Composable
fun StreamScreen(
    modifier: Modifier = Modifier,
    viewModel: StreamViewModel = hiltViewModel()
) {
    val sessionState by viewModel.sessionState.collectAsState()
    val latestFrame by viewModel.latestFrame.collectAsState()
    val photoCaptureResult by viewModel.photoCaptureResult.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Alex: We pre-resolve string resources here, outside of LaunchedEffect,
    // because stringResource() is a @Composable function and can't be called
    // inside a coroutine lambda. This is a common Compose gotcha that causes
    // "Composable invocations can only happen from the context of a Composable"
    // errors. Pre-resolving avoids it cleanly.
    val capturedMsg = stringResource(R.string.photo_captured)
    val failedMsg = stringResource(R.string.photo_capture_failed)

    // Alex: LaunchedEffect keyed on photoCaptureResult — fires every time a
    // photo capture completes (success or failure). Shows a snackbar, then
    // clears the result so we don't show it again on recomposition.
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
            null -> { /* Alex: Initial state, nothing to show */ }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Alex: Exhaustive when on sealed interface. The compiler guarantees
        // we handle every state. If someone adds a new StreamUiState variant,
        // this won't compile until they handle it here. That's the deal.
        when (val state = sessionState) {
            is StreamUiState.Idle -> {
                IdleContent(onStartStream = { viewModel.startStream() })
            }
            is StreamUiState.Connecting -> {
                ConnectingContent()
            }
            is StreamUiState.Active -> {
                ActiveStreamContent(
                    frameBitmap = latestFrame?.bitmap,
                    onCapturePhoto = { viewModel.capturePhoto() },
                    onStopStream = { viewModel.stopStream() }
                )
            }
            is StreamUiState.Error -> {
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

// Alex: Idle state — glasses not streaming. Big obvious button to start.
// Workers are often wearing gloves, so the tap target is intentionally large.
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
            Text(text = stringResource(R.string.start_stream))
        }
    }
}

// Alex: Connecting state — show a spinner while we wait for the DAT SDK
// to negotiate with the glasses over Bluetooth Classic. This typically takes
// 1-3 seconds on a good day, up to 10 seconds if BLE is congested.
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

/**
 * Active streaming layout — video preview fills the screen with controls overlaid.
 *
 * Alex: The frameBitmap is nullable because there's a brief window between the session
 * becoming Active and the first frame arriving. During that time we show a black
 * background with a spinner. Once frames flow, they replace the spinner immediately.
 * We use ContentScale.Crop so the 504x896 frames fill the screen even on weird
 * aspect ratio devices like the Pixel Fold (which Duchess targets).
 */
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
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // Alex: Waiting for first frame — black background with spinner.
            // This should only last a fraction of a second after STREAMING state.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // Alex: Green dot = live indicator. Top-end corner, always visible.
        // Workers glance at this to confirm the stream is active. Simple but essential.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(12.dp)
                .clip(CircleShape)
                .background(Color.Green)
        )

        // Controls overlay at bottom
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onCapturePhoto) {
                Text(text = stringResource(R.string.capture_photo))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Button(
                onClick = onStopStream,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(text = stringResource(R.string.stop_stream))
            }
        }
    }
}

// Alex: Error state with a retry button. Common causes: device out of range,
// BLE disconnected, or Developer Mode not enabled in Meta AI app.
// We show the raw error message for debugging (workers can screenshot it
// and send to their site supervisor).
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
            Text(text = stringResource(R.string.retry_button))
        }
    }
}
