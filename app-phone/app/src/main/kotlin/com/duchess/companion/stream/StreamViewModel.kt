package com.duchess.companion.stream

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.device.AutoDeviceSelector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface StreamSessionState {
    data object Idle : StreamSessionState
    data object Connecting : StreamSessionState
    data class Active(val session: StreamSession) : StreamSessionState
    data class Error(val message: String) : StreamSessionState
}

@HiltViewModel
class StreamViewModel @Inject constructor() : ViewModel() {

    private val _sessionState = MutableStateFlow<StreamSessionState>(StreamSessionState.Idle)
    val sessionState: StateFlow<StreamSessionState> = _sessionState.asStateFlow()

    private val _latestFrame = MutableStateFlow<VideoFrame?>(null)
    val latestFrame: StateFlow<VideoFrame?> = _latestFrame.asStateFlow()

    private val _photoCaptureResult = MutableStateFlow<PhotoCaptureResult?>(null)
    val photoCaptureResult: StateFlow<PhotoCaptureResult?> = _photoCaptureResult.asStateFlow()

    fun startStream() {
        if (_sessionState.value is StreamSessionState.Active) return

        viewModelScope.launch {
            _sessionState.value = StreamSessionState.Connecting

            val config = StreamConfiguration(VideoQuality.MEDIUM, 24)
            val result = Wearables.startStreamSession(AutoDeviceSelector(), config)

            result.fold(
                onSuccess = { session ->
                    _sessionState.value = StreamSessionState.Active(session)
                    collectFrames(session)
                },
                onFailure = { error ->
                    _sessionState.value = StreamSessionState.Error(error.toString())
                }
            )
        }
    }

    fun stopStream() {
        val currentState = _sessionState.value
        if (currentState is StreamSessionState.Active) {
            viewModelScope.launch {
                currentState.session.stop()
                _sessionState.value = StreamSessionState.Idle
                _latestFrame.value = null
            }
        }
    }

    fun capturePhoto() {
        val currentState = _sessionState.value
        if (currentState !is StreamSessionState.Active) return

        viewModelScope.launch {
            val result = currentState.session.capturePhoto()
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

    fun clearPhotoCaptureResult() {
        _photoCaptureResult.value = null
    }

    private fun collectFrames(session: StreamSession) {
        viewModelScope.launch {
            session.videoFrames.collect { frame ->
                _latestFrame.value = frame
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopStream()
    }
}

sealed interface PhotoCaptureResult {
    data class Success(val data: Any) : PhotoCaptureResult
    data class Failure(val message: String) : PhotoCaptureResult
}
