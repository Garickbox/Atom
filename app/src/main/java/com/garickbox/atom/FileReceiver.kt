package com.garickbox.atom

import android.content.Context
import com.mesh.core.InAppLogger
import com.mesh.core.MeshNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object FileReceiver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeSessions = ConcurrentHashMap<String, ReceiveSession>()
    private val _isBusy = AtomicBoolean(false)

    val isBusy: Boolean get() = _isBusy.get()

    data class ReceiveSession(
        val tid: String,
        val hash: String,
        val name: String,
        val totalSize: Long,
        val threadCount: Int,
        val peerName: String,
        val partFile: File,
        val raf: RandomAccessFile,
        val channel: FileChannel,
        val received: AtomicLong = AtomicLong(0),
        val finished: AtomicInteger = AtomicInteger(0),
        val failed: AtomicBoolean = AtomicBoolean(false),
        val sockets: CopyOnWriteArrayList<Socket> = CopyOnWriteArrayList()
    )

    fun onOffer(offer: MeshNode.FileOffer, ctx: Context) {
        InAppLogger.d("FileReceiver",
            "offer from ${offer.peerName}: ${offer.name} (${offer.size} b), busy=${isBusy}")

        if (_isBusy.get()) {
            // Self-heal: если нет активных приёмов — busy застрял, сбрасываем
            val anyReceiving = activeSessions.values.any {
                it.finished.get() < it.threadCount
            }
            if (!anyReceiving) {
                InAppLogger.d("FileReceiver", "busy stuck (no active receive) — self-heal to false")
                _isBusy.set(false)
                // Не return — продолжаем приём
            } else {
                InAppLogger.d("FileReceiver", "decline busy — active session still receiving")
                sendDecline(ctx, offer, "busy")
                return
            }
        }

        val required = offer.size + 5 * 1024 * 1024
        val free = ctx.getExternalFilesDir(null)?.usableSpace ?: 0L
        if (free < required) {
            sendDecline(ctx, offer, "no_space")
            return
        }

        sendAccept(ctx, offer, 0L)
        _isBusy.set(true)
        InAppLogger.d("FileReceiver", "accepted, waiting for TCP threads")

        FileReceiverService.start(ctx, offer.name, offer.size, offer.peerName, offer.transferId)

        val node = AtomController.meshNode ?: return
        node.onIncomingTcp = { socket -> handleIncomingSocket(socket, ctx, node, offer) }
    }

    private fun sendAccept(ctx: Context, offer: MeshNode.FileOffer, offset: Long) {
        try {
            val node = AtomController.meshNode ?: return
            val json = org.json.JSONObject().apply {
                put("tid", offer.transferId)
                put("offset", offset)
            }
            node.sendPacket(offer.fromIp,
                com.mesh.core.MeshPacket(com.mesh.core.PacketType.FILE_ACCEPT, json))
            InAppLogger.d("FileReceiver", "FILE_ACCEPT sent (offset=$offset)")
        } catch (e: Exception) {
            InAppLogger.d("FileReceiver", "sendAccept error: ${e.message}")
        }
    }

    private fun sendDecline(ctx: Context, offer: MeshNode.FileOffer, reason: String) {
        try {
            val node = AtomController.meshNode ?: return
            val json = org.json.JSONObject().apply {
                put("tid", offer.transferId)
                put("reason", reason)
            }
            node.sendPacket(offer.fromIp,
                com.mesh.core.MeshPacket(com.mesh.core.PacketType.FILE_DECLINE, json))
            InAppLogger.d("FileReceiver", "FILE_DECLINE: $reason")
        } catch (e: Exception) {
            InAppLogger.d("FileReceiver", "sendDecline error: ${e.message}")
        }
    }

    private fun handleIncomingSocket(
        socket: Socket,
        ctx: Context,
        node: MeshNode,
        offer: MeshNode.FileOffer
    ) {
        var registered = false
        try {
            socket.receiveBufferSize = 4 * 1024 * 1024
            val dis = DataInputStream(BufferedInputStream(socket.getInputStream(), 512 * 1024))

            // Header
            val tidLen = dis.readInt()
            val tid = String(readN(dis, tidLen), Charsets.UTF_8)

            val hashLen = dis.readInt()
            val senderHash = String(readN(dis, hashLen), Charsets.UTF_8)

            val nameLen = dis.readInt()
            val name = String(readN(dis, nameLen), Charsets.UTF_8)

            val totalSize = dis.readLong()
            val offset = dis.readLong()
            val end = dis.readLong()
            val threadIdx = dis.readInt()
            val threadCount = dis.readInt()

            InAppLogger.d("FileReceiver",
                "TCP thread $threadIdx/$threadCount: range=$offset..$end total=$totalSize tid=${tid.take(8)}")

            val session = activeSessions.getOrPut(tid) {
                val partDir = File(ctx.getExternalFilesDir(null), "received").apply { mkdirs() }
                val partFile = File(partDir, "$tid.part")
                val raf = RandomAccessFile(partFile, "rw")
                raf.setLength(totalSize)
                InAppLogger.d("FileReceiver", "session created: ${partFile.absolutePath}")
                ReceiveSession(
                    tid = tid,
                    hash = senderHash,
                    name = name,
                    totalSize = totalSize,
                    threadCount = threadCount,
                    peerName = offer.peerName,
                    partFile = partFile,
                    raf = raf,
                    channel = raf.channel
                )
            }
            session.sockets.add(socket)
            registered = true

            startWatchdog(session, ctx)

            scope.launch {
                var pos = offset
                try {
                    val buf = ByteArray(512 * 1024)
                    while (pos < end) {
                        if (session.failed.get()) break
                        val toRead = minOf(buf.size.toLong(), end - pos).toInt()
                        val read = dis.read(buf, 0, toRead)
                        if (read < 0) break

                        val bb = ByteBuffer.wrap(buf, 0, read)
                        var written = 0
                        while (written < read) {
                            val w = session.channel.write(bb, pos + written)
                            if (w <= 0) break
                            written += w
                        }
                        pos += written
                        val total = session.received.addAndGet(written.toLong())
                        FileReceiverService.updateProgress(ctx, name, total, totalSize)
                    }
                    InAppLogger.d("FileReceiver",
                        "thread $threadIdx done: ${pos - offset} bytes")
                } catch (e: Exception) {
                    InAppLogger.d("FileReceiver",
                        "thread $threadIdx error: ${e.javaClass.simpleName}: ${e.message}")
                    session.failed.set(true)
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                    session.sockets.remove(socket)
                    val done = session.finished.incrementAndGet()
                    InAppLogger.d("FileReceiver",
                        "thread $threadIdx finished, $done/${session.threadCount}")
                    if (done >= session.threadCount) {
                        InAppLogger.d("FileReceiver", "all threads done, releasing busy")
                        _isBusy.set(false)
                        scope.launch { finalizeSession(session, ctx) }
                    }
                }
            }
        } catch (e: Exception) {
            InAppLogger.d("FileReceiver", "header read error: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
            if (!registered) _isBusy.set(false)
        }
    }

    private fun readN(dis: DataInputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        dis.readFully(b)
        return b
    }

    private fun startWatchdog(session: ReceiveSession, ctx: Context) {
        scope.launch {
            delay(300_000)  // 5 мин
            if (session.finished.get() < session.threadCount) {
                InAppLogger.d("FileReceiver", "watchdog: ${session.tid.take(8)} timeout")
                session.failed.set(true)
                session.sockets.forEach { try { it.close() } catch (_: Exception) {} }
                try { session.channel.close() } catch (_: Exception) {}
                try { session.raf.close() } catch (_: Exception) {}
                session.partFile.delete()
                activeSessions.remove(session.tid)
                // busy уже освобождён в handleIncomingSocket
                FileReceiverService.stop(ctx)
            }
        }
    }

    private fun finalizeSession(session: ReceiveSession, ctx: Context) {
        InAppLogger.d("FileReceiver", "finalize START tid=${session.tid.take(8)}")
        try {
            InAppLogger.d("FileReceiver",
                "finalize: ${session.received.get()}/${session.totalSize} received, failed=${session.failed.get()}")

            try { session.channel.force(true) } catch (_: Exception) {}
            try { session.channel.close() } catch (_: Exception) {}
            try { session.raf.close() } catch (_: Exception) {}

            if (session.failed.get()) {
                session.partFile.delete()
                activeSessions.remove(session.tid)
                // busy уже освобождён в handleIncomingSocket
                AtomController.transferState.value = TransferState.Failed(
                    session.tid, session.peerName, session.name, "Ошибка потока", "", ""
                )
                FileReceiverService.stop(ctx)
                return
            }

            if (session.partFile.length() != session.totalSize) {
                InAppLogger.d("FileReceiver",
                    "size mismatch: ${session.partFile.length()}/${session.totalSize}")
                session.partFile.delete()
                activeSessions.remove(session.tid)
                // busy уже освобождён в handleIncomingSocket
                FileReceiverService.stop(ctx)
                return
            }

            // SHA-256
            val actualHash = computeSha256(session.partFile)
            val hashOk = actualHash.equals(session.hash, ignoreCase = true)
            InAppLogger.d("FileReceiver",
                "SHA-256: ${if (hashOk) "OK" else "MISMATCH"} actual=${actualHash.take(16)}… expected=${session.hash.take(16)}…")

            // MediaStore
            val uniqueName = resolveUniqueName(ctx, session.name)
            val result = MediaStoreSaver.save(ctx, session.partFile, uniqueName, null)

            session.partFile.delete()
            activeSessions.remove(session.tid)
            // busy уже освобождён в handleIncomingSocket

            if (result.uri != null) {
                InAppLogger.d("FileReceiver", "saved to ${result.displayPath}")
                TransferHistory.add(ctx, TransferHistory.Record(
                    id = session.tid,
                    peerName = session.peerName,
                    fileName = uniqueName,
                    bytes = session.totalSize,
                    durationMs = 0L,
                    status = if (hashOk) "success" else "corrupted",
                    timestamp = System.currentTimeMillis(),
                    savedPath = result.displayPath
                ))
                AtomController.transferState.value = TransferState.Success(
                    session.peerName, 1, session.totalSize, 0
                )
            } else {
                InAppLogger.d("FileReceiver", "MediaStore save failed")
            }
            FileReceiverService.stop(ctx)
        } catch (e: Exception) {
            InAppLogger.d("FileReceiver", "finalize error: ${e.message}")
            activeSessions.remove(session.tid)
            // busy уже освобождён в handleIncomingSocket
            FileReceiverService.stop(ctx)
        }
    }

    private fun computeSha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resolveUniqueName(ctx: Context, name: String): String {
        val (base, ext) = splitExt(name)
        val dir = File(ctx.getExternalFilesDir(null), "received")
        var candidate = name
        var n = 1
        while (File(dir, candidate).exists()) {
            candidate = "$base ($n)$ext"
            n++
        }
        return candidate
    }

    private fun splitExt(name: String): Pair<String, String> {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name to ""
        else name.substring(0, dot) to name.substring(dot)
    }

    fun cancelActiveReceive(ctx: Context) {
        InAppLogger.d("FileReceiver", "cancelActiveReceive")
        activeSessions.values.forEach { s ->
            s.failed.set(true)
            s.sockets.forEach { try { it.close() } catch (_: Exception) {} }
            try { s.channel.close() } catch (_: Exception) {}
            try { s.raf.close() } catch (_: Exception) {}
            s.partFile.delete()
        }
        activeSessions.clear()
            // busy уже освобождён в handleIncomingSocket
        AtomController.transferState.value = TransferState.Idle
        FileReceiverService.stop(ctx)
    }

    fun onCancel(ctx: Context, transferId: String) {
        InAppLogger.d("FileReceiver", "FILE_CANCEL tid=${transferId.take(8)}")
        val session = activeSessions.remove(transferId)
        if (session != null) {
            session.failed.set(true)
            session.sockets.forEach { try { it.close() } catch (_: Exception) {} }
            try { session.channel.close() } catch (_: Exception) {}
            try { session.raf.close() } catch (_: Exception) {}
            session.partFile.delete()
        }
            // busy уже освобождён в handleIncomingSocket
        AtomController.transferState.value = TransferState.Idle
        FileReceiverService.stop(ctx)
    }
}
