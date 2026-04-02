package com.duchess.companion.gemma

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

// --- ViewModel ---

@HiltViewModel
class ModelSetupViewModel @Inject constructor(
    val modelManager: GemmaModelManager
) : ViewModel() {

    val downloadState = modelManager.state

    fun startDownload() {
        viewModelScope.launch {
            modelManager.downloadModel()
        }
    }

    fun skipToDemo() {
        // Alex: The user can skip model download and run in demo mode.
        // GemmaInferenceEngine will gracefully handle a missing model file by
        // returning "no violation" results. The rest of the UI works normally.
    }
}

// --- Screen ---

/**
 * First-launch screen shown when the Gemma 4 E2B model hasn't been downloaded yet.
 *
 * Alex: We can't bundle the 7.2GB model in the APK (Play Store limit is 150MB).
 * This screen handles the "download on first run" UX. Key design decisions:
 *
 *   1. ALWAYS offer a "Demo Mode" skip option. Workers who just got handed the phone
 *      shouldn't be blocked by a multi-hour download. Demo mode shows all the UI
 *      with synthetic alerts — useful for training and presentations.
 *
 *   2. The download progress shows both % and MB/GB numbers so users know it's
 *      not frozen. "Downloading 1,240 / 7,200 MB" is more reassuring than "17%".
 *
 *   3. WiFi recommendation: the model is ~7GB. Downloading on cellular is expensive
 *      and will likely be interrupted. We show a prominent WiFi reminder.
 *
 *   4. On completion, we call onModelReady() and the caller navigates to Dashboard.
 *      We don't auto-navigate — the user should confirm they saw the "Ready" state.
 */
@Composable
fun ModelSetupScreen(
    onModelReady: () -> Unit,
    onSkipToDemo: () -> Unit,
    viewModel: ModelSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.downloadState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Icon
        Icon(
            imageVector = when (state) {
                is ModelDownloadState.Ready, is ModelDownloadState.AlreadyDownloaded ->
                    Icons.Default.CheckCircle
                is ModelDownloadState.Failed -> Icons.Default.Error
                else -> Icons.Default.CloudDownload
            },
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = when (state) {
                is ModelDownloadState.Ready, is ModelDownloadState.AlreadyDownloaded ->
                    MaterialTheme.colorScheme.primary
                is ModelDownloadState.Failed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.secondary
            }
        )

        Spacer(Modifier.height(24.dp))

        // Title
        Text(
            text = when (state) {
                ModelDownloadState.Idle, ModelDownloadState.Checking ->
                    "AI Safety Model"
                is ModelDownloadState.Downloading ->
                    "Downloading AI Model"
                ModelDownloadState.Installing ->
                    "Installing…"
                ModelDownloadState.Ready, ModelDownloadState.AlreadyDownloaded ->
                    "AI Model Ready"
                is ModelDownloadState.Failed ->
                    "Download Failed"
            },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(12.dp))

        // Subtitle / description
        Text(
            text = when (val s = state) {
                ModelDownloadState.Idle ->
                    "Duchess uses Gemma 4 E2B for on-device safety analysis.\n\n" +
                    "The AI model (~7 GB) needs to be downloaded once.\n" +
                    "Recommended: connect to WiFi before starting."
                ModelDownloadState.Checking ->
                    "Checking for existing model…"
                is ModelDownloadState.Downloading -> {
                    val dlMb = s.downloadedMb.toInt()
                    val totalMb = if (s.totalMb > 0) s.totalMb.toInt().toString() else "?"
                    "Downloading: $dlMb / $totalMb MB  •  ${s.progressPercent}%\n\nKeep the app open and stay on WiFi."
                }
                ModelDownloadState.Installing ->
                    "Verifying model integrity…"
                ModelDownloadState.AlreadyDownloaded ->
                    "Gemma 4 E2B is ready for on-device safety analysis."
                ModelDownloadState.Ready ->
                    "Download complete. On-device AI is active.\nAll inference is private — frames never leave your device."
                is ModelDownloadState.Failed ->
                    "Could not download the model.\n\n${s.reason}\n\n" +
                    "You can run in Demo Mode without the AI model."
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))

        // Progress indicator
        when (val s = state) {
            ModelDownloadState.Checking, ModelDownloadState.Installing -> {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
            }
            is ModelDownloadState.Downloading -> {
                LinearProgressIndicator(
                    progress = { s.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            else -> {}
        }

        Spacer(Modifier.height(32.dp))

        // Primary action button
        when (state) {
            ModelDownloadState.Idle, is ModelDownloadState.Failed -> {
                Button(
                    onClick = { viewModel.startDownload() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Download AI Model (~7 GB)")
                }
            }
            ModelDownloadState.Ready, ModelDownloadState.AlreadyDownloaded -> {
                Button(
                    onClick = onModelReady,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text("Start Monitoring")
                }
            }
            else -> {
                // Downloading / Installing — no primary button, just progress
            }
        }

        Spacer(Modifier.height(12.dp))

        // Skip / Demo Mode option — always available except when Ready
        if (state !is ModelDownloadState.Ready && state !is ModelDownloadState.AlreadyDownloaded) {
            OutlinedButton(
                onClick = onSkipToDemo,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Skip — Use Demo Mode")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Demo mode shows all features with simulated safety data.\nAI inference is disabled until the model downloads.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
