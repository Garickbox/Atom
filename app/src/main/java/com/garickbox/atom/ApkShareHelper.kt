package com.garickbox.atom

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import com.mesh.core.InAppLogger

object ApkShareHelper {

    /** Ищет последний Atom*.apk в Download/Atom (через MediaStore.Downloads). */
    fun findLatestApk(ctx: Context): Uri? {
        return try {
            val projection = arrayOf(MediaStore.Downloads._ID)
            val selection = MediaStore.Downloads.DISPLAY_NAME + " LIKE ?"
            val args = arrayOf("Atom%.apk")
            val sort = MediaStore.Downloads.DATE_ADDED + " DESC"
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection, selection, args, sort
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                    )
                    InAppLogger.d("ApkShare", "found apk: $uri")
                    return uri
                }
            }
            InAppLogger.d("ApkShare", "no apk found in Downloads")
            null
        } catch (e: Exception) {
            InAppLogger.d("ApkShare", "findLatestApk error: ${e.message}")
            null
        }
    }

    /** System chooser — Атом, Telegram, Drive, Bluetooth, всё что умеет apk. */
    fun shareApk(activity: Activity, uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.android.package-archive"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Поделиться APK"))
            InAppLogger.d("ApkShare", "chooser opened")
        } catch (e: Exception) {
            InAppLogger.d("ApkShare", "share error: ${e.message}")
            Toast.makeText(activity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
