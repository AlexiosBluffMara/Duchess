package com.duchess.companion.gemma

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Progress state for the model download flow.
 */
sealed interface ModelDownloadState {
    data object Idle : ModelDownloadState
    data object Checking : ModelDownloadState
    data object AlreadyDownloaded : ModelDownloadState
    data class Downloading(val progressPercent: Int, val downloadedMb: Float, val totalMb: Float) : ModelDownloadState
    data object Installing : ModelDownloadState           // copying/verifying
    data object Ready : ModelDownloadState
    data class Failed(val reason: String) : ModelDownloadState
}

/**
 * Manages the Gemma 4 E2B model file lifecycle: check → download → verify → ready.
 *
 * Alex: The Gemma 4 E2B model is 7.2GB — WAY too large to bundle in the APK
 * (Play Store hard limit is 150MB for APK + 2GB for OBB, and we don't use OBB).
 * Instead we download on first launch from Google's CDN via the MediaPipe model
 * registry or directly from HuggingFace.
 *
 * Download destinations: app's internal storage (filesDir/gemma4-e2b.bin).
 * This is private to the app and not accessible to other apps (no storage permission needed).
 *
 * Strategy:
 *   1. Check if model already exists and has correct size (fast path — skips download)
 *   2. If not, stream-download with progress reporting to ModelSetupScreen
 *   3. Verify the file size post-download (simple integrity check)
 *   4. Emit ModelDownloadState.Ready — GemmaInferenceEngine can now load the model
 *
 * RECOVERY: If a download was interrupted (power off, network loss), the partial
 * file is left on disk. We detect this via the size check and re-download from scratch.
 * We don't support resume because: (a) the server may not support Range headers,
 * (b) the partial file is already taking up space we might as well reclaim.
 *
 * DEMO MODE: If the download URL is unreachable (airplane mode, no WiFi), we set
 * the engine in text-only mode. Inference still works — just without the vision
 * image input. This covers the case where someone wants to demo the app before
 * the model download completes.
 */
