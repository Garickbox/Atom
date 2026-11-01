package com.mesh.core

import android.content.Context
import java.io.File
import java.util.Locale

object CompactLog {
    private const val MAX_BYTES = 100_000L
    private const val MAX_FILES = 2

    private var dir: File? = null
    @Volatile private var appCtx: Context? = null
    @Volatile private var lastSyncAt: Long = 0L
    private var sessionStartMs: Long = 0L
    private var sessionOpen: Boolean = false
    private var lastPauseIdx: Int = -1
    private val sb = StringBuilder()

    fun init(ctx: Context) {
        val base = ctx.getExternalFilesDir("logs") ?: File(ctx.filesDir, "logs")
        base.mkdirs()
        dir = base
        appCtx = ctx.applicationContext
    }

    fun getLogFile(): File? {
        val d = dir ?: return null
        val f = File(d, "atom-compact.log")
        return if (f.exists() && f.length() > 0) f else null
    }

    /** B40: очистка всех compact-логов при смене версии. */
    @Synchronized
    fun clear() {
        val d = dir ?: return
        try {
            File(d, "atom-compact.log").delete()
            for (i in 1..MAX_FILES) {
                File(d, "atom-compact.log.$i").delete()
            }
        } catch (_: Exception) {}
    }

    private var lastSpeedMbps: Double = 0.0

    @Synchronized
    fun sessionStart(files: Int, resumed: Boolean, connectMs: Long = 0L) {
        if (resumed && sessionOpen) {
            sb.append("r\n")
            flush()
            return
        }
        if (sessionOpen) {
            sb.append("E0/0/1/0.0/0.0\n")
        }
        sessionStartMs = System.currentTimeMillis()
        lastPauseIdx = -1
        lastSpeedMbps = 0.0
        sessionOpen = true
        sb.append('S').append(files)
        if (connectMs > 0) sb.append('/').append(connectMs / 1000)
        sb.append('\n')
        flush()
    }

    @Synchronized
    fun file(idx: Int, sizeKB: Long, acceptMs: Long, busyCnt: Int,
             transMs: Long, hashMs: Long, speedMbps: Double,
             failReason: String? = null) {
        val dt = (System.currentTimeMillis() - sessionStartMs) / 1000
        sb.append(dt).append('T').append(idx)
            .append('/').append(sizeKB)
            .append('/').append(acceptMs)
            .append('/').append(busyCnt)
            .append('/').append(transMs)
            .append('/').append(hashMs)
            .append('/').append("%.1f".format(Locale.US, speedMbps))
        if (failReason != null) {
            sb.append("-F/").append(failReason.replace("/", "_").take(14))
        } else if (lastSpeedMbps > 1.0 && speedMbps < lastSpeedMbps * 0.3) {
            sb.append('!')
        }
        if (speedMbps > 0) lastSpeedMbps = speedMbps
        sb.append('\n')
        flush()
    }

    @Synchronized
    fun pause(idx: Int) {
        if (idx == lastPauseIdx) return
        lastPauseIdx = idx
        sb.append('P').append(idx).append('\n')
        flush()
    }

    @Synchronized
    fun cancel(idx: Int) {
        sb.append('C').append(idx).append('\n')
        flush()
    }

    @Synchronized
    fun sessionEnd(ok: Int, fail: Int, durMs: Long, avgMbps: Double) {
        if (!sessionOpen) return
        sessionOpen = false
        sb.append('E').append(ok + fail)
            .append('/').append(ok)
            .append('/').append(fail)
            .append('/').append("%.1f".format(Locale.US, durMs / 1000.0))
            .append('/').append("%.1f".format(Locale.US, avgMbps))
            .append('\n')
        flush()
    }

    @Synchronized
    fun receiver(sizeMB: Long, saveMs: Long, shaMs: Long, ok: Boolean) {
        if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis()
        val dt = (System.currentTimeMillis() - sessionStartMs) / 1000
        sb.append(dt).append('R')
            .append('/').append(sizeMB)
            .append('/').append(saveMs)
            .append('/').append(shaMs)
        if (!ok) sb.append("/F")
        sb.append('\n')
        flush()
    }

    private fun flush() {
        val d = dir ?: return
        try {
            val f = File(d, "atom-compact.log")
            if (f.exists() && f.length() > MAX_BYTES) rotate(d)
            f.appendText(sb.toString())
            sb.setLength(0)
            throttleSyncToDownloads("atom-compact.log")
        } catch (_: Exception) {}
    }

    /** B43 axiom#52: копия в Download/Atom/logs — Termux читает без root. */
    private fun throttleSyncToDownloads(name: String) {
        val now = System.currentTimeMillis()
        if (now - lastSyncAt < 5000L) return
        lastSyncAt = now
        val ctx = appCtx ?: return
        val d = dir ?: return
        val src = File(d, name)
        if (!src.exists()) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val resolver = ctx.contentResolver
                val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val relPath = "Download/Atom/logs/"
                // B43.2: MediaStore добавляет .txt (MIME text/plain) → LIKE + _id DESC
                val cursor = resolver.query(collection,
                    arrayOf(android.provider.MediaStore.Downloads._ID),
                    android.provider.MediaStore.Downloads.DISPLAY_NAME + " LIKE ? AND " +
                        android.provider.MediaStore.Downloads.RELATIVE_PATH + "=?",
                    arrayOf("$name%", relPath),
                    android.provider.MediaStore.Downloads._ID + " DESC")
                val uri = if (cursor != null && cursor.moveToFirst()) {
                    val id = cursor.getLong(0); cursor.close()
                    android.content.ContentUris.withAppendedId(collection, id)
                } else {
                    cursor?.close()
                    val v = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, relPath)
                    }
                    resolver.insert(collection, v)
                }
                if (uri != null) {
                    resolver.openOutputStream(uri, "wt")?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val pub = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS)
                val dst = File(pub, "Atom/logs/$name")
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
        } catch (_: Exception) {}
    }

    private fun rotate(d: File) {
        for (i in MAX_FILES downTo 2) {
            val src = File(d, "atom-compact.log.$i")
            val dst = File(d, "atom-compact.log.${i + 1}")
            if (src.exists()) src.renameTo(dst)
        }
        val cur = File(d, "atom-compact.log")
        if (cur.exists()) cur.renameTo(File(d, "atom-compact.log.1"))
    }
}
