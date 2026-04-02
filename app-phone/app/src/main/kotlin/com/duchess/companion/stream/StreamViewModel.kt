package com.duchess.companion.stream

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
// Alex: The AutoDeviceSelector lives in core.selectors, NOT core.device.
// I wasted an hour on this import once. The DAT SDK docs show the correct path
// but the package explorer in Android Studio auto-suggests the wrong one.
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// Alex: Sealed interface because we need exhaustive when-checks.
// If you add a new state, the compiler FORCES you to handle it everywhere.
// Trust me, I've debugged enough "else -> {}" branches to appreciate this.
// Using a sealed interface (not sealed class) so we get value equality on data objects.
sealed interface StreamUiState {
    data object Idle : StreamUiState
    data object Connecting : StreamUiState
    data class Active(val session: StreamSession) : StreamUiState
    data class Error(val message: String) : StreamUiState
}

// Alex: Typed PhotoData here — NOT Any. The DAT SDK gives us PhotoData with
// a .data ByteArray and metadata. Using Any loses all that type safety
// and means the UI layer has to do ugly casts. Don't do that. Be precise.
sealed interface PhotoCaptureResult {
    data class Success(val data: PhotoData) : PhotoCaptureResult
    data class Failure(val message: String) : PhotoCaptureResult
}

@HiltViewModel
class StreamViewModel @Inject constructor(
    // Alex: We inject Application context via Hilt because startStreamSession()
    // needs a Context parameter. Using @ApplicationContext avoids Activity lifecycle
    // issues — the ViewModel outlives configuration changes (rotation, etc.) and
    // holding an Activity context here would leak it on rotation. Classic mistake.
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _sessionState = MutableStateFlow<StreamUiState>(StreamUiState.Idle)
    val sessionState: StateFlow<StreamUiState> = _sessionState.asStateFlow()

    private val _latestFrame = MutableStateFlow<VideoFrame?>(null)
    val latestFrame: StateFlow<VideoFrame?> = _latestFrame.asStateFlow()

    private val _photoCaptureResult = MutableStateFlow<PhotoCaptureResult?>(null)
    val photoCaptureResult: StateFlow<PhotoCaptureResult?> = _photoCaptureResult.asStateFlow()

    /**
     * Start streaming video from the connected Meta glasses.
     *
     * Alex: The correct API signature is startStreamSession(context, deviceSelector, config).
     * The old code was missing the context param entirely and passing the wrong arg order.
     * Also: MEDIUM quality (504x896) is the sweet spot for BLE bandwidth. HIGH looks better
     * per-frame but the Bluetooth Classic pipe chokes and the SDK auto-downgrades anyway.
     * 24fps is the max before compression artifacts get ugly on BLE bandwidth.
     */
    fun startStream() {
        // Alex: Guard against double-start. If we're already streaming or connecting,
        // don't spin up a second session. The DAT SDK would technically handle it
        // (it returns an error), but we shouldn't waste the round-trip.
        if (_sessionState.value is StreamUiState.Active ||
            _sessionState.value is StreamUiState.Connecting
        ) return

        viewModelScope.launch {
            _sessionState.value = StreamUiState.Connecting

            val config = StreamConfiguration(
                videoQuality = VideoQuality.MEDIUM,  // 504x896 — best quality-to-bandwidth ratio
                frameRate = 24                        // Higher than 24 = too much compression on BLE
            )

            // Alex: The THREE-parameter version is the correct API.
            // context comes first, then deviceSelector, then config.
            // AutoDeviceSelector picks the best available paired device — which is
            // what we want since most workers only have one pair of glasses paired.
            val result = Wearables.startStreamSession(
                context = appContext,
                deviceSelector = AutoDeviceSelector(),
                streamConfiguration = config
            )

            // Alex: DatResult<T, E>.fold() — ALWAYS use fold(), NEVER getOrThrow().
            // The DAT SDK returns typed errors we can act on (device not found,
            // permission denied, etc.). Swallowing them with getOrThrow() means
            // we crash instead of showing the user a helpful recovery screen.
            result.fold(
                onSuccess = { session ->
                    _sessionState.value = StreamUiState.Active(session)
                    collectFrames(session)
                },
                onFailure = { error ->
                    _sessionState.value = StreamUiState.Error(error.toString())
                }
            )
        }
    }

    /**
     * Stop the active streaming session and release resources.
     *
     * Alex: We null out the latestFrame too, because stale frames from a previous
     * session shouldn't linger in the UI. The Compose layer observes this and
     * switches back to the idle state automatically.
     */
    fun stopStream() {
        val currentState = _sessionState.value
        if (currentState is StreamUiState.Active) {
            currentState.session.stop()
            _sessionState.value = StreamUiState.Idle
            _latestFrame.value = null
        }
    }

    /**
     * Capture a still photo from the active stream.
     *
     * Alex: capturePhoto() only works when the session is ACTIVE and STREAMING.
     * If we're idle/connecting/error, this is a no-op. The UI should disable
     * the capture button when not streaming, but defensive coding never hurts.
     *
     * PRIVACY: The captured photo stays on-device. It only leaves through the
     * escalation pipeline if Gemma 4 flags a PPE violation.
     */
    fun capturePhoto() {
        val currentState = _sessionState.value
        if (currentState !is StreamUiState.Active) return

        viewModelScope.launch {
            val result = currentState.session.capturePhoto()

            // Alex: DatResult fold again — notice the typed PhotoData in Success.
            // PhotoData.data gives us the raw image bytes, PhotoData has metadata too.
            // The old code used Any here which is... not great.
            result.fold(
                onSuccess = { photoData ->
                    _photoCaptureResult.value = PhotoCaptureResult.Success(photoData)
                },
                onFailure = { error ->
                    _photoCaptureResult.value = PhotoCaptureResult.Failure(error.toString())
                }
            )
        }
    }

    /**
     * Clear the photo capture result after the UI has shown a snackbar.
     * Without this, re-composition would re-trigger the snackbar every time.
     */
    fun clearPhotoCaptureResult() {
        _photoCaptureResult.value = null
    }

    /**
     * Collect video frames from the active session.
     *
     * Alex: The correct property is session.videoStream — NOT session.videoFrames.
     * The DAT SDK docs clearly show .videoStream as a Flow<VideoFrame>.
     * I've seen .videoFrames in older SDK versions but 0.5.0 renamed it.
     * This is a hot Flow that emits frames as they arrive from the glasses.
     */
    private fun collectFrames(session: StreamSession) {
        viewModelScope.launch {
            session.videoStream.collect { frame ->
                _latestFrame.value = frame
            }
        }
    }

    /**
     * Alex: onCleared() is called when the ViewModel is about to be destroyed
     * (Activity finishing, Fragment detached, etc.). We MUST stop the stream
     * here or we leak the BLE connection and the DAT SDK session.
     * The session keeps the Bluetooth Classic pipe open, which drains battery
     * at ~150mA on the glasses side. Not stopping = angry workers with dead glasses.
     */
    override fun onCleared() {
        super.onCleared()
        stopStream()
    }
}
