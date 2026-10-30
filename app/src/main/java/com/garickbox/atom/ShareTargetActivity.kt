package com.garickbox.atom

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.garickbox.atom.databinding.SheetShareStep1Binding
import com.garickbox.atom.databinding.SheetShareStep2Binding
import com.garickbox.atom.databinding.SheetSearchBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.mesh.core.InAppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ShareTargetActivity : AppCompatActivity() {
    private var pendingPreflightCallback: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        val cb = pendingPreflightCallback ?: return
        val issues = PreflightChecker.issues(this)
        if (issues.isEmpty()) {
            InAppLogger.d("ShareTarget", "preflight cleared, continuing")
            pendingPreflightCallback = null
            cb.invoke()
        } else {
            InAppLogger.d("ShareTarget",
                "still has issues: ${PreflightChecker.summary(issues)}")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val cb = pendingPreflightCallback ?: return@registerForActivityResult
        // После grant — перепроверить всё остальное
        val stillMissing = PreflightChecker.issues(this)
        if (stillMissing.isEmpty()) {
            pendingPreflightCallback = null
            cb.invoke()
        } else {
            // Остались системные проблемы — покажем диалог
            PreflightDialog.check(this, null, { issue ->
                PreflightChecker.openSettingsFor(this, issue)
            }, cb)
        }
    }


    private var uris: List<Uri> = emptyList()
    private var urisRelativePaths: List<String?> = emptyList()
    private var dialog: BottomSheetDialog? = null
    private var tvDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onDestroy() {
        try { tvDialog?.dismiss() } catch (_: Exception) {}
        tvDialog = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.garickbox.atom.TvHelper.isTv(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        InAppLogger.d("ShareTargetActivity", "onCreate action=${intent?.action}")

        if (!AtomController.hasName()) {
            startActivity(Intent(this, NameSetupActivity::class.java))
            finish()
            return
        }

        uris = collectUris(intent)
        // B15: читаем relative paths (parallel list)
        val pathsRaw = intent?.getStringArrayListExtra("atom_relative_paths") ?: arrayListOf()
        urisRelativePaths = uris.mapIndexed { i, _ ->
            pathsRaw.getOrNull(i)?.ifBlank { null }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "Нет файлов", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Persist grant
        for (u in uris) {
            try {
                contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
        }

        if (isTv()) {
            showTvFlow()
            return
        }
        showStep1()
    }

    private fun collectUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val list = mutableListOf<Uri>()
        // Android не придерживается строгих типов: из FAB может прийти
        // ACTION_SEND + ArrayList, из внешних приложений — ACTION_SEND + Uri.
        // Пробуем оба варианта для обоих action.
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { list.addAll(it) }
        if (list.isEmpty()) {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { list.add(it) }
        }
        // Fallback: clipData (Android 11+ часто использует clipData вместо EXTRA_STREAM)
        if (list.isEmpty()) {
            intent.clipData?.let { clip ->
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i)?.uri?.let { list.add(it) }
                }
            }
        }
        return list
    }

    private fun totalSize(): Long {
        var total = 0L
        for (u in uris) {
            try {
                contentResolver.openAssetFileDescriptor(u, "r")?.use {
                    total += it.length
                }
            } catch (_: Exception) {}
        }
        return total
    }

    private fun showStep1() {
        dialog?.dismiss()
        InAppLogger.d("ShareTarget", "step1 shown (${uris.size} files)")
        val b = SheetShareStep1Binding.inflate(LayoutInflater.from(this))
        val d = BottomSheetDialog(this)
        d.setContentView(b.root)
        dialog = d

        val n = uris.size
        val sz = formatBytes(totalSize())
        b.headerText.text = if (n == 1) "Отправить 1 файл ($sz)" else "Отправить $n файлов ($sz)"

        val last = AtomController.lastPeer
        if (last != null) {
            b.lastPeerRow.visibility = View.VISIBLE
            b.lastPeerText.text = "📱 ${last.name}"
            b.lastPeerRow.setOnClickListener { onPeerSelected(last) }
        } else {
            b.lastPeerRow.visibility = View.GONE
        }

        b.otherPeerRow.setOnClickListener {
            showStep2(d)
        }

        d.show()
    }

    private fun showStep2(parent: BottomSheetDialog) {
        InAppLogger.d("ShareTarget", "step2 shown")
        val b = SheetShareStep2Binding.inflate(LayoutInflater.from(this))
        parent.setContentView(b.root)

        b.wifiDirectRow.setOnClickListener {
            preflightCheck(parent) {
                val known = AtomController.getKnownP2pPeers()
                if (known.isNotEmpty()) {
                    showKnownP2pDialog(parent)
                } else {
                    startWifiDirectFlow(parent)
                }
            }
        }
        b.scanRow.setOnClickListener {
            Toast.makeText(this, "QR будет в следующем патче", Toast.LENGTH_SHORT).show()
        }
        b.showQrRow.setOnClickListener {
            Toast.makeText(this, "QR будет в следующем патче", Toast.LENGTH_SHORT).show()
        }
        b.backBtn.setOnClickListener { showStep1() }
    }

    private fun showKnownP2pDialog(parent: BottomSheetDialog) {
        val known = AtomController.getKnownP2pPeers()
        val items = known.map { "📶 ${it.name}" } + "🔍 Найти других"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Куда отправить?")
            .setItems(items.toTypedArray()) { _, which ->
                if (which < known.size) {
                    parent.dismiss()
                    connectP2pQuick(known[which].mac, known[which].name)
                } else {
                    startWifiDirectFlow(parent)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun connectP2pQuick(mac: String, name: String) {
        InAppLogger.d("ShareTarget", "quick connect START: mac=$mac name=$name")
        val wd = AtomController.wifiDirect ?: return

        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Подключаюсь к $name…")
            .setMessage("Это займёт 5-15 секунд")
            .setCancelable(false)
            .show()

        var done = false
        val timeoutJob = lifecycleScope.launch {
            delay(15000)
            if (!done) {
                done = true
                try { dialog.dismiss() } catch (_: Exception) {}
                Toast.makeText(this@ShareTargetActivity,
                    "Не удалось — попробуй через поиск", Toast.LENGTH_LONG).show()
                finish()
            }
        }

        wd.onConnected = onConnected@ { conn ->
            if (done) return@onConnected
            done = true
            timeoutJob.cancel()
            InAppLogger.d("ShareTarget",
                "quick p2p connected: isGO=${conn.isGroupOwner} goIp=${conn.groupOwnerAddress}")
            AtomController.saveKnownP2pPeer(mac, name)
            val node = AtomController.meshNode
            if (node != null && !conn.isGroupOwner) {
                node.sendHello(conn.groupOwnerAddress)
            }
            lifecycleScope.launch {
                delay(2500)
                val peer = AtomController.peers.value.firstOrNull {
                    it.ip.startsWith("192.168.49.") && it.id != AtomController.myId
                }
                try { dialog.dismiss() } catch (_: Exception) {}
                if (peer != null) {
                    AtomController.saveKnownP2pPeer(mac, peer.name)
                    FileSenderService.startTransfer(this@ShareTargetActivity, peer, uris, urisRelativePaths)
                    finish()
                } else {
                    val targetIp = if (!conn.isGroupOwner) conn.groupOwnerAddress else null
                    if (targetIp != null) {
                        val fakePeer = Peer(id = "p2p", name = name, ip = targetIp)
                        FileSenderService.startTransfer(this@ShareTargetActivity, fakePeer, uris, urisRelativePaths)
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

    private fun onPeerSelected(peer: Peer) {
        InAppLogger.d("ShareTargetActivity", "peer selected: ${peer.name} (${peer.ip})")
        checkBatteryForLarge {
            dialog?.dismiss()
            Toast.makeText(this, "Отправка ${uris.size} файл(ов) → ${peer.name}", Toast.LENGTH_SHORT).show()
            FileSenderService.startTransfer(this, peer, uris, urisRelativePaths)
            finish()
        }
    }

    private fun checkBatteryForLarge(action: () -> Unit) {
        val total = totalSize()
        // Только для больших файлов и только если whitelist нет
        if (total < 100L * 1024 * 1024) {
            action()
            return
        }
        if (BatteryOptimizationHelper.isWhitelisted(this)) {
            action()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🔋 Большая передача")
            .setMessage(
                "Файл ${"%.1f".format(total / 1024.0 / 1024.0)} МБ может " +
                "оборваться, если экран выключен.\n\n" +
                "Разреши Атому работать в фоне — иначе Android " +
                "«усыпит» его через пару минут."
            )
            .setPositiveButton("Разрешить") { _, _ ->
                BatteryOptimizationHelper.requestWhitelist(this)
                action()
            }
            .setNegativeButton("Всё равно") { _, _ -> action() }
            .show()
    }

    private fun formatBytes(b: Long): String {
        if (b < 1024) return "$b Б"
        if (b < 1024 * 1024) return "%.1f КБ".format(b / 1024.0)
        if (b < 1024 * 1024 * 1024) return "%.1f МБ".format(b / 1024.0 / 1024.0)
        return "%.1f ГБ".format(b / 1024.0 / 1024.0 / 1024.0)
    }

    // ═══════════════════════════════════════════════════════════
    //  TV FLOW: AlertDialog вместо BottomSheet
    // ═══════════════════════════════════════════════════════════

    private fun isTv(): Boolean {
        return try {
            val uiMode = getSystemService(android.content.Context.UI_MODE_SERVICE)
                as? android.app.UiModeManager
            val isTvMode = uiMode?.currentModeType ==
                android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
            val hasLeanback = packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_LEANBACK
            )
            isTvMode || hasLeanback
        } catch (e: Exception) {
            InAppLogger.d("ShareTarget", "isTv error: ${e.message}")
            false
        }
    }

    private fun showTvFlow() {
        InAppLogger.d("ShareTarget", "TV flow START")
        preflightCheck(null) {
            val known = AtomController.getKnownP2pPeers()
            if (known.isNotEmpty()) {
                showTvKnownPicker()
            } else {
                startTvDiscovery()
            }
        }
    }

    private fun showTvKnownPicker() {
        val known = AtomController.getKnownP2pPeers()
        val items = known.map { "📶 ${it.name}" } + "🔍 Найти других"
        tvDialog?.dismiss()
        tvDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Куда отправить?")
            .setItems(items.toTypedArray()) { _, which ->
                if (which < known.size) {
                    connectP2pTv(known[which].mac, known[which].name)
                } else {
                    startTvDiscovery()
                }
            }
            .setNegativeButton("Отмена") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun startTvDiscovery() {
        InAppLogger.d("ShareTarget", "TV P2P discovery START")
        val wd = AtomController.wifiDirect
        if (wd == null) {
            Toast.makeText(this, "Wi-Fi Direct не инициализирован", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        wd.stopContinuousDiscover()

        tvDialog?.dismiss()
        tvDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📡 Поиск получателя…")
            .setMessage("Ищу устройства через Wi-Fi Direct")
            .setCancelable(true)
            .setOnCancelListener { finish() }
            .show()

        var found = false
        wd.onPeersChanged = onPeersChanged@ { peers ->
            if (found) return@onPeersChanged
            val filtered = peers.filter { it.deviceName.isNotBlank() }
            InAppLogger.d("ShareTarget",
                "TV p2p peers: ${filtered.map { "${it.deviceName}/${it.deviceAddress}" }}")
            if (filtered.isNotEmpty()) {
                found = true
                tvDialog?.dismiss()
                runOnUiThread { showTvPeerPicker(filtered) }
            }
        }
        wd.onError = { err ->
            InAppLogger.d("ShareTarget", "TV p2p error: $err")
            tvDialog?.dismiss()
            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            finish()
        }
        wd.discoverPeers()
    }

    private fun showTvPeerPicker(peers: List<android.net.wifi.p2p.WifiP2pDevice>) {
        val names = peers.map { "📶 ${it.deviceName}" }.toTypedArray()
        tvDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Куда отправить?")
            .setItems(names) { _, which ->
                val p = peers[which]
                connectP2pTv(p.deviceAddress, p.deviceName)
            }
            .setNegativeButton("Отмена") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun connectP2pTv(mac: String, name: String) {
        InAppLogger.d("ShareTarget", "TV connect START: mac=$mac name=$name")
        val wd = AtomController.wifiDirect ?: return

        tvDialog?.dismiss()
        tvDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Подключаюсь к $name…")
            .setMessage("Это займёт 10-20 секунд")
            .setCancelable(false)
            .show()

        var done = false
        val timeoutJob = lifecycleScope.launch {
            delay(20000)
            if (!done) {
                done = true
                tvDialog?.dismiss()
                Toast.makeText(this@ShareTargetActivity,
                    "Не удалось подключиться. Попробуй ещё раз.",
                    Toast.LENGTH_LONG).show()
                finish()
            }
        }

        wd.onConnected = onConnected@ { conn ->
            if (done) return@onConnected
            done = true
            timeoutJob.cancel()
            InAppLogger.d("ShareTarget",
                "TV p2p connected: isGO=${conn.isGroupOwner} goIp=${conn.groupOwnerAddress}")
            AtomController.saveKnownP2pPeer(mac, name)

            val node = AtomController.meshNode
            if (node != null && !conn.isGroupOwner) {
                node.sendHello(conn.groupOwnerAddress)
            }

            lifecycleScope.launch {
                delay(2500)
                val peer = AtomController.peers.value.firstOrNull {
                    it.ip.startsWith("192.168.49.") && it.id != AtomController.myId
                }
                tvDialog?.dismiss()
                if (peer != null) {
                    InAppLogger.d("ShareTarget", "TV peer resolved: ${peer.name} @ ${peer.ip}")
                    FileSenderService.startTransfer(this@ShareTargetActivity, peer, uris, urisRelativePaths)
                    finish()
                } else {
                    val targetIp = if (!conn.isGroupOwner) conn.groupOwnerAddress else null
                    if (targetIp != null) {
                        val fakePeer = Peer(id = "p2p", name = name, ip = targetIp)
                        FileSenderService.startTransfer(
                            this@ShareTargetActivity, fakePeer, uris, urisRelativePaths)
                        finish()
                    } else {
                        Toast.makeText(this@ShareTargetActivity,
                            "Не удалось получить IP пира", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }
        wd.connect(mac)
    }

    // ═══════════════════════════════════════════════════════════
    //  Wi-Fi Direct (P2P) — мобильный flow
    // ═══════════════════════════════════════════════════════════
    private fun startWifiDirectFlow(parent: BottomSheetDialog) {
        InAppLogger.d("ShareTarget", "P2P search START")
        val wd = AtomController.wifiDirect
        if (wd == null) {
            Toast.makeText(this, "Wi-Fi Direct не инициализирован", Toast.LENGTH_SHORT).show()
            return
        }
        wd.stopContinuousDiscover()

        val b = SheetSearchBinding.inflate(LayoutInflater.from(this))
        parent.setContentView(b.root)
        b.spinner.visibility = View.VISIBLE
        b.emptyText.visibility = View.GONE

        // Wi-Fi Direct ищет бесконечно, пока пользователь не отменит
        // или пока не найдёт пира. Без таймаута.
        var found = false

        // Кнопка «Отмена» сразу
        b.emptyText.visibility = View.VISIBLE
        b.emptyText.text = "Ищу через Wi-Fi Direct…\n\n" +
            "На втором телефоне достаточно:\n" +
            "просто открыть Атом\n\n" +
            "Он сам станет видимым через несколько секунд."
        val cancelBtn = com.google.android.material.button.MaterialButton(this@ShareTargetActivity).apply {
            text = "Отмена"
            setOnClickListener {
                // НЕ вызываем cleanup() — он ломает channel и receiver
                // Просто закрываем диалог, continuous discover продолжает работать
                parent.dismiss()
            }
        }
        b.peersContainer.addView(cancelBtn)

        wd.onPeersChanged = onPeersChanged@ { peers ->
            if (found) return@onPeersChanged
            val filtered = peers.filter { it.deviceName.isNotBlank() }
            InAppLogger.d("ShareTarget", "p2p peers: ${filtered.map { "${it.deviceName}/${it.deviceAddress}" }}")
            if (filtered.isNotEmpty()) {
                found = true
                filtered.forEach { p ->
                    if (p.deviceAddress.isNotBlank()) {
                        AtomController.saveKnownP2pPeer(p.deviceAddress, p.deviceName)
                    }
                }
                b.spinner.visibility = View.GONE
                b.emptyText.visibility = View.GONE
                b.peersContainer.removeAllViews()
                for (p in filtered) {
                    val row = TextView(this@ShareTargetActivity).apply {
                        text = "📶 ${p.deviceName}\n     ${p.deviceAddress}"
                        textSize = 15f
                        setPadding(24, 24, 24, 24)
                        isClickable = true
                        setOnClickListener {
                            connectP2p(p.deviceAddress, p.deviceName, parent)
                        }
                    }
                    b.peersContainer.addView(row)
                }
            }
        }

        wd.onError = { err ->
            InAppLogger.d("ShareTarget", "p2p error: $err")
            Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
            b.spinner.visibility = View.GONE
        }

        wd.discoverPeers()
    }

    private fun connectP2p(mac: String, name: String, parent: BottomSheetDialog) {
        InAppLogger.d("ShareTarget", "connect START: mac=$mac name=$name")
        val wd = AtomController.wifiDirect ?: return
        val b = SheetSearchBinding.inflate(LayoutInflater.from(this))
        parent.setContentView(b.root)
        b.spinner.visibility = View.VISIBLE
        b.emptyText.visibility = View.VISIBLE
        b.emptyText.text = "Подключаюсь к $name…\n(10-20 сек)"

        var done = false
        val timeoutJob = lifecycleScope.launch {
            delay(20000)
            if (!done) {
                done = true
                Toast.makeText(this@ShareTargetActivity,
                    "Не удалось подключиться через Wi-Fi Direct. Попробуй ещё раз.",
                    Toast.LENGTH_LONG).show()
                parent.dismiss()
            }
        }

        wd.onConnected = onConnected@ { conn ->
            if (done) return@onConnected
            done = true
            timeoutJob.cancel()
            InAppLogger.d("ShareTarget", "p2p connected: isGO=${conn.isGroupOwner} goIp=${conn.groupOwnerAddress}")
            AtomController.saveKnownP2pPeer(mac, name)

            // Отправляем HELLO через GO IP (если мы клиент) — иначе ждём HELLO
            val node = AtomController.meshNode
            if (node != null && !conn.isGroupOwner) {
                node.sendHello(conn.groupOwnerAddress)
            }

            // Ждём 2-3 сек чтобы пир узнал наш IP / мы узнали его IP
            lifecycleScope.launch {
                delay(2500)
                val peer = AtomController.peers.value.firstOrNull {
                    it.ip.startsWith("192.168.49.") && it.id != AtomController.myId
                }
                if (peer != null) {
                    InAppLogger.d("ShareTarget", "p2p peer resolved: ${peer.name} @ ${peer.ip}")
                    parent.dismiss()
                    Toast.makeText(this@ShareTargetActivity,
                        "Wi-Fi Direct → ${peer.name}", Toast.LENGTH_SHORT).show()
                    FileSenderService.startTransfer(this@ShareTargetActivity, peer, uris, urisRelativePaths)
                    finish()
                } else {
                    InAppLogger.d("ShareTarget", "p2p peer not in list, falling back to ping")
                    // Пробуем GO IP напрямую, если мы клиент
                    val targetIp = if (!conn.isGroupOwner) conn.groupOwnerAddress else null
                    if (targetIp != null) {
                        val fakePeer = Peer(id = "p2p", name = name, ip = targetIp)
                        parent.dismiss()
                        Toast.makeText(this@ShareTargetActivity,
                            "Wi-Fi Direct → $name", Toast.LENGTH_SHORT).show()
                        FileSenderService.startTransfer(this@ShareTargetActivity, fakePeer, uris, urisRelativePaths)
                        finish()
                    } else {
                        Toast.makeText(this@ShareTargetActivity,
                            "Не удалось получить IP пира", Toast.LENGTH_LONG).show()
                        parent.dismiss()
                    }
                }
            }
        }

        wd.connect(mac)
    }


    /**
     * Единая проверка всего перед поиском:
     * разрешения, режим полёта, Wi-Fi, геолокация.
     * Если всё ок — onReady(). Иначе — диалог + открыть настройки.
     * После возврата из настроек onResume продолжит автоматически.
     */
    private fun preflightCheck(parent: BottomSheetDialog?, onReady: () -> Unit) {
        pendingPreflightCallback = onReady
        PreflightDialog.check(
            activity = this,
            onRequestPermissions = {
                val missing = PermissionsHelper.missing(this)
                if (missing.isEmpty()) {
                    // Разрешения уже есть, но остались системные проблемы
                    preflightCheck(parent, onReady)
                } else {
                    InAppLogger.d("ShareTarget", "requesting permissions: $missing")
                    permissionLauncher.launch(missing.toTypedArray())
                }
            },
            onOpenSystemSettings = { issue ->
                InAppLogger.d("ShareTarget", "opening settings for ${issue.id}")
                PreflightChecker.openSettingsFor(this, issue)
            },
            onReady = {
                pendingPreflightCallback = null
                onReady()
            }
        )
    }

}
