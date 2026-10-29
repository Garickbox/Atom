package com.garickbox.atom

import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.IBinder
import android.provider.OpenableColumns
import com.mesh.core.InAppLogger
import com.mesh.core.MeshNode
import com.mesh.core.PowerLocks
import com.mesh.core.PacketType
import com.mesh.core.MeshPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileNotFoundException
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FileSenderService : Service() {

    companion object {
        const val ACTION_START = "com.garickbox.atom.START_TRANSFER"
        const val EXTRA_PEER_IP = "peer_ip"
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_PEER_NAME = "peer_name"
        const val EXTRA_URIS = "uris"

        private const val NOTIF_ID = 1001
        private const val CHUNK = 512 * 1024

        fun startTransfer(ctx: Context, peer: Peer, uris: List<Uri>) {
            val intent = Intent(ctx, FileSenderService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PEER_IP, peer.ip)
                putExtra(EXTRA_PEER_ID, peer.id)
                putExtra(EXTRA_PEER_NAME, peer.name)
                putStringArrayListExtra(EXTRA_URIS, ArrayList(uris.map { it.toString() }))
            }
            androidx.core.content.ContextCompat.startForegroundService(ctx, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val senderSpeedTracker = SpeedTracker()
    private val pendingAccepts = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Result<Long>>>()
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var currentJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, TransferNotification.buildRunning(
            this, "prep", "…", "Подготовка", 0, 0
        ))
        AtomController.setTransferActive(true)
        wifiLock = PowerLocks.acquireWifi(this, "atom-send-wifi")
        wakeLock = PowerLocks.acquireWake(this, "atom-send-wake")

        // Подключаем приём ответов на FILE_OFFER
        AtomController.meshNode?.onFileAccept = { tid, offset ->
            InAppLogger.d("FileSenderService", "got FILE_ACCEPT tid=$tid offset=$offset")
            pendingAccepts[tid]?.complete(Result.success(offset))
        }
        AtomController.meshNode?.onFileDecline = { tid, reason ->
            InAppLogger.d("FileSenderService", "got FILE_DECLINE tid=$tid reason=$reason")
            pendingAccepts[tid]?.complete(Result.failure(DeclineException(reason)))
        }

        InAppLogger.d("FileSenderService", "onCreate, locks acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val peerIp = intent.getStringExtra(EXTRA_PEER_IP) ?: return START_NOT_STICKY
                val peerId = intent.getStringExtra(EXTRA_PEER_ID) ?: ""
                val peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "?"
                val uris = intent.getStringArrayListExtra(EXTRA_URIS)?.map { Uri.parse(it) } ?: emptyList()
                if (uris.isEmpty()) { stopSelf(); return START_NOT_STICKY }
                runTransfer(peerIp, peerId, peerName, uris)
            }
            TransferNotification.ACTION_CANCEL -> {
                InAppLogger.d("FileSenderService", "cancel requested")
                val curState = AtomController.transferState.value
                if (curState is TransferState.Running) {
                    // ВАЖНО: синхронный sendPacketBlocking — иначе scope.cancel убьёт пакет
                    try {
                        val ip = AtomController.lastPeer?.ip
                        val node = AtomController.meshNode
                        if (ip != null && node != null) {
                            val cancelJson = JSONObject().apply {
                                put("tid", curState.transferId)
                            }
                            val ok = node.sendPacketBlocking(
                                ip, MeshPacket(PacketType.FILE_CANCEL, cancelJson), 800L
                            )
                            InAppLogger.d("FileSenderService",
                                "FILE_CANCEL ${if (ok) "SENT" else "FAILED"} to $ip")
                        } else {
                            InAppLogger.d("FileSenderService",
                                "cancel: no ip/node (${ip ?: "null"}, ${node ?: "null"})")
                        }
                    } catch (e: Exception) {
                        InAppLogger.d("FileSenderService", "cancel packet error: ${e.message}")
                    }

                    AtomController.transferState.value = TransferState.Idle
                }

                // Отменяем корутину
                currentJob?.cancel()

                // ВАЖНО: НЕ stopSelf сразу — даём 800 мс на реальную отправку пакета
                // и на то, чтобы приёмник получил его и закрыл socket
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    stopSelf()
                    InAppLogger.d("FileSenderService", "delayed stopSelf after cancel")
                }, 800L)
            }
            TransferNotification.ACTION_RESUME -> {
                InAppLogger.d("FileSenderService", "resume requested")
                // Для MVP: resume после провала — пользователь жмёт «Продолжить»,
                // сервис перезапускает передачу с теми же URIs.
                // Полный resume через MAC пока не реализован (нужен Wi-Fi Direct).
            }
        }
        return START_STICKY
    }

    private fun runTransfer(peerIp: String, peerId: String, peerName: String, uris: List<Uri>) {
        currentJob = scope.launch {
            val node = AtomController.meshNode
            if (node == null) {
                InAppLogger.d("FileSenderService", "no mesh node")
                finishWithError(peerName, "Сеть недоступна")
                return@launch
            }

            // Quick probe (500ms) — только для лога, не блокирует
            val alive = node.probePeer(peerIp, 500)
            if (alive) {
                InAppLogger.d("FileSenderService", "probe OK for $peerIp")
            } else {
                InAppLogger.d("FileSenderService",
                    "probe timeout — но продолжаем FILE_OFFER retry")
            }

            val startedAt = System.currentTimeMillis()
            var totalSent = 0L
            var filesOk = 0

            for ((idx, uri) in uris.withIndex()) {
                if (!isActive) break
                val name = displayName(uri) ?: "file_${idx}"
                val size = fileSize(uri)
                val transferId = UUID.randomUUID().toString()
                AtomController.transferState.value = TransferState.Running(
                    transferId, peerName, name, 0, size
                )
                val notif = TransferNotification.buildRunning(
                    this@FileSenderService, transferId, peerName, name, 0, size
                )
                TransferNotification.update(this@FileSenderService, NOTIF_ID, notif)

                val sent = sendOneFile(node, peerIp, uri, name, size, transferId, peerName)
                if (sent < 0) {
                    finishWithError(peerName, "Ошибка отправки $name")
                    return@launch
                }
                totalSent += sent
                filesOk++
                TransferHistory.add(this@FileSenderService, TransferHistory.Record(
                    id = transferId,
                    peerName = peerName,
                    fileName = name,
                    bytes = sent,
                    durationMs = System.currentTimeMillis() - startedAt,
                    status = "success",
                    timestamp = System.currentTimeMillis(),
                    savedPath = ""
                ))
            }

            val duration = System.currentTimeMillis() - startedAt
            AtomController.transferState.value = TransferState.Success(
                peerName, filesOk, totalSent, duration
            )
            val ok = TransferNotification.buildSuccess(
                this@FileSenderService, peerName, filesOk, totalSent, duration
            )
            TransferNotification.update(this@FileSenderService, NOTIF_ID, ok)
            InAppLogger.d("FileSenderService", "DONE: $filesOk files, $totalSent bytes, ${duration}ms")
            kotlinx.coroutines.delay(10_000)
            stopSelf()
        }
    }

    private suspend fun sendOneFile(
        node: MeshNode,
        peerIp: String,
        uri: Uri,
        name: String,
        size: Long,
        transferId: String,
        peerName: String
    ): Long {
        // 1) SHA-256 до отправки
        val notifPrep = TransferNotification.buildRunning(
            this, transferId, peerName, "$name (хэш…)", 0, size
        )
        TransferNotification.update(this, NOTIF_ID, notifPrep)

        val contentHash = computeSha256(uri)
        if (contentHash == null) {
            InAppLogger.d("FileSenderService", "hash compute failed for $name")
            return -1
        }
        InAppLogger.d("FileSenderService",
            "hash($name)=${contentHash.take(16)}… size=$size")

        // 2) FILE_OFFER
        val offerJson = JSONObject().apply {
            put("tid", transferId)
            put("hash", contentHash)
            put("name", name)
            put("size", size)
            put("peer_id", AtomController.myId)
            put("peer_name", AtomController.myName)
        }

        val acceptDeferred = kotlinx.coroutines.CompletableDeferred<Result<Long>>()
        pendingAccepts[transferId] = acceptDeferred

        var acceptResult: Result<Long>? = null
        for (attempt in 1..10) {
            node.sendPacket(peerIp, MeshPacket(PacketType.FILE_OFFER, offerJson))
            InAppLogger.d("FileSenderService", "FILE_OFFER attempt $attempt/10")
            acceptResult = try {
                kotlinx.coroutines.withTimeoutOrNull(1_000L) { acceptDeferred.await() }
            } catch (e: Exception) { null }
            if (acceptResult != null) break
        }
        pendingAccepts.remove(transferId)

        if (acceptResult == null) {
            InAppLogger.d("FileSenderService", "accept timeout")
            AtomController.transferState.value = TransferState.Failed(
                transferId, peerName, name, "Получатель не ответил", peerIp, contentHash
            )
            TransferNotification.update(this, NOTIF_ID,
                TransferNotification.buildFailed(this, transferId, peerName, name, "Нет ответа"))
            return -1
        }

        if (acceptResult.isFailure) {
            val reason = (acceptResult.exceptionOrNull() as? DeclineException)?.reason ?: "declined"
            val humanReason = when (reason) {
                "busy" -> "занят"
                "no_space" -> "нет места"
                else -> reason
            }
            AtomController.transferState.value = TransferState.Failed(
                transferId, peerName, name, humanReason, peerIp, contentHash
            )
            TransferNotification.update(this, NOTIF_ID,
                TransferNotification.buildFailed(this, transferId, peerName, name, humanReason))
            return -1
        }

        // 3) Multi-thread или single
        // 2 потока — компромисс между параллелизмом и contention на диске
        val threadCount = if (size >= 8 * 1024 * 1024) 2 else 1
        InAppLogger.d("FileSenderService",
            "start transfer: ${threadCount} thread(s) for $size bytes")

        return try {
            if (threadCount == 1) {
                sendSingleThread(node, peerIp, uri, name, size, transferId, peerName, contentHash)
            } else {
                sendMultiThread(node, peerIp, uri, name, size, transferId, peerName, contentHash, threadCount)
            }
        } catch (e: Exception) {
            InAppLogger.d("FileSenderService", "send error: ${e.message}")
            AtomController.transferState.value = TransferState.Failed(
                transferId, peerName, name, e.message ?: "ошибка", peerIp, contentHash
            )
            TransferNotification.update(this, NOTIF_ID,
                TransferNotification.buildFailed(this, transferId, peerName, name,
                    e.message ?: "соединение"))
            -1L
        }
    }

    private suspend fun sendMultiThread(
        node: MeshNode,
        peerIp: String,
        uri: Uri,
        name: String,
        size: Long,
        transferId: String,
        peerName: String,
        contentHash: String,
        threadCount: Int
    ): Long = kotlinx.coroutines.coroutineScope {
        senderSpeedTracker.reset()
        val progress = java.util.concurrent.atomic.AtomicLong(0L)
        val failed = java.util.concurrent.atomic.AtomicBoolean(false)
        val sockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()

        val chunkSize = (size + threadCount - 1) / threadCount
        val startTime = System.currentTimeMillis()

        val jobs = (0 until threadCount).map { i ->
            val start = i * chunkSize
            val end = minOf(start + chunkSize, size)
            if (start >= end) return@map null

            async(Dispatchers.IO) {
                if (failed.get()) return@async
                try {
                    val socket = node.openTcpTo(peerIp, 5000)
                    sockets.add(socket)
                    try {
                        val dos = DataOutputStream(
                            java.io.BufferedOutputStream(socket.getOutputStream(), 512 * 1024)
                        )

                        // Header: tid, hash, name, totalSize, offset, end, threadIdx, threadCount
                        val tidB = transferId.toByteArray(Charsets.UTF_8)
                        val hashB = contentHash.toByteArray(Charsets.UTF_8)
                        val nameB = name.toByteArray(Charsets.UTF_8)
                        dos.writeInt(tidB.size); dos.write(tidB)
                        dos.writeInt(hashB.size); dos.write(hashB)
                        dos.writeInt(nameB.size); dos.write(nameB)
                        dos.writeLong(size)
                        dos.writeLong(start)
                        dos.writeLong(end)
                        dos.writeInt(i)
                        dos.writeInt(threadCount)
                        dos.flush()

                        // Данные
                        val pfd = contentResolver.openFileDescriptor(uri, "r")
                            ?: throw java.io.FileNotFoundException("cannot open $uri")
                        pfd.use { descriptor ->
                            java.io.FileInputStream(descriptor.fileDescriptor).use { ins ->
                                ins.channel.position(start)
                                val buf = ByteArray(512 * 1024)
                                var pos = start
                                var lastNotif = 0L
                                while (pos < end && currentCoroutineContext().isActive) {
                                    val toRead = minOf(buf.size.toLong(), end - pos).toInt()
                                    val read = ins.read(buf, 0, toRead)
                                    if (read < 0) break
                                    dos.write(buf, 0, read)
                                    pos += read
                                    val total = progress.addAndGet(read.toLong())

                                val now = System.currentTimeMillis()
                                if (now - lastNotif > 500) {
                                    lastNotif = now
                                    dos.flush()
                                    senderSpeedTracker.onProgress(total)
                                    val bps = senderSpeedTracker.bytesPerSecond()
                                    TransferNotification.update(this@FileSenderService, NOTIF_ID,
                                        TransferNotification.buildRunning(
                                            this@FileSenderService, transferId, peerName,
                                            name, total, size, bps))
                                    AtomController.transferState.value = TransferState.Running(
                                        transferId, peerName, name, total, size,
                                        bytesPerSecond = bps, isReceiver = false)
                                }
                            }
                                dos.flush()
                                InAppLogger.d("FileSenderService",
                                    "thread $i done: ${pos - start} bytes")
                            }
                        }
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    InAppLogger.d("FileSenderService",
                        "thread $i error: ${e.javaClass.simpleName}: ${e.message}")
                    failed.set(true)
                    throw e
                }
            }
        }.filterNotNull()

        try {
            jobs.forEach {
                try { it.await() } catch (_: Exception) { /* уже в failed */ }
            }
        } finally {
            if (failed.get()) {
                sockets.forEach { try { it.close() } catch (_: Exception) {} }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        val totalSent = progress.get()
        val avgMbps = totalSent / 1024.0 / 1024.0 / (elapsed / 1000.0)
        InAppLogger.d("FileSenderService",
            "multi done: $totalSent/$size in ${elapsed}ms = ${"%.1f".format(avgMbps)} MB/s avg")

        if (failed.get() || totalSent < size) {
            throw Exception("Transfer failed: $totalSent/$size")
        }
        totalSent
    }

    private suspend fun sendSingleThread(
        node: MeshNode,
        peerIp: String,
        uri: Uri,
        name: String,
        size: Long,
        transferId: String,
        peerName: String,
        contentHash: String
    ): Long {
        senderSpeedTracker.reset()
        val socket = node.openTcpTo(peerIp, 5000)
        try {
            val dos = DataOutputStream(
                java.io.BufferedOutputStream(socket.getOutputStream(), 512 * 1024)
            )
            val tidB = transferId.toByteArray(Charsets.UTF_8)
            val hashB = contentHash.toByteArray(Charsets.UTF_8)
            val nameB = name.toByteArray(Charsets.UTF_8)
            dos.writeInt(tidB.size); dos.write(tidB)
            dos.writeInt(hashB.size); dos.write(hashB)
            dos.writeInt(nameB.size); dos.write(nameB)
            dos.writeLong(size)
            dos.writeLong(0L)
            dos.writeLong(size)
            dos.writeInt(0)
            dos.writeInt(1)
            dos.flush()

            var sent = 0L
            val input = contentResolver.openInputStream(uri)
                ?: throw java.io.FileNotFoundException("cannot open $uri")
            input.use {
                val buf = ByteArray(512 * 1024)
                var lastNotif = 0L
                while (currentCoroutineContext().isActive) {
                    val read = it.read(buf)
                    if (read <= 0) break
                    dos.write(buf, 0, read)
                    sent += read
                    val now = System.currentTimeMillis()
                    if (now - lastNotif > 500) {
                        lastNotif = now
                        dos.flush()
                        senderSpeedTracker.onProgress(sent)
                        val bps = senderSpeedTracker.bytesPerSecond()
                        TransferNotification.update(this, NOTIF_ID,
                            TransferNotification.buildRunning(
                                this, transferId, peerName, name, sent, size, bps))
                        AtomController.transferState.value = TransferState.Running(
                            transferId, peerName, name, sent, size,
                            bytesPerSecond = bps, isReceiver = false)
                    }
                }
            }
            dos.flush()
            InAppLogger.d("FileSenderService", "single done: $sent/$size bytes")
            return sent
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private suspend fun computeSha256(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val md = MessageDigest.getInstance("SHA-256")
            contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    md.update(buf, 0, r)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            InAppLogger.d("FileSenderService", "hash error: ${e.message}")
            null
        }
    }

    private fun finishWithError(peerName: String, reason: String) {
        val notif = TransferNotification.buildFailed(
            this, "prep", peerName, "—", reason
        )
        TransferNotification.update(this, NOTIF_ID, notif)
        stopSelf()
    }

    private fun simpleHash(name: String, size: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("$name:$size".toByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun displayName(uri: Uri): String? {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c: Cursor ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment
    }

    private fun fileSize(uri: Uri): Long {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c: Cursor ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0) return c.getLong(idx)
                }
            }
        } catch (_: Exception) {}
        return 0L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AtomController.setTransferActive(false)
        PowerLocks.releaseWifi(wifiLock)
        PowerLocks.release(wakeLock)
        scope.cancel()
        InAppLogger.d("FileSenderService", "onDestroy, locks released")
        super.onDestroy()
    }

    class DeclineException(val reason: String) : Exception("Peer declined: $reason")

}
