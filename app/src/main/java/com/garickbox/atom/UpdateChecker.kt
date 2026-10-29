package com.garickbox.atom

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.mesh.core.InAppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object UpdateChecker {

    private const val REPO = "Garickbox/Atom"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val NOTIF_ID = 2001

    data class Release(val buildNumber: Int, val tag: String, val apkUrl: String, val apkName: String)

    /** Ожидание разрешения на установку — если пользователь ушёл в Settings. */
    private var pendingRelease: Release? = null

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
            val current = currentVersionCode(context)
            if (buildNum <= current) return@withContext null
            val assets = json.getJSONArray("assets")
            if (assets.length() == 0) return@withContext null
            val asset = assets.getJSONObject(0)
            val url = asset.getString("browser_download_url")
            val name = asset.getString("name")
            Release(buildNum, tag, url, name)
        } catch (e: Exception) {
            InAppLogger.d("UpdateChecker", "check error: ${e.message}")
            null
        }
    }

    fun promptAndInstall(activity: Activity, release: Release) {
        val cur = currentVersionCode(activity)
        AlertDialog.Builder(activity)
            .setTitle("Доступно обновление")
            .setMessage("Новая: ${release.tag}\nТекущая: build-$cur\n\nПосле скачивания откроется системный установщик.")
            .setPositiveButton("Обновить") { _, _ ->
                startOrRequest(activity, release)
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    /**
     * Старт скачивания. Если нет разрешения — запоминаем release и уходим в Settings,
     * после возврата onResume вызовет checkPending() и продолжит.
     */
    fun startOrRequest(activity: Activity, release: Release) {
        if (Build.VERSION.SDK_INT >= 26 && !canInstall(activity)) {
            pendingRelease = release
            InAppLogger.d("UpdateChecker", "no install permission, opening settings")
            requestInstallPermission(activity)
            return
        }
        pendingRelease = null
        downloadAndInstall(activity, release)
    }

    /**
     * Вызывать из MainActivity.onResume — если ожидали разрешение и оно выдано, стартуем.
     */
    fun checkPending(activity: Activity) {
        val pending = pendingRelease ?: return
        if (Build.VERSION.SDK_INT >= 26 && !canInstall(activity)) {
            return // разрешение ещё не выдано
        }
        pendingRelease = null
        InAppLogger.d("UpdateChecker", "pending release resumed: ${pending.tag}")
        downloadAndInstall(activity, pending)
    }

    private fun downloadAndInstall(activity: Activity, release: Release) {
        InAppLogger.d("UpdateChecker", "download start: ${release.apkUrl}")
        Toast.makeText(activity, "Скачиваю ${release.tag}…", Toast.LENGTH_SHORT).show()
        showNotification(activity, release, 0, 0, true)

        Thread {
            try {
                val dir = File(activity.cacheDir, "apk").apply { mkdirs() }
                val out = File(dir, "atom-${release.tag}.apk")
                if (out.exists()) out.delete()

                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(release.apkUrl)
                    .header("User-Agent", "Atom-Android")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IllegalStateException("empty body")
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(out).use { output ->
                            val buf = ByteArray(64 * 1024)
                            var downloaded = 0L
                            var lastUpdate = 0L
                            while (true) {
                                val read = input.read(buf)
                                if (read < 0) break
                                output.write(buf, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastUpdate > 300) {
                                    lastUpdate = now
                                    showNotification(activity, release, downloaded, total, false)
                                }
                            }
                            InAppLogger.d("UpdateChecker",
                                "download done: ${out.length()} bytes → ${out.absolutePath}")
                        }
                    }
                }
                hideNotification(activity)
                activity.runOnUiThread {
                    Toast.makeText(activity, "Скачано. Открываю установщик…", Toast.LENGTH_SHORT).show()
                    installApk(activity, out)
                }
            } catch (e: Exception) {
                InAppLogger.d("UpdateChecker", "download error: ${e.message}")
                hideNotification(activity)
                activity.runOnUiThread {
                    Toast.makeText(activity, "Ошибка загрузки: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun showNotification(
        activity: Activity,
        release: Release,
        downloaded: Long,
        total: Long,
        indeterminate: Boolean
    ) {
        val pct = if (total > 0) ((downloaded * 100) / total).toInt() else 0
        val text = if (total > 0) "${formatBytes(downloaded)} / ${formatBytes(total)}"
                   else formatBytes(downloaded)
        val notif = NotificationCompat.Builder(activity, NotificationHelper.CHANNEL_TRANSFER)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Скачиваю обновление ${release.tag}")
            .setContentText(text)
            .setProgress(100, pct, indeterminate || total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        try {
            NotificationManagerCompat.from(activity).notify(NOTIF_ID, notif)
        } catch (e: SecurityException) {
            InAppLogger.d("UpdateChecker", "notif denied: ${e.message}")
        }
    }

    private fun hideNotification(activity: Activity) {
        try {
            NotificationManagerCompat.from(activity).cancel(NOTIF_ID)
        } catch (_: Exception) {}
    }

    fun canInstall(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 26) {
            ctx.packageManager.canRequestPackageInstalls()
        } else true
    }

    private fun requestInstallPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 26) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"))
            activity.startActivity(intent)
            Toast.makeText(activity,
                "Разрешите установку — скачивание начнётся автоматически при возврате",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun installApk(ctx: Context, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            InAppLogger.d("UpdateChecker", "installer started")
        } catch (e: Exception) {
            InAppLogger.d("UpdateChecker", "install error: ${e.message}")
            Toast.makeText(ctx, "Не удалось открыть установщик: ${e.message}",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun currentVersionCode(ctx: Context): Int {
        return try {
            val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode.toInt() else pkg.versionCode
        } catch (_: Exception) { 0 }
    }

    private fun formatBytes(b: Long): String {
        if (b < 1024) return "$b Б"
        if (b < 1024 * 1024) return "%.1f КБ".format(b / 1024.0)
        if (b < 1024 * 1024 * 1024) return "%.1f МБ".format(b / 1024.0 / 1024.0)
        return "%.1f ГБ".format(b / 1024.0 / 1024.0 / 1024.0)
    }
}
