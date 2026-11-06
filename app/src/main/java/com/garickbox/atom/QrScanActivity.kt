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
        const val EXTRA_PENDING_ACTION_SHEET = "pending_action_sheet"
        var boundCm: ConnectivityManager? = null
        var boundNetwork: Network? = null
        fun unbind() {
            try { boundCm?.bindProcessToNetwork(null) } catch (_: Exception) {}
            boundCm = null
            boundNetwork = null
            // B30: вернуть udpSocket на дефолтную сеть после QR-сессии
            AtomController.meshNode?.recreateUdpSocket()
        }
    }

    private lateinit var statusText: TextView
    private var pendingUris: List<Uri> = emptyList()
    private var pendingActionSheet: Boolean = false
    private var currentConnectedPeer: Peer? = null
    private var pendingRelativePaths: List<String?> = emptyList()  // B113
    private val handler = Handler(Looper.getMainLooper())
    private var wnsAttempt: Int = 0  // B239 axiom#476

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchScanner()
        else {
            statusText.text = "Нужно разрешение на камеру"
            Toast.makeText(this, "Разреши камеру в настройках", Toast.LENGTH_LONG).show()
        }
    }

    private val mediaPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> if (uris.isNotEmpty()) sendPickedUris(uris) }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
        val uris = mutableListOf<Uri>()
        result.data?.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
        result.data?.data?.let { uris.add(it) }
        if (uris.isNotEmpty()) sendPickedUris(uris)
    }

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val files = collectTreeFiles(treeUri)
        if (files.isNotEmpty()) sendPickedUrisWithPaths(files)
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
        setContentView(R.layout.activity_qr_scan)
        statusText = findViewById(R.id.statusText)
        pendingUris = collectUris(intent)
        pendingRelativePaths = intent?.getStringArrayListExtra("atom_relative_paths")
            ?.map { it.ifBlank { null } } ?: emptyList()
        pendingActionSheet = intent?.getBooleanExtra(EXTRA_PENDING_ACTION_SHEET, false) ?: false

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            launchScanner()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
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
        // B114: clipData fallback (Samsung OneUI, Photo Picker, SAF)
        if (list.isEmpty()) {
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i)?.uri?.let { list.add(it) }
                }
            }
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
                        try {
                            cm.bindProcessToNetwork(network)
                            boundCm = cm
                            boundNetwork = network
                            // B30/B226 axiom#398: не recreate во время активного transfer
                            if (!AtomController.transferActive) {
                                AtomController.meshNode?.recreateUdpSocket()
                            }
                        } catch (e: Exception) {
                            InAppLogger.d("QrScan", "bind error: ${e.message}")
                        }
                        handler.post { afterConnected(ip) }
                    }
                    override fun onUnavailable() {
                        handler.post {
                            // B239 axiom#476: retry до 3 попыток (axiom#228 WNS race)
                            wnsAttempt++
                            if (wnsAttempt < 3) {
                                InAppLogger.qr("QrScan: WNS unavailable, retry ${wnsAttempt}/3 in ${2000L * wnsAttempt}ms")
                                handler.postDelayed({ connectWifi(ssid, pass, ip) }, 2000L * wnsAttempt)
                            } else {
                                statusText.text = "Wi-Fi не подключился"
                                handler.postDelayed({ finish() }, 2000L)
                            }
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

    private fun showPostConnectActionSheet() {
        try {
            val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
            val c = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 56, 48, 40)
            }
            c.addView(android.widget.TextView(this).apply {
                text = "Что отправить на ${currentConnectedPeer?.name ?: "пира"}?"
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 32)
            })
            val opts = arrayOf("📷  Фото", "🎬  Видео", "📄  Файл", "📁  Папка")
            val acts = arrayOf<() -> Unit>(
                { mediaPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                { mediaPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                { filePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }) },
                { folderPicker.launch(null) }
            )
            opts.forEachIndexed { i, opt ->
                c.addView(android.widget.TextView(this).apply {
                    text = opt; textSize = 16f
                    setPadding(16, 36, 16, 36)
                    isClickable = true; isFocusable = true
                    setOnClickListener { sheet.dismiss(); acts[i]() }
                })
            }
            sheet.setOnCancelListener { if (!isFinishing) finish() }
            sheet.setContentView(c)
            sheet.show()
        } catch (e: Exception) {
            InAppLogger.err("QrScan", "showPostConnectActionSheet: ${e.message}")
            finish()
        }
    }

    private fun sendPickedUris(uris: List<Uri>) {
        val peer = currentConnectedPeer ?: run { finish(); return }
        try { FileSenderService.startTransfer(this, peer, uris, emptyList()) } catch (e: Exception) { InAppLogger.err("QrScan", "send: ${e.message}") }
        MainActivity.returnToMain(this)
        finish()
    }

    private fun sendPickedUrisWithPaths(files: List<Pair<Uri, String>>) {
        val peer = currentConnectedPeer ?: run { finish(); return }
        try { FileSenderService.startTransfer(this, peer, files.map { it.first }, files.map { it.second }) } catch (e: Exception) { InAppLogger.err("QrScan", "send folder: ${e.message}") }
        MainActivity.returnToMain(this)
        finish()
    }

    private fun collectTreeFiles(treeUri: Uri): List<Pair<Uri, String>> {
        val cap = 501
        val result = mutableListOf<Pair<Uri, String>>()
        val rootDocId = try { android.provider.DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { return emptyList() }
        val rootName = try {
            var n = "folder"
            val rootUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            contentResolver.query(rootUri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) n = c.getString(0) ?: "folder" }
            n
        } catch (_: Exception) { "folder" }
        fun walk(parentId: String, path: String) {
            if (result.size >= cap) return
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            try {
                contentResolver.query(childrenUri, arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                ), null, null, null)?.use { c ->
                    while (c.moveToNext() && result.size < cap) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: ""
                        if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) walk(id, "$path/$name")
                        else result.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, id) to "$path/$name")
                    }
                }
            } catch (_: Exception) {}
        }
        walk(rootDocId, rootName)
        return result
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
            currentConnectedPeer = peer
            if (pendingUris.isNotEmpty()) {
                FileSenderService.startTransfer(this, peer, pendingUris, pendingRelativePaths)
                MainActivity.returnToMain(this)
                finish()
            } else if (pendingActionSheet) {
                showPostConnectActionSheet()
            } else {
                Toast.makeText(this, "QR отсканирован, но файлы не выбраны", Toast.LENGTH_LONG).show()
                finish()
            }
        }, 2500L)
    }
}
