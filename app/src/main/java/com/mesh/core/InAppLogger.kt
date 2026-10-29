package com.mesh.core

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object InAppLogger {

    private const val MAX_BYTES = 1_000_000L
    private const val MAX_FILES = 3

    private var dir: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun getLogFile(): File? {
        val d = dir ?: return null
        val f = File(d, "atom.log")
        return if (f.exists()) f else null
    }

    fun init(ctx: Context) {
        val base = ctx.getExternalFilesDir("logs") ?: File(ctx.filesDir, "logs")
        if (!base.exists()) base.mkdirs()
        dir = base
    }

    @Synchronized
    fun d(tag: String, msg: String) {
        val d = dir ?: return
        try {
            val f = File(d, "atom.log")
            if (f.exists() && f.length() > MAX_BYTES) rotate(d)
            f.appendText("${fmt.format(Date())} [$tag] $msg\n")
        } catch (_: Exception) {}
    }

    private fun rotate(d: File) {
        for (i in MAX_FILES downTo 2) {
            val src = File(d, "atom.log.$i")
            val dst = File(d, "atom.log.${i + 1}")
            if (src.exists()) src.renameTo(dst)
        }
        val cur = File(d, "atom.log")
        if (cur.exists()) cur.renameTo(File(d, "atom.log.1"))
    }
}
