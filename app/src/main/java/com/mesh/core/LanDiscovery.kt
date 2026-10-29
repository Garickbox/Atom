package com.mesh.core

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object LanDiscovery {

    const val PORT = 47822
    private const val BROADCAST_ADDR = "255.255.255.255"

    fun sendBroadcastHello(peerId: String, name: String): Boolean {
        return try {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                val packet = MeshPacket(
                    PacketType.HELLO,
                    HelloPayload(peerId, name).toJson()
                ).encode()
                val addr = InetAddress.getByName(BROADCAST_ADDR)
                sock.send(DatagramPacket(packet, packet.size, addr, PORT))
            }
            true
        } catch (e: Exception) {
            InAppLogger.d("LanDiscovery", "broadcast error: ${e.message}")
            false
        }
    }

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
