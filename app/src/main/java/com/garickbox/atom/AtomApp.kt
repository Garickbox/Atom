package com.garickbox.atom

import android.app.Application
import com.mesh.core.InAppLogger

class AtomApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // B184c: Coil + VideoFrameDecoder для превью видео
        try {
            val imageLoader = coil.ImageLoader.Builder(this)
                .components { add(coil.decode.VideoFrameDecoder.Factory()) }
                .crossfade(true)
                .build()
            coil.Coil.setImageLoader(imageLoader)
        } catch (e: Exception) { /* coil may fail, not critical */ }
        com.google.android.material.color.DynamicColors.applyToActivitiesIfAvailable(this)
        InAppLogger.init(this)
        com.mesh.core.CompactLog.init(this)
        NotificationHelper.createChannels(this)
        AtomController.init(this)
        // B182: auto-migrate fallback Atom-XXXX → marketname (POCO X5 5G)
        if (AtomController.myName.isBlank() || AtomController.myName.startsWith("Atom-")) {
            val marketName = try {
                val p = Runtime.getRuntime().exec(arrayOf("getprop", "ro.product.marketname"))
                val name = p.inputStream.bufferedReader().readText().trim()
                p.waitFor()
                if (name.isNotBlank()) name else null
            } catch (_: Exception) { null }
            val fallback = if (marketName != null) marketName
                else (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim()
            AtomController.saveMyName(fallback)
            InAppLogger.d("AtomApp", "B182: name -> $fallback (was '${AtomController.myName}')")
        }
        AtomController.onKnownPeersUpdated = { peers ->
            try { ShortcutPublisher.update(this, peers) } catch (_: Exception) {}
        }
        try { ShortcutPublisher.update(this, AtomController.getKnownP2pPeers()) } catch (_: Exception) {}
        cleanupOldParts()
        InAppLogger.d("AtomApp", "app started, version=${BuildConfig.VERSION_NAME}, myId=${AtomController.myId}")
        if (BuildConfig.DEBUG) {
            try { TestApi.start(this) } catch (_: Exception) {}
        } else {
            InAppLogger.d("AtomApp", "B112: TestApi disabled (release build)")
        }
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
