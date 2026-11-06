package com.mesh.core

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * B227 axiom#409: ONE log file atom.log. Теги [ERR][NFC][CMP][USER][STATE][LIFE][SEND][RECV][QR][P2P][PREF].
 * axiom#410: whitelist удалён — всё пишется (этап диагностики).
 */
object InAppLogger {

    private const val MAX_BYTES = 1_000_000L  // 1MB, rotate at 1MB

    private var dir: File? = null
    private var prefs: SharedPreferences? = null
    @Volatile private var verboseEnabled: Boolean = true

    private val fmtMs = SimpleDateFormat("HHmmss.SSS", Locale.US)

    private fun shortTag(tag: String): String = when (tag) {
        "FileSenderService" -> "SNDR"; "FileReceiver" -> "RCVR"; "FileReceiverService" -> "RCVS"
        "WifiDirect" -> "P2P"; "MeshNode" -> "MESH"; "MainActivity" -> "MAIN"
        "MainScreen" -> "SCRN"
        "ShareTarget" -> "SHAR"; "ShareTargetActivity" -> "SHAR"
        "UpdateChecker" -> "UPD"; "MediaStoreSaver" -> "MSSV"
        "WidgetUltra" -> "WU"; "WidgetCompact" -> "WC"; "AtomQSTile" -> "QS"
        "NfcReader" -> "NFC"; "NfcReaderActivity" -> "NFC"; "NfcHce" -> "NFC"
        "QrScan" -> "QR"; "QrScanActivity" -> "QR"; "QrShare" -> "QRSH"
        "Preflight" -> "PRFL"; "Onboarding" -> "ONBD"
        "NameSetup" -> "NAME"; "LogViewer" -> "LOGS"; "Settings" -> "SETG"
        "History" -> "HIST"; "ApkShare" -> "APKS"; "About" -> "ABOT"
        else -> tag.take(4).uppercase()
    }

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("atom", Context.MODE_PRIVATE)
        verboseEnabled = true
        val base = ctx.getExternalFilesDir("logs") ?: File(ctx.filesDir, "logs")
        base.mkdirs()
        dir = base
    }

    fun setVerbose(enabled: Boolean) { verboseEnabled = enabled }
    fun isVerbose(): Boolean = verboseEnabled

    private fun append(tag: String, msg: String) {
        if (!verboseEnabled) return
        val dd = dir ?: return
        try {
            val t = fmtMs.format(Date())
            val st = shortTag(tag)
            val line = "$t [$st] ${msg.take(500)}\n"
            val f = File(dd, "atom.log")
            if (f.exists() && f.length() > MAX_BYTES) rotate()
            f.appendText(line)
        } catch (_: Exception) {}
    }

    // ─── base ───
    fun d(tag: String, msg: String) = append(tag, msg)

    // ─── tagged (axiom#409) ───
    fun lifecycle(msg: String) = append("LIFE", msg)
    fun user(msg: String) = append("USER", msg)
    fun state(msg: String) = append("STATE", msg)
    fun send(msg: String) = append("SEND", msg)
    fun recv(msg: String) = append("RECV", msg)
    fun nfc(tag: String, msg: String) = append("NFC", "$tag $msg")
    fun qr(msg: String) = append("QR", msg)
    fun p2p(msg: String) = append("P2P", msg)
    fun compact(msg: String) = append("CMP", msg)
    fun err(tag: String, msg: String) = append("ERR", "$tag $msg")
    fun mark(tag: String) = append("MARK", tag)

    // ─── backward-compat: nfc/err/compact ранее писали в отдельные файлы ───
    fun getLogFile(): File? {
        val dd = dir ?: return null
        val f = File(dd, "atom.log")
        return if (f.exists() && f.length() > 0) f else null
    }
    fun getNfcLogFile(): File? = getLogFile()  // backward-compat (axiom#411)
    fun getErrFile(): File? = getLogFile()     // backward-compat (axiom#411)

    private fun rotate() {
        val dd = dir ?: return
        val cur = File(dd, "atom.log")
        if (cur.exists()) cur.renameTo(File(dd, "atom.log.1"))
    }
}
