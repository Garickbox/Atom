package com.garickbox.atom

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.mesh.core.InAppLogger

object PreflightDialog {

    /**
     * Показывает диалог со списком проблем и кнопкой "Настроить".
     * Если пользователь соглашается — открываются настройки по первой проблеме.
     * После возврата из настроек — onResume должен перепроверить через PreflightChecker.
     *
     * Если issues пусто — сразу onReady().
     * Если только PERMISSIONS — вызывается requestPermissions(), потом onReady().
     */
    fun check(
        activity: Activity,
        onRequestPermissions: (() -> Unit)?,
        onOpenSystemSettings: ((PreflightChecker.Issue) -> Unit)?,
        onReady: () -> Unit
    ) {
        val issues = PreflightChecker.issues(activity)
        InAppLogger.d("Preflight", "issues: ${PreflightChecker.summary(issues)}")

        if (issues.isEmpty()) {
            onReady()
            return
        }

        // Только разрешения — запрашиваем сразу, без диалога
        if (issues.all { it.id == PreflightChecker.Id.PERMISSIONS }) {
            onRequestPermissions?.invoke()
            return
        }

        // Есть системные проблемы — показываем список
        val text = buildString {
            append("Чтобы найти устройство, нужно:\n\n")
            for (i in issues) {
                append("❌ ")
                append(i.title)
                append(" — ")
                append(i.hint)
                append("\n")
            }
        }

        AlertDialog.Builder(activity)
            .setTitle("⚠️ Настройки не готовы")
            .setMessage(text.trimEnd())
            .setPositiveButton("Настроить") { _, _ ->
                // Откроем настройки для первой системной проблемы
                val firstSystemIssue = issues.firstOrNull {
                    it.id != PreflightChecker.Id.PERMISSIONS
                }
                if (firstSystemIssue != null) {
                    onOpenSystemSettings?.invoke(firstSystemIssue)
                } else {
                    onRequestPermissions?.invoke()
                }
            }
            .setNegativeButton("Отмена") { _, _ ->
                InAppLogger.d("Preflight", "user cancelled")
            }
            .show()
    }
}
