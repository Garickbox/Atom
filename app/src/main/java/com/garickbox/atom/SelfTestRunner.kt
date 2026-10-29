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

object SelfTestRunner {

    data class TestResult(val name: String, val passed: Boolean, val detail: String)

    private const val PORT = 47822

    suspend fun runAll(ctx: Context): List<TestResult> = withContext(Dispatchers.IO) {
        listOf(
            checkPermissions(ctx),
            checkPorts(),
            checkStorage(),
            checkOta(),
            checkFgs(ctx)
        )
    }

    private fun checkPermissions(ctx: Context): TestResult {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) needed.add("POST_NOTIFICATIONS")
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) needed.add("ACCESS_FINE_LOCATION")
        return if (needed.isEmpty()) TestResult("Разрешения", true, "Все выданы")
        else TestResult("Разрешения", false, "Не выданы: ${needed.joinToString()}")
    }

    private fun checkPorts(): TestResult {
        return try {
            val udp = DatagramSocket(PORT)
            udp.close()
            val tcp = ServerSocket(PORT)
            tcp.close()
            TestResult("Порты $PORT", true, "UDP и TCP свободны")
        } catch (e: Exception) {
            TestResult("Порты $PORT", false, "Занято: ${e.message}")
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
}