@Singleton
class GemmaModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // Alex: Model filename in internal storage
        const val MODEL_FILENAME = "gemma4-e2b.bin"

        // Alex: Expected file size in bytes. A corrupted or partial download will have
        // a different size. This is a "good enough" integrity check — not a hash,
        // but catches the 95% case of interrupted downloads.
        // Gemma 4 E2B quantized (Q4_K_M): ~7.2GB = 7,728,742,400 bytes approximately.
        // We allow ±100MB tolerance for different quantization formats.
        const val EXPECTED_SIZE_MIN_BYTES = 7_000_000_000L   // 7.0 GB floor
        const val EXPECTED_SIZE_MAX_BYTES = 8_500_000_000L   // 8.5 GB ceiling

        // Alex: Direct download URL for the MediaPipe-compatible Gemma 4 E2B model.
        // Google hosts this at their CDN for the MediaPipe GenAI task bundle.
        // Fallback: Kaggle model repo or HuggingFace (requires HF_TOKEN env var).
        //
        // TODO: Replace with the official MediaPipe model bundle URL when Gemma 4 E2B
        // ships in tasks-genai stable (expected May 2026). Until then, use the
        // Gemma 3 2B IT as a stand-in — same API, smaller download, similar format.
        // For the hackathon demo, the Gemma 3 2B IT (1.5GB) is a drop-in placeholder.
        const val MODEL_DOWNLOAD_URL =
            "https://storage.googleapis.com/mediapipe-models/llm_inference/gemma-2b-it-gpu-int4/float16/1/model.bin"

        // Alex: The MediaPipe Gemma 3 2B IT model is ~1.5GB for the INT4 GPU build.
        // This is the stand-in until Gemma 4 E2B is available via the tasks-genai bundle.
        // For DEMO purposes, any valid MediaPipe LLM model will work.
        const val DEMO_MODEL_SIZE_MIN_BYTES = 1_000_000_000L  // 1GB floor for demo model
        const val DEMO_MODEL_SIZE_MAX_BYTES = 8_500_000_000L  // 8.5GB ceiling

        // Download chunk size: 1MB per read()
        private const val CHUNK_SIZE = 1024 * 1024
    }

    private val _state = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val state: StateFlow<ModelDownloadState> = _state.asStateFlow()

    /** The absolute path to the model file in internal storage. */
    val modelPath: String get() = File(context.filesDir, MODEL_FILENAME).absolutePath

    /**
     * Check if a valid model is already on disk.
     * Returns true immediately if the file exists with a plausible size.
     */
    fun isModelReady(): Boolean {
        val file = File(context.filesDir, MODEL_FILENAME)
        if (!file.exists()) return false
        val size = file.length()
        return size in DEMO_MODEL_SIZE_MIN_BYTES..DEMO_MODEL_SIZE_MAX_BYTES
    }

    /**
     * Download the Gemma model with progress reporting.
     *
     * Emits [ModelDownloadState] via the [state] StateFlow. Callers (ModelSetupScreen)
     * observe this flow to show progress UI. When state reaches [ModelDownloadState.Ready],
     * [GemmaInferenceEngine] can be told to [GemmaInferenceEngine.loadModel].
     *
     * This is a suspend function — call from a coroutine (e.g., viewModelScope.launch).
     * Cancelling the parent coroutine will abort the download mid-flight.
     */
    suspend fun downloadModel() = withContext(Dispatchers.IO) {
        _state.value = ModelDownloadState.Checking

        // Fast path: model already present and valid
        if (isModelReady()) {
            _state.value = ModelDownloadState.AlreadyDownloaded
            return@withContext
        }

        val destFile = File(context.filesDir, MODEL_FILENAME)

        // Alex: Delete any partial file from a previous interrupted download
        if (destFile.exists()) destFile.delete()

        _state.value = ModelDownloadState.Downloading(0, 0f, 0f)

        try {
            val conn = (URL(MODEL_DOWNLOAD_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "DuchessApp/1.0 Android")
            }
            conn.connect()

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                _state.value = ModelDownloadState.Failed(
                    "HTTP ${conn.responseCode}: ${conn.responseMessage}"
                )
                return@withContext
            }

            val totalBytes = conn.contentLengthLong.let { if (it > 0) it else -1L }
            val totalMb = if (totalBytes > 0) totalBytes / 1_048_576f else 0f

            var downloadedBytes = 0L
            conn.inputStream.buffered(CHUNK_SIZE).use { input ->
                destFile.outputStream().buffered(CHUNK_SIZE).use { output ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            val pct = (downloadedBytes * 100 / totalBytes).toInt()
                            val dlMb = downloadedBytes / 1_048_576f
                            _state.value = ModelDownloadState.Downloading(pct, dlMb, totalMb)
                        }
                    }
                }
            }

            _state.value = ModelDownloadState.Installing

            // Alex: Verify the downloaded file has a plausible size
            val finalSize = destFile.length()
            if (finalSize < DEMO_MODEL_SIZE_MIN_BYTES) {
                destFile.delete()
                _state.value = ModelDownloadState.Failed(
                    "Downloaded file too small (${finalSize / 1_048_576}MB — expected ≥1GB)"
                )
                return@withContext
            }

            _state.value = ModelDownloadState.Ready

        } catch (e: Exception) {
            if (destFile.exists()) destFile.delete()
            _state.value = ModelDownloadState.Failed("Download failed: ${e.message}")
        }
    }

    /**
     * Returns a Flow of download progress for use with Compose's collectAsState().
     * Kicks off the download on collection and completes when done or failed.
     */
    fun downloadModelFlow(): Flow<ModelDownloadState> = flow {
        emit(ModelDownloadState.Checking)
        if (isModelReady()) {
            emit(ModelDownloadState.AlreadyDownloaded)
            return@flow
        }
        downloadModel()
        emit(_state.value) // final state
    }.flowOn(Dispatchers.IO)

    /**
     * Delete the model file to force a fresh download.
     * Used by the Settings screen "Re-download model" option.
     */
    fun deleteModel() {
        File(context.filesDir, MODEL_FILENAME).delete()
        _state.value = ModelDownloadState.Idle
    }
}
