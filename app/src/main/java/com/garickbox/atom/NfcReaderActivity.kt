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
        setContentView(R.layout.activity_nfc_reader)
        statusText = findViewById(R.id.nfcStatus)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        // B178/recall: восстановить pending после rotate/recreate
        if (savedInstanceState != null) {
            val peerId = savedInstanceState.getString("peer_id")
            val peerName = savedInstanceState.getString("peer_name")
            val peerIp = savedInstanceState.getString("peer_ip")
            if (!peerId.isNullOrBlank() && !peerIp.isNullOrBlank()) {
                currentPeer = Peer(id = peerId, name = peerName ?: "?", ip = peerIp)
            }
            val ssid = savedInstanceState.getString("handover_ssid")
            val pwd = savedInstanceState.getString("handover_pwd")
            val hIp = savedInstanceState.getString("handover_ip")
            if (!ssid.isNullOrBlank() && !pwd.isNullOrBlank() && !hIp.isNullOrBlank()) {
                currentHandover = NfcHandover(ssid, pwd, hIp)
            }
            sheetShown = savedInstanceState.getBoolean("sheet_shown", false)
            afterConnectedCalled = savedInstanceState.getBoolean("after_called", false)
            InAppLogger.nfc("NfcReader", "B178: restore peer=${currentPeer?.name} sheetShown=$sheetShown afterCalled=$afterConnectedCalled")
        }
        if (nfcAdapter == null) { statusText.text = "NFC не поддерживается"; return }
        if (!nfcAdapter!!.isEnabled) { statusText.text = "Включи NFC в настройках"; return }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentPeer?.let { p ->
            outState.putString("peer_id", p.id)
            outState.putString("peer_name", p.name)
            outState.putString("peer_ip", p.ip)
        }
        currentHandover?.let { h ->
            outState.putString("handover_ssid", h.ssid)
            outState.putString("handover_pwd", h.passphrase)
            outState.putString("handover_ip", h.ip)
        }
        outState.putBoolean("sheet_shown", sheetShown)
        outState.putBoolean("after_called", afterConnectedCalled)
    }

    override fun onResume() {
        super.onResume()
        val opts = Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250) }
        nfcAdapter?.enableReaderMode(
            this,
            { tag -> onTag(tag) },
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            opts
        )
        // B179: fallback — connect был, sheet не показан → показать
        if (currentPeer != null && !sheetShown && afterConnectedCalled) {
            InAppLogger.nfc("NfcReader", "B179: onResume fallback — show sheet for ${currentPeer?.name}")
            lifecycleScope.launch {
                delay(300L)
                if (!isFinishing && !isDestroyed && !sheetShown) {
                    showPostConnectActionSheet()
                }
            }
        }
    }

    override fun onPause() { super.onPause(); nfcAdapter?.disableReaderMode(this) }
    override fun onDestroy() {
        super.onDestroy()
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
        // B164: WNS→P2P SSID → onUnavailable (Android 14), WifiManager.addNetwork → -1 (deprecated).
        // Единственный рабочий путь на HyperOS — WifiP2pManager.connect после discoverPeers.
        InAppLogger.nfc("NfcReader", "B164: skip WNS, сразу P2P fallback (ssid=${data.ssid})")
        runOnUiThread { statusText.text = "P2P поиск..." }
        // B173a: real check via requestGroupInfo (не time heuristic axiom#161)
        val wd = AtomController.wifiDirect
        if (wd == null) { fallbackWifiConnect(data); return }
        wd.checkGroupAlive { alive ->
            if (isFinishing || isDestroyed) return@checkGroupAlive
            if (alive) {
                InAppLogger.nfc("NfcReader", "B173a: group ALIVE → fast reinvoke")
                handler.postDelayed({ fallbackWifiConnect(data) }, 100L)
            } else {
                InAppLogger.nfc("NfcReader", "B173a: group DEAD → full cleanup")
                wd.forgetAllGroupsAndThen {
                    handler.postDelayed({ fallbackWifiConnect(data) }, 300L)
                }
            }
        }
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
            if (conn.groupOwnerAddress.isNotBlank()) {
                try { AtomController.meshNode?.sendHello(conn.groupOwnerAddress) } catch (_: Exception) {}
            }
            handler.postDelayed({ afterConnected(data) }, 2000L)
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

    private fun afterConnected(data: NfcHandover) {
        afterConnectedCalled = true
        val node = AtomController.meshNode
        if (node == null) { InAppLogger.nfc("NfcReader:ERR", "afterConnected: node null"); finish(); return }
        InAppLogger.nfc("NfcReader", "afterConnected ip=${data.ip} ssid=${data.ssid}")
        node.sendHello(data.ip)
        InAppLogger.nfc("NfcReader", "sendHello → ${data.ip}")
        currentHandover = data
        // B178: lifecycleScope вместо handler.postDelayed — не отменяется onDestroy
        lifecycleScope.launch {
            delay(2000L)
            if (isFinishing || isDestroyed) {
                InAppLogger.nfc("NfcReader", "B178: skip afterConnected body (destroyed) — B179 onResume покажет sheet")
                return@launch
            }
            val peer = AtomController.peers.value.firstOrNull { it.ip == data.ip }
                ?: Peer(id = "nfc", name = "NFC", ip = data.ip)
            currentPeer = peer
            if (pendingUris.isNotEmpty()) {
                FileSenderService.startTransfer(this@NfcReaderActivity, peer, pendingUris, pendingPaths)
                InAppLogger.nfc("NfcReader", "sent ${pendingUris.size} files to ${data.ip}")
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
        val rootName = try {
            var n = "folder"
            val rootUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            contentResolver.query(rootUri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cur -> if (cur.moveToFirst()) n = cur.getString(0) ?: "folder" }
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
                ), null, null, null)?.use { cur ->
                    while (cur.moveToNext() && result.size < cap) {
                        val id = cur.getString(0) ?: continue
                        val name = cur.getString(1) ?: continue
                        val mime = cur.getString(2) ?: ""
                        if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) walk(id, "$path/$name")
                        else result.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, id) to "$path/$name")
                    }
                }
            } catch (_: Exception) {}
        }
        walk(rootDocId, rootName)
        return result
    }
}
