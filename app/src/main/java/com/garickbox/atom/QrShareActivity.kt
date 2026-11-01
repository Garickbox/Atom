package com.garickbox.atom

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.mesh.core.InAppLogger

class QrShareActivity : AppCompatActivity() {

    private var groupCreated = false
    private var hotspotRetryCount = 0
    private var usingHotspot = false
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var waitingForWifiOff = false
    private var wifiWasOff = false

    private lateinit var qrImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // B36-TV: на телефоне — портрет, на TV — свободно (landscape layout)
        if (!TvHelper.isTv(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        setContentView(R.layout.activity_qr_share)
        qrImage = findViewById(R.id.qrImage)
        statusText = findViewById(R.id.statusText)
        infoText = findViewById(R.id.infoText)

        // B53: кнопка «← В главное» (axiom#52 — видимая навигация на QR-экране)
        findViewById<android.view.View>(R.id.btnClose)?.setOnClickListener {
            finish()
        }

        // B35-TV: reset state на повторный запуск (fix #18 — single-shot TV)
        hotspotRetryCount = 0
        groupCreated = false
        usingHotspot = false

        // B39: hotspot напрямую (P2P+WifiNetworkSpecifier несовместимы, B26)
        startHotspot()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // B35-TV: Back = свернуть (AP живёт в фоне). Закрыть = force-stop app.
        InAppLogger.d("QrServer", "back → moveTaskToBack (AP alive)")
        moveTaskToBack(true)
    }

    private fun startP2pGroup() {
        statusText.text = "Создаю P2P группу…"
        val wm = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wm == null) {
            InAppLogger.d("QrServer", "no WifiP2pManager → hotspot")
            startHotspot()
            return
        }
        val channel = wm.initialize(this, Looper.getMainLooper()) {}
        if (channel == null) {
            startHotspot(); return
        }

        // Таймаут: если за 8 сек группа не сформировалась — hotspot
        val timeout = Runnable {
            if (!groupCreated) {
                InAppLogger.d("QrServer", "createGroup timeout 8s → hotspot")
                startHotspot()
            }
        }
        handler.postDelayed(timeout, 8000L)

        // Сначала removeGroup — иначе BUSY от stale group
        try {
            wm.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    InAppLogger.d("QrServer", "removeGroup OK, createGroup in 500ms")
                    handler.postDelayed({ doCreateGroup(wm, channel, timeout) }, 500L)
                }
                override fun onFailure(reason: Int) {
                    InAppLogger.d("QrServer", "removeGroup fail $reason, createGroup anyway")
                    handler.postDelayed({ doCreateGroup(wm, channel, timeout) }, 500L)
                }
            })
        } catch (e: Exception) {
            InAppLogger.d("QrServer", "removeGroup exception: ${e.message}")
            doCreateGroup(wm, channel, timeout)
        }
    }

    private fun doCreateGroup(wm: WifiP2pManager, channel: WifiP2pManager.Channel, timeout: Runnable) {
        try {
            wm.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    InAppLogger.d("QrServer", "createGroup OK, requestGroupInfo retry")
                    tryRequestGroupInfo(wm, channel, timeout, 0)
                }
                override fun onFailure(reason: Int) {
                    handler.removeCallbacks(timeout)
                    InAppLogger.d("QrServer", "createGroup FAIL: $reason → hotspot")
                    startHotspot()
                }
            })
        } catch (e: Exception) {
            handler.removeCallbacks(timeout)
            InAppLogger.d("QrServer", "createGroup exception: ${e.message}")
            startHotspot()
        }
    }

    private fun tryRequestGroupInfo(
        wm: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        timeout: Runnable,
        attempt: Int
    ) {
        if (attempt >= 10) {
            InAppLogger.d("QrServer", "requestGroupInfo 10x null → hotspot")
            handler.removeCallbacks(timeout)
            startHotspot()
            return
        }
        try {
            wm.requestGroupInfo(channel) { group ->
                val ssid = group?.networkName ?: ""
                val pass = group?.passphrase ?: ""
                if (ssid.isNotBlank() && pass.isNotBlank()) {
                    handler.removeCallbacks(timeout)
                    groupCreated = true
                    usingHotspot = false
                    val ip = "192.168.49.1"
                    InAppLogger.d("QrServer", "creds OK on attempt $attempt, ip=$ip")
                    showQr(ssid, pass, ip, "P2P")
                } else {
                    InAppLogger.d("QrServer", "requestGroupInfo $attempt: empty → retry 500ms")
                    handler.postDelayed({
                        tryRequestGroupInfo(wm, channel, timeout, attempt + 1)
                    }, 500L)
                }
            }
        } catch (e: Exception) {
            InAppLogger.d("QrServer", "requestGroupInfo error: ${e.message} → retry")
            handler.postDelayed({
                tryRequestGroupInfo(wm, channel, timeout, attempt + 1)
            }, 500L)
        }
    }

    private fun showHotspotRetryDialog(reason: Int) {
        val why = when (reason) {
            2 -> "HyperOS не разрешает точку доступа при включённом Wi-Fi."
            3 -> "Канал Wi-Fi занят для точки доступа."
            else -> "Hotspot не создался."
        }
        try {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Wi-Fi мешает точке доступа")
                .setMessage(
                    why + "\n\n" +
                    "1. Открой шторку → выключи Wi-Fi\n" +
                    "2. Жди 10-30 секунд (framework отпустит)\n" +
                    "3. Вернись в Атом — создам hotspot автоматически\n\n" +
                    "После передачи включи Wi-Fi обратно."
                )
                .setPositiveButton("Открыть Wi-Fi") { _, _ ->
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            startActivity(Intent(android.provider.Settings.Panel.ACTION_WIFI))
                        } else {
                            startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                        }
                    } catch (_: Exception) {}
                }
                .setNegativeButton("Отмена") { _, _ -> finish() }
                .setCancelable(false)
                .show()
        } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        if (!waitingForWifiOff) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return
            if (!wm.isWifiEnabled) {
                if (!wifiWasOff) {
                    wifiWasOff = true
                    InAppLogger.d("QrServer", "B48: Wi-Fi off detected")
                    statusText.text = "Wi-Fi выключен. Жду включения…"
                }
            } else if (wifiWasOff) {
                InAppLogger.d("QrServer", "B48: Wi-Fi on → retry hotspot")
                waitingForWifiOff = false
                wifiWasOff = false
                hotspotRetryCount = 0
                startHotspot()
            }
        } catch (_: Exception) {}
    }

    private fun startHotspot() {
        statusText.text = "Создаю hotspot…"
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm == null) {
            statusText.text = "Ошибка: WifiManager недоступен"
            return
        }
        try {
            startHotspotLegacy(wm)
        } catch (e: Exception) {
            InAppLogger.d("QrServer", "hotspot exception: ${e.message}")
            statusText.text = "Ошибка: ${e.message}"
        }
    }

    /** B39: API < 30 (TV 26-28) — SSID/пароль framework генерирует, показываем как есть. */
    private fun startHotspotLegacy(wm: WifiManager) {
        try {
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspotReservation = reservation
                    val conf: WifiConfiguration = reservation.wifiConfiguration
                        ?: run {
                            statusText.text = "Ошибка: wifiConfiguration null"
                            return
                        }
                    val ssid = conf.SSID?.trim('"') ?: ""
                    val pass = conf.preSharedKey?.trim('"') ?: ""
                    if (ssid.isBlank() || pass.isBlank()) {
                        statusText.text = "Ошибка: пустые credentials"
                        return
                    }
                    groupCreated = true
                    usingHotspot = true
                    showQr(ssid, pass, getHotspotIp(), "Hotspot")
                }
                override fun onFailed(reason: Int) {
                    InAppLogger.d("QrServer", "hotspot FAIL: $reason retry=$hotspotRetryCount")
                    // reason=2 INCOMPATIBLE_MODE — retry до 2 раз (fast fail)
                    if (reason == 2 && hotspotRetryCount < 2) {
                        hotspotRetryCount++
                        statusText.text = "Hotspot занят, retry $hotspotRetryCount/2…"
                        handler.postDelayed({ startHotspot() }, 1500L)
                        return
                    }
                    // B52: reason=2 (retry исчерпан) ИЛИ reason=3 → одна инструкция:
                    // выключить Wi-Fi. HyperOS не даёт concurrent STA+AP.
                    if (reason == 2 || reason == 3) {
                        waitingForWifiOff = true
                        wifiWasOff = false
                        hotspotRetryCount = 0
                        statusText.text = "Выключи Wi-Fi в шторке на 10-30с → hotspot создастся"
                        showHotspotRetryDialog(reason)
                        return
                    }
                    statusText.text = "Не удалось создать группу (код $reason)"
                }
                override fun onStopped() {
                    InAppLogger.d("QrServer", "hotspot stopped")
                }
            }, handler)
        } catch (e: Exception) {
            InAppLogger.d("QrServer", "hotspot legacy exception: ${e.message}")
            statusText.text = "Ошибка: ${e.message}"
        }
    }

    /** B39: SSID до 32 байт UTF-8 (framework reject длиннее). */
    private fun safeSsid(raw: String): String {
        val trimmed = raw.trim().ifBlank { "Atom" }
        var s = trimmed
        while (s.toByteArray(Charsets.UTF_8).size > 32 && s.length > 1) {
            s = s.dropLast(1)
        }
        return s.ifBlank { "Atom" }
    }

    /** B39: реальный AP-IP для QR (вместо хардкода 43.1). */
    private fun getHotspotIp(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { java.util.Collections.list(it.inetAddresses) }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { it.hostAddress?.startsWith("192.168.") == true }
                ?.hostAddress ?: "192.168.43.1"
        } catch (_: Exception) { "192.168.43.1" }
    }

    private fun showQr(ssid: String, pass: String, ip: String, mode: String) {
        InAppLogger.d("QrServer", "READY mode=$mode ssid=$ssid ip=$ip")
        statusText.text = if (usingHotspot) "✅ Hotspot готов" else "✅ P2P готов"
        infoText.text = "SSID: $ssid\nПароль: $pass\nIP: $ip\nРежим: $mode"

        val qrText = "WIFI:T:WPA;S:$ssid;P:$pass;IP:$ip;;"
        try {
            val bmp = BarcodeEncoder().encodeBitmap(qrText, BarcodeFormat.QR_CODE, 700, 700)
            qrImage.setImageBitmap(bmp)
        } catch (e: Exception) {
            InAppLogger.d("QrServer", "QR gen error: ${e.message}")
            statusText.text = "QR ошибка: ${e.message}"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { hotspotReservation?.close() } catch (_: Exception) {}
        if (groupCreated && !usingHotspot) {
            try {
                val wm = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                val channel = wm?.initialize(this, Looper.getMainLooper()) {}
                wm?.removeGroup(channel, null)
            } catch (_: Exception) {}
        }
        InAppLogger.d("QrServer", "onDestroy, group cleaned")
    }
}
