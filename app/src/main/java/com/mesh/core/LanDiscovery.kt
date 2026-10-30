package com.mesh.core

import android.content.Context
import android.net.wifi.WifiManager

object LanDiscovery {

    const val PORT = 47822

    fun acquireMulticastLock(ctx: Context): WifiManager.MulticastLock? {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wm.createMulticastLock("atom-discovery")
            lock.setReferenceCounted(true)
            lock.acquire()
            lock
        } catch (e: Exception) {
            InAppLogger.d("LanDiscovery", "multicast lock error: ${e.message}")
            null
        }
    }
}
