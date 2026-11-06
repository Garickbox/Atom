package com.mesh.core

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * B227 axiom#409: ONE log file. CompactLog = обёртка → InAppLogger.compact().
 * Все методы сохранены (backward-compat всех 6 call sites).
 */
object CompactLog {

    private var sessionStartMs: Long = 0L
    private var sessionOpen: Boolean = false
    private var lastPauseIdx: Int = -1
    private var lastSpeedMbps: Double = 0.0

    fun init(ctx: Context) { /* no-op, InAppLogger.init covers */ }
    fun getLogFile(): File? = InAppLogger.getLogFile()

    fun sessionStart(files: Int, resumed: Boolean, connectMs: Long = 0L) {
        if (resumed && sessionOpen) {
            InAppLogger.compact("r")
            return
        }
        if (sessionOpen) InAppLogger.compact("E0/0/1/0.0/0.0")
        sessionStartMs = System.currentTimeMillis()
        lastPauseIdx = -1
        lastSpeedMbps = 0.0
        sessionOpen = true
        val conn = if (connectMs > 0) "/${connectMs / 1000}" else ""
        InAppLogger.compact("S$files$conn")
    }

    fun file(idx: Int, sizeKB: Long, acceptMs: Long, busyCnt: Int,
             transMs: Long, hashMs: Long, speedMbps: Double, failReason: String? = null) {
        val dt = (System.currentTimeMillis() - sessionStartMs) / 1000
        val sb = StringBuilder()
        sb.append(dt).append('T').append(idx)
            .append('/').append(sizeKB)
            .append('/').append(acceptMs)
            .append('/').append(busyCnt)
            .append('/').append(transMs)
            .append('/').append(hashMs)
            .append('/').append("%.1f".format(Locale.US, speedMbps))
        if (failReason != null) sb.append("-F/").append(failReason.replace("/", "_").take(14))
        else if (lastSpeedMbps > 1.0 && speedMbps < lastSpeedMbps * 0.3) sb.append('!')
        if (speedMbps > 0) lastSpeedMbps = speedMbps
        InAppLogger.compact(sb.toString())
    }

    fun pause(idx: Int) {
        if (idx == lastPauseIdx) return
        lastPauseIdx = idx
        InAppLogger.compact("P$idx")
    }

    fun cancel(idx: Int) = InAppLogger.compact("C$idx")

    fun sessionEnd(ok: Int, fail: Int, durMs: Long, avgMbps: Double) {
        if (!sessionOpen) return
        sessionOpen = false
        InAppLogger.compact(
            "E${ok + fail}/$ok/$fail/" +
                "%.1f".format(Locale.US, durMs / 1000.0) + "/" +
                "%.1f".format(Locale.US, avgMbps)
        )
    }

    fun receiver(sizeMB: Long, saveMs: Long, shaMs: Long, ok: Boolean) {
        if (sessionStartMs == 0L) sessionStartMs = System.currentTimeMillis()
        val dt = (System.currentTimeMillis() - sessionStartMs) / 1000
        val tail = if (ok) "" else "/F"
        InAppLogger.compact("$dt R/$sizeMB/$saveMs/$shaMs$tail")
    }
}
