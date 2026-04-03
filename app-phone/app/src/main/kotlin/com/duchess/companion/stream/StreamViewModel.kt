package com.duchess.companion.stream

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// Alex: Sealed interface for exhaustive when-checks on stream session state.
// Compiler enforces handling of all 4 variants — no silent "else -> {}" gaps.
sealed interface StreamUiState {
    data object Idle : StreamUiState
    data object Connecting : StreamUiState
    data class Active(val session: StreamSession) : StreamUiState
    data class Error(val message: String) : StreamUiState
}

// Alex: Typed PhotoData — NOT Any. Preserves type safety into the UI layer.
sealed interface PhotoCaptureResult {
    data class Success(val data: PhotoData) : PhotoCaptureResult
    data class Failure(val message: String) : PhotoCaptureResult
}

@HiltViewModel
class StreamViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    // Alex: InferencePipelineCoordinator is injected here (not in AlertsViewModel) because
    // frames arrive in StreamViewModel. The coordinator is a @Singleton — same instance
    // is also injected into AlertsViewModel for the alert flow subscription.
    private val coordinator: InferencePipelineCoordinator,
) : ViewModel() {

    private val _sessionState = MutableStateFlow<StreamUiState>(StreamUiState.Idle)
    val sessionState: StateFlow<StreamUiState> = _sessionState.asStateFlow()

    private val _latestFrame = MutableStateFlow<VideoFrame?>(null)
    val latestFrame: StateFlow<VideoFrame?> = _latestFrame.asStateFlow()

    private val _photoCaptureResult = MutableStateFlow<PhotoCaptureResult?>(null)
    val photoCaptureResult: StateFlow<PhotoCaptureResult?> = _photoCaptureResult.asStateFlow()

    // Alex: inferenceEnabled gates whether frames are sent to Gemma.
    // Off by default — the supervisor explicitly enables it from StreamScreen when they
    // want active monitoring. Starting with it off prevents accidental NPU load during
    // the initial BLE pairing and stream-quality check.
    private val _inferenceEnabled = MutableStateFlow(false)
    val inferenceEnabled: StateFlow<Boolean> = _inferenceEnabled.asStateFlow()

    // Alex: Current zone ID used to label live alerts. The supervisor selects this
    // from a zone picker in StreamScreen before starting inference. Defaults to
    // "zone-unknown" so alerts are never silently unlabeled if zone selection is skipped.
    // zoneId is zone-level granularity (NOT exact GPS) per ADR-002 + privacy policy.
    private val _currentZoneId = MutableStateFlow("zone-unknown")
    val currentZoneId: StateFlow<String> = _currentZoneId.asStateFlow()

    /**
     * Enable or disable live inference on incoming frames.
     *
     * Alex: Designed as a toggle so the UI (StreamScreen) can show a clear
     * "AI Monitoring: ON/OFF" indicator and let the supervisor control when
     * active scanning is happening. This matters for:
     *   1. Battery conservation during non-critical periods
     *   2. Worker awareness — they should know when AI is actively watching
     *   3. Targeted monitoring — supervisor enables it when entering a high-risk zone
     */
    fun setInferenceEnabled(enabled: Boolean) {
        _inferenceEnabled.value = enabled
    }

    /**
     * Set the current zone ID for alert labeling.
     *
     * Alex: The supervisor selects a zone from a dropdown (e.g., "zone-A-framing")
     * before enabling inference. All alerts detected while in this zone are labeled
     * with this ID. Zone changes mid-session are fine — the new zoneId takes effect
     * on the next processFrame() call. No alert is retroactively re-labeled.
     *
     * @param zoneId Zone identifier from the site map (e.g., "zone-B-excavation")
     */
    fun setCurrentZone(zoneId: String) {
        _currentZoneId.value = zoneId
    }

    /**
     * Start streaming video from the connected Meta glasses.
     *
     * Alex: MEDIUM quality (504x896) is the sweet spot for BLE bandwidth.
     * HIGH quality looks better per-frame but the Bluetooth Classic pipe chokes
     * and the SDK auto-downgrades anyway. 24fps max before compression artifacts
     * get ugly on BLE bandwidth. Three-param API: context, deviceSelector, config.
     */
    fun startStream() {
        if (_sessionState.value is StreamUiState.Active ||
            _sessionState.value is StreamUiState.Connecting
        ) return

        _sessionState.value = StreamUiState.Connecting

        viewModelScope.launch {
            val config = StreamConfiguration(
                videoQuality = VideoQuality.MEDIUM,
                frameRate = 24
            )

            try {
                val session = Wearables.startStreamSession(
                    context = appContext,
                    deviceSelector = AutoDeviceSelector(),
                    streamConfiguration = config
                )
                _sessionState.value = StreamUiState.Active(session)
                collectFrames(session)
            } catch (e: Throwable) {
                _sessionState.value = StreamUiState.Error(e.message ?: "Failed to start stream")
            }
        }
    }

    /**
     * Stop the active stream and clear the latest frame.
     *
     * Alex: Null out latestFrame — stale frames from a previous session shouldn't
     * linger in the UI after reconnect. Also disables inference so we don't try
     * to route frames to the coordinator when no session is active.
     */
    fun stopStream() {
        val currentState = _sessionState.value
        if (currentState is StreamUiState.Active) {
            currentState.session.close()
            _sessionState.value = StreamUiState.Idle
            _latestFrame.value = null
            _inferenceEnabled.value = false
        }
    }

    /**
     * Capture a still photo from the active stream.
     *
     * Alex: capturePhoto() only works when ACTIVE. The UI disables the capture
     * button when not streaming, but we guard defensively here too.
     * PRIVACY: Captured photo stays on-device unless Gemma flags a violation.
     */
    fun capturePhoto() {
        val currentState = _sessionState.value
        if (currentState !is StreamUiState.Active) return

        viewModelScope.launch {
            val result = currentState.session.capturePhoto()
            result.fold(
                onSuccess = { photoData ->
                    _photoCaptureResult.value = PhotoCaptureResult.Success(photoData)
                },
                onFailure = { error, _ ->
                    _photoCaptureResult.value = PhotoCaptureResult.Failure(error.toString())
                }
            )
        }
    }

    /**
     * Clear the photo capture result after the UI has shown the snackbar.
     * Without this, re-composition re-triggers the snackbar on every state change.
     */
    fun clearPhotoCaptureResult() {
        _photoCaptureResult.value = null
    }

    /**
     * Collect video frames from the active session and optionally route to inference.
     *
     * Alex: For each frame we do two things:
     *   1. Update _latestFrame for the StreamScreen preview (always, regardless of inference)
     *   2. If inferenceEnabled, launch a separate coroutine to call coordinator.processFrame()
     *
     * Why a separate launch() for inference? Because coordinator.processFrame() is a
     * suspend function that can take up to ~2 seconds (model inference time). If we called
     * it directly in the collect block, frame collection would stall — no new frames would
     * update the preview while inference is running. The separate launch() lets collection
     * and inference run concurrently.
     *
     * The coordinator's internal throttle + mutex handles the case where launches accumulate
     * faster than inference completes. The throttle (1 FPS) means at most 1 inference runs
     * at a time; extra launches return immediately at the pre-mutex throttle check.
     *
     * Alex: session.videoStream is the correct property name in DAT SDK 0.5.0.
     * The old name was session.videoFrames — renamed in 0.5.0 (wasted an hour on this).
     */
    private fun collectFrames(session: StreamSession) {
        viewModelScope.launch {
            session.videoStream.collect { frame ->
                _latestFrame.value = frame

                if (_inferenceEnabled.value) {
                    // Alex: launch{} — don't suspend the collect block on inference.
                    // The coordinator's throttle ensures this doesn't pile up.
                    viewModelScope.launch {
                        coordinator.processFrame(frame, _currentZoneId.value)
                    }
                }
            }
        }
    }

    /**
     * Stop stream on ViewModel destruction to avoid leaking the BLE connection.
     *
     * Alex: The DAT SDK session holds a Bluetooth Classic pipe open at ~150mA on
     * the glasses side. Not stopping = angry workers with dead glasses by lunchtime.
     */
    override fun onCleared() {
        super.onCleared()
        stopStream()
    }
}
