package com.mesh.core

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * B20: радикальная экономия логов.
 *
 * По умолчанию:
 *   - atom.log НЕ пишется (verbose off)
 *   - atom-compact.log пишется (CompactLog, ~50-300 B/сессия)
 *   - atom-err.log пишется при ошибках (короткие строки)
 *
 * При verbose ON (меню Атом → 🐛):
 *   - atom.log пишется в сжатом формате: HHMM+1буква msg
 *   - шум (HELLO/HERE/requestPeers/discover) выкидывается
 *   - IP: 192.168.49.38 → 192.168.xx.xx
 *
 * ВАЖНО: не содержит ссылок на com.garickbox.atom.* (rule build_check).
 */
object InAppLogger {

    private const val MAX_BYTES = 200_000L
    private const val MAX_FILES = 2
    private const val MAX_ERR_BYTES = 50_000L

    private var dir: File? = null
    private var prefs: SharedPreferences? = null

    @Volatile private var verboseEnabled: Boolean = false

    private val fmtHm = SimpleDateFormat("HHmm", Locale.US)
    private val fmtHmS = SimpleDateFormat("HHmmss", Locale.US)

    private val NOISE_PREFIXES = arrayOf(
        "send HELLO",
        "received HELLO",
        "received HERE",
        "known_peer saved",
        "continuousDiscover",
        "this device:",
        "discover tick",
        "skip continuous",
        "preflight (silent)",
        "requestPeers \u2192",
    )

    private fun shortTag(tag: String): String = when (tag) {
        "FileSenderService" -> "S"
        "FileReceiver" -> "R"
        "FileReceiverService" -> "RS"
        "WifiDirect" -> "W"
        "MeshNode" -> "N"
        "MainActivity" -> "M"
        "ShareTarget" -> "T"
        "ShareTargetActivity" -> "T"
        "NameSetup" -> "N"
        "UpdateChecker" -> "U"
        "MediaStoreSaver" -> "MS"
        "PowerLocks" -> "P"
        "Widget" -> "Wg"
        "WidgetReceive" -> "Wr"
        "Preflight" -> "P"
        "ApkShare" -> "AS"
        "About" -> "Ab"
        else -> tag.take(2).lowercase()
    }

    private fun compress(msg: String): String {
        var s = msg
        s = s.replace(Regex("""(\d+\.\d+)\.\d+\.\d+"""), "$1.xx.xx")
        s = s.replace(":47822", "")
        s = s.replace(Regex("""(\d{7,}) b""")) {
            val b = it.groupValues[1].toLongOrNull() ?: 0L
            "${b / 1024}KB"
        }
        return s.take(180)
    }

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("atom", Context.MODE_PRIVATE)
        verboseEnabled = prefs?.getBoolean("debug_verbose", false) ?: false
        val base = ctx.getExternalFilesDir("logs") ?: File(ctx.filesDir, "logs")
        base.mkdirs()
        dir = base
    }

    fun setVerbose(enabled: Boolean) {
        verboseEnabled = enabled
        prefs?.edit()?.putBoolean("debug_verbose", enabled)?.apply()
    }

    fun isVerbose(): Boolean = verboseEnabled

    @Synchronized
    fun d(tag: String, msg: String) {
        if (!verboseEnabled) return
        val dd = dir ?: return
        try {
            for (p in NOISE_PREFIXES) {
                if (msg.startsWith(p)) return
            }
            val compressed = compress(msg)
            if (compressed.isBlank()) return
            val t = fmtHm.format(Date())
            val st = shortTag(tag)
            val f = File(dd, "atom.log")
            if (f.exists() && f.length() > MAX_BYTES) rotate()
            f.appendText(t + st + " " + compressed + "\n")
        } catch (_: Exception) {}
    }

    @Synchronized
    fun err(tag: String, msg: String) {
        val dd = dir ?: return
        try {
            val t = fmtHmS.format(Date())
            val st = shortTag(tag)
            val line = t + st + " " + compress(msg) + "\n"
            val f = File(dd, "atom-err.log")
            if (f.exists() && f.length() > MAX_ERR_BYTES) f.delete()
            f.appendText(line)
        } catch (_: Exception) {}
    }

    fun getLogFile(): File? {
        val dd = dir ?: return null
        val f = File(dd, "atom.log")
        return if (f.exists() && f.length() > 0) f else null
    }

    fun getErrFile(): File? {
        val dd = dir ?: return null
        val f = File(dd, "atom-err.log")
        return if (f.exists() && f.length() > 0) f else null
    }

    private fun rotate() {
        val dd = dir ?: return
        for (i in MAX_FILES downTo 2) {
            val src = File(dd, "atom.log.$i")
            val dst = File(dd, "atom.log.${i + 1}")
            if (src.exists()) src.renameTo(dst)
        }
        val cur = File(dd, "atom.log")
        if (cur.exists()) cur.renameTo(File(dd, "atom.log.1"))
    }
}
