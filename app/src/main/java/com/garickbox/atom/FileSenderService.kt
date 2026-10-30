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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
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
        private const val CHUNK = 128 * 1024

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
    private var keepAliveJob: Job? = null

    override fun onCreate() {
        try { AtomController.setTransferActive(true) } catch (_: Exception) {}
        super.onCreate()
        startForeground(NOTIF_ID, TransferNotification.buildRunning(
            this, "prep", "…", "Подготовка", 0, 0
        ))
        wifiLock = PowerLocks.acquireWifi(this, "atom-send-wifi")
        wakeLock = PowerLocks.acquireWake(this, "atom-send-wake")

        // Подключаем приём ответов на FILE_OFFER
        AtomController.meshNode?.onFileAccept = { tid, offset ->
            val deferred = pendingAccepts[tid]
            if (deferred != null) {
                InAppLogger.d("FileSenderService",
                    "got FILE_ACCEPT tid=${tid.take(8)} offset=$offset")
                deferred.complete(Result.success(offset))
            } else {
                InAppLogger.d("FileSenderService",
                    "got FILE_ACCEPT tid=${tid.take(8)} but no awaiter (next attempt)")
            }
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
                val curStateBefore = AtomController.transferState.value
                val cancelInfo = if (curStateBefore is TransferState.Running) {
                    "file=${curStateBefore.fileName} progress=${curStateBefore.bytesSent}/${curStateBefore.bytesTotal}"
                } else "state=$curStateBefore"
                InAppLogger.d("FileSenderService", "cancel requested ($cancelInfo)")
                keepAliveJob?.cancel()
                keepAliveJob = null
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
                InAppLogger.d("FileSenderService", "resume requested — not implemented, stop")
                // TODO: реализовать resume через MAC. Пока — просто останавливаемся
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // null (после ребута системы) или неизвестный action — не держим lock'и
                InAppLogger.d("FileSenderService", "onStartCommand null/unknown — stopSelf")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun runTransfer(peerIp: String, peerId: String, peerName: String, uris: List<Uri>) {
        currentJob = scope.launch {
            val node = AtomController.meshNode
            // Defensive: callbacks могли не установиться, если meshNode был null в onCreate.
            // Тогда FILE_ACCEPT придёт в пустоту → 30 retry впустую. Лучше упасть сразу.
            if (node == null || node.onFileAccept == null) {
                InAppLogger.d("FileSenderService",
                    "meshNode not ready: node=" + (node != null) +
                        " callbacks=" + (node?.onFileAccept != null))
                finishWithError(peerName, "Сеть не инициализирована")
                return@launch
            }

            // Probe — только в лог, не блокирует
            val alive = node.probePeer(peerIp, 500)
            if (alive) {
                InAppLogger.d("FileSenderService", "probe OK for $peerIp")
            } else {
                InAppLogger.d("FileSenderService", "probe timeout — продолжаем FILE_OFFER")
            }
            AtomController.saveLastPeer(Peer(peerId, peerName, peerIp))
            InAppLogger.d("FileSenderService",
                "═══ transfer START: ${uris.size} files, peer=$peerName($peerIp)")
            val startedAt = System.currentTimeMillis()
            var totalSent = 0L
            var filesOk = 0

            // Keep-alive: HELLO каждые 10 сек — framework HyperOS не гасит P2P
            keepAliveJob?.cancel()
            keepAliveJob = scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(10_000)
                    try {
                        node.sendHello(peerIp)
                    } catch (_: Exception) {}
                }
            }

            for ((idx, uri) in uris.withIndex()) {
                if (!isActive) break
                val name = displayName(uri) ?: "file_${idx}"
                val size = fileSize(uri)
                val transferId = UUID.randomUUID().toString()
                InAppLogger.d("FileSenderService",
                    "file ${idx + 1}/${uris.size}: $name ($size b) tid=${transferId.take(8)}")
                AtomController.transferState.value = TransferState.Running(
                    transferId, peerName, name, 0, size
                )
                val notif = TransferNotification.buildRunning(
                    this@FileSenderService, transferId, peerName, name, 0, size
                )
                TransferNotification.update(this@FileSenderService, NOTIF_ID, notif)

                val sent = sendOneFile(node, peerIp, uri, name, size, transferId, peerName)
                if (sent < 0) {
                    InAppLogger.d("FileSenderService",
                        "file ${idx + 1}/${uris.size} SKIP: $name failed — продолжаем")
                    TransferHistory.add(this@FileSenderService, TransferHistory.Record(
                        id = transferId,
                        peerName = peerName,
                        fileName = name,
                        bytes = 0L,
                        durationMs = 0L,
                        status = "failed",
                        timestamp = System.currentTimeMillis(),
                        savedPath = ""
                    ))
                    continue  // НЕ return — продолжаем следующий файл
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

            keepAliveJob?.cancel()
            // Если отменили — не показывать Success
            if (!isActive) {
                InAppLogger.d("FileSenderService",
                    "cancelled — не показываем Success (${filesOk} файлов успели)")
                return@launch
            }
            val duration = System.currentTimeMillis() - startedAt
            AtomController.transferState.value = TransferState.Success(
                peerName, filesOk, totalSent, duration
            )
            val ok = TransferNotification.buildSuccess(
                this@FileSenderService, peerName, filesOk, totalSent, duration
            )
            TransferNotification.update(this@FileSenderService, NOTIF_ID, ok)
            val avgMbps = if (duration > 0) totalSent / 1024.0 / 1024.0 / (duration / 1000.0) else 0.0
            InAppLogger.d("FileSenderService",
                "═══ transfer DONE: $filesOk/${uris.size} files, ${duration}ms, avg ${"%.1f".format(avgMbps)} MB/s")
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

        val hashStart = System.currentTimeMillis()
        val contentHash = computeSha256(uri)
        if (contentHash == null) {
            InAppLogger.d("FileSenderService", "hash compute failed for $name")
            return -1
        }
        val hashMs = System.currentTimeMillis() - hashStart
        InAppLogger.d("FileSenderService",
            "hash($name)=${contentHash.take(16)}… size=$size in ${hashMs}ms")

        if (size <= 0) {
            InAppLogger.d("FileSenderService",
                "size=$size — не могу определить размер, пропускаем файл")
            return -1
        }

        // 2) FILE_OFFER
        val offerJson = JSONObject().apply {
            put("tid", transferId)
            put("hash", contentHash)
            put("name", name)
            put("size", size)
            put("peer_id", AtomController.myId)
            put("peer_name", AtomController.myName)
        }

        // ВАЖНО: CompletableDeferred одноразовый. Новый на каждую попытку,
        // иначе первый DECLINE{busy} навсегда закроет его, и ACCEPT на следующих
        // попытках молча игнорируется (await вернёт старое failure).
        var acceptResult: Result<Long>? = null
        var lastReason: String? = null
        val offerStart = System.currentTimeMillis()
        for (attempt in 1..30) {
            val deferred = kotlinx.coroutines.CompletableDeferred<Result<Long>>()
            pendingAccepts[transferId] = deferred
            node.sendPacket(peerIp, MeshPacket(PacketType.FILE_OFFER, offerJson))
            val r = try {
                kotlinx.coroutines.withTimeoutOrNull(1_500L) { deferred.await() }
            } catch (e: Exception) { null } finally {
                pendingAccepts.remove(transferId, deferred)
            }

            if (r != null) {
                if (r.isSuccess) {
                    acceptResult = r
                    val offerMs = System.currentTimeMillis() - offerStart
                    InAppLogger.d("FileSenderService",
                        "accept OK in ${offerMs}ms (attempt $attempt)")
                    break
                }
                val reason = (r.exceptionOrNull() as? DeclineException)?.reason ?: "declined"
                if (reason == "busy") {
                    lastReason = "busy"
                    // Backoff: 1с → 2с → 3с → 3с...
                    val delayMs = 1000L * minOf(attempt, 3)
                    InAppLogger.d("FileSenderService",
                        "busy (attempt $attempt/30) — retry через ${delayMs}ms")
                    kotlinx.coroutines.delay(delayMs)
                    continue
                }
                // no_space / declined — не retry
                acceptResult = r
                break
            }
            // timeout — retry
        }
        if (acceptResult == null && lastReason == "busy") {
            InAppLogger.d("FileSenderService", "busy timeout 30 сек — сдаюсь")
        }

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
                "busy" -> "занят — принимает другой файл"
                "no_space" -> "нет места на устройстве"
                else -> reason
            }
            AtomController.transferState.value = TransferState.Failed(
                transferId, peerName, name, humanReason, peerIp, contentHash
            )
            TransferNotification.update(this, NOTIF_ID,
                TransferNotification.buildFailed(this, transferId, peerName, name, humanReason))
            return -1
        }

        // 3) Multi-thread: 4 потока если size >= 8 MB, иначе 1
        val threadCount = 1  // single-thread как build-37 — быстрее на 2.4 GHz
        InAppLogger.d("FileSenderService",
            "start transfer: ${threadCount} thread(s) for $size bytes")

        return try {
            sendMultiThread(node, peerIp, uri, name, size, transferId, peerName, contentHash, threadCount)
        } catch (e: kotlinx.coroutines.CancellationException) {
            InAppLogger.d("FileSenderService", "cancelled by coroutine")
            throw e
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

    /**
     * 4 параллельных TCP соединения. Каждый поток читает свой диапазон [start, end)
     * через FileInputStream.channel.position(start). Скорость считается по СУММЕ.
     */
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
    ): Long = coroutineScope {
        senderSpeedTracker.reset()
        val progress = AtomicLong(0L)
        val failed = AtomicBoolean(false)
        val sockets = CopyOnWriteArrayList<Socket>()
        val lastNotif = AtomicLong(0L)
        val startTime = System.currentTimeMillis()

        val chunkSize = (size + threadCount - 1) / threadCount

        val jobs = (0 until threadCount).map { i ->
            val start = i * chunkSize
            val end = minOf(start + chunkSize, size)
            if (start >= end) return@map null

            async(Dispatchers.IO) {
                if (failed.get()) return@async
                var socket: Socket? = null
                try {
                    // TCP retry при ECONNREFUSED (framework HyperOS BUSY)
                    var lastConnectErr: Exception? = null
                    val tcpStart = System.currentTimeMillis()
                    for (retry in 1..5) {
                        try {
                            socket = node.openTcpTo(peerIp, 5000)
                            val tcpMs = System.currentTimeMillis() - tcpStart
                            InAppLogger.d("FileSenderService",
                                "tcp connect OK (thread $i, attempt $retry, ${tcpMs}ms)")
                            break
                        } catch (ce: Exception) {
                            lastConnectErr = ce
                            InAppLogger.d("FileSenderService",
                                "tcp connect fail (thread $i, $retry/5): ${ce.javaClass.simpleName}")
                            if (retry < 5) kotlinx.coroutines.delay(1000)
                        }
                    }
                    if (socket == null) {
                        throw lastConnectErr ?: Exception("tcp connect failed")
                    }
                    sockets.add(socket)
                    val dos = DataOutputStream(socket.getOutputStream())  // прямой socket — build-37

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
                    var pos = start
                    pfd.use { descriptor ->
                        java.io.FileInputStream(descriptor.fileDescriptor).use { ins ->
                            ins.channel.position(start)
                            val buf = ByteArray(512 * 1024)
                            while (pos < end && currentCoroutineContext().isActive) {
                                if (failed.get()) break
                                val toRead = minOf(buf.size.toLong(), end - pos).toInt()
                                val read = ins.read(buf, 0, toRead)
                                if (read < 0) break
                                dos.write(buf, 0, read)
                                pos += read
                                val total = progress.addAndGet(read.toLong())

                                val now = System.currentTimeMillis()
                                val prev = lastNotif.get()
                                if (now - prev > 500 && lastNotif.compareAndSet(prev, now)) {
                                    dos.flush()
                                    senderSpeedTracker.onProgress(total)
                                    val bps = senderSpeedTracker.bytesPerSecond()
                                    TransferNotification.updateIfChanged(this@FileSenderService, NOTIF_ID,
                                        TransferNotification.buildRunning(
                                            this@FileSenderService, transferId, peerName,
                                            name, total, size, bps))
                                    AtomController.transferState.value = TransferState.Running(
                                        transferId, peerName, name, total, size,
                                        bytesPerSecond = bps, isReceiver = false)
                                }
                            }
                            dos.flush()
                        }
                    }
                    InAppLogger.d("FileSenderService",
                        "thread $i done: ${pos - start} bytes")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    InAppLogger.d("FileSenderService",
                        "thread $i error: ${e.javaClass.simpleName}: ${e.message}")
                    failed.set(true)
                    throw e
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                }
            }
        }.filterNotNull()

        try {
            jobs.forEach { try { it.await() } catch (_: Exception) {} }
        } finally {
            if (failed.get()) {
                sockets.forEach { try { it.close() } catch (_: Exception) {} }
            }
        }

        val elapsed = System.currentTimeMillis() - startTime
        val totalSent = progress.get()
        val avgMbps = if (elapsed > 0) totalSent / 1024.0 / 1024.0 / (elapsed / 1000.0) else 0.0
        InAppLogger.d("FileSenderService",
            "multi done: $totalSent/$size in ${elapsed}ms = ${"%.1f".format(avgMbps)} MB/s avg (${threadCount} thr)")

        if (failed.get() || totalSent < size) {
            throw Exception("Transfer failed: $totalSent/$size")
        }
        totalSent
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
        TransferNotification.resetCache(NOTIF_ID)
        try { AtomController.setTransferActive(false) } catch (_: Exception) {}
        PowerLocks.releaseWifi(wifiLock)
        PowerLocks.release(wakeLock)
        scope.cancel()
        InAppLogger.d("FileSenderService", "onDestroy, locks released")
        super.onDestroy()
    }

    class DeclineException(val reason: String) : Exception("Peer declined: $reason")

}
