package com.mesh.core

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * B19: компактный лог. ~50-300 байт на сессию (было 12-100 KB).
 *
 * Формат (по одной строке на событие):
 *   S10                     — старт сессии, 10 файлов
 *   S10/r                   — resume (r)
 *   0T1/2613/451/0/86/148/64/17.2          — +0s, файл 1, sizeKB/accept/busy/trans/hash/speed
 *   5T7/2613/0/0/5000/0/0-F/no-accept      — -F: fail + reason
 *   P3                      — pause на файле 3
 *   C3                      — cancel на файле 3
 *   E10/9ok1fail/14.2/6.8   — end: total/ok/fail/dur_s/avgMBps
 *   R1/700/140/31/OK        — receiver: files/sizeMB/saveMs/shaMs
 */
object CompactLog {

    private const val MAX_BYTES = 100_000L
    private const val MAX_FILES = 2

    private var dir: File? = null
    private var sessionStartMs: Long = 0L
    private val sb = StringBuilder()
    private var lastPauseIdx: Int = -1

    fun init(ctx: Context) {
        val base = ctx.getExternalFilesDir("logs") ?: File(ctx.filesDir, "logs")
        base.mkdirs()
        dir = base
    }

    fun getLogFile(): File? {
        val d = dir ?: return null
        val f = File(d, "atom-compact.log")
        return if (f.exists() && f.length() > 0) f else null
    }

    @Synchronized
    fun sessionStart(files: Int, resumed: Boolean) {
        sessionStartMs = System.currentTimeMillis()
        sb.setLength(0)
        lastPauseIdx = -1
        sb.append('S').append(files)
        if (resumed) sb.append("/r")
        sb.append('\n')
        flush()
    }

    @Synchronized
    fun file(idx: Int, sizeKB: Long, acceptMs: Long, busyCnt: Int,
             transMs: Long, hashMs: Long, speedMbps: Double,
             failReason: String? = null) {
        val now = System.currentTimeMillis()
        val dt = if (sessionStartMs > 0) (now - sessionStartMs) / 1000 else 0
        sb.append(dt).append('T').append(idx)
            .append('/').append(sizeKB)
            .append('/').append(acceptMs)
            .append('/').append(busyCnt)
            .append('/').append(transMs)
            .append('/').append(hashMs)
            .append('/').append("%.1f".format(Locale.US, speedMbps))
        if (failReason != null) {
            sb.append("-F/").append(failReason.replace("/", "_").take(14))
        }
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
            .append('/').append(if (ok) "OK" else "F")
            .append('\n')
        flush()
    }

    private fun flush() {
        val d = dir ?: return
        try {
            val f = File(d, "atom-compact.log")
            if (f.exists() && f.length() > MAX_BYTES) rotate(d)
            f.appendText(sb.toString())
            sb.setLength(0)
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
