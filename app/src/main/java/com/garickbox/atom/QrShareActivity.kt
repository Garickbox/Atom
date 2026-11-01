package com.garickbox.atom

import android.content.Context
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

    companion object {
        // B39.3: hotspot живёт между Activity-instance. Back = finish,
        // AP не умирает. Новое открытие = reuse (не BUSY).
        @Volatile var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
        @Volatile var savedSsid: String = ""
        @Volatile var savedPass: String = ""
        @Volatile var savedIp: String = ""

        fun closeHotspot() {
            try { hotspotReservation?.close() } catch (_: Exception) {}
            hotspotReservation = null
            savedSsid = ""
            savedPass = ""
            savedIp = ""
        }

        fun isAlive(): Boolean =
            hotspotReservation != null && savedSsid.isNotBlank()
    }

    private var groupCreated = false
    private var hotspotRetryCount = 0
    private var usingHotspot = false

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

        // B35-TV: reset state на повторный запуск (fix #18 — single-shot TV)
        hotspotRetryCount = 0
        groupCreated = false
        usingHotspot = false

        // B39.2: явная кнопка «В главное» + Back (нет toolbar на landscape QR)
        findViewById<android.view.View>(R.id.btnClose)?.setOnClickListener {
            onBackPressed()
        }
        // B39.3: явное закрытие AP (иначе reuse навсегда, нельзя пересоздать)
        findViewById<android.view.View>(R.id.btnStopHotspot)?.setOnClickListener {
            InAppLogger.d("QrServer", "manual hotspot close")
            Companion.closeHotspot()
            finish()
        }

        // B39.3: reuse живого AP — не создаём заново (иначе BUSY retry-loop)
        if (Companion.isAlive()) {
            InAppLogger.d("QrServer", "reuse existing hotspot ssid=${Companion.savedSsid}")
            groupCreated = true
            usingHotspot = true
            showQr(Companion.savedSsid, Companion.savedPass, Companion.savedIp, "Hotspot (reuse)")
            return
        }

        // B39: hotspot напрямую (P2P+WifiNetworkSpecifier несовместимы, B26)
        startHotspot()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // B39.2: Back = finish (→ Main). Если передача активна — свернуть,
        // иначе AP умрёт и передача порвётся.
        val st = AtomController.transferState.value
        val active = st is TransferState.Running || st is TransferState.Paused
        if (active) {
            InAppLogger.d("QrServer", "back: transfer active → moveTaskToBack")
            moveTaskToBack(true)
        } else {
            InAppLogger.d("QrServer", "back: no transfer → finish")
            finish()
        }
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
                    Companion.hotspotReservation = reservation
                    Companion.savedSsid = ssid
                    Companion.savedPass = pass
                    Companion.savedIp = getHotspotIp()
                    groupCreated = true
                    usingHotspot = true
                    showQr(ssid, pass, Companion.savedIp, "Hotspot")
                }
                override fun onFailed(reason: Int) {
                    InAppLogger.d("QrServer", "hotspot FAIL: $reason retry=$hotspotRetryCount")
                    if (reason == 2 && hotspotRetryCount < 5) {
                        hotspotRetryCount++
                        statusText.text = "Hotspot занят, retry $hotspotRetryCount/5…"
                        handler.postDelayed({ startHotspot() }, 2000L)
                        return
                    }
                    // B39.3: если AP уже жив (stale от прошлого instance) — reuse
                    if (Companion.isAlive()) {
                        InAppLogger.d("QrServer", "FAIL but existing alive → reuse")
                        groupCreated = true
                        usingHotspot = true
                        showQr(Companion.savedSsid, Companion.savedPass, Companion.savedIp, "Hotspot (reuse)")
                        return
                    }
                    statusText.text = "Не удалось создать hotspot (код $reason).\nНажми «⏹ Закрыть» и попробуй снова."
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
        // B39.3: НЕ закрываем hotspotReservation — AP живёт в companion,
        // следующее открытие = reuse. Явное закрытие = кнопка «⏹ Закрыть».
        InAppLogger.d("QrServer", "onDestroy (AP kept alive in companion)")
    }
}
