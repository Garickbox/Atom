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
        if (nfcAdapter == null) { statusText.text = "NFC не поддерживается"; return }
        if (!nfcAdapter!!.isEnabled) { statusText.text = "Включи NFC в настройках"; return }
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
    }

    override fun onPause() { super.onPause(); nfcAdapter?.disableReaderMode(this) }
    override fun onDestroy() {
        super.onDestroy()
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
            InAppLogger.d("NfcReader", "selectAid size=${r1.size} sw=$sw1")
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
            InAppLogger.d("NfcReader", "nlen=$nlen")
            InAppLogger.d("NfcReader", "nlen=$nlen")
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
            InAppLogger.d("NfcReader", "uri=$uri")
            if (uri == null) { runOnUiThread { statusText.text = "Не URI" }; return }
            val data = NfcHandover.parse(uri.toString())
            if (data == null) { runOnUiThread { statusText.text = "Не Atom: $uri" }; return }
            runOnUiThread { statusText.text = "→ ${data.ssid}..." }
            connectWifi(data)
        } catch (e: Exception) {
            InAppLogger.err("NfcReader", "processTag: ${e.message}")
            runOnUiThread { statusText.text = "Ошибка: ${e.message}" }
        }
    }

    private fun connectWifi(data: NfcHandover) {
        if (Build.VERSION.SDK_INT < 29) { runOnUiThread { statusText.text = "API<29 unsupported" }; return }
        try {
            val spec = WifiNetworkSpecifier.Builder()
                .setSsid(data.ssid)
                .setWpa2Passphrase(data.passphrase)
                .build()
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(spec)
                .build()
            val cm = getSystemService(ConnectivityManager::class.java)
            cm.requestNetwork(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    InAppLogger.d("NfcReader", "WNS onAvailable network=$network")
                    try {
                        cm.bindProcessToNetwork(network)
                        boundCm = cm
                        AtomController.meshNode?.recreateUdpSocket()
                        InAppLogger.d("NfcReader", "WNS bind + recreate UDP ok")
                    } catch (e: Exception) {
                        InAppLogger.err("NfcReader", "WNS bind: ${e.message}")
                    }
                    // B162: дать сокету время после bind перед sendHello (axiom#53)
                    handler.postDelayed({ afterConnected(data) }, 300L)
                }
                override fun onUnavailable() {
                    InAppLogger.err("NfcReader", "WNS onUnavailable — fallback WifiManager")
                    runOnUiThread { statusText.text = "WNS fail, fallback..." }
                    fallbackWifiConnect(data)
                }
            }, 20000)
        } catch (e: Exception) {
            InAppLogger.err("NfcReader", "connect: ${e.message}")
        }
    }

    private fun fallbackWifiConnect(data: NfcHandover) {
        try {
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
            if (wm == null) { InAppLogger.err("NfcReader", "fallback: no WifiManager"); finish(); return }
            @Suppress("DEPRECATION")
            val conf = android.net.wifi.WifiConfiguration().apply {
                SSID = "\"${data.ssid}\""
                preSharedKey = "\"${data.passphrase}\""
                allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
            }
            @Suppress("DEPRECATION")
            val netId = wm.addNetwork(conf)
            if (netId < 0) {
                InAppLogger.err("NfcReader", "fallback addNetwork=$netId → finish")
                finish(); return
            }
            InAppLogger.d("NfcReader", "fallback netId=$netId, enable+reconnect")
            @Suppress("DEPRECATION")
            run {
                wm.disconnect()
                wm.enableNetwork(netId, true)
                wm.reconnect()
            }
            handler.postDelayed({ afterConnected(data) }, 5000L)
        } catch (e: Exception) {
            InAppLogger.err("NfcReader", "fallback: ${e.message}")
            finish()
        }
    }

    private fun afterConnected(data: NfcHandover) {
        val node = AtomController.meshNode
        if (node == null) { InAppLogger.err("NfcReader", "afterConnected: node null"); finish(); return }
        InAppLogger.d("NfcReader", "afterConnected ip=${data.ip} ssid=${data.ssid}")
        node.sendHello(data.ip)
        InAppLogger.d("NfcReader", "sendHello → ${data.ip}")
        currentHandover = data
        handler.postDelayed({
            val peer = AtomController.peers.value.firstOrNull { it.ip == data.ip }
                ?: Peer(id = "nfc", name = "NFC", ip = data.ip)
            currentPeer = peer
            if (pendingUris.isNotEmpty()) {
                FileSenderService.startTransfer(this, peer, pendingUris, pendingPaths)
                InAppLogger.d("NfcReader", "sent ${pendingUris.size} files to ${data.ip}")
                finish()
            } else {
                // B160: файл не выбран на старте NFC (FAB sheet) → показать выбор
                showPostConnectActionSheet()
            }
        }, 2000L)
    }

    private fun showPostConnectActionSheet() {
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
            InAppLogger.d("NfcReader", "B160: post-connect sheet")
        } catch (e: Exception) {
            InAppLogger.err("NfcReader", "B160 showPostConnectActionSheet: ${e.message}")
            finish()
        }
    }

    private fun sendPickedUris(uris: List<Uri>) {
        val peer = currentPeer ?: run { finish(); return }
        try {
            FileSenderService.startTransfer(this, peer, uris, emptyList())
            InAppLogger.d("NfcReader", "B160: send ${uris.size} files to ${peer.name}")
        } catch (e: Exception) {
            InAppLogger.err("NfcReader", "B160 send: ${e.message}")
        }
        finish()
    }

    private fun sendPickedUrisWithPaths(files: List<Pair<Uri, String>>) {
        val peer = currentPeer ?: run { finish(); return }
        try {
            FileSenderService.startTransfer(this, peer, files.map { it.first }, files.map { it.second })
            InAppLogger.d("NfcReader", "B160: send folder ${files.size} files to ${peer.name}")
        } catch (e: Exception) {
            InAppLogger.err("NfcReader", "B160 send folder: ${e.message}")
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
