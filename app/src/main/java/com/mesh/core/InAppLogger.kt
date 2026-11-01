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
    @Volatile private var appCtx: Context? = null
    @Volatile private var lastSyncAt: Long = 0L

    @Volatile private var verboseEnabled: Boolean = false

    private val fmtHm = SimpleDateFormat("HHmm", Locale.US)
    private val fmtHmS = SimpleDateFormat("HHmmss", Locale.US)

    // B20.1: verbose пишет ТОЛЬКО эти ключи (белый список)
    private val WHITELIST = arrayOf(
        "accept OK", "busy (", "multi done", "thread done", "thread error",
        "transfer DONE", "transfer START", "tcp OK", "tcp FAIL", "tcp connect",
        "connect initiated", "connected:", "connected isGO",
        "SHA-256", "MISMATCH", "hash(", "MediaStore save",
        "finalize", "watchdog:", "bind error", "onStartCommand",
        "cancel requested", "pause:", "resume:",
        "error", "Error", "fail", "FAIL", "Exception",
        // B22r: discovery диагностика (иначе verbose пустой при P2P проблемах)
        "discover", "reinit", "initialized", "cleanup", "channel",
        "P2P ERROR", "requestPeers", "peers changed",
        // B31: QR/hotspot диагностика (временно до стабилизации)
        "HELLO", "recreate", "probe", "startTransfer", "peer resolved",
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
        // B40: verbose всегда ON (axioma)
        verboseEnabled = true
        val base = ctx.getExternalFilesDir("logs") ?: File(ctx.filesDir, "logs")
        base.mkdirs()
        dir = base
        appCtx = ctx.applicationContext
    }

    /** B40: очистка всех логов при смене версии. */
    @Synchronized
    fun clearAll() {
        val dd = dir ?: return
        try {
            File(dd, "atom.log").delete()
            File(dd, "atom-err.log").delete()
            for (i in 1..MAX_FILES) {
                File(dd, "atom.log.$i").delete()
            }
        } catch (_: Exception) {}
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
            // B40: whitelist убран — пишем всё (verbose всегда ON)
            val compressed = compress(msg)
            if (compressed.isBlank()) return
            val t = fmtHm.format(Date())
            val st = shortTag(tag)
            val f = File(dd, "atom.log")
            if (f.exists() && f.length() > MAX_BYTES) rotate()
            f.appendText(t + st + " " + compressed + "\n")
            throttleSyncToDownloads("atom.log")
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
            throttleSyncToDownloads("atom-err.log")
        } catch (_: Exception) {}
    }

    /** B43 axiom#52: копия лога в Download/Atom/logs — Termux читает без root. */
    private fun throttleSyncToDownloads(name: String) {
        val now = System.currentTimeMillis()
        if (now - lastSyncAt < 5000L) return
        lastSyncAt = now
        val ctx = appCtx ?: return
        val dd = dir ?: return
        val src = File(dd, name)
        if (!src.exists()) return
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val resolver = ctx.contentResolver
                val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val relPath = "Download/Atom/logs/"
                val cursor = resolver.query(collection,
                    arrayOf(android.provider.MediaStore.Downloads._ID),
                    android.provider.MediaStore.Downloads.DISPLAY_NAME + "=? AND " +
                        android.provider.MediaStore.Downloads.RELATIVE_PATH + "=?",
                    arrayOf(name, relPath), null)
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
