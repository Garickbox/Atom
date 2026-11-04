package com.garickbox.atom

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiNetworkSpecifier
import android.nfc.Ndef
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
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
            val ndef = Ndef.get(tag)
            if (ndef == null) {
                runOnUiThread { statusText.text = "Не NDEF: ${tag.techList.joinToString()}" }
                return
            }
            ndef.connect()
            val msg = ndef.ndefMessage
            ndef.close()
            if (msg == null || msg.records.isEmpty()) {
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
                    try {
                        cm.bindProcessToNetwork(network)
                        boundCm = cm
                        AtomController.meshNode?.recreateUdpSocket()
                    } catch (_: Exception) {}
                    handler.post { afterConnected(data) }
                }
                override fun onUnavailable() {
                    runOnUiThread { statusText.text = "WiFi не подключился" }
                }
            }, 20000)
        } catch (e: Exception) {
            InAppLogger.err("NfcReader", "connect: ${e.message}")
        }
    }

    private fun afterConnected(data: NfcHandover) {
        val node = AtomController.meshNode
        if (node == null) { finish(); return }
        node.sendHello(data.ip)
        handler.postDelayed({
            val peer = AtomController.peers.value.firstOrNull { it.ip == data.ip }
                ?: Peer(id = "nfc", name = "NFC", ip = data.ip)
            if (pendingUris.isNotEmpty()) {
                FileSenderService.startTransfer(this, peer, pendingUris, pendingPaths)
                InAppLogger.d("NfcReader", "sent ${pendingUris.size} files to ${data.ip}")
            }
            finish()
        }, 2000L)
    }
}
