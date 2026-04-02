package com.meta.wearable.dat.camera

import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.core.DatResult
import com.meta.wearable.dat.core.WearablesError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

interface StreamSession {
    val state: StateFlow<StreamSessionState>
    val videoStream: Flow<VideoFrame>
    fun stop()
    fun capturePhoto(): DatResult<PhotoData, WearablesError>
}

/**
 * Internal stub implementation of StreamSession.
 */
internal class StubStreamSession : StreamSession {
    override val state: StateFlow<StreamSessionState> =
        MutableStateFlow(StreamSessionState.STOPPED)
    override val videoStream: Flow<VideoFrame> = emptyFlow()
    override fun stop() {}
    override fun capturePhoto(): DatResult<PhotoData, WearablesError> =
        DatResult.Failure(WearablesError.DEVICE_NOT_FOUND)
}
