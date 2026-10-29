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
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.BufferedOutputStream
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
    private val pendingAccepts = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Result<Long>>>()
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var currentJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, TransferNotification.buildRunning(
            this, "prep", "…", "Подготовка", 0, 0
        ))
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
                // Отправляем FILE_CANCEL пиру чтобы удалил .part
                val curState = AtomController.transferState.value
                if (curState is TransferState.Running) {
                    try {
                        val ip = AtomController.lastPeer?.ip
                        val node = AtomController.meshNode
                        if (ip != null && node != null) {
                            val cancelJson = JSONObject().apply {
                                put("tid", curState.transferId)
                            }
                            node.sendPacket(ip, MeshPacket(PacketType.FILE_CANCEL, cancelJson))
                            InAppLogger.d("FileSenderService", "FILE_CANCEL sent to $ip")
                        }
                    } catch (e: Exception) {
                        InAppLogger.d("FileSenderService", "cancel packet error: ${e.message}")
                    }
                    // Записываем в историю
                    TransferHistory.add(this, TransferHistory.Record(
                        id = curState.transferId,
                        peerName = curState.peerName,
                        fileName = curState.fileName,
                        bytes = curState.bytesSent,
                        durationMs = 0L,
                        status = "cancelled",
                        timestamp = System.currentTimeMillis(),
                        savedPath = ""
                    ))
                }
                currentJob?.cancel()
                stopSelf()
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

            // Probe
            val alive = node.probePeer(peerIp, 1500)
            if (!alive) {
                InAppLogger.d("FileSenderService", "probe failed for $peerIp")
                finishWithError(peerName, "Получатель не в сети")
                return@launch
            }

            AtomController.saveLastPeer(Peer(peerId, peerName, peerIp))
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
        // 1) Считаем SHA-256 контента (для целостности и будущего dedup)
        val notifPrep = TransferNotification.buildRunning(
            this, transferId, peerName, "$name (хэш…)", 0, size
        )
        TransferNotification.update(this, NOTIF_ID, notifPrep)

        val contentHash = computeSha256(uri)
        if (contentHash == null) {
            InAppLogger.d("FileSenderService", "hash compute failed for $name")
            return -1
        }
        InAppLogger.d("FileSenderService", "hash($name)=${contentHash.take(16)}… size=$size")

        // 2) FILE_OFFER
        val offerJson = JSONObject().apply {
            put("tid", transferId)
            put("hash", contentHash)
            put("name", name)
            put("size", size)
            put("peer_id", AtomController.myId)
            put("peer_name", AtomController.myName)
        }
        node.sendPacket(peerIp, MeshPacket(PacketType.FILE_OFFER, offerJson))

        // Ждём FILE_ACCEPT / FILE_DECLINE (таймаут 10 сек)
        val acceptDeferred = kotlinx.coroutines.CompletableDeferred<Result<Long>>()
        pendingAccepts[transferId] = acceptDeferred
        val acceptResult = try {
            kotlinx.coroutines.withTimeoutOrNull(10_000L) { acceptDeferred.await() }
        } finally {
            pendingAccepts.remove(transferId)
        }

        if (acceptResult == null) {
            InAppLogger.d("FileSenderService", "accept timeout for $name")
            AtomController.transferState.value = TransferState.Failed(
                transferId, peerName, name, "Получатель не ответил", peerIp, contentHash
            )
            TransferNotification.update(this, NOTIF_ID,
                TransferNotification.buildFailed(this, transferId, peerName, name, "Нет ответа"))
            return -1
        }

        val startOffset = acceptResult.getOrNull()
        if (startOffset == null) {
            val reason = (acceptResult.exceptionOrNull() as? DeclineException)?.reason ?: "declined"
            val humanReason = when (reason) {
                "busy" -> "занят — принимает другой файл"
                "no_space" -> "нет места на устройстве"
                "user_declined" -> "отклонил передачу"
                else -> "отклонил: $reason"
            }
            InAppLogger.d("FileSenderService", "declined: $reason → $humanReason")
            AtomController.transferState.value = TransferState.Failed(
                transferId, peerName, name, humanReason, peerIp, contentHash
            )
            TransferNotification.update(this, NOTIF_ID,
                TransferNotification.buildFailed(this, transferId, peerName, name, humanReason))
            return -1
        }

        if (startOffset > 0) {
            InAppLogger.d("FileSenderService", "resume from offset=$startOffset for $name")
        }

        // 3) TCP-сессия
        try {
            val socket: Socket = node.openTcpTo(peerIp, 5000)
            try {
                // Ускорение TCP
                socket.sendBufferSize = 4 * 1024 * 1024
                socket.receiveBufferSize = 4 * 1024 * 1024
                socket.tcpNoDelay = true

                val dos = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), CHUNK))
                val tidBytes = transferId.toByteArray(Charsets.UTF_8)
                val hashBytes = contentHash.toByteArray(Charsets.UTF_8)
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                dos.writeInt(tidBytes.size); dos.write(tidBytes)
                dos.writeInt(hashBytes.size); dos.write(hashBytes)
                dos.writeLong(startOffset)
                dos.writeLong(size)
                dos.writeInt(nameBytes.size); dos.write(nameBytes)

                var sent = 0L
                val input = contentResolver.openInputStream(uri)
                    ?: throw FileNotFoundException("cannot open $uri")
                input.use {
                    // skip startOffset для resume
                    if (startOffset > 0) {
                        var toSkip = startOffset
                        while (toSkip > 0) {
                            val skipped = it.skip(toSkip)
                            if (skipped <= 0) break
                            toSkip -= skipped
                        }
                        sent = startOffset
                        InAppLogger.d("FileSenderService", "skipped $startOffset bytes for resume")
                    }
                    val buf = ByteArray(CHUNK)
                    var lastNotif = 0L
                    while (currentCoroutineContext().isActive) {
                        val read = it.read(buf)
                        if (read <= 0) break
                        dos.write(buf, 0, read)
                        sent += read
                        val now = System.currentTimeMillis()
                        if (now - lastNotif > 300) {
                            lastNotif = now
                            val notif = TransferNotification.buildRunning(
                                this, transferId, peerName, name, sent, size
                            )
                            TransferNotification.update(this, NOTIF_ID, notif)
                            AtomController.transferState.value =
                                TransferState.Running(transferId, peerName, name, sent, size)
                        }
                    }
                }
                dos.flush()
                InAppLogger.d("FileSenderService", "sent $sent/$size bytes for $name")
                return sent
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            InAppLogger.d("FileSenderService", "send error: ${e.message}")
            val notif = TransferNotification.buildFailed(
                this, transferId, peerName, name, e.message ?: "соединение"
            )
            TransferNotification.update(this, NOTIF_ID, notif)
            AtomController.transferState.value = TransferState.Failed(
                transferId, peerName, name, e.message ?: "соединение", peerIp, contentHash
            )
            return -1
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
        PowerLocks.releaseWifi(wifiLock)
        PowerLocks.release(wakeLock)
        scope.cancel()
        InAppLogger.d("FileSenderService", "onDestroy, locks released")
        super.onDestroy()
    }

    class DeclineException(val reason: String) : Exception("Peer declined: $reason")

}
