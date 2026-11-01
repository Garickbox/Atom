package com.garickbox.atom

import android.app.Application
import com.mesh.core.InAppLogger

class AtomApp : Application() {
    override fun onCreate() {
        super.onCreate()
        InAppLogger.init(this)
        com.mesh.core.CompactLog.init(this)
        // B40: при смене versionCode — чистим логи (axioma)
        checkVersionAndCleanupLogs()
        NotificationHelper.createChannels(this)
        AtomController.init(this)
        AtomController.onKnownPeersUpdated = { peers ->
            try { ShortcutPublisher.update(this, peers) } catch (_: Exception) {}
        }
        try { ShortcutPublisher.update(this, AtomController.getKnownP2pPeers()) } catch (_: Exception) {}
        cleanupOldParts()
        // B40: system state в лог на каждом старте (axioma)
        logSystemState()
        // B44: HTTP API для автотестов (только debug build)
        try { TestApi.start(this) } catch (_: Exception) {}
    }

    private fun checkVersionAndCleanupLogs() {
        try {
            val prefs = getSharedPreferences("atom", MODE_PRIVATE)
            val lastVc = prefs.getInt("last_run_version_code", -1)
            val currentVc = BuildConfig.VERSION_CODE
            if (lastVc != -1 && lastVc != currentVc) {
                InAppLogger.clearAll()
                com.mesh.core.CompactLog.clear()
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    try {
                        contentResolver.delete(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            android.provider.MediaStore.Downloads.DISPLAY_NAME + " LIKE ? OR " +
                                android.provider.MediaStore.Downloads.DISPLAY_NAME + " LIKE ? OR " +
                                android.provider.MediaStore.Downloads.DISPLAY_NAME + " LIKE ?",
                            arrayOf("atom.log%", "atom-compact.log%", "atom-err.log%"))
                    } catch (_: Exception) {}
                }
            }
            prefs.edit().putInt("last_run_version_code", currentVc).apply()
        } catch (e: Exception) {
            InAppLogger.d("AtomApp", "cleanup error: ${e.message}")
        }
    }

    private fun logSystemState() {
        try {
            val wm = getSystemService(android.content.Context.WIFI_SERVICE)
                as? android.net.wifi.WifiManager
            val pm = getSystemService(android.content.Context.POWER_SERVICE)
                as? android.os.PowerManager
            val wifiOn = wm?.isWifiEnabled == true
            @Suppress("DEPRECATION")
            val ssid = wm?.connectionInfo?.ssid?.trim('"') ?: "?"
            val whitelist = try {
                pm?.isIgnoringBatteryOptimizations(packageName) == true
            } catch (_: Exception) { false }
            val p2pSupported = try {
                getSystemService(android.content.Context.WIFI_P2P_SERVICE) != null
            } catch (_: Exception) { false }
            val perms = try { PermissionsHelper.missing(this) } catch (_: Exception) { emptyList() }
            val freeMb = try {
                (getExternalFilesDir(null)?.usableSpace ?: 0L) / 1024 / 1024
            } catch (_: Exception) { 0L }

            InAppLogger.d("AtomApp", "═══ SYSTEM STATE ═══")
            InAppLogger.d("AtomApp",
                "build=${BuildConfig.VERSION_CODE} ${BuildConfig.VERSION_NAME}")
            InAppLogger.d("AtomApp",
                "device=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}")
            InAppLogger.d("AtomApp",
                "android=${android.os.Build.VERSION.SDK_INT}/${android.os.Build.VERSION.RELEASE}")
            InAppLogger.d("AtomApp",
                "myId=${AtomController.myId.take(8)} myName=${AtomController.myName}")
            InAppLogger.d("AtomApp", "wifi=$wifiOn ssid=$ssid")
            InAppLogger.d("AtomApp", "battery_whitelist=$whitelist")
            InAppLogger.d("AtomApp", "p2p_supported=$p2pSupported")
            InAppLogger.d("AtomApp",
                "perms_missing=${perms.size} ${perms.map { it.substringAfterLast('.') }}")
            InAppLogger.d("AtomApp", "free_mb=$freeMb")
            InAppLogger.d("AtomApp", "═══ END SYSTEM STATE ═══")
        } catch (e: Exception) {
            InAppLogger.d("AtomApp", "logSystemState error: ${e.message}")
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
