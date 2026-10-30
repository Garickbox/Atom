package com.mesh.core

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    var onFileCancel: ((String) -> Unit)? = null
    var onFilePause: ((String) -> Unit)? = null
    var onFileAccept: ((String, Long) -> Unit)? = null
    var onFileDecline: ((String, String) -> Unit)? = null
    var onIncomingTcp: ((Socket) -> Unit)? = null

    companion object {
        private const val PORT = 47822
    }

    var isRunning: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket? = null

    private val hereAwaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val helloSendCounter = java.util.concurrent.atomic.AtomicLong(0)

    data class HelloInfo(val peerId: String, val name: String, val ip: String)

    data class FileOffer(
        val transferId: String,
        val hash: String,
        val name: String,
        val size: Long,
        val peerId: String,
        val peerName: String,
        val fromIp: String,
        val resumeOffset: Long = 0L,
        val relativePath: String? = null   // B15
    )

    // ═══════════════════════════════════════════════════════════
    //  START / STOP
    // ═══════════════════════════════════════════════════════════

    fun start() {
        if (isRunning) return
        isRunning = true

        try {
            udpSocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(PORT))
            }
            InAppLogger.d("MeshNode", "UDP bound to ${PORT}")
        } catch (e: Exception) {
            InAppLogger.d("MeshNode", "UDP bind error: ${e.message}")
            isRunning = false
            return
        }

        try {
            tcpServer = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
            InAppLogger.d("MeshNode", "TCP listening on ${PORT}")
        } catch (e: Exception) {
            InAppLogger.d("MeshNode", "TCP bind error: ${e.message}")
        }

        scope.launch { udpLoop() }
        scope.launch { tcpLoop() }
    }

    fun stop() {
        isRunning = false
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tcpServer?.close() } catch (_: Exception) {}
        scope.cancel()
    }

    // ═══════════════════════════════════════════════════════════
    //  UDP / TCP LOOPS
    // ═══════════════════════════════════════════════════════════

    private suspend fun udpLoop() {
        val buf = ByteArray(8192)
        while (isRunning) {
            try {
                val sock = udpSocket ?: break
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)
                val packet = MeshPacket.decode(buf, pkt.length) ?: continue
                val fromIp = pkt.address.hostAddress ?: continue
                handleUdp(packet, fromIp)
            } catch (e: Exception) {
                if (isRunning) InAppLogger.d("MeshNode", "udp recv: ${e.message}")
            }
        }
    }

    private suspend fun tcpLoop() {
        while (isRunning) {
            try {
                val server = tcpServer ?: break
                val client = server.accept()
                val handler = onIncomingTcp
                if (handler != null) {
                    scope.launch { handler(client) }
                } else {
                    try { client.close() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                if (isRunning) InAppLogger.d("MeshNode", "tcp accept: ${e.message}")
            }
        }
    }

    private fun handleUdp(packet: MeshPacket, fromIp: String) {
        when (packet.type) {
            PacketType.HELLO -> {
                val hp = HelloPayload.fromJson(packet.payload)
                if (hp.peerId == myId) return
                InAppLogger.d("MeshNode", "received HELLO from ${hp.name} @ $fromIp")
                onHello?.invoke(HelloInfo(hp.peerId, hp.name, fromIp))
                sendPacket(fromIp, MeshPacket(PacketType.HERE, hp.toJson()))
            }
            PacketType.HERE -> {
                val hp = HelloPayload.fromJson(packet.payload)
                val waiter = hereAwaiters[fromIp]
                if (waiter != null) {
                    InAppLogger.d("MeshNode", "received HERE from ${hp.name} @ $fromIp")
                    waiter.complete(true)
                }
            }
            PacketType.FILE_OFFER -> {
                val offer = FileOffer(
                    transferId = packet.payload.optString("tid"),
                    hash = packet.payload.optString("hash"),
                    name = packet.payload.optString("name"),
                    size = packet.payload.optLong("size", 0L),
                    peerId = packet.payload.optString("peer_id"),
                    peerName = packet.payload.optString("peer_name"),
                    fromIp = fromIp,
                    resumeOffset = packet.payload.optLong("resume_offset", 0L),
                    relativePath = packet.payload.optString("relative_path", "").ifBlank { null }
                )
                onFileOffer?.invoke(offer)
            }
            PacketType.FILE_ACCEPT -> {
                val tid = packet.payload.optString("tid")
                val offset = packet.payload.optLong("offset", 0L)
                if (tid.isNotBlank()) onFileAccept?.invoke(tid, offset)
            }
            PacketType.FILE_DECLINE -> {
                val tid = packet.payload.optString("tid")
                val reason = packet.payload.optString("reason", "declined")
                if (tid.isNotBlank()) onFileDecline?.invoke(tid, reason)
            }
            PacketType.FILE_CANCEL -> {
                val tid = packet.payload.optString("tid")
                if (tid.isNotBlank()) onFileCancel?.invoke(tid)
            }
            PacketType.FILE_PAUSE -> {
                val tid = packet.payload.optString("tid")
                if (tid.isNotBlank()) onFilePause?.invoke(tid)
            }
            else -> {}
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  DISCOVERY: broadcast + ARP unicast + subnet unicast
    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    //  SEND
    // ═══════════════════════════════════════════════════════════

    fun sendHello(ip: String) {
        val payload = HelloPayload(myId, myName).toJson()
        sendPacket(ip, MeshPacket(PacketType.HELLO, payload))
    }

    fun sendPacket(ip: String, packet: MeshPacket) {
        scope.launch {
            try {
                val sock = udpSocket ?: return@launch
                if (sock.isClosed) return@launch
                val bytes = packet.encode()
                val addr = InetAddress.getByName(ip)
                sock.send(DatagramPacket(bytes, bytes.size, addr, PORT))
                if (packet.type == PacketType.HELLO) {
                    val n = helloSendCounter.incrementAndGet()
                    if (n == 1L || n % 60L == 0L) {
                        InAppLogger.d("MeshNode", "send HELLO to $ip (n=$n)")
                    }
                } else {
                    InAppLogger.d("MeshNode", "send ${packet.type} to $ip:$PORT")
                }
            } catch (e: Exception) {
                InAppLogger.d("MeshNode", "send ${packet.type} to $ip error: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** Синхронная отправка — для критичных пакетов (FILE_CANCEL) до stopSelf. */
    fun sendPacketBlocking(ip: String, packet: MeshPacket, timeoutMs: Long = 500L): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        val t = Thread {
            try {
                val sock = udpSocket
                if (sock != null && !sock.isClosed) {
                    val bytes = packet.encode()
                    val addr = InetAddress.getByName(ip)
                    sock.send(DatagramPacket(bytes, bytes.size, addr, PORT))
                    ok = true
                    InAppLogger.d("MeshNode", "sendBlocking ${packet.type} → $ip OK")
                } else {
                    InAppLogger.d("MeshNode", "sendBlocking: socket closed/null")
                }
            } catch (e: Exception) {
                InAppLogger.d("MeshNode", "sendBlocking ${packet.type} error: ${e.message}")
            } finally {
                latch.countDown()
            }
        }
        t.isDaemon = true
        t.start()
        try { latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        return ok
    }

    suspend fun probePeer(ip: String, timeoutMs: Long = 1500): Boolean {
        val future = CompletableDeferred<Boolean>()
        val existing = hereAwaiters.putIfAbsent(ip, future)
        if (existing != null) {
            // Присоединяемся к чужому probe — но НЕ удаляем (владелец удалит)
            return withTimeoutOrNull(timeoutMs) {
                sendHello(ip)
                existing.await()
            } ?: false
        }
        try {
            return withTimeoutOrNull(timeoutMs) {
                sendHello(ip)
                future.await()
            } ?: false
        } finally {
            // ВСЕГДА удаляем свой (или уже удалённый) — ключевой фикс
            hereAwaiters.remove(ip, future)
        }
    }

    fun openTcpTo(ip: String, connectTimeoutMs: Int = 5000): Socket {
        val tcpStart = System.currentTimeMillis()
        InAppLogger.d("MeshNode", "tcp START → $ip (timeout=${connectTimeoutMs}ms)")
        val s = Socket()
        try {
            s.sendBufferSize = 4 * 1024 * 1024
            s.receiveBufferSize = 4 * 1024 * 1024
            s.tcpNoDelay = false
        } catch (e: Exception) {
            InAppLogger.d("MeshNode", "socket opts error: ${e.message}")
        }
        try {
            s.connect(InetSocketAddress(ip, PORT), connectTimeoutMs)
            val ms = System.currentTimeMillis() - tcpStart
            InAppLogger.d("MeshNode",
                "tcp OK → $ip in ${ms}ms, snd=${s.sendBufferSize} rcv=${s.receiveBufferSize}")
            return s
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - tcpStart
            InAppLogger.d("MeshNode",
                "tcp FAIL → $ip in ${ms}ms: ${e.javaClass.simpleName}: ${e.message}")
            try { s.close() } catch (_: Exception) {}
            throw e
        }
    }

}
