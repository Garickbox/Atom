package com.mesh.core

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

object PowerLocks {

    private const val WAKE_TIMEOUT_MS = 30 * 60 * 1000L  // 30 минут
    private const val WIFI_TIMEOUT_MS = 30 * 60 * 1000L

    @SuppressLint("WakelockTimeout", "Deprecation")
    fun acquireWifi(ctx: Context, tag: String): WifiManager.WifiLock? {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as? WifiManager ?: return null
            val lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, tag)
            lock.setReferenceCounted(false)
            lock.acquire()
            InAppLogger.d("PowerLocks", "wifi lock acquired: $tag")
            lock
        } catch (e: Exception) {
            InAppLogger.d("PowerLocks", "wifi lock error: ${e.message}")
            null
        }
    }

    @SuppressLint("WakelockTimeout")
    fun acquireWake(ctx: Context, tag: String): PowerManager.WakeLock? {
        return try {
            val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE)
                as? PowerManager ?: return null
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
            lock.setReferenceCounted(false)
            lock.acquire(WAKE_TIMEOUT_MS)
            InAppLogger.d("PowerLocks", "wake lock acquired: $tag")
            lock
        } catch (e: Exception) {
            InAppLogger.d("PowerLocks", "wake lock error: ${e.message}")
            null
        }
    }

    fun release(lock: android.os.PowerManager.WakeLock?) {
        try {
            if (lock?.isHeld == true) lock.release()
        } catch (_: Exception) {}
    }

    fun releaseWifi(lock: android.net.wifi.WifiManager.WifiLock?) {
        try {
            if (lock?.isHeld == true) lock.release()
        } catch (_: Exception) {}
    }
}
