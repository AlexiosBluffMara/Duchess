package com.meta.wearable.dat.camera.types

data class PhotoData(
    val data: ByteArray = byteArrayOf(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PhotoData) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
}
