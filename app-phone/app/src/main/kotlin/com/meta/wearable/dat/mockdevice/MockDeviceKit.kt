package com.meta.wearable.dat.mockdevice

import android.content.Context
import com.meta.wearable.dat.mockdevice.api.MockRaybanMeta

class MockDeviceKit private constructor() {
    companion object {
        fun getInstance(context: Context): MockDeviceKit = MockDeviceKit()
    }

    fun pairRaybanMeta(): MockRaybanMeta = MockRaybanMeta()
    fun reset() {}
}
