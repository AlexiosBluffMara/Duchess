package com.duchess.companion

import android.app.Application
import com.meta.wearable.dat.core.Wearables
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DuchessApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Wearables.initialize(this)
    }
}
