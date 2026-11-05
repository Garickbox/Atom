package com.garickbox.atom

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.nfc.NdefMessage
import android.nfc.tech.IsoDep
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.mesh.core.InAppLogger

class NfcReaderActivity : AppCompatActivity() {
    companion object {
        @Volatile var pendingUris: List<Uri> = emptyList()
        @Volatile var pendingPaths: List<String?> = emptyList()
    }
    private lateinit var statusText: TextView
    private var nfcAdapter: NfcAdapter? = null
    private var boundCm: ConnectivityManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var currentPeer: Peer? = null
    private var currentHandover: NfcHandover? = null
    @Volatile private var sheetShown = false
    @Volatile private var afterConnectedCalled = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // B195: no resume (FileSender/Receiver handles)
        try { AtomController.nfcFlowActive = false } catch (_: Exception) {}
        // B166: сброс P2P-клиента — иначе второй connect идёт на stale group
        // (Redmi становится GO, POCO не клиент, роли перепутаны)
        // B166b: НЕ трогаем P2P в onDestroy — FileSenderService ещё использует P2P.
        // B166a forgetAllGroups → RST TCP во время transfer. Cleanup — в connectWifi (перед new connect).
        try { boundCm?.bindProcessToNetwork(null) } catch (_: Exception) {}
        NfcHceService.clear()
        handler.removeCallbacksAndMessages(null)
    }

    private fun onTag(tag: Tag) {
        runOnUiThread { statusText.text = "Метка: ${tag.techList.size} tech" }
        handler.post { processTag(tag) }
    }

    private fun processTag(tag: Tag) {
        try {
            val isoDep = IsoDep.get(tag)
            if (isoDep == null) {
                runOnUiThread { statusText.text = "Не IsoDep: ${tag.techList.joinToString()}" }
                return
            }
            isoDep.connect()
            isoDep.timeout = 5000
            // B147: HCE не сканируется через Ndef.get. Manual APDU Type 4 Tag:
            // SELECT AID → SELECT CC → READ CC → SELECT NDEF → READ NLEN → READ NDEF
            // B158: Lc=05 (AID F041544F4D = 5 байт), Le=00 (256).
            // Было Lc=06 → 00 съедался как часть data → AID = F041544F4D00 → 6A82
            val selectAid = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x05,
                0xF0.toByte(), 0x41, 0x54, 0x4F, 0x4D, 0x00)
            val r1 = isoDep.transceive(selectAid)
            val sw1 = if (r1.size >= 2) String.format("%02X%02X", r1[r1.size-2], r1[r1.size-1]) else "short"
            InAppLogger.nfc("NfcReader", "selectAid size=${r1.size} sw=$sw1")
            if (sw1 != "9000") {
                try { isoDep.close() } catch (_: Exception) {}
                runOnUiThread { statusText.text = "AID: $sw1" }
                return
            }
            isoDep.transceive(byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, 0xE1.toByte(), 0x03))
            isoDep.transceive(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x0F))
            isoDep.transceive(byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, 0xE1.toByte(), 0x04))
            val nlenResp = isoDep.transceive(byteArrayOf(0x00, 0xB0.toByte(), 0x00, 0x00, 0x02))
            if (nlenResp.size < 2) {
                try { isoDep.close() } catch (_: Exception) {}
                runOnUiThread { statusText.text = "NLEN не прочитан" }
                return
            }
            val nlen = ((nlenResp[0].toInt() and 0xFF) shl 8) or (nlenResp[1].toInt() and 0xFF)
            InAppLogger.nfc("NfcReader", "nlen=$nlen")
            InAppLogger.nfc("NfcReader", "nlen=$nlen")
            if (nlen <= 0 || nlen > 900) {
                try { isoDep.close() } catch (_: Exception) {}
                runOnUiThread { statusText.text = "NLEN=$nlen (пусто)" }
                return
            }
            val ndefBuf = ByteArray(nlen)
            var off = 0
            while (off < nlen) {
                val chunk = minOf(50, nlen - off)
                val cmd = byteArrayOf(0x00, 0xB0.toByte(),
                    (((off + 2) shr 8) and 0xFF).toByte(),
                    ((off + 2) and 0xFF).toByte(),
                    chunk.toByte())
                val resp = isoDep.transceive(cmd)
                if (resp.size < chunk + 2 || resp[resp.size-2] != 0x90.toByte() || resp[resp.size-1] != 0x00.toByte()) {
                    try { isoDep.close() } catch (_: Exception) {}
                    runOnUiThread { statusText.text = "read off=$off resp=${resp.size}" }
                    return
                }
                System.arraycopy(resp, 0, ndefBuf, off, chunk)
                off += chunk
            }
            try { isoDep.close() } catch (_: Exception) {}
            val msg = NdefMessage(ndefBuf)
            if (msg.records.isEmpty()) {
                runOnUiThread { statusText.text = "NDEF пуст" }
                return
            }
            val uri = msg.records[0].toUri()
            InAppLogger.nfc("NfcReader", "uri=$uri")
            if (uri == null) { runOnUiThread { statusText.text = "Не URI" }; return }
            val data = NfcHandover.parse(uri.toString())
            if (data == null) { runOnUiThread { statusText.text = "Не Atom: $uri" }; return }
            runOnUiThread { statusText.text = "→ ${data.ssid}..." }
            connectWifi(data)
        } catch (e: Exception) {
            InAppLogger.nfc("NfcReader:ERR", "processTag: ${e.message}")
            runOnUiThread { statusText.text = "Ошибка: ${e.message}" }
        }
    }

    private fun connectWifi(data: NfcHandover) {
        // B192: NFC = QR (axiom#221). Не проверяем peerSeen — доверяем credentials. WNS → afterConnected.
        InAppLogger.nfc("NfcReader", "B193: WNS connect ssid=${data.ssid} ip=${data.ip}")
        runOnUiThread { statusText.text = "Подключаюсь к Wi-Fi…" }
        if (Build.VERSION.SDK_INT < 29) { fallbackWifiConnect(data); return }
        var attempt = 0
        var pendingCb: ConnectivityManager.NetworkCallback? = null
        var pendingCm: ConnectivityManager? = null
        fun tryWns() {
            if (isFinishing || isDestroyed) return
            attempt++
            try {
                // B197 axiom#241: unregister previous NetworkCallback before retry
                if (pendingCb != null && pendingCm != null) {
                    try { pendingCm!!.unregisterNetworkCallback(pendingCb!!) } catch (_: Exception) {}
                    InAppLogger.nfc("NfcReader", "B197: unregister prev cb (attempt $attempt)")
                }
                pendingCb = null; pendingCm = null
                val spec = WifiNetworkSpecifier.Builder()
                    .setSsid(data.ssid)
                    .setWpa2Passphrase(data.passphrase)
                    .build()
                val req = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(spec)
                    .build()
                val cm = getSystemService(ConnectivityManager::class.java)
                val finished = java.util.concurrent.atomic.AtomicBoolean(false)
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (!finished.compareAndSet(false, true)) return
                        InAppLogger.nfc("NfcReader", "B192: WNS onAvailable $network (attempt $attempt)")
                        try {
                            cm.bindProcessToNetwork(network)
                            boundCm = cm
                            AtomController.meshNode?.recreateUdpSocket()
                        } catch (e: Exception) {
                            InAppLogger.err("NfcReader", "B192: bind ${e.message}")
                        }
                        handler.post { afterConnected(data) }
                    }
                    override fun onUnavailable() {
                        if (!finished.compareAndSet(false, true)) return
                        InAppLogger.err("NfcReader", "B192: WNS onUnavailable (attempt $attempt)")
                        if (attempt < 5) {
                            InAppLogger.nfc("NfcReader", "B195: WNS retry ${attempt}/5 in ${500L * attempt}ms")
                            handler.postDelayed({ tryWns() }, 500L * attempt)
                        } else {
                            InAppLogger.err("NfcReader", "B195: WNS fail x5 → fallback WPS-PBC")
                            if (!isFinishing && !isDestroyed) fallbackWifiConnect(data)
                        }
                    }
                }
                pendingCb = cb
                pendingCm = cm
                cm.requestNetwork(req, cb, 20000)
            } catch (e: Exception) {
                InAppLogger.err("NfcReader", "B192: setup ${e.message}")
                if (!isFinishing && !isDestroyed) fallbackWifiConnect(data)
            }
        }
        tryWns()
    }

    private fun fallbackWifiConnect(data: NfcHandover) {
        val wd = AtomController.wifiDirect
        if (wd == null) { InAppLogger.nfc("NfcReader:ERR", "B164: no WifiDirect"); finish(); return }
        InAppLogger.nfc("NfcReader", "B164: P2P discover для ssid=${data.ssid}")

        // Save & restore callbacks (axiom#75)
        val prevOnConnected = wd.onConnected
        val prevOnError = wd.onError
        val prevOnPeers = wd.onPeersChanged
        var done = false

        fun restore() {
            wd.onConnected = prevOnConnected
            wd.onError = prevOnError
            wd.onPeersChanged = prevOnPeers
        }

        fun finishOnce(err: String?) {
            if (done) return
            done = true
            restore()
            if (err != null) {
                InAppLogger.nfc("NfcReader:ERR", "B164: $err")
                try { finish() } catch (_: Exception) {}
            }
        }

        // SSID "DIRECT-xx-DeviceName" → "DeviceName"
        val deviceNameFromSsid = data.ssid
            .removePrefix("DIRECT-")
            .substringAfter("-", "")
            .trim()

        var connectInFlight = false
        var connectAttempts = 0
        var targetMac: String? = null

        fun tryConnect() {
            val mac = targetMac ?: return
            if (done) return
            if (connectAttempts >= 8) { finishOnce("connect attempts=8"); return }
            connectAttempts++
            connectInFlight = true
            InAppLogger.nfc("NfcReader", "B165: connect attempt #$connectAttempts mac=$mac")
            try { wd.connectAsClient(mac) } catch (e: Exception) {
                connectInFlight = false
                finishOnce("connect throw: ${e.message}")
            }
        }

        wd.onPeersChanged = peers@{ peers ->
            if (done) return@peers
            InAppLogger.nfc("NfcReader", "B165: peers ${peers.map { "${it.deviceName}/${it.deviceAddress}/${it.status}" }}")
            if (connectInFlight || targetMac != null) return@peers
            val exact = peers.firstOrNull {
                it.deviceName.equals(deviceNameFromSsid, ignoreCase = true) &&
                    it.deviceAddress.isNotBlank()
            }
            val fallback = peers.firstOrNull {
                it.deviceAddress.isNotBlank() &&
                    it.status == android.net.wifi.p2p.WifiP2pDevice.AVAILABLE
            }
            val target = exact ?: fallback
            if (target != null) {
                InAppLogger.nfc("NfcReader", "B165: P2P found ${target.deviceName} ${target.deviceAddress} → connect ONCE")
                targetMac = target.deviceAddress
                handler.post { tryConnect() }
            }
        }

        var inversionCount = 0
        wd.onConnected = con@{ conn ->
            if (done) return@con
            InAppLogger.nfc("NfcReader", "B165: P2P connected isGO=${conn.isGroupOwner} goIp=${conn.groupOwnerAddress}")
            // B173c: детект инверсии — Redmi должен быть client (isGO=false)
            if (conn.isGroupOwner) {
                InAppLogger.nfc("NfcReader:ERR", "B173c: ROLE INVERSION — Redmi became GO (${inversionCount + 1}/3)")
                if (inversionCount < 3) {
                    inversionCount++
                    connectInFlight = false
                    targetMac = null
                    try {
                        wd.forgetAllGroupsAndThen {
                            handler.postDelayed({
                                try { wd.discoverPeers() } catch (_: Exception) {}
                            }, 500L)
                        }
                    } catch (_: Exception) {
                        handler.postDelayed({
                            try { wd.discoverPeers() } catch (_: Exception) {}
                        }, 1500L)
                    }
                    return@con
                }
                finishOnce("B173c: role inversion x3 → finish")
                return@con
            }
            done = true
            connectInFlight = false
            restore()
            // B195: КОРЕНЬ — после WPS-PBC пересоздать сокеты на p2p0 (QR это делает в onAvailable)
            try {
                boundCm?.bindProcessToNetwork(null)
                AtomController.meshNode?.recreateUdpSocket()
                AtomController.meshNode?.recreateTcpServer()
                InAppLogger.nfc("NfcReader", "B195: fallback onConnected — UDP+TCP recreated")
            } catch (e: Exception) {
                InAppLogger.err("NfcReader", "B195: recreate ${e.message}")
            }
            handler.postDelayed({
                if (conn.groupOwnerAddress.isNotBlank()) {
                    try { AtomController.meshNode?.sendHello(conn.groupOwnerAddress) } catch (_: Exception) {}
                }
                handler.postDelayed({ afterConnected(data) }, 1500L)
            }, 500L)
        }

        wd.onError = err@{ err ->
            if (done) return@err
            InAppLogger.nfc("NfcReader:ERR", "B168: P2P error $err (attempt $connectAttempts)")
            connectInFlight = false
            val isBusy = err.contains("BUSY") || err.contains("код 2") || err.contains("busy")
            if (isBusy) {
                // B168: cancel pending + retry с backoff (BUSY = framework busy, подожди)
                try { wd.cancelPendingConnect() } catch (_: Exception) {}
                if (connectAttempts < 8) {
                    val backoff = if (connectAttempts <= 2) 1500L else 2500L
                    InAppLogger.nfc("NfcReader", "B168: BUSY → retry ${backoff}ms (attempt $connectAttempts/8)")
                    handler.postDelayed({ tryConnect() }, backoff)
                } else {
                    finishOnce("BUSY max attempts 8")
                }
            } else {
                if (connectAttempts < 5) {
                    handler.postDelayed({ tryConnect() }, 1500L)
                } else {
                    finishOnce("max attempts 5")
                }
            }
        }

        handler.postDelayed({ finishOnce("P2P timeout 25s") }, 25000L)

        try {
            wd.stopContinuousDiscover()
            wd.discoverPeers()
        } catch (e: Exception) {
            finishOnce("discover: ${e.message}")
        }
    }

    /** B193: реальный gateway после WNS connect (как в QrScanActivity, axiom#223) */
    private fun getGatewayIp(): String? {
        return try {
            val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val dhcp = wm.dhcpInfo
            if (dhcp.gateway != 0) {
                val g = dhcp.gateway
                val ip = "${g and 0xff}.${(g shr 8) and 0xff}.${(g shr 16) and 0xff}.${(g shr 24) and 0xff}"
                if (ip != "0.0.0.0") return ip
            }
            @Suppress("DEPRECATION")
            val myIp = wm.connectionInfo?.ipAddress ?: 0
            if (myIp != 0) {
                val a = myIp and 0xff; val b = (myIp shr 8) and 0xff; val c = (myIp shr 16) and 0xff
                return "$a.$b.$c.1"
            }
            null
        } catch (e: Exception) {
            InAppLogger.err("NfcReader", "B193: getGatewayIp ${e.message}")
            null
        }
    }

    private fun afterConnected(data: NfcHandover) {
        afterConnectedCalled = true
        val node = AtomController.meshNode
        if (node == null) { InAppLogger.nfc("NfcReader:ERR", "afterConnected: node null"); finish(); return }
        // B193: как QR (axiom#223) — реальный gateway, не NDEF IP
        val gateway = getGatewayIp()
        val targetIp = gateway ?: data.ip
        InAppLogger.nfc("NfcReader", "B193: targetIp=$targetIp (gateway=$gateway ndef=${data.ip})")
        node.sendHello(targetIp)
        currentHandover = data
        lifecycleScope.launch {
            delay(2500L)
            if (isFinishing || isDestroyed) {
                InAppLogger.nfc("NfcReader", "B193: skip (destroyed)")
                return@launch
            }
            val peer = AtomController.peers.value.firstOrNull { it.ip == targetIp }
                ?: Peer(id = "nfc", name = "NFC", ip = targetIp)
            currentPeer = peer
            InAppLogger.nfc("NfcReader", "B193: peer=${peer.name} ip=${peer.ip} uris=${pendingUris.size}")
            if (pendingUris.isNotEmpty()) {
                FileSenderService.startTransfer(this@NfcReaderActivity, peer, pendingUris, pendingPaths)
                InAppLogger.nfc("NfcReader", "B193: sent ${pendingUris.size} files to ${targetIp}")
                finish()
            } else {
                showPostConnectActionSheet()
            }
        }
    }

    private fun showPostConnectActionSheet() {
        if (sheetShown) {
            InAppLogger.nfc("NfcReader", "B178: sheet already shown, skip")
            return
        }
        sheetShown = true
        try {
            val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
            val c = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 56, 48, 40)
            }
            c.addView(android.widget.TextView(this).apply {
                text = "Что отправить на ${currentPeer?.name ?: "пира"}?"
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
            InAppLogger.nfc("NfcReader", "B160: post-connect sheet")
        } catch (e: Exception) {
            InAppLogger.nfc("NfcReader:ERR", "B160 showPostConnectActionSheet: ${e.message}")
            finish()
        }
    }

    private fun sendPickedUris(uris: List<Uri>) {
        val peer = currentPeer ?: run { finish(); return }
        try {
            FileSenderService.startTransfer(this, peer, uris, emptyList())
            InAppLogger.nfc("NfcReader", "B160: send ${uris.size} files to ${peer.name}")
        } catch (e: Exception) {
            InAppLogger.nfc("NfcReader:ERR", "B160 send: ${e.message}")
        }
        finish()
    }

    private fun sendPickedUrisWithPaths(files: List<Pair<Uri, String>>) {
        val peer = currentPeer ?: run { finish(); return }
        try {
            FileSenderService.startTransfer(this, peer, files.map { it.first }, files.map { it.second })
            InAppLogger.nfc("NfcReader", "B160: send folder ${files.size} files to ${peer.name}")
        } catch (e: Exception) {
            InAppLogger.nfc("NfcReader:ERR", "B160 send folder: ${e.message}")
        }
        finish()
    }

    private fun collectTreeFiles(treeUri: Uri): List<Pair<Uri, String>> {
        val cap = 501
        val result = mutableListOf<Pair<Uri, String>>()
        val rootDocId = try { android.provider.DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { return emptyList() }
        // B181d: сохранить имя выбранной папки (S01), но skip системный root (Download → "")
        // "primary:Download/S01" → "S01",  "primary:Download" → ""
        val docPathFromId = rootDocId.substringAfter(":", rootDocId).trim('/')
        val selectedFolderName = if (docPathFromId.contains("/")) docPathFromId.substringAfterLast('/') else ""
        fun walk(parentId: String, path: String) {
            if (result.size >= cap) return
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            try {
                contentResolver.query(childrenUri, arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                ), null, null, null)?.use { cur ->
                    while (cur.moveToNext() && result.size < cap) {
                        val id = cur.getString(0) ?: continue
                        val name = cur.getString(1) ?: continue
                        val mime = cur.getString(2) ?: ""
                        val newPath = if (path.isBlank()) name else "$path/$name"
                        if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) walk(id, newPath)
                        else result.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, id) to newPath)
                    }
                }
            } catch (_: Exception) {}
        }
        walk(rootDocId, selectedFolderName)
        return result
    }
}
