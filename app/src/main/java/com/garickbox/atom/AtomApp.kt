package com.garickbox.atom

import android.app.Application
import com.mesh.core.InAppLogger

class AtomApp : Application() {
    override fun onCreate() {
        super.onCreate()
        InAppLogger.init(this)
        com.mesh.core.CompactLog.init(this)
        NotificationHelper.createChannels(this)
        AtomController.init(this)
        cleanupOldParts()
        InAppLogger.d("AtomApp", "app started, version=${BuildConfig.VERSION_NAME}, myId=${AtomController.myId}")
    }

    private fun cleanupOldParts() {
        try {
            val dir = java.io.File(getExternalFilesDir(null), "received")
            if (!dir.exists()) return
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            var deleted = 0
            dir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".part") && f.lastModified() < cutoff) {
                    if (f.delete()) deleted++
                }
            }
            if (deleted > 0) {
                InAppLogger.d("AtomApp", "cleanup: deleted $deleted stale .part files")
            }
        } catch (e: Exception) {
            InAppLogger.d("AtomApp", "cleanup error: ${e.message}")
        }
    }

}
