package com.garickbox.atom

import android.app.Application
import com.mesh.core.InAppLogger

class AtomApp : Application() {
    override fun onCreate() {
        super.onCreate()
        InAppLogger.init(this)
        NotificationHelper.createChannels(this)
        // Cleanup старой P2P группы (если процесс умер без cleanup)
        try {
            val wm = getSystemService(android.content.Context.WIFI_P2P_SERVICE)
                as? android.net.wifi.p2p.WifiP2pManager
            if (wm != null) {
                val ch = wm.initialize(this, android.os.Looper.getMainLooper()) {}
                ch?.let {
                    wm.removeGroup(it, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            com.mesh.core.InAppLogger.d("AtomApp", "startup removeGroup OK")
                        }
                        override fun onFailure(reason: Int) {
                            com.mesh.core.InAppLogger.d("AtomApp", "startup removeGroup reason=$reason")
                        }
                    })
                    try { it.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            com.mesh.core.InAppLogger.d("AtomApp", "startup cleanup error: ${e.message}")
        }

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
