package com.garickbox.atom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import com.mesh.core.InAppLogger

object SelfTestRunner {

    data class TestResult(val name: String, val passed: Boolean, val detail: String)

    private const val PORT = 47822

    suspend fun runAll(ctx: Context): List<TestResult> = withContext(Dispatchers.IO) {
        listOf(
            checkPermissions(ctx),
            checkPorts(),
            checkStorage(),
            checkOta(),
            checkFgs(ctx),
            checkWifiEnabled(ctx),
            checkAirplane(ctx),
            checkLocationEnabled(ctx),
            checkBattery(ctx),
            checkP2p(ctx),
            checkP2pStale(ctx),
            checkHotspotCapability(ctx)
        )
    }

    private fun checkPermissions(ctx: Context): TestResult {
        val missing = PermissionsHelper.missing(ctx)
        if (missing.isEmpty()) {
            return TestResult("Разрешения", true, "Все выданы")
        }
        val shortNames = missing.map { it.substringAfterLast(".") }
        return TestResult(
            "Разрешения",
            false,
            "Не выданы: ${shortNames.joinToString()} (настройки → Атом → Разрешения)"
        )
    }

    private fun checkPorts(): TestResult {
        val node = AtomController.meshNode
        if (node != null && node.isRunning) {
            return TestResult("Порты $PORT", true, "Активны (MeshNode слушает)")
        }
        return try {
            val udp = DatagramSocket(PORT)
            udp.close()
            val tcp = ServerSocket(PORT)
            tcp.close()
            TestResult("Порты $PORT", true, "Свободны (запустятся при работе)")
        } catch (e: Exception) {
            TestResult("Порты $PORT", false, "Занято другим приложением: ${e.message}")
        }
    }

    private fun checkStorage(): TestResult {
        val dir = Environment.getExternalStorageDirectory()
        val free = dir.usableSpace / (1024 * 1024)
        return if (free > 500) TestResult("Хранилище", true, "Свободно $free МБ")
        else TestResult("Хранилище", false, "Мало места: $free МБ (нужно >500)")
    }

    private fun checkOta(): TestResult {
        return try {
            val url = URL("https://api.github.com/repos/Garickbox/Atom/releases/latest")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299 || code == 404) TestResult("OTA (GitHub)", true, "API доступен (HTTP $code)")
            else TestResult("OTA (GitHub)", false, "HTTP $code")
        } catch (e: Exception) {
            TestResult("OTA (GitHub)", false, "Сеть: ${e.message}")
        }
    }

    private fun checkFgs(ctx: Context): TestResult {
        return if (Build.VERSION.SDK_INT >= 33) {
            val granted = (ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED)
            if (granted) TestResult("FGS/уведомления", true, "Разрешение есть")
            else TestResult("FGS/уведомления", false, "POST_NOTIFICATIONS не выдано")
        } else {
            TestResult("FGS/уведомления", true, "Не требуется (API<33)")
        }
    }

    private fun checkP2p(ctx: Context): TestResult {
        return try {
            val m = ctx.getSystemService(Context.WIFI_P2P_SERVICE)
                as? android.net.wifi.p2p.WifiP2pManager
            if (m == null) {
                TestResult("Wi-Fi Direct", false, "Не поддерживается устройством")
            } else {
                val wd = AtomController.wifiDirect
                if (wd != null && wd.isP2pEnabled()) {
                    TestResult("Wi-Fi Direct", true, "Поддерживается")
                } else {
                    TestResult("Wi-Fi Direct", false, "Manager доступен, но инициализация не удалась")
                }
            }
        } catch (e: Exception) {
            TestResult("Wi-Fi Direct", false, "Ошибка: ${e.message}")
        }
    }


    private fun checkLocationEnabled(ctx: Context): TestResult {
        return if (LocationHelper.isEnabled(ctx)) {
            TestResult("Геолокация", true, "Включена")
        } else {
            TestResult("Геолокация", false,
                "Выключена — Wi-Fi Direct не найдёт устройства. " +
                "Включи в шторке (иконка геолокации)")
        }
    }


    private fun checkWifiEnabled(ctx: Context): TestResult {
        return if (PreflightChecker.isWifiEnabled(ctx)) {
            TestResult("Wi-Fi", true, "Включён")
        } else {
            TestResult("Wi-Fi", false,
                "Выключен — включи в шторке (иконка Wi-Fi)")
        }
    }

    private fun checkAirplane(ctx: Context): TestResult {
        return if (!PreflightChecker.isAirplaneMode(ctx)) {
            TestResult("Режим полёта", true, "Выключен")
        } else {
            TestResult("Режим полёта", false,
                "Включён — выключи в шторке (иконка самолёта)")
        }
    }


    private fun checkP2pStale(ctx: Context): TestResult {
        return try {
            val m = ctx.getSystemService(Context.WIFI_P2P_SERVICE)
                as? android.net.wifi.p2p.WifiP2pManager
            if (m == null) return TestResult("P2P группа", true, "не поддерживается")
            val existing = try { AtomController.wifiDirect } catch (_: Exception) { null }
            if (existing == null) return TestResult("P2P группа", true, "skip (wifiDirect null)")
            var ch: android.net.wifi.p2p.WifiP2pManager.Channel? = null
            try {
                ch = m.initialize(ctx, android.os.Looper.getMainLooper()) {}
            } catch (_: Exception) {}
            if (ch == null) return TestResult("P2P группа", true, "channel null (skip)")
            InAppLogger.d("SelfTest", "B110: checkP2pStale reuses manager channel")
            var res: TestResult? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            try {
                m.requestGroupInfo(ch) { group ->
                    res = if (group == null) TestResult("P2P группа", true, "чисто (нет групп)")
                        else TestResult("P2P группа", false,
                            "зависла: ${group.networkName} (может блокировать discover код 2)")
                    latch.countDown()
                }
            } catch (_: Exception) { latch.countDown() }
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            res ?: TestResult("P2P группа", true, "timeout (skip)")
        } catch (e: Exception) {
            TestResult("P2P группа", false, "ошибка: ${e.message}")
        }
    }

    private fun checkHotspotCapability(ctx: Context): TestResult {
        return try {
            val hasFeature = ctx.packageManager.hasSystemFeature("android.hardware.wifi")
            if (hasFeature) TestResult("Hotspot", true, "wifi feature OK (axiom#70: не запускаем)")
            else TestResult("Hotspot", false, "нет wifi feature")
        } catch (e: Exception) {
            TestResult("Hotspot", false, "ошибка: ${e.message}")
        }
    }

    private fun checkBattery(ctx: Context): TestResult {
        return if (BatteryOptimizationHelper.isWhitelisted(ctx)) {
            TestResult("Фоновая работа", true, "Battery whitelist: приложение не усыпляется")
        } else {
            TestResult("Фоновая работа", false,
                "Battery optimization включена — большие передачи могут прерываться. " +
                "Настройки → Атом → Батарея → Без ограничений")
        }
    }

}
