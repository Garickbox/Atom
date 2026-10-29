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
import com.mesh.core.PacketType
import com.mesh.core.MeshPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
        private const val CHUNK = 32 * 1024

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
    private var currentJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, TransferNotification.buildRunning(
            this, "prep", "…", "Подготовка", 0, 0
        ))
        InAppLogger.d("FileSenderService", "onCreate")
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
                if (!isActive()) break
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
        // Вычисляем "hash" для MVP: размер+имя (полноценный SHA-256 — этап 9)
        val hash = simpleHash(name, size)
        // FILE_OFFER
        val offerJson = JSONObject().apply {
            put("tid", transferId)
            put("hash", hash)
            put("name", name)
            put("size", size)
            put("peer_id", AtomController.myId)
            put("peer_name", AtomController.myName)
        }
        node.sendPacket(peerIp, MeshPacket(PacketType.FILE_OFFER, offerJson))

        // Ждём FILE_ACCEPT/RESUME — но у нас нет receiver'а, который бы отвечал,
        // поэтому для MVP: sender сам открывает TCP и сразу шлёт с offset=0.
        // TODO: ждать FILE_ACCEPT и обрабатывать offset (для resume)

        try {
            val socket: Socket = node.openTcpTo(peerIp, 5000)
            try {
                val dos = DataOutputStream(socket.getOutputStream())
                val tidBytes = transferId.toByteArray(Charsets.UTF_8)
                val hashBytes = hash.toByteArray(Charsets.UTF_8)
                dos.writeInt(tidBytes.size); dos.write(tidBytes)
                dos.writeInt(hashBytes.size); dos.write(hashBytes)
                dos.writeLong(0L)  // offset
                dos.writeLong(size) // total
                dos.writeInt(name.toByteArray(Charsets.UTF_8).size)
                dos.write(name.toByteArray(Charsets.UTF_8))

                var sent = 0L
                val input = contentResolver.openInputStream(uri)
                    ?: throw FileNotFoundException("cannot open $uri")
                input.use {
                    val buf = ByteArray(CHUNK)
                    var lastNotif = 0L
                    while (isActive()) {
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
                transferId, peerName, name, e.message ?: "соединение", peerIp, hash
            )
            return -1
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
        scope.cancel()
        super.onDestroy()
    }
}
