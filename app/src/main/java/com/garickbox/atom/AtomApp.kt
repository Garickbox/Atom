package com.garickbox.atom

import android.app.Application
import com.mesh.core.InAppLogger

class AtomApp : Application() {
    override fun onCreate() {
        super.onCreate()
        InAppLogger.init(this)
        NotificationHelper.createChannels(this)
        InAppLogger.d("AtomApp", "app started, version=${BuildConfig.VERSION_NAME}")
    }
}
