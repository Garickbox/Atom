package com.garickbox.atom

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.mesh.core.InAppLogger

object AboutDialog {

    const val GITHUB_PAGE = "https://github.com/Garickbox/Atom"
    const val LATEST_RELEASE = "https://github.com/Garickbox/Atom/releases/latest/download/Atom.apk"

    fun show(activity: Activity) {
        val version = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"

        val message = """
            ⚛️  Атом — быстрая передача файлов между Android

            📦  Версия: $version

            Что умеет:
            •  Передача файлов и папок без проводов
            •  Wi-Fi Direct — работает без роутера
            •  Скорость до 100 МБ/с
            •  Фоновая работа — можно закрыть и не ждать
            •  Бесплатно, с открытым кодом

            🔗  github.com/Garickbox/Atom
        """.trimIndent()

        val apkUri = ApkShareHelper.findLatestApk(activity)

        AlertDialog.Builder(activity)
            .setTitle("⚛️ Атом")
            .setMessage(message)
            .setPositiveButton("📤 Поделиться") { _, _ ->
                if (apkUri != null) {
                    showShareChoice(activity, apkUri)
                } else {
                    shareLink(activity)
                }
            }
            .setNeutralButton("🌐 GitHub") { _, _ -> openGitHub(activity) }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showShareChoice(activity: Activity, apkUri: Uri) {
        AlertDialog.Builder(activity)
            .setTitle("Поделиться")
            .setItems(arrayOf(
                "📦 APK-файлом (build ${BuildConfig.VERSION_CODE})",
                "📤 Ссылкой на GitHub"
            )) { _, which ->
                when (which) {
                    0 -> ApkShareHelper.shareApk(activity, apkUri)
                    1 -> shareLink(activity)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun shareLink(activity: Activity) {
        val text = """
            ⚛️ Атом — передача файлов между Android за секунды

            ✅  Wi-Fi Direct — без роутера и интернета
            ✅  Папки, фото, видео — любые файлы
            ✅  Скорость до 100 МБ/с
            ✅  Всё напрямую — никаких облаков
            ✅  Бесплатно, открытый исходный код

            📲 Скачать APK:
            $LATEST_RELEASE

            ⚡ Установка ~30 сек. Если система спросит —
               разреши установку из этого источника.
        """.trimIndent()

        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "⚛️ Атом — быстрая передача файлов")
            }
            activity.startActivity(Intent.createChooser(intent, "Поделиться ссылкой"))
            InAppLogger.d("About", "share chooser opened")
        } catch (e: Exception) {
            InAppLogger.d("About", "share error: ${e.message}")
            Toast.makeText(activity,
                "Не удалось открыть chooser", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGitHub(activity: Activity) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_PAGE)))
            InAppLogger.d("About", "github opened")
        } catch (e: Exception) {
            InAppLogger.d("About", "github error: ${e.message}")
            Toast.makeText(activity,
                "Не удалось открыть браузер", Toast.LENGTH_SHORT).show()
        }
    }
}
