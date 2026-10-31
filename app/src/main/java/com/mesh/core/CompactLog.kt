package com.mesh.core

import android.content.Context
import java.io.File
import java.util.Locale

object CompactLog {
    private const val MAX_BYTES = 100_000L
    private const val MAX_FILES = 2

    private var dir: File? = null
    private var sessionStartMs: Long = 0L
    private var sessionOpen: Boolean = false
    private var lastPauseIdx: Int = -1
    private val sb = StringBuilder()

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
        sessionOpen = true
        sb.append('S').append(files).append('\n')
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
