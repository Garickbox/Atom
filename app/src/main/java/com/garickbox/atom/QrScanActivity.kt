package com.garickbox.atom

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.mesh.core.InAppLogger

class QrScanActivity : AppCompatActivity() {
    companion object {
        var boundCm: ConnectivityManager? = null
        var boundNetwork: Network? = null
        var activeCallback: ConnectivityManager.NetworkCallback? = null

        fun unbind() {
            try { boundCm?.bindProcessToNetwork(null) } catch (_: Exception) {}
            // B37: unregister → specifier-network освобождается,
            // Android авто-переподключается к сохранённому роутеру.
            try {
                activeCallback?.let { boundCm?.unregisterNetworkCallback(it) }
            } catch (_: Exception) {}
            activeCallback = null
            boundCm = null
            boundNetwork = null
            AtomController.meshNode?.recreateUdpSocket()
        }
    }

    private lateinit var statusText: TextView
    private var pendingUris: List<Uri> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    // B38: manual-connect flow — ждём пока юзер сам подключится к TV-hotspot
    private var manualQrSsid: String = ""
    private var manualQrPass: String = ""
    private var manualQrIp: String = ""
    private var manualAwaitConnect: Boolean = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) disconnectFromWifi { launchScanner() }
        else {
            statusText.text = "Нужно разрешение на камеру"
            Toast.makeText(this, "Разреши камеру в настройках", Toast.LENGTH_LONG).show()
        }
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents.isNullOrBlank()) {
            statusText.text = "Отменено"
            finish()
            return@registerForActivityResult
        }
        handleQrResult(result.contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // B36-TV: на телефоне — портрет, на TV — свободно
        if (!TvHelper.isTv(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        setContentView(R.layout.activity_qr_scan)
        statusText = findViewById(R.id.statusText)
        pendingUris = collectUris(intent)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            disconnectFromWifi { launchScanner() }
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** B37: HyperOS POCO не поддерживает concurrent STA+STA — если подключены
     *  к роутеру, WifiNetworkSpecifier.requestNetwork на hotspot fail'ит молча.
     *  Отключаемся на 1.5s, requestNetwork проходит. После transfer unbind()
     *  unregister'ит callback → Android авто-возвращает на роутер. */
    private fun disconnectFromWifi(callback: () -> Unit) {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm == null || !wm.isWifiEnabled) { callback(); return }
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            // B37.1: networkId всегда -1 на Android 12+ (deprecated).
            // Определяем наличие подключения через supplicantState.
            val isConnected = info?.supplicantState == android.net.wifi.SupplicantState.COMPLETED
            if (!isConnected) { callback(); return }
            @Suppress("DEPRECATION")
            val ssid = info.ssid ?: "?"
            InAppLogger.d("QrScan", "disconnect from Wi-Fi: $ssid (B37.1)")
            statusText.text = "Отключаюсь от Wi-Fi для прямой связи…"
            @Suppress("DEPRECATION")
            wm.disconnect()
            handler.postDelayed({
                try {
                    @Suppress("DEPRECATION")
                    val check = wm.connectionInfo
                    val stillConnected =
                        check?.supplicantState == android.net.wifi.SupplicantState.COMPLETED
                    if (stillConnected) {
                        // HyperOS не даёт user-app disconnect — просим вручную
                        InAppLogger.d("QrScan", "disconnect no-op, prompting user (B37.1)")
                        showManualWifiOffDialog(callback)
                    } else {
                        InAppLogger.d("QrScan", "disconnected OK")
                        callback()
                    }
                } catch (e: Exception) {
                    InAppLogger.d("QrScan", "check disconnect error: ${e.message}")
                    callback()
                }
            }, 1200L)
        } catch (e: Exception) {
            InAppLogger.d("QrScan", "disconnect error: ${e.message}")
            callback()
        }
    }

    /** B38: HyperOS блокирует wm.disconnect() — просим юзера подключиться вручную.
     *  onResume ловит момент подключения к TV-hotspot и продолжает автоматически. */
    private fun showManualWifiOffDialog(callback: () -> Unit) {
        manualAwaitConnect = true
        val msg = buildString {
            append("Android не разрешает приложению отключиться от роутера.\n\n")
            append("Подключись к TV вручную:\n\n")
            append("   Wi-Fi:      ").append(manualQrSsid).append("\n")
            append("   Пароль:     ").append(manualQrPass).append("\n\n")
            append("1. Нажми «Открыть Wi-Fi настройки»\n")
            append("2. Выбери «").append(manualQrSsid).append("»\n")
            append("3. Введи пароль (можно скопировать)\n")
            append("4. Вернись в Атом — передача начнётся автоматически")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Подключись к TV")
            .setMessage(msg)
            .setPositiveButton("📋 Копировать пароль") { _, _ ->
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("atom_qr_pass", manualQrPass))
                    android.widget.Toast.makeText(this, "Пароль скопирован", android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: Exception) {}
                openWifiSettings()
            }
            .setNeutralButton("Открыть Wi-Fi настройки") { _, _ -> openWifiSettings() }
            .setNegativeButton("Отмена") { _, _ ->
                manualAwaitConnect = false
                finish()
            }
            .setOnCancelListener {
                manualAwaitConnect = false
                finish()
            }
            .show()
    }

    private fun openWifiSettings() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startActivity(Intent(android.provider.Settings.Panel.ACTION_WIFI))
            } else {
                startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            }
        } catch (_: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) } catch (_: Exception) {}
        }
    }

    /** B38: возврат из Wi-Fi настроек — если подключён к TV, продолжаем. */
    override fun onResume() {
        super.onResume()
        if (!manualAwaitConnect) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            val state = info?.supplicantState
            @Suppress("DEPRECATION")
            val ssid = info?.ssid?.trim('"') ?: ""
            val connected = state == android.net.wifi.SupplicantState.COMPLETED
            InAppLogger.d("QrScan", "onResume check: ssid=$ssid expected=$manualQrSsid connected=$connected")
            if (connected && ssid == manualQrSsid) {
                manualAwaitConnect = false
                InAppLogger.d("QrScan", "manual connect OK → afterConnected")
                statusText.text = "Подключено, отправляю…"
                // Небольшая пауза чтобы сеть стабилизировалась
                handler.postDelayed({ afterConnected(manualQrIp) }, 1500L)
            }
        } catch (e: Exception) {
            InAppLogger.d("QrScan", "onResume check error: ${e.message}")
        }
    }

    private fun launchScanner() {
        val opts = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Наведите на QR на другом телефоне")
            setBeepEnabled(false)
            setOrientationLocked(true)
        }
        scanLauncher.launch(opts)
    }

    private fun collectUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val list = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { list.add(it) }
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { list.addAll(it) }
        }
        return list
    }

    private fun handleQrResult(text: String) {
        // WIFI:T:WPA;S:xxx;P:yyy;IP:zzz;;
        val parts = text.split(";").mapNotNull {
            val idx = it.indexOf(':')
            if (idx <= 0) null else it.substring(0, idx) to it.substring(idx + 1)
        }.toMap()
        val ssid = parts["S"] ?: ""
        val pass = parts["P"] ?: ""
        val ip = parts["IP"] ?: ""
        if (ssid.isBlank() || ip.isBlank()) {
            statusText.text = "Неверный QR"
            handler.postDelayed({ finish() }, 1500L)
            return
        }
        InAppLogger.d("QrScan", "parsed ssid=$ssid ip=$ip")
        manualQrSsid = ssid
        manualQrPass = pass
        manualQrIp = ip
        statusText.text = "Подключаюсь к $ssid…"
        connectWifi(ssid, pass, ip)
    }

    private fun connectWifi(ssid: String, pass: String, ip: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val spec = WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(pass)
                    .build()
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(spec)
                    .build()
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.requestNetwork(req, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        InAppLogger.d("QrScan", "wifi connected, bindProcessToNetwork($network)")
                        activeCallback = this
                        try {
                            cm.bindProcessToNetwork(network)
                            boundCm = cm
                            boundNetwork = network
                            AtomController.meshNode?.recreateUdpSocket()
                        } catch (e: Exception) {
                            InAppLogger.d("QrScan", "bind error: ${e.message}")
                        }
                        handler.post { afterConnected(ip) }
                    }
                    override fun onUnavailable() {
                        handler.post {
                            statusText.text = "Wi-Fi не подключился"
                            handler.postDelayed({ finish() }, 2000L)
                        }
                    }
                }, 20000)
            } catch (e: Exception) {
                InAppLogger.d("QrScan", "specifier error: ${e.message}")
                statusText.text = "Ошибка: ${e.message}"
            }
        } else {
            // API 26-28: WifiManager.addNetwork
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val conf = WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    preSharedKey = "\"$pass\""
                }
                val netId = wm.addNetwork(conf)
                wm.disconnect()
                wm.enableNetwork(netId, true)
                wm.reconnect()
                handler.postDelayed({ afterConnected(ip) }, 4000L)
            } catch (e: Exception) {
                statusText.text = "Ошибка WiFi: ${e.message}"
            }
        }
    }

    /**
     * B32: реальный IP host-AP берём из DHCP gateway, а не хардкодом из QR.
     * На HyperOS startLocalOnlyHotspot может отдать AP с gateway не 192.168.43.1,
     * а 192.168.232.1 / 192.168.251.1 и т.п. → HELLO на хардкод-IP уходит в никуда,
     * приёмник «принимает» offer, но TCP-connect fail с SocketException.
     */
    private fun getGatewayIp(): String? {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val dhcp = wm.dhcpInfo
            if (dhcp.gateway != 0) {
                val g = dhcp.gateway
                return "${g and 0xff}.${(g shr 8) and 0xff}.${(g shr 16) and 0xff}.${(g shr 24) and 0xff}"
            }
            @Suppress("DEPRECATION")
            val myIp = wm.connectionInfo?.ipAddress ?: 0
            if (myIp != 0) {
                val a = myIp and 0xff
                val b = (myIp shr 8) and 0xff
                val c = (myIp shr 16) and 0xff
                return "$a.$b.$c.1"
            }
            null
        } catch (e: Exception) {
            InAppLogger.d("QrScan", "getGatewayIp error: ${e.message}")
            null
        }
    }

    private fun afterConnected(qrIp: String) {
        statusText.text = "Подключено, отправляю…"
        val node = AtomController.meshNode
        if (node == null) {
            statusText.text = "Mesh не готов"
            return
        }
        val gateway = getGatewayIp()
        val targetIp = gateway ?: qrIp
        InAppLogger.d("QrScan", "target IP: gateway=$gateway qr=$qrIp → using $targetIp")
        node.sendHello(targetIp)
        handler.postDelayed({
            // Через 2.5 сек — ждём HELLO
            val peer = AtomController.peers.value.firstOrNull { it.ip == targetIp }
                ?: Peer(id = "qr", name = "QR", ip = targetIp)
            if (pendingUris.isNotEmpty()) {
                FileSenderService.startTransfer(this, peer, pendingUris)
            } else {
                Toast.makeText(this, "Нет файлов", Toast.LENGTH_SHORT).show()
            }
            finish()
        }, 2500L)
    }
}
