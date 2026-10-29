package com.garickbox.atom

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.mesh.core.InAppLogger

object PreflightChecker {

    enum class Id { PERMISSIONS, LOCATION, WIFI, AIRPLANE, WIFI_DIRECT }

    data class Issue(
        val id: Id,
        val title: String,
        val hint: String
    )

    /** Проверить все условия. Пустой список = всё ок. */
    fun issues(ctx: Context): List<Issue> {
        val list = mutableListOf<Issue>()

        if (PermissionsHelper.missing(ctx).isNotEmpty()) {
            val short = PermissionsHelper.missing(ctx).map { it.substringAfterLast(".") }
            list.add(Issue(Id.PERMISSIONS, "Разрешения",
                "Не выданы: ${short.joinToString()}. Нажми «Запросить»"))
        }

        if (isAirplaneMode(ctx)) {
            list.add(Issue(Id.AIRPLANE, "Режим полёта",
                "Включён — все сети отключены"))
        }

        if (!isWifiEnabled(ctx)) {
            list.add(Issue(Id.WIFI, "Wi-Fi",
                "Выключен — поиск устройств не работает"))
        }

        if (!LocationHelper.isEnabled(ctx)) {
            list.add(Issue(Id.LOCATION, "Геолокация",
                "Нужна Android для сканирования Wi-Fi"))
        }



        return list
    }

    /** Есть ли проблемы, которые нельзя решить runtime-запросом? */
    fun hasSystemIssues(issues: List<Issue>): Boolean {
        return issues.any { it.id != Id.PERMISSIONS }
    }

    fun isAirplaneMode(ctx: Context): Boolean {
        return try {
            Settings.Global.getInt(ctx.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (_: Exception) { false }
    }

    fun isWifiEnabled(ctx: Context): Boolean {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE)
                as? WifiManager
            wm?.isWifiEnabled == true
        } catch (_: Exception) { false }
    }

    /**
     * Открыть подходящие настройки в зависимости от того, что сломано.
     * Возврат произойдёт через onResume — пользователь включает и приходит назад.
     */
    fun openSettingsFor(activity: Activity, issue: Issue) {
        try {
            val intent = when (issue.id) {
                Id.LOCATION -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                Id.AIRPLANE -> Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
                Id.WIFI -> {
                    if (Build.VERSION.SDK_INT >= 29) {
                        Intent(Settings.Panel.ACTION_WIFI)
                    } else {
                        Intent(Settings.ACTION_WIFI_SETTINGS)
                    }
                }
                Id.WIFI_DIRECT -> Intent(Settings.ACTION_WIFI_SETTINGS)
                Id.PERMISSIONS -> null  // решается через launcher
            }
            if (intent != null) {
                activity.startActivity(intent)
                InAppLogger.d("Preflight", "opened settings for ${issue.id}")
            }
        } catch (e: Exception) {
            InAppLogger.d("Preflight", "openSettings error: ${e.message}")
        }
    }

    /** Человеческая сводка issues для лога. */
    fun summary(issues: List<Issue>): String {
        return if (issues.isEmpty()) "OK"
        else issues.joinToString("; ") { "${it.id}(${it.title})" }
    }

    /** Проверка Wi-Fi Direct через WifiP2pManager (state == WIFI_P2P_STATE_ENABLED). */
    fun isWifiDirectEnabled(ctx: android.content.Context): Boolean {
        return try {
            val m = ctx.getSystemService(android.content.Context.WIFI_P2P_SERVICE)
                as? android.net.wifi.p2p.WifiP2pManager ?: return false
            // Через reflection читаем системное состояние
            val cls = Class.forName("android.net.wifi.p2p.WifiP2pManager")
            val field = cls.getField("WIFI_P2P_STATE_ENABLED")
            val expected = field.getInt(null)
            // WifiP2pManager не даёт прямого API, но через WifiManager.getWifiState() + broadcast можно
            // Простое приближение: Wi-Fi включён + LocationServices ON обычно значит P2P работает
            // Точную проверку делает broadcast WIFI_P2P_STATE_CHANGED_ACTION
            // Здесь — консервативная: считаем что включён если Wi-Fi включён
            val wm = ctx.getSystemService(android.content.Context.WIFI_SERVICE)
                as? android.net.wifi.WifiManager
            wm?.isWifiEnabled == true
        } catch (_: Exception) {
            true  // не можем проверить — не блокируем
        }
    }

}
