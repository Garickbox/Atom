package com.garickbox.atom

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.mesh.core.InAppLogger
import java.io.File

/**
 * Единый корень: Download/Atom
 * Внутри — категории: Pictures / Movies / Music / Documents
 *
 * API 29+: MediaStore.Downloads с RELATIVE_PATH (без разрешений)
 * API < 29: прямая запись в /sdcard/Download/Atom/<category>/ + MediaScanner
 */
object MediaStoreSaver {

    private const val ROOT_DIR = "Download/Atom"

    data class SaveResult(val uri: Uri?, val displayPath: String)

    fun save(ctx: Context, source: File, fileName: String, mimeType: String?): SaveResult {
        val type = mimeType ?: guessMime(fileName)
        val category = categoryFor(type)
        val relPath = "$ROOT_DIR/$category"

        // B10a: MediaStore.Downloads на некоторых TV не создаёт RELATIVE_PATH поддиректории.
        // При любой ошибке saveModern → fallback на прямую запись saveLegacy.
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val r = saveModern(ctx, source, fileName, type, relPath)
                if (r.uri != null) return r
                InAppLogger.d("MediaStoreSaver", "saveModern: null uri → fallback to legacy")
            } catch (e: Exception) {
                InAppLogger.d("MediaStoreSaver", "saveModern error: ${e.message} → fallback to legacy")
            }
        }

        return try {
            saveLegacy(ctx, source, fileName, relPath)
        } catch (e: Exception) {
            InAppLogger.d("MediaStoreSaver", "saveLegacy error: ${e.message}")
            SaveResult(null, "")
        }
    }

    private fun saveModern(
        ctx: Context, source: File, fileName: String,
        mime: String, relPath: String
    ): SaveResult {
        // B11.3: правильный MediaStore-URI по типу.
        // Downloads не создаёт подкаталоги на некоторых TV (API 29-30).
        // Video/Audio/Images работают везде.
        val sdk = android.os.Build.VERSION.SDK_INT
        val (collection, realRelPath) = when {
            mime.startsWith("video/") ->
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Movies/Atom/"
            mime.startsWith("audio/") ->
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "Music/Atom/"
            mime.startsWith("image/") ->
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/Atom/"
            else ->
                MediaStore.Downloads.EXTERNAL_CONTENT_URI to "Download/Atom/Documents/"
        }
        InAppLogger.d("MediaStoreSaver",
            "saveModern: sdk=$sdk file=$fileName mime=$mime rel=$realRelPath")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, realRelPath)
        }
        val uri = ctx.contentResolver.insert(collection, values)
        if (uri == null) {
            InAppLogger.d("MediaStoreSaver", "saveModern: insert null")
            return SaveResult(null, "")
        }
        InAppLogger.d("MediaStoreSaver", "saveModern: insert ok $uri")
        val out = ctx.contentResolver.openOutputStream(uri)
        if (out == null) {
            InAppLogger.d("MediaStoreSaver", "saveModern: openOutputStream null")
            try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            return SaveResult(null, "")
        }
        try {
            out.use { o ->
                source.inputStream().use { input -> input.copyTo(o, 128 * 1024) }
            }
        } catch (e: Exception) {
            InAppLogger.d("MediaStoreSaver", "saveModern: copy error: ${e.message}")
            try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            return SaveResult(null, "")
        }
        val path = "$realRelPath$fileName"
        InAppLogger.d("MediaStoreSaver", "saveModern: saved $path")
        return SaveResult(uri, path)
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        ctx: Context, source: File, fileName: String, relPath: String
    ): SaveResult {
        val sdk = android.os.Build.VERSION.SDK_INT
        val category = relPath.substringAfter("Atom/", "Documents").trimEnd('/')
        // Сначала пробуем public Downloads
        try {
            val downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val dir = File(downloads, "Atom/$category")
            if (!dir.exists()) {
                val ok = dir.mkdirs()
                InAppLogger.d("MediaStoreSaver",
                    "saveLegacy: mkdirs=$ok path=${dir.absolutePath}")
            }
            val target = File(dir, fileName)
            source.copyTo(target, overwrite = true)
            android.media.MediaScannerConnection.scanFile(
                ctx, arrayOf(target.absolutePath), null, null
            )
            InAppLogger.d("MediaStoreSaver", "saved (legacy public): ${target.absolutePath}")
            return SaveResult(null, target.absolutePath)
        } catch (e: Exception) {
            InAppLogger.d("MediaStoreSaver",
                "saveLegacy public error (sdk=$sdk): ${e.message} → fallback app-specific")
        }
        // Fallback: app-specific Download — доступно всегда, без permissions
        try {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Atom/$category")
            if (!dir.exists()) dir.mkdirs()
            val target = File(dir, fileName)
            source.copyTo(target, overwrite = true)
            InAppLogger.d("MediaStoreSaver", "saved (app-specific): ${target.absolutePath}")
            return SaveResult(null, target.absolutePath)
        } catch (e: Exception) {
            InAppLogger.d("MediaStoreSaver", "saveLegacy app-specific error: ${e.message}")
            return SaveResult(null, "")
        }
    }

    /** Категория внутри Download/Atom по MIME */
    private fun categoryFor(mime: String): String = when {
        mime.startsWith("image/") -> "Pictures"
        mime.startsWith("video/") -> "Movies"
        mime.startsWith("audio/") -> "Music"
        else -> "Documents"
    }

    /** Расширенный MIME — 60+ расширений */
    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            // ─── Images ───
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "tiff", "tif" -> "image/tiff"
            "ico" -> "image/x-icon"
            "avif" -> "image/avif"
            // ─── Videos ───
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            "webm" -> "video/webm"
            "ts" -> "video/mp2t"
            "3gp" -> "video/3gpp"
            "mpeg", "mpg" -> "video/mpeg"
            "m4v" -> "video/x-m4v"
            "ogv" -> "video/ogg"
            // ─── Audio ───
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "opus" -> "audio/opus"
            "wma" -> "audio/x-ms-wma"
            "amr" -> "audio/amr"
            "aiff", "aif" -> "audio/x-aiff"
            "m4b" -> "audio/mp4"
            // ─── Documents ───
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "odt" -> "application/vnd.oasis.opendocument.text"
            "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
            "odp" -> "application/vnd.oasis.opendocument.presentation"
            "rtf" -> "application/rtf"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            "md" -> "text/markdown"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "kt" -> "text/plain"
            "java" -> "text/plain"
            "py" -> "text/plain"
            // ─── Archives ───
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz" -> "application/gzip"
            "bz2" -> "application/x-bzip2"
            // ─── APK ───
            "apk" -> "application/vnd.android.package-archive"
            // ─── Fallback ───
            else -> "application/octet-stream"
        }
    }
}
