package com.garickbox.atom

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
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

    private lateinit var qrImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // B188: NFC-flow → stop continuousDiscover (axiom#212 — BUSY framework)
        try {
            AtomController.nfcFlowActive = true
            AtomController.wifiDirect?.stopContinuousDiscover()
            InAppLogger.nfc("QrServer", "B188: onCreate stopContinuousDiscover (receiver)")
        } catch (_: Exception) {}
        // B166a: каждое открытие QrShare — с чистого P2P
        try { AtomController.wifiDirect?.forgetAllGroups() } catch (_: Exception) {}
        // B102b: TV — снять portrait lock + overscan 48/27dp
        if (!TvHelper.isTv(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        setContentView(R.layout.activity_qr_share)
        qrImage = findViewById(R.id.qrImage)
        statusText = findViewById(R.id.statusText)
        infoText = findViewById(R.id.infoText)

        if (TvHelper.isTv(this)) {
            val d = resources.displayMetrics.density
            val padH = (48 * d).toInt()
            val padV = (27 * d).toInt()
            findViewById<android.view.View>(R.id.qrRoot)?.setPadding(padH, padV, padH, padV)
            statusText.textSize = 22f
            infoText.visibility = android.view.View.GONE
        }

        // B71: кнопка ← В главное (axiom#62)
        val closeBtn = findViewById<android.view.View>(R.id.btnClose)
        closeBtn?.setOnClickListener {
            if (!isFinishing && !isDestroyed) finish()
        }
        if (TvHelper.isTv(this)) closeBtn?.requestFocus()

        startP2pGroup()
    }

    private fun startP2pGroup() {
        statusText.text = "Создаю P2P группу…"
        val wm = getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wm == null) {
            InAppLogger.nfc("QrServer", "no WifiP2pManager → hotspot")
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
                InAppLogger.nfc("QrServer", "createGroup timeout 8s → hotspot")
                startHotspot()
            }
        }
        handler.postDelayed(timeout, 8000L)

        // Сначала removeGroup — иначе BUSY от stale group
        try {
            wm.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    InAppLogger.nfc("QrServer", "removeGroup OK, createGroup in 500ms")
                    handler.postDelayed({ doCreateGroup(wm, channel, timeout) }, 500L)
                }
                override fun onFailure(reason: Int) {
                    InAppLogger.nfc("QrServer", "removeGroup fail $reason, createGroup anyway")
                    handler.postDelayed({ doCreateGroup(wm, channel, timeout) }, 500L)
                }
            })
        } catch (e: Exception) {
            InAppLogger.nfc("QrServer", "removeGroup exception: ${e.message}")
            doCreateGroup(wm, channel, timeout)
        }
    }

    private fun doCreateGroup(wm: WifiP2pManager, channel: WifiP2pManager.Channel, timeout: Runnable) {
        // B221 axiom#331: 5GHz via WifiP2pConfig.Builder (public API 29+), fallback plain
        val legacy = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                InAppLogger.nfc("QrServer", "createGroup OK (plain), requestGroupInfo retry")
                tryRequestGroupInfo(wm, channel, timeout, 0)
            }
            override fun onFailure(reason: Int) {
                handler.removeCallbacks(timeout)
                InAppLogger.nfc("QrServer", "createGroup FAIL: $reason -> hotspot")
                startHotspot()
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                val config = WifiP2pConfig.Builder()
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                    .build()
                InAppLogger.nfc("QrServer", "B221: createGroup(5GHz config)")
                wm.createGroup(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        InAppLogger.nfc("QrServer", "B221: createGroup 5GHz OK")
                        tryRequestGroupInfo(wm, channel, timeout, 0)
                    }
                    override fun onFailure(reason: Int) {
                        InAppLogger.nfc("QrServer", "B221: 5GHz fail reason=$reason -> plain")
                        try { wm.createGroup(channel, legacy) }
                        catch (e: Exception) {
                            handler.removeCallbacks(timeout)
                            InAppLogger.nfc("QrServer", "B221 plain exc: ${e.message}")
                            startHotspot()
                        }
                    }
                })
                return
            } catch (e: Exception) {
                InAppLogger.err("QrServer", "B221: 5GHz throw: ${e.message} -> plain")
            }
        }
        try {
            wm.createGroup(channel, legacy)
        } catch (e: Exception) {
            handler.removeCallbacks(timeout)
            InAppLogger.nfc("QrServer", "createGroup exception: ${e.message}")
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
            InAppLogger.nfc("QrServer", "requestGroupInfo 10x null → hotspot")
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
                    // B220 axiom#326: WifiP2pGroup.frequency (API 29+, WifiP2pInfo НЕ содержит)
                    val freq = if (android.os.Build.VERSION.SDK_INT >= 29) group.frequency else 0
                    val band = when { freq >= 5000 -> "5GHz"; freq >= 2400 -> "2.4GHz"; else -> "?" }
                    InAppLogger.nfc("QrServer", "B220: createGroup freq=${freq}MHz ($band)")
                    InAppLogger.nfc("QrServer", "creds OK on attempt $attempt, ip=$ip")
                    showQr(ssid, pass, ip, "P2P")
                } else {
                    InAppLogger.nfc("QrServer", "requestGroupInfo $attempt: empty → retry 500ms")
                    handler.postDelayed({
                        tryRequestGroupInfo(wm, channel, timeout, attempt + 1)
                    }, 500L)
                }
            }
        } catch (e: Exception) {
            InAppLogger.nfc("QrServer", "requestGroupInfo error: ${e.message} → retry")
            handler.postDelayed({
                tryRequestGroupInfo(wm, channel, timeout, attempt + 1)
            }, 500L)
        }
    }

    private fun showHotspotFailDialog(hint: String, reason: Int) {
        try {
            val msg = when (reason) {
                2, 3 -> "HyperOS блокирует точку доступа.\n\n" +
                    "Варианты:\n" +
                    "1. Забыть сеть Wi-Fi → переподключиться\n" +
                    "2. Wi-Fi тумблер OFF → открыть QR → вернуть\n" +
                    "3. Перезагрузить телефон\n\n" +
                    "После одного из действий — повтори."
                else -> "Не удалось создать hotspot ($hint)"
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Hotspot не создался")
                .setMessage(msg)
                // B121: диалог с кнопкой «Открыть Wi-Fi» (axiom#128 — toggle только через system panel)
                .setPositiveButton("Открыть Wi-Fi") { _, _ ->
                    try {
                        val i = if (android.os.Build.VERSION.SDK_INT >= 29)
                            android.content.Intent(android.provider.Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                        else
                            android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                        startActivity(i)
                    } catch (_: Exception) {}
                    finish()
                }
                .setNegativeButton("Закрыть") { _, _ -> finish() }
                .setCancelable(false)
                .show()
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
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspotReservation = reservation
                    val conf: WifiConfiguration = reservation.wifiConfiguration
                        ?: run {
                            statusText.text = "Ошибка: wifiConfiguration null"
                            return
                        }
                    val ssid = conf.SSID ?: ""
                    val pass = conf.preSharedKey ?: ""
                    if (ssid.isBlank() || pass.isBlank()) {
                        statusText.text = "Ошибка: пустые credentials"
                        return
                    }
                    groupCreated = true
                    usingHotspot = true
                    showQr(ssid, pass, "192.168.43.1", "Hotspot")
                }
                override fun onFailed(reason: Int) {
                    InAppLogger.nfc("QrServer", "hotspot FAIL: $reason retry=$hotspotRetryCount")
                    if (reason == 2 && hotspotRetryCount < 2) {
                        hotspotRetryCount++
                        statusText.text = "Hotspot занят, retry…"
                        handler.postDelayed({ startHotspot() }, 1500L)
                        return
                    }
                    // B70: понятный диалог вместо немого текста
                    val hint = when (reason) {
                        2 -> "HyperOS не даёт AP при активном Wi-Fi"
                        3 -> "Все каналы 2.4GHz заняты"
                        else -> "код $reason"
                    }
                    statusText.text = "Не удалось: $hint"
                    showHotspotFailDialog(hint, reason)
                }
                override fun onStopped() {
                    InAppLogger.nfc("QrServer", "hotspot stopped")
                }
            }, handler)
        } catch (e: Exception) {
            InAppLogger.nfc("QrServer", "hotspot exception: ${e.message}")
            statusText.text = "Ошибка: ${e.message}"
        }
    }

    private fun showQr(ssid: String, pass: String, ip: String, mode: String) {
        InAppLogger.nfc("QrServer", "READY mode=$mode ssid=$ssid ip=$ip")
        try {
            NfcHceService.setUri(NfcHandover(ssid, pass, ip).toUri())
            InAppLogger.nfc("QrServer", "B145: HCE armed $ssid@$ip")
        } catch (e: Exception) {
            InAppLogger.nfc("QrServer:ERR", "B145: HCE arm: ${e.message}")
        }
        statusText.text = "✅ Готов принимать\n📱 Коснись телефоном или покажи QR"
        val displayPass = if (TvHelper.isTv(this)) pass else "••••••••"
        infoText.text = "SSID: $ssid\nПароль: $displayPass\nIP: $ip\nРежим: $mode"

        val qrText = "WIFI:T:WPA;S:$ssid;P:$pass;IP:$ip;;"
        // B102b: динамический размер по qrImage.width (phone ~400dp / TV ~1000dp)
        qrImage.post {
            try {
                val sizePx = if (qrImage.width > 0)
                    minOf(qrImage.width, qrImage.height).coerceAtLeast(300)
                else 700
                val bmp = BarcodeEncoder().encodeBitmap(qrText, BarcodeFormat.QR_CODE, sizePx, sizePx)
                qrImage.setImageBitmap(bmp)
            } catch (e: Exception) {
                InAppLogger.nfc("QrServer", "QR gen error: ${e.message}")
                statusText.text = "QR ошибка: ${e.message}"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            val nfcManager = getSystemService(android.nfc.NfcManager::class.java)
            val adapter = nfcManager?.defaultAdapter
            if (adapter == null || !adapter.isEnabled) {
                InAppLogger.nfc("NfcHce", "B157: NFC off/disabled")
                return
            }
            val ce = android.nfc.cardemulation.CardEmulation.getInstance(adapter)
            val comp = android.content.ComponentName(this, NfcHceService::class.java)
            val ok = ce.setPreferredService(this, comp)
            InAppLogger.nfc("NfcHce", "B157: setPreferredService=$ok")
        } catch (e: Exception) {
            InAppLogger.nfc("NfcHce:ERR", "B157: setPreferredService: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            val nfcManager = getSystemService(android.nfc.NfcManager::class.java)
            val adapter = nfcManager?.defaultAdapter ?: return
            if (!adapter.isEnabled) return
            val ce = android.nfc.cardemulation.CardEmulation.getInstance(adapter)
            // B187: НЕ снимаем preferred service — HCE работает в фоне (AID F041544F4D уникален)
            InAppLogger.nfc("NfcHce", "B187: keep preferred service (HCE in background)")
        } catch (e: Exception) {
            InAppLogger.nfc("NfcHce:ERR", "B157: unsetPreferredService: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { NfcHceService.clear() } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        try { hotspotReservation?.close() } catch (_: Exception) {}
        // B194: НЕ resume — FileReceiverService ещё использует P2P (axiom#225)
        try {
            AtomController.nfcFlowActive = false
            InAppLogger.nfc("QrServer", "B194: onDestroy — nfcFlowActive=false (no resume)")
        } catch (_: Exception) {}
        // B187: НЕ убиваем P2P-группу в onDestroy — она нужна для connect в фоне.
        // Cleanup — в onCreate следующего QrShare (см. line 33 forgetAllGroups).
        InAppLogger.nfc("QrServer", "B187: onDestroy, group kept for background connect")
    }
}
