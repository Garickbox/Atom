package com.mesh.core

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class MeshNode(private val ctx: Context) {

    var myId: String = ""
    var myName: String = ""

    var onHello: ((HelloInfo) -> Unit)? = null
    var onFileOffer: ((FileOffer) -> Unit)? = null
    var onPeerUnreachable: ((String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket? = null
    var isRunning: Boolean = false
        private set

    private var running = false

    private val hereAwaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    data class HelloInfo(val peerId: String, val name: String, val ip: String)
    data class FileOffer(
        val transferId: String,
        val hash: String,
        val name: String,
        val size: Long,
        val peerId: String,
        val peerName: String,
        val fromIp: String
    )

    fun start() {
        if (running) return
        running = true
        isRunning = true
        try {
            udpSocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(LanDiscovery.PORT))
            }
            InAppLogger.d("MeshNode", "UDP bound to ${LanDiscovery.PORT}")
        } catch (e: Exception) {
            InAppLogger.d("MeshNode", "UDP bind error: ${e.message}")
            running = false
            return
        }
        try {
            tcpServer = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(LanDiscovery.PORT))
            }
            InAppLogger.d("MeshNode", "TCP listening on ${LanDiscovery.PORT}")
        } catch (e: Exception) {
            InAppLogger.d("MeshNode", "TCP bind error: ${e.message}")
        }
        scope.launch { udpLoop() }
        scope.launch { tcpLoop() }
    }

    fun stop() {
        running = false
        isRunning = false
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tcpServer?.close() } catch (_: Exception) {}
        scope.cancel()
    }

    private suspend fun udpLoop() {
        val buf = ByteArray(8192)
        while (running) {
            try {
                val sock = udpSocket ?: break
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val packet = MeshPacket.decode(buf, pkt.length) ?: continue
                val fromIp = pkt.address.hostAddress ?: continue
                handleUdp(packet, fromIp)
            } catch (e: Exception) {
                if (running) InAppLogger.d("MeshNode", "udp recv: ${e.message}")
            }
        }
    }

    private suspend fun tcpLoop() {
        while (running) {
            try {
                val server = tcpServer ?: break
                val client = server.accept()
                scope.launch { handleTcpClient(client) }
            } catch (e: Exception) {
                if (running) InAppLogger.d("MeshNode", "tcp accept: ${e.message}")
            }
        }
    }

    private fun handleUdp(packet: MeshPacket, fromIp: String) {
        when (packet.type) {
            PacketType.HELLO -> {
                val hp = HelloPayload.fromJson(packet.payload)
                if (hp.peerId == myId) return
                val info = HelloInfo(hp.peerId, hp.name, fromIp)
                onHello?.invoke(info)
                sendPacket(fromIp, MeshPacket(PacketType.HERE, hp.toJson()))
            }
            PacketType.HERE -> {
                val hp = HelloPayload.fromJson(packet.payload)
                hereAwaiters[fromIp]?.complete(true)
            }
            PacketType.FILE_OFFER -> {
                val offer = FileOffer(
                    transferId = packet.payload.optString("tid"),
                    hash = packet.payload.optString("hash"),
                    name = packet.payload.optString("name"),
                    size = packet.payload.optLong("size", 0L),
                    peerId = packet.payload.optString("peer_id"),
                    peerName = packet.payload.optString("peer_name"),
                    fromIp = fromIp
                )
                onFileOffer?.invoke(offer)
            }
            else -> {}
        }
    }

    private fun handleTcpClient(client: Socket) {
        // Приём файла встроен позже — здесь заглушка для MVP.
        // Реальный приём реализуется в FileSenderService? Нет, отдельным FileReceiver.
        // Для MVP: соединение открывается sender'ом после FILE_ACCEPT,
        //          и receiver знает через FILE_ACCEPT что ждём.
        // Мы регистрируем pending в FileReceiver через callback onIncomingTcp.
        onIncomingTcp?.invoke(client)
        if (onIncomingTcp == null) {
            try { client.close() } catch (_: Exception) {}
        }
    }

    var onIncomingTcp: ((Socket) -> Unit)? = null

    fun sendHello(ip: String) {
        val payload = HelloPayload(myId, myName).toJson()
        sendPacket(ip, MeshPacket(PacketType.HELLO, payload))
    }

    fun broadcastHello() {
        LanDiscovery.sendBroadcastHello(myId, myName)
    }

    suspend fun probePeer(ip: String, timeoutMs: Long = 1500): Boolean {
        val future = CompletableDeferred<Boolean>()
        hereAwaiters[ip] = future
        try {
            return withTimeoutOrNull(timeoutMs) {
                sendHello(ip)
                future.await()
            } ?: false
        } finally {
            hereAwaiters.remove(ip)
        }
    }

    fun sendPacket(ip: String, packet: MeshPacket) {
        try {
            val sock = udpSocket ?: return
            val bytes = packet.encode()
            val addr = InetAddress.getByName(ip)
            sock.send(DatagramPacket(bytes, bytes.size, addr, LanDiscovery.PORT))
        } catch (e: Exception) {
            InAppLogger.d("MeshNode", "send to $ip: ${e.message}")
        }
    }

    fun openTcpTo(ip: String, connectTimeoutMs: Int = 5000): Socket {
        val s = Socket()
        s.connect(InetSocketAddress(ip, LanDiscovery.PORT), connectTimeoutMs)
        return s
    }
}
