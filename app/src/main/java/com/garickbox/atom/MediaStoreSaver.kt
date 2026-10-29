package com.garickbox.atom

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.mesh.core.InAppLogger
import java.io.File

object MediaStoreSaver {

    data class SaveResult(val uri: Uri?, val displayPath: String)

    /** Сохранить файл в публичное место (Downloads / Pictures / Movies / Music). */
    fun save(ctx: Context, source: File, fileName: String, mimeType: String?): SaveResult {
        return try {
            val type = mimeType ?: guessMime(fileName)
            val (collection, subDir) = pickCollection(type)

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, type)
                if (Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, subDir)
                }
            }

            val uri = ctx.contentResolver.insert(collection, values)
                ?: return SaveResult(null, "")

            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input ->
                    input.copyTo(out, 128 * 1024)
                }
            } ?: return SaveResult(null, "")

            val path = "$subDir/$fileName"
            InAppLogger.d("MediaStoreSaver", "saved: $path ($type)")
            SaveResult(uri, path)
        } catch (e: Exception) {
            InAppLogger.d("MediaStoreSaver", "error: ${e.message}")
            SaveResult(null, "")
        }
    }

    private fun pickCollection(mime: String): Pair<Uri, String> {
        return when {
            mime.startsWith("image/") ->
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "Pictures/Atom"
            mime.startsWith("video/") ->
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "Movies/Atom"
            mime.startsWith("audio/") ->
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI to "Music/Atom"
            else ->
                MediaStore.Downloads.EXTERNAL_CONTENT_URI to "Download/Atom"
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
