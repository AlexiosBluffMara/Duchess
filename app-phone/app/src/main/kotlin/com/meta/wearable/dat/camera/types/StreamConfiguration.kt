package com.meta.wearable.dat.camera.types

data class StreamConfiguration(
    val videoQuality: VideoQuality = VideoQuality.MEDIUM,
    val frameRate: Int = 24,
)
