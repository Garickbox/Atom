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
        setContentView(R.layout.activity_qr_share)
        qrImage = findViewById(R.id.qrImage)
        statusText = findViewById(R.id.statusText)
        infoText = findViewById(R.id.infoText)

        startP2pGroup()
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
                .setPositiveButton("ОК") { _, _ -> finish() }
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
                    InAppLogger.d("QrServer", "hotspot FAIL: $reason retry=$hotspotRetryCount")
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
                    InAppLogger.d("QrServer", "hotspot stopped")
                }
            }, handler)
        } catch (e: Exception) {
            InAppLogger.d("QrServer", "hotspot exception: ${e.message}")
            statusText.text = "Ошибка: ${e.message}"
        }
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
