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
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class MeshNode(private val ctx: Context) {

    var myId: String = ""
    var myName: String = ""

    var onHello: ((HelloInfo) -> Unit)? = null
    var onFileOffer: ((FileOffer) -> Unit)? = null
    var onFileCancel: ((String) -> Unit)? = null
    var onFileAccept: ((String, Long) -> Unit)? = null
    var onFileDecline: ((String, String) -> Unit)? = null
    var onIncomingTcp: ((Socket) -> Unit)? = null

    var isRunning: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

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

    // ═══════════════════════════════════════════════════════════
    //  START / STOP
    // ═══════════════════════════════════════════════════════════

    fun start() {
        if (isRunning) return
        isRunning = true

        acquireMulticastLock()

        try {
            udpSocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(LanDiscovery.PORT))
            }
            InAppLogger.d("MeshNode", "UDP bound to ${LanDiscovery.PORT}")
        } catch (e: Exception) {
            InAppLogger.d("MeshNode", "UDP bind error: ${e.message}")
            isRunning = false
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
        isRunning = false
        try { udpSocket?.close() } catch (_: Exception) {}
        try { tcpServer?.close() } catch (_: Exception) {}
        releaseMulticastLock()
        scope.cancel()
    }

    // ═══════════════════════════════════════════════════════════
    //  MULTICAST LOCK (обязательно для broadcast)
    // ═══════════════════════════════════════════════════════════

    private fun acquireMulticastLock() {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as? android.net.wifi.WifiManager
            if (wm == null) {
                InAppLogger.d("MeshNode", "no WifiManager — multicast lock skipped")
                return
            }
            val lock = wm.createMulticastLock("atom-multicast")
            lock.setReferenceCounted(true)
            lock.acquire()
            multicastLock = lock
            InAppLogger.d("MeshNode", "MulticastLock acquired")
        } catch (e: Exception) {
            InAppLogger.d("MeshNode", "MulticastLock error: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                InAppLogger.d("MeshNode", "MulticastLock released")
            }
            multicastLock = null
        } catch (_: Exception) {}
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
                pulseWifiLock()
                onHello?.invoke(HelloInfo(hp.peerId, hp.name, fromIp))
                sendPacket(fromIp, MeshPacket(PacketType.HERE, hp.toJson()))
            }
            PacketType.HERE -> {
                val hp = HelloPayload.fromJson(packet.payload)
                InAppLogger.d("MeshNode", "received HERE from ${hp.name} @ $fromIp")
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
            else -> {}
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  DISCOVERY: broadcast + ARP unicast + subnet unicast
    // ═══════════════════════════════════════════════════════════

    fun broadcastHello() {
        scope.launch {
            val localIp = getLocalIpv4()
            InAppLogger.d("MeshNode", "broadcastHello, localIp=$localIp")
            if (localIp == null) {
                InAppLogger.d("MeshNode", "no wlan IPv4 — Wi-Fi off?")
                return@launch
            }

            doBroadcastHello()
            doArpScanAndUnicast()
            doSubnetUnicastScan(localIp)
        }
    }

    private fun doBroadcastHello() {
        try {
            val sock = udpSocket ?: return
            val bytes = MeshPacket(PacketType.HELLO, HelloPayload(myId, myName).toJson()).encode()
            val targets = listOfNotNull(
                "255.255.255.255",
                computeSubnetBroadcast()?.hostAddress
            ).distinct()
            for (addr in targets) {
                try {
                    sock.send(DatagramPacket(bytes, bytes.size,
                        InetAddress.getByName(addr), LanDiscovery.PORT))
                    InAppLogger.d("MeshNode", "broadcast → $addr")
                } catch (e: Exception) {
                    InAppLogger.d("MeshNode", "broadcast $addr err: ${e.message}")
                }
            }
        } catch (e: Exception) {
            InAppLogger.d("MeshNode", "doBroadcastHello error: ${e.message}")
        }
    }

    private fun doArpScanAndUnicast() {
        try {
            val arp = java.io.File("/proc/net/arp")
            if (!arp.exists() || !arp.canRead()) {
                InAppLogger.d("MeshNode", "ARP not readable")
                return
            }
            val localIp = getLocalIpv4()
            val ips = mutableSetOf<String>()
            arp.forEachLine { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 4 && parts[3].matches(Regex("..:..:..:..:..:.."))) {
                    val ip = parts[0]
                    if (ip != "0.0.0.0" && ip != localIp) ips.add(ip)
                }
            }
            InAppLogger.d("MeshNode", "ARP: ${ips.size} devices → unicast HELLO")
            if (ips.isEmpty()) return
            val bytes = MeshPacket(PacketType.HELLO, HelloPayload(myId, myName).toJson()).encode()
            val sock = udpSocket ?: return
            for (ip in ips) {
                try {
                    sock.send(DatagramPacket(bytes, bytes.size,
                        InetAddress.getByName(ip), LanDiscovery.PORT))
                    InAppLogger.d("MeshNode", "arp unicast → $ip")
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            InAppLogger.d("MeshNode", "arp scan error: ${e.message}")
        }
    }

    private fun doSubnetUnicastScan(localIp: String) {
        try {
            val parts = localIp.split(".")
            if (parts.size != 4) return
            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
            val bytes = MeshPacket(PacketType.HELLO, HelloPayload(myId, myName).toJson()).encode()
            val sock = udpSocket ?: return
            InAppLogger.d("MeshNode", "subnet unicast scan: $prefix.1-254")
            for (i in 1..254) {
                val ip = "$prefix.$i"
                if (ip == localIp) continue
                try {
                    sock.send(DatagramPacket(bytes, bytes.size,
                        InetAddress.getByName(ip), LanDiscovery.PORT))
                } catch (_: Exception) {}
            }
            InAppLogger.d("MeshNode", "subnet scan done (253 packets sent)")
        } catch (e: Exception) {
            InAppLogger.d("MeshNode", "subnet scan error: ${e.message}")
        }
    }

    /** Локальный IPv4 wlan* интерфейса (не p2p-wlan). */
    fun getLocalIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan")
                          && !it.name.startsWith("p2p") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull()?.hostAddress
        } catch (_: Exception) { null }
    }

    private fun computeSubnetBroadcast(): InetAddress? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan")
                          && !it.name.startsWith("p2p") }
                .flatMap { it.interfaceAddresses.toList() }
                .firstOrNull { it.address is java.net.Inet4Address && it.broadcast != null }
                ?.broadcast
        } catch (_: Exception) { null }
    }

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
                sock.send(DatagramPacket(bytes, bytes.size, addr, LanDiscovery.PORT))
                InAppLogger.d("MeshNode", "send ${packet.type} to $ip:${LanDiscovery.PORT}")
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
                    sock.send(DatagramPacket(bytes, bytes.size, addr, LanDiscovery.PORT))
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
        // putIfAbsent — не перезаписывать существующий awaiter
        val existing = hereAwaiters.putIfAbsent(ip, future)
        if (existing != null) {
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
            hereAwaiters.remove(ip, future)
        }
    }

    fun openTcpTo(ip: String, connectTimeoutMs: Int = 5000): Socket {
        val s = Socket()
        try {
            s.sendBufferSize = 4 * 1024 * 1024
            s.receiveBufferSize = 4 * 1024 * 1024
            s.tcpNoDelay = false
        } catch (e: Exception) {
            InAppLogger.d("MeshNode", "socket opts error: ${e.message}")
        }
        s.connect(InetSocketAddress(ip, LanDiscovery.PORT), connectTimeoutMs)
        InAppLogger.d("MeshNode",
            "tcp open → $ip, snd=${s.sendBufferSize} rcv=${s.receiveBufferSize} nodelay=${s.tcpNoDelay}")
        return s
    }

    private fun pulseWifiLock() {
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as? android.net.wifi.WifiManager ?: return
            val lock = wm.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "atom-pulse"
            )
            lock.setReferenceCounted(false)
            lock.acquire()
            scope.launch {
                kotlinx.coroutines.delay(30_000)
                try { if (lock.isHeld) lock.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

}
