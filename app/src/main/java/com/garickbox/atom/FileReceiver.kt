package com.garickbox.atom

import android.content.Context
import com.mesh.core.InAppLogger
import com.mesh.core.MeshNode
import java.io.DataInputStream
import java.io.File
import java.net.Socket

object FileReceiver {

    fun onOffer(offer: MeshNode.FileOffer, ctx: Context) {
        InAppLogger.d("FileReceiver", "offer from ${offer.peerName} (${offer.fromIp}): ${offer.name} (${offer.size} b)")
        // Запустить FGS ПЕРЕД приёмом — чтобы процесс не убили на больших файлах
        FileReceiverService.start(ctx, offer.name, offer.size)

        val node = AtomController.meshNode ?: return
        node.onIncomingTcp = { socket -> handleIncoming(socket, ctx) }
    }

    private fun handleIncoming(socket: Socket, ctx: Context) {
        try {
            DataInputStream(socket.getInputStream()).use { dis ->
                val tidLen = dis.readInt()
                val tidBytes = ByteArray(tidLen); dis.readFully(tidBytes)
                val transferId = String(tidBytes, Charsets.UTF_8)
                val hashLen = dis.readInt()
                val hashBytes = ByteArray(hashLen); dis.readFully(hashBytes)
                val hash = String(hashBytes, Charsets.UTF_8)
                val offset = dis.readLong()
                val total = dis.readLong()
                val nameLen = dis.readInt()
                val nameBytes = ByteArray(nameLen); dis.readFully(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)

                val dir = File(ctx.getExternalFilesDir(null), "received").apply { mkdirs() }
                val target = File(dir, name)
                val part = File(dir, "$name.part")

                var written = 0L
                if (offset > 0 && part.exists()) {
                    written = part.length()
                }
                java.io.RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(written)
                    val buf = ByteArray(32 * 1024)
                    var remaining = total - written
                    var lastNotif = 0L
                    while (remaining > 0) {
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val read = dis.read(buf, 0, toRead)
                        if (read < 0) break
                        raf.write(buf, 0, read)
                        written += read
                        remaining -= read
                        val now = System.currentTimeMillis()
                        if (now - lastNotif > 300) {
                            lastNotif = now
                            FileReceiverService.updateProgress(ctx, name, written, total)
                        }
                    }
                }
                if (part.length() >= total) {
                    part.renameTo(target)
                    InAppLogger.d("FileReceiver", "received: $name ($written b) → ${target.absolutePath}")
                } else {
                    InAppLogger.d("FileReceiver", "partial: $name ($written/$total b)")
                }
                FileReceiverService.stop(ctx)
                AtomController.transferState.value = TransferState.Success(
                    "receiver", 1, written, 0
                )
            }
        } catch (e: Exception) {
            InAppLogger.d("FileReceiver", "recv error: ${e.message}")
            FileReceiverService.stop(ctx)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }
}
