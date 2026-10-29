package com.garickbox.atom

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.mesh.core.InAppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object UpdateChecker {

    private const val REPO = "Garickbox/Atom"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    data class Release(val buildNumber: Int, val tag: String, val apkUrl: String, val apkName: String)

    suspend fun checkForUpdate(context: Context): Release? = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            val req = Request.Builder()
                .url(API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Atom-Android")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                InAppLogger.d("UpdateChecker", "api http ${resp.code}")
                return@withContext null
            }
            val body = resp.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            val tag = json.getString("tag_name")
            val buildNum = tag.removePrefix("build-").toIntOrNull() ?: return@withContext null
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            val current = if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode.toInt() else pkg.versionCode
            if (buildNum <= current) return@withContext null
            val assets = json.getJSONArray("assets")
            if (assets.length() == 0) return@withContext null
            val asset = assets.getJSONObject(0)
            val url = asset.getString("browser_download_url")
            val name = asset.getString("name")
            Release(buildNum, tag, url, name)
        } catch (e: Exception) {
            InAppLogger.d("UpdateChecker", "error: ${e.message}")
            null
        }
    }

    fun promptAndInstall(activity: Activity, release: Release) {
        AlertDialog.Builder(activity)
            .setTitle("Доступно обновление")
            .setMessage("Сборка ${release.tag}\nТекущая версия будет обновлена.")
            .setPositiveButton("Обновить") { _, _ ->
                downloadAndInstall(activity, release)
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun downloadAndInstall(activity: Activity, release: Release) {
        if (Build.VERSION.SDK_INT >= 26 && !canInstall(activity)) {
            requestInstallPermission(activity)
            return
        }
        Thread {
            try {
                val dir = File(activity.cacheDir, "apk").apply { mkdirs() }
                val out = File(dir, "atom-${release.tag}.apk")
                val client = OkHttpClient()
                val req = Request.Builder().url(release.apkUrl).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    resp.body?.byteStream()?.use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    }
                }
                activity.runOnUiThread { installApk(activity, out) }
            } catch (e: Exception) {
                InAppLogger.d("UpdateChecker", "download error: ${e.message}")
                activity.runOnUiThread {
                    android.widget.Toast.makeText(activity, "Ошибка загрузки: ${e.message}",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun canInstall(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 26) {
            return ctx.packageManager.canRequestPackageInstalls()
        }
        return true
    }

    private fun requestInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 26) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"))
            activity.startActivity(intent)
            android.widget.Toast.makeText(activity,
                "Разрешите установку и нажмите Обновить ещё раз",
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun installApk(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }
}
