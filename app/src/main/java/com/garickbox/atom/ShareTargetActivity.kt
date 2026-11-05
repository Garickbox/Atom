package com.garickbox.atom

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import com.mesh.core.InAppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ShareTargetActivity : AppCompatActivity() {
    private var pendingPreflightCallback: (() -> Unit)? = null

    private var uris: List<Uri> = emptyList()
    private var urisRelativePaths: List<String?> = emptyList()
    private var tvDialog: androidx.appcompat.app.AlertDialog? = null

    private val livePeersState = mutableStateOf<List<Peer>>(emptyList())
    private val p2pPeersState = mutableStateOf<List<android.net.wifi.p2p.WifiP2pDevice>>(emptyList())
    private val p2pSearchingState = mutableStateOf(false)

    private var livePeersJob: kotlinx.coroutines.Job? = null

    override fun onResume() {
        super.onResume()
        val cb = pendingPreflightCallback ?: return
        val issues = PreflightChecker.issues(this)
        if (issues.isEmpty()) {
            pendingPreflightCallback = null
            cb.invoke()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val cb = pendingPreflightCallback ?: return@registerForActivityResult
        val stillMissing = PreflightChecker.issues(this)
        if (stillMissing.isEmpty()) {
            pendingPreflightCallback = null
            cb.invoke()
        } else {
            PreflightDialog.check(this, null, { issue ->
                PreflightChecker.openSettingsFor(this, issue)
            }, cb)
        }
    }

    override fun onDestroy() {
        livePeersJob?.cancel()
        try {
            AtomController.wifiDirect?.onConnected = { conn ->
                if (!conn.isGroupOwner && conn.groupOwnerAddress.isNotBlank()) {
                    AtomController.meshNode?.sendHello(conn.groupOwnerAddress)
                }
            }
        } catch (_: Exception) {}
        try {
            AtomController.wifiDirect?.onPeersChanged = null
            AtomController.wifiDirect?.onError = null
        } catch (_: Exception) {}
        try { tvDialog?.dismiss() } catch (_: Exception) {}
        tvDialog = null
        try {
            if (AtomController.appForeground) {
                AtomController.wifiDirect?.startContinuousDiscover()
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InAppLogger.d("ShareTarget", "onCreate action=${intent?.action}")

        if (!AtomController.hasName()) {
            startActivity(Intent(this, NameSetupActivity::class.java))
            finish()
            return
        }

        uris = collectUris(intent)
        val pathsRaw = intent?.getStringArrayListExtra("atom_relative_paths") ?: arrayListOf()
        urisRelativePaths = uris.mapIndexed { i, _ -> pathsRaw.getOrNull(i)?.ifBlank { null } }
        if (uris.isEmpty()) {
            Toast.makeText(this, "Нет файлов", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        for (u in uris) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
        }

        // B83: Direct Share (long-press shortcut)
        val directMac = intent?.getStringExtra("atom_direct_peer_mac") ?: ""
        val directName = intent?.getStringExtra("atom_direct_peer_name") ?: "?"
        if (directMac.isNotBlank() && !isTv()) {
            connectP2pQuick(directMac, directName)
            return
        }

        // B24/B136: preselected peer
        val presel = MainActivity.pendingPeerForShareTarget
        if (presel != null && !isTv()) {
            MainActivity.pendingPeerForShareTarget = null
            if (presel.ip.isNotBlank()) {
                try {
                    FileSenderService.startTransfer(this, presel, uris, urisRelativePaths)
                } catch (e: Exception) {
                    InAppLogger.err("ShareTarget", "direct send: ${e.message}")
                }
                MainActivity.returnToMain(this)
                finish()
                return
            }
            if (presel.mac.isNotBlank()) {
                connectP2pQuick(presel.mac, presel.name)
                return
            }
        }

        if (isTv()) {
            showTvFlow()
            return
        }

        // B207: Compose ModalBottomSheet
        setContentView(R.layout.activity_share_target)
        val root = findViewById<ComposeView>(R.id.composeShare)
        livePeersJob = lifecycleScope.launch {
            AtomController.peers.collect { peers ->
                val now = System.currentTimeMillis()
                livePeersState.value = peers.filter {
                    now - it.lastSeenMs < 5 * 60_000L && it.name.isNotBlank()
                }
            }
        }
        root.setContent {
            MaterialTheme {
                ShareTargetScreen(
                    uris = uris,
                    livePeers = livePeersState.value,
                    p2pPeers = p2pPeersState.value,
                    p2pSearching = p2pSearchingState.value,
                    actions = buildActions()
                )
            }
        }
    }

    private fun buildActions(): ShareActions = ShareActions(
        onSendToPeer = { peer ->
            try {
                FileSenderService.startTransfer(this, peer, uris, urisRelativePaths)
            } catch (e: Exception) {
                InAppLogger.err("ShareTarget", "send to peer: ${e.message}")
            }
            MainActivity.returnToMain(this)
            finish()
        },
        onSendToP2pDevice = { device -> connectP2p(device.deviceAddress, device.deviceName) },
        onStartP2pSearch = { startP2pSearch() },
        onSendViaNfc = {
            try {
                NfcReaderActivity.pendingUris = uris
                NfcReaderActivity.pendingPaths = urisRelativePaths
                startActivity(Intent(this, NfcReaderActivity::class.java))
            } catch (e: Exception) {
                InAppLogger.err("ShareTarget", "NFC: ${e.message}")
            }
            finish()
        },
        onSendViaQr = {
            try {
                val qIntent = Intent(this, QrScanActivity::class.java).apply {
                    action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
                    type = "*/*"
                    if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
                    else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    putStringArrayListExtra("atom_relative_paths",
                        ArrayList(urisRelativePaths.map { it ?: "" }))
                    clipData = android.content.ClipData.newRawUri("", uris.first()).apply {
                        for (i in 1 until uris.size) addItem(android.content.ClipData.Item(uris[i]))
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(qIntent)
            } catch (e: Exception) {
                InAppLogger.err("ShareTarget", "QR: ${e.message}")
            }
            finish()
        },
        onShowQr = {
            startActivity(Intent(this, QrShareActivity::class.java))
            finish()
        },
        onCancel = { finish() }
    )

    private fun startP2pSearch() {
        val wd = AtomController.wifiDirect ?: return
        try { wd.stopContinuousDiscover() } catch (_: Exception) {}
        p2pSearchingState.value = true
        p2pPeersState.value = emptyList()
        wd.onPeersChanged = { peers ->
            val filtered = peers.filter { it.deviceName.isNotBlank() }
            p2pPeersState.value = filtered
            if (filtered.isNotEmpty()) p2pSearchingState.value = false
            filtered.forEach {
                if (it.deviceAddress.isNotBlank()) {
                    AtomController.saveKnownP2pPeer(it.deviceAddress, it.deviceName)
                }
            }
        }
        wd.onError = { err ->
            InAppLogger.d("ShareTarget", "p2p error: $err")
            Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
            p2pSearchingState.value = false
        }
        wd.discoverPeers()
    }

    private fun connectP2pQuick(mac: String, name: String) {
        InAppLogger.d("ShareTarget", "quick connect: $mac $name")
        val wd = AtomController.wifiDirect ?: return
        try { wd.stopContinuousDiscover() } catch (_: Exception) {}
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Подключаюсь к $name…")
            .setMessage("5-15 секунд")
            .setCancelable(false).show()
        var done = false
        lifecycleScope.launch {
            delay(3000L)
            if (!done) wd.forceFreshConnect(mac)
        }
        lifecycleScope.launch {
            delay(20000L)
            if (!done) {
                done = true
                try { dialog.dismiss() } catch (_: Exception) {}
                try { wd.disconnect() } catch (_: Exception) {}
                Toast.makeText(this@ShareTargetActivity, "Не удалось", Toast.LENGTH_LONG).show()
                finish()
            }
        }
        wd.onError = err@{ err ->
            if (done) return@err
            done = true
            try { dialog.dismiss() } catch (_: Exception) {}
            try { wd.disconnect() } catch (_: Exception) {}
            Toast.makeText(this@ShareTargetActivity, err, Toast.LENGTH_LONG).show()
            finish()
        }
        wd.onConnected = onConnected@ { conn ->
            if (done) return@onConnected
            done = true
            InAppLogger.d("ShareTarget", "connected isGO=${conn.isGroupOwner} goIp=${conn.groupOwnerAddress}")
            AtomController.saveKnownP2pPeer(mac, name)
            val node = AtomController.meshNode
            if (node != null && !conn.isGroupOwner) node.sendHello(conn.groupOwnerAddress)
            lifecycleScope.launch {
                delay(2500L)
                val peer = AtomController.peers.value.firstOrNull {
                    it.ip == conn.groupOwnerAddress && it.id != AtomController.myId
                }
                try { dialog.dismiss() } catch (_: Exception) {}
                if (peer != null) {
                    AtomController.saveKnownP2pPeer(mac, peer.name)
                    FileSenderService.startTransfer(this@ShareTargetActivity, peer, uris, urisRelativePaths)
                    MainActivity.returnToMain(this@ShareTargetActivity)
                    finish()
                } else {
                    val targetIp = if (!conn.isGroupOwner) conn.groupOwnerAddress else null
                    if (targetIp != null) {
                        val fakePeer = Peer(id = "p2p", name = name, ip = targetIp)
                        FileSenderService.startTransfer(this@ShareTargetActivity, fakePeer, uris, urisRelativePaths)
                        MainActivity.returnToMain(this@ShareTargetActivity)
                        finish()
                    } else {
                        Toast.makeText(this@ShareTargetActivity, "Не получили IP", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }
        wd.connect(mac)
    }

    private fun connectP2p(mac: String, name: String) {
        InAppLogger.d("ShareTarget", "connect: $mac $name")
        val wd = AtomController.wifiDirect ?: return
        try { wd.stopContinuousDiscover() } catch (_: Exception) {}
        p2pSearchingState.value = false
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Подключаюсь к $name…")
            .setMessage("10-20 секунд")
            .setCancelable(false).show()
        var done = false
        lifecycleScope.launch {
            delay(20000L)
            if (!done) {
                done = true
                try { dialog.dismiss() } catch (_: Exception) {}
                try { wd.disconnect() } catch (_: Exception) {}
                Toast.makeText(this@ShareTargetActivity, "Не удалось подключиться", Toast.LENGTH_LONG).show()
                finish()
            }
        }
        wd.onConnected = onConnected@ { conn ->
            if (done) return@onConnected
            done = true
            InAppLogger.d("ShareTarget", "p2p connected: isGO=${conn.isGroupOwner}")
            AtomController.saveKnownP2pPeer(mac, name)
            val node = AtomController.meshNode
            if (node != null && !conn.isGroupOwner) node.sendHello(conn.groupOwnerAddress)
            lifecycleScope.launch {
                delay(2500L)
                val peer = AtomController.peers.value.firstOrNull {
                    it.ip == conn.groupOwnerAddress && it.id != AtomController.myId
                }
                try { dialog.dismiss() } catch (_: Exception) {}
                if (peer != null) {
                    FileSenderService.startTransfer(this@ShareTargetActivity, peer, uris, urisRelativePaths)
                    MainActivity.returnToMain(this@ShareTargetActivity)
                    finish()
                } else {
                    val targetIp = if (!conn.isGroupOwner) conn.groupOwnerAddress else null
                    if (targetIp != null) {
                        val fakePeer = Peer(id = "p2p", name = name, ip = targetIp)
                        FileSenderService.startTransfer(this@ShareTargetActivity, fakePeer, uris, urisRelativePaths)
                        MainActivity.returnToMain(this@ShareTargetActivity)
                        finish()
                    } else {
                        Toast.makeText(this@ShareTargetActivity, "Не получили IP", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }
        wd.connect(mac)
    }

    // ═════ TV flow (не трогаем) ═════
    private fun isTv(): Boolean {
        return try {
            val uiMode = getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            uiMode?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
                packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        } catch (_: Exception) { false }
    }

    private fun showTvFlow() {
        preflightCheck(null) {
            val known = AtomController.getKnownP2pPeers()
            if (known.isNotEmpty()) showTvKnownPicker() else startTvDiscovery()
        }
    }

    private fun showTvKnownPicker() {
        val known = AtomController.getKnownP2pPeers()
        val items = known.map { "📶 ${it.name}" } + "🔍 Найти других"
        tvDialog?.dismiss()
        tvDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Куда отправить?")
            .setItems(items.toTypedArray()) { _, which ->
                if (which < known.size) connectP2pQuick(known[which].mac, known[which].name)
                else startTvDiscovery()
            }
            .setNegativeButton("Отмена") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    private fun startTvDiscovery() {
        val wd = AtomController.wifiDirect ?: return finish()
        wd.stopContinuousDiscover()
        tvDialog?.dismiss()
        tvDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📡 Поиск…")
            .setMessage("Ищу через Wi-Fi Direct")
            .setOnCancelListener { finish() }.show()
        var found = false
        wd.onPeersChanged = { peers ->
            if (found) return@onPeersChanged
            val filtered = peers.filter { it.deviceName.isNotBlank() }
            if (filtered.isNotEmpty()) {
                found = true
                tvDialog?.dismiss()
                runOnUiThread { showTvPeerPicker(filtered) }
            }
        }
        wd.onError = { err -> tvDialog?.dismiss(); Toast.makeText(this, err, Toast.LENGTH_LONG).show(); finish() }
        wd.discoverPeers()
    }

    private fun showTvPeerPicker(peers: List<android.net.wifi.p2p.WifiP2pDevice>) {
        val names = peers.map { "📶 ${it.deviceName}" }.toTypedArray()
        tvDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Куда отправить?")
            .setItems(names) { _, which ->
                val p = peers[which]
                connectP2pQuick(p.deviceAddress, p.deviceName)
            }
            .setNegativeButton("Отмена") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }

    // ═════ helpers ═════
    private fun collectUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val list = mutableListOf<Uri>()
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { list.addAll(it) }
        if (list.isEmpty()) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { list.add(it) }
        }
        if (list.isEmpty()) {
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let { list.add(it) }
            }
        }
        return list
    }

    private fun preflightCheck(parent: Any?, onReady: () -> Unit) {
        pendingPreflightCallback = onReady
        PreflightDialog.check(
            activity = this,
            onRequestPermissions = {
                val missing = PermissionsHelper.missing(this)
                if (missing.isEmpty()) preflightCheck(parent, onReady)
                else permissionLauncher.launch(missing.toTypedArray())
            },
            onOpenSystemSettings = { issue -> PreflightChecker.openSettingsFor(this, issue) },
            onReady = { pendingPreflightCallback = null; onReady() }
        )
    }
}
