package com.garickbox.atom

import android.content.Context
import com.mesh.core.InAppLogger
import com.mesh.core.MeshNode
import com.mesh.core.MeshPacket
import com.mesh.core.PacketType
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object FileReceiver {

    private val activeParts = ConcurrentHashMap<String, File>()
    private val _isBusy = AtomicBoolean(false)

    val isBusy: Boolean get() = _isBusy.get()

    fun onOffer(offer: MeshNode.FileOffer, ctx: Context) {
        InAppLogger.d("FileReceiver",
            "offer from ${offer.peerName}: ${offer.name} (${offer.size} b), busy=${isBusy}")

        // Проверка 1: занят
        if (_isBusy.get()) {
            sendDecline(ctx, offer, "busy")
            InAppLogger.d("FileReceiver", "declined: busy")
            return
        }

        // Проверка 2: свободное место
        val required = offer.size + 5 * 1024 * 1024  // +5 МБ буфер
        val free = ctx.getExternalFilesDir(null)?.usableSpace ?: 0L
        if (free < required) {
            sendDecline(ctx, offer, "no_space")
            InAppLogger.d("FileReceiver",
                "declined: no_space (нужно ${required / (1024*1024)} МБ, есть ${free / (1024*1024)} МБ)")
            return
        }

        // Принимаем: отправляем ACCEPT с offset (resume если .part существует)
        val partDir = File(ctx.getExternalFilesDir(null), "received").apply { mkdirs() }
        val existingPart = File(partDir, "${offer.hash}.part")
        val offset = if (existingPart.exists()) existingPart.length() else 0L

        sendAccept(ctx, offer, offset)
        _isBusy.set(true)
        InAppLogger.d("FileReceiver", "accepted, offset=$offset")

        FileReceiverService.start(ctx, offer.name, offer.size)

        val node = AtomController.meshNode ?: return
        node.onIncomingTcp = { socket -> handleIncoming(socket, ctx, node, offer) }
    }

    private fun sendAccept(ctx: Context, offer: MeshNode.FileOffer, offset: Long) {
        try {
            val node = AtomController.meshNode ?: return
            val json = JSONObject().apply {
                put("tid", offer.transferId)
                put("offset", offset)
            }
            node.sendPacket(offer.fromIp, MeshPacket(PacketType.FILE_ACCEPT, json))
            InAppLogger.d("FileReceiver", "FILE_ACCEPT sent (offset=$offset)")
        } catch (e: Exception) {
            InAppLogger.d("FileReceiver", "sendAccept error: ${e.message}")
        }
    }

    private fun sendDecline(ctx: Context, offer: MeshNode.FileOffer, reason: String) {
        try {
            val node = AtomController.meshNode ?: return
            val json = JSONObject().apply {
                put("tid", offer.transferId)
                put("reason", reason)
            }
            node.sendPacket(offer.fromIp, MeshPacket(PacketType.FILE_DECLINE, json))
            InAppLogger.d("FileReceiver", "FILE_DECLINE sent: $reason")
        } catch (e: Exception) {
            InAppLogger.d("FileReceiver", "sendDecline error: ${e.message}")
        }
    }

    fun onCancel(ctx: Context, transferId: String) {
        InAppLogger.d("FileReceiver", "cancel requested for $transferId")
        val part = activeParts.remove(transferId)
        if (part != null && part.exists()) {
            part.delete()
            InAppLogger.d("FileReceiver", "part deleted: ${part.name}")
        }
        _isBusy.set(false)
        FileReceiverService.stop(ctx)
    }

    private fun handleIncoming(
        socket: Socket,
        ctx: Context,
        node: MeshNode,
        offer: MeshNode.FileOffer
    ) {
        try {
            DataInputStream(socket.getInputStream().buffered(128 * 1024)).use { dis ->
                val tidLen = dis.readInt()
                val tidBytes = ByteArray(tidLen); dis.readFully(tidBytes)
                val transferId = String(tidBytes, Charsets.UTF_8)

                val hashLen = dis.readInt()
                val hashBytes = ByteArray(hashLen); dis.readFully(hashBytes)
                val senderHash = String(hashBytes, Charsets.UTF_8)

                val offset = dis.readLong()
                val total = dis.readLong()

                val nameLen = dis.readInt()
                val nameBytes = ByteArray(nameLen); dis.readFully(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)

                InAppLogger.d("FileReceiver",
                    "incoming $name tid=$transferId offset=$offset total=$total hash=${senderHash.take(8)}…")

                val partDir = File(ctx.getExternalFilesDir(null), "received").apply { mkdirs() }
                val partFile = File(partDir, "$transferId.part")
                activeParts[transferId] = partFile

                val startPos = if (partFile.exists()) partFile.length().coerceAtMost(offset) else offset
                java.io.RandomAccessFile(partFile, "rw").use { raf ->
                    raf.seek(startPos)
                    val md = MessageDigest.getInstance("SHA-256")

                    if (startPos > 0) {
                        raf.seek(0)
                        val oldBuf = ByteArray(128 * 1024)
                        var remaining = startPos
                        while (remaining > 0) {
                            val r = raf.read(oldBuf, 0, minOf(oldBuf.size.toLong(), remaining).toInt())
                            if (r <= 0) break
                            md.update(oldBuf, 0, r)
                            remaining -= r
                        }
                        raf.seek(startPos)
                    }

                    val buf = ByteArray(128 * 1024)
                    var written = startPos
                    var remaining = total - startPos
                    var lastNotif = 0L

                    while (remaining > 0) {
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val read = dis.read(buf, 0, toRead)
                        if (read < 0) break
                        raf.write(buf, 0, read)
                        md.update(buf, 0, read)
                        written += read
                        remaining -= read
                        val now = System.currentTimeMillis()
                        if (now - lastNotif > 300) {
                            lastNotif = now
                            FileReceiverService.updateProgress(ctx, name, written, total)
                        }
                    }

                    if (written < total) {
                        InAppLogger.d("FileReceiver", "partial: $name ($written/$total b)")
                        return
                    }

                    val actualHash = md.digest().joinToString("") { "%02x".format(it) }
                    val ok = actualHash.equals(senderHash, ignoreCase = true)
                    InAppLogger.d("FileReceiver",
                        "hash check: ${if (ok) "OK" else "FAIL"} (actual=${actualHash.take(8)}… expected=${senderHash.take(8)}…)")

                    if (!ok) {
                        partFile.delete()
                        activeParts.remove(transferId)
                        AtomController.transferState.value = TransferState.Failed(
                            transferId, offer.peerName, name, "Хэш не совпал", "", hashBytes.decodeToString()
                        )
                        FileReceiverService.stop(ctx)
                        _isBusy.set(false)
                        return
                    }
                }

                val fileName = resolveUniqueName(ctx, name)
                val result = MediaStoreSaver.save(ctx, partFile, fileName, null)
                if (result.uri != null) {
                    partFile.delete()
                    activeParts.remove(transferId)
                    InAppLogger.d("FileReceiver", "saved to ${result.displayPath}")

                    TransferHistory.add(ctx, TransferHistory.Record(
                        id = transferId,
                        peerName = offer.peerName.ifBlank { "unknown" },
                        fileName = fileName,
                        bytes = total,
                        durationMs = 0L,
                        status = "success",
                        timestamp = System.currentTimeMillis(),
                        savedPath = result.displayPath
                    ))

                    AtomController.transferState.value = TransferState.Success(
                        offer.peerName, 1, total, 0
                    )
                } else {
                    InAppLogger.d("FileReceiver",
                        "MediaStore save failed, keeping .part at ${partFile.absolutePath}")
                }
                FileReceiverService.stop(ctx)
                _isBusy.set(false)
            }
        } catch (e: Exception) {
            InAppLogger.d("FileReceiver", "recv error: ${e.message}")
            FileReceiverService.stop(ctx)
            _isBusy.set(false)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
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
}
