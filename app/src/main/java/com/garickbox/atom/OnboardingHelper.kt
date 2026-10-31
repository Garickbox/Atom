package com.garickbox.atom

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.mesh.core.InAppLogger

/**
 * B21: единый onboarding при первом запуске.
 * Шаги:
 *   0. Runtime permissions (вызывающий запускает launcher)
 *   1. Battery whitelist
 *   2. Install unknown apps
 *   3. HyperOS autostart (только Xiaomi/Redmi/POCO)
 *   4. done → onboarding_done=true
 *
 * После каждого шага — step сохраняется ДО открытия настроек,
 * чтобы при возврате продолжить со следующего (иначе цикл).
 */
object OnboardingHelper {

    private const val PREF_STEP = "onboarding_step"
    private const val PREF_DONE = "onboarding_done"

    fun isDone(ctx: android.content.Context): Boolean =
        ctx.getSharedPreferences("atom", android.content.Context.MODE_PRIVATE)
            .getBoolean(PREF_DONE, false)

    private fun getStep(ctx: android.content.Context): Int =
        ctx.getSharedPreferences("atom", android.content.Context.MODE_PRIVATE)
            .getInt(PREF_STEP, 0)

    private fun setStep(ctx: android.content.Context, step: Int) {
        ctx.getSharedPreferences("atom", android.content.Context.MODE_PRIVATE)
            .edit().putInt(PREF_STEP, step).apply()
    }

    private fun markDone(ctx: android.content.Context) {
        ctx.getSharedPreferences("atom", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_DONE, true).putInt(PREF_STEP, 5).apply()
    }

    private fun isHyperOs(): Boolean = try {
        val b = (android.os.Build.BRAND ?: "").lowercase()
        val m = (android.os.Build.MANUFACTURER ?: "").lowercase()
        b in listOf("xiaomi", "redmi", "poco") || m in listOf("xiaomi", "redmi", "poco")
    } catch (_: Exception) { false }

    /**
     * Продолжить onboarding. Если нужны permissions — вызывает onNeedPermissions(missing).
     * Если шаг = диалог — показывает диалог. При done — Toast "Готово".
     */
    fun next(activity: Activity, onNeedPermissions: (List<String>) -> Unit) {
        if (isDone(activity)) return
        if (!AtomController.hasName()) return

        var step = getStep(activity)
        InAppLogger.d("Onboarding", "step=$step")

        when (step) {
            0 -> {
                val missing = PermissionsHelper.missing(activity)
                if (missing.isEmpty()) {
                    setStep(activity, 1)
                    next(activity, onNeedPermissions)
                    return
                }
                onNeedPermissions(missing)
            }
            1 -> {
                if (BatteryOptimizationHelper.isWhitelisted(activity)) {
                    setStep(activity, 2); next(activity, onNeedPermissions); return
                }
                AlertDialog.Builder(activity)
                    .setTitle("🔋 Фоновая работа")
                    .setMessage("Чтобы приём файлов не прерывался при выключенном экране — " +
                        "разреши Атому работать в фоне без ограничений.")
                    .setPositiveButton("Разрешить") { _, _ ->
                        setStep(activity, 2)
                        try { BatteryOptimizationHelper.requestWhitelist(activity) } catch (_: Exception) {}
                    }
                    .setNegativeButton("Позже") { _, _ ->
                        setStep(activity, 2); next(activity, onNeedPermissions)
                    }
                    .setCancelable(false)
                    .show()
            }
            2 -> {
                if (android.os.Build.VERSION.SDK_INT < 26 || UpdateChecker.canInstall(activity)) {
                    setStep(activity, 3); next(activity, onNeedPermissions); return
                }
                AlertDialog.Builder(activity)
                    .setTitle("📦 Установка обновлений")
                    .setMessage("Разреши Атому устанавливать APK — иначе автообновление " +
                        "и приём обновлений через Атом не работают.")
                    .setPositiveButton("Разрешить") { _, _ ->
                        setStep(activity, 3)
                        try {
                            activity.startActivity(Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")))
                        } catch (_: Exception) {}
                    }
                    .setNegativeButton("Позже") { _, _ ->
                        setStep(activity, 3); next(activity, onNeedPermissions)
                    }
                    .setCancelable(false)
                    .show()
            }
            3 -> {
                if (!isHyperOs()) {
                    setStep(activity, 4); next(activity, onNeedPermissions); return
                }
                val shown = activity.getSharedPreferences("atom", android.content.Context.MODE_PRIVATE)
                    .getBoolean("hyperos_autostart_prompt_shown", false)
                if (shown) {
                    setStep(activity, 4); next(activity, onNeedPermissions); return
                }
                AlertDialog.Builder(activity)
                    .setTitle("🚀 Автозапуск (HyperOS)")
                    .setMessage("На Xiaomi/Redmi/POCO нужно включить Автозапуск, " +
                        "иначе виджет и приём в фоне не работают.\n\n" +
                        "Настройки → Приложения → Атом → Автозапуск → Вкл")
                    .setPositiveButton("Открыть") { _, _ ->
                        activity.getSharedPreferences("atom", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("hyperos_autostart_prompt_shown", true).apply()
                        setStep(activity, 4)
                        try {
                            activity.startActivity(Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${activity.packageName}")))
                        } catch (_: Exception) {}
                    }
                    .setNegativeButton("Позже") { _, _ ->
                        activity.getSharedPreferences("atom", android.content.Context.MODE_PRIVATE)
                            .edit().putBoolean("hyperos_autostart_prompt_shown", true).apply()
                        setStep(activity, 4); next(activity, onNeedPermissions)
                    }
                    .setCancelable(false)
                    .show()
            }
            else -> {
                markDone(activity)
                Toast.makeText(activity, "✅ Атом готов к работе", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
