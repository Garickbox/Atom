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
        return try {
            val type = mimeType ?: guessMime(fileName)
            val category = categoryFor(type)
            val relPath = "$ROOT_DIR/$category"

            if (Build.VERSION.SDK_INT >= 29) {
                saveModern(ctx, source, fileName, type, relPath)
            } else {
                saveLegacy(ctx, source, fileName, relPath)
            }
        } catch (e: Exception) {
            InAppLogger.d("MediaStoreSaver", "error: ${e.message}")
            SaveResult(null, "")
        }
    }

    private fun saveModern(
        ctx: Context, source: File, fileName: String,
        mime: String, relPath: String
    ): SaveResult {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return SaveResult(null, "")
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { input -> input.copyTo(out, 128 * 1024) }
        } ?: return SaveResult(null, "")
        val path = "$relPath/$fileName"
        InAppLogger.d("MediaStoreSaver", "saved: $path ($mime)")
        return SaveResult(uri, path)
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        ctx: Context, source: File, fileName: String, relPath: String
    ): SaveResult {
        val downloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        // relPath = "Download/Atom/Pictures" → "Pictures"
        val category = relPath.substringAfter("Atom/", "Documents")
        val dir = File(downloads, "Atom/$category")
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, fileName)
        source.copyTo(target, overwrite = true)
        android.media.MediaScannerConnection.scanFile(
            ctx, arrayOf(target.absolutePath), null, null
        )
        InAppLogger.d("MediaStoreSaver", "saved (legacy): ${target.absolutePath}")
        return SaveResult(null, target.absolutePath)
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
