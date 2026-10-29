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
    private var dialog: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InAppLogger.d("ShareTargetActivity", "onCreate action=${intent?.action}")

        if (!AtomController.hasName()) {
            startActivity(Intent(this, NameSetupActivity::class.java))
            finish()
            return
        }

        uris = collectUris(intent)
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

        showStep1()
    }

    private fun collectUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val list = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { list.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { list.addAll(it) }
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
        val b = SheetShareStep2Binding.inflate(LayoutInflater.from(this))
        parent.setContentView(b.root)

        b.searchRow.setOnClickListener {
            preflightCheck(parent) { startSearch(parent) }
        }
        b.wifiDirectRow.setOnClickListener {
            preflightCheck(parent) { startWifiDirectFlow(parent) }
        }
        b.scanRow.setOnClickListener {
            Toast.makeText(this, "QR будет в следующем патче", Toast.LENGTH_SHORT).show()
        }
        b.showQrRow.setOnClickListener {
            Toast.makeText(this, "QR будет в следующем патче", Toast.LENGTH_SHORT).show()
        }
        b.backBtn.setOnClickListener { showStep1() }
    }

    private fun startSearch(parent: BottomSheetDialog) {
        val b = SheetSearchBinding.inflate(LayoutInflater.from(this))
        parent.setContentView(b.root)
        AtomController.peers.value = emptyList()
        // Стоп continuous — не мешаем ручному поиску
        AtomController.wifiDirect?.stopContinuousDiscover()

        lifecycleScope.launch {
            val node = AtomController.meshNode
            if (node != null) {
                node.broadcastHello()
                delay(600)
                node.broadcastHello()
            }
            delay(6500)
            b.spinner.visibility = View.GONE
            val peers = AtomController.peers.value.filter { it.id != AtomController.myId }
            if (peers.isEmpty()) {
                showEmptySearchDialog(b, parent)
            } else {
                for (p in peers) {
                    val row = TextView(this@ShareTargetActivity).apply {
                        text = "📱 ${p.name}  (${p.ip})"
                        textSize = 16f
                        setPadding(0, 24, 0, 24)
                        isClickable = true
                        setOnClickListener { onPeerSelected(p) }
                    }
                    b.peersContainer.addView(row)
                }
            }
        }
    }

    private fun showEmptySearchDialog(b: SheetSearchBinding, parent: BottomSheetDialog) {
        b.emptyText.visibility = View.VISIBLE
        b.emptyText.text = "Никого не нашли через Wi-Fi.\n\n" +
            "Проверь:\n" +
            "• Оба телефона в одной Wi-Fi сети\n" +
            "• На роутере не включена изоляция клиентов\n" +
            "• На втором телефоне открыт Атом\n\n" +
            "Или попробуй Wi-Fi Direct — он работает " +
            "без роутера, достаточно открыть Атом на обоих."

        val retryBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "🔄 Повторить"
            setOnClickListener {
                b.emptyText.visibility = View.GONE
                b.spinner.visibility = View.VISIBLE
                b.peersContainer.removeAllViews()
                startSearch(parent)
            }
        }
        val p2pBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "📶 Wi-Fi Direct"
            setOnClickListener {
                b.emptyText.visibility = View.GONE
                b.spinner.visibility = View.VISIBLE
                b.peersContainer.removeAllViews()
                startWifiDirectFlow(parent)
            }
        }
        b.peersContainer.addView(retryBtn)
        b.peersContainer.addView(p2pBtn)
    }

    private fun onPeerSelected(peer: Peer) {
        InAppLogger.d("ShareTargetActivity",
            "peer selected: ${peer.name} ip=${peer.ip} mac=${peer.mac}")

        // Случай 1: MAC есть — пытаемся быстро probe, если мёртв → auto P2P
        if (peer.mac.isNotEmpty()) {
            lifecycleScope.launch {
                val node = AtomController.meshNode
                val alive = node?.probePeer(peer.ip, 800) ?: false
                if (alive) {
                    InAppLogger.d("ShareTarget", "peer alive @ ${peer.ip} → direct")
                    startTransferNow(peer)
                } else {
                    InAppLogger.d("ShareTarget",
                        "peer dead @ ${peer.ip} → auto-P2P connect to ${peer.mac}")
                    autoP2pConnect(peer.mac, peer.name)
                }
            }
            return
        }

        // Случай 2: MAC нет (старый формат prefs) — просто пробуем
        InAppLogger.d("ShareTarget", "peer has no MAC → direct transfer attempt")
        startTransferNow(peer)
    }

    private fun startTransferNow(peer: Peer) {
        checkBatteryForLarge {
            dialog?.dismiss()
            Toast.makeText(this, "Отправка ${uris.size} файл(ов) → ${peer.name}",
                Toast.LENGTH_SHORT).show()
            FileSenderService.startTransfer(this, peer, uris)
            finish()
        }
    }

    /** Автоматический P2P connect без ручного выбора в списке. */
    private fun autoP2pConnect(mac: String, name: String) {
        val d = dialog ?: BottomSheetDialog(this).also {
            dialog = it
        }
        connectP2p(mac, name, d)
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
    //  Wi-Fi Direct (P2P)
    // ═══════════════════════════════════════════════════════════
    private fun startWifiDirectFlow(parent: BottomSheetDialog) {
        val wd = AtomController.wifiDirect
        if (wd == null) {
            Toast.makeText(this, "Wi-Fi Direct не инициализирован", Toast.LENGTH_SHORT).show()
            return
        }
        // СТОП continuous — чтобы не было BUSY при removeGroup/discoverPeers
        InAppLogger.d("ShareTarget", "stop continuous before manual discover")
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
                parent.dismiss()
                // Возобновляем continuous discover после отмены
                AtomController.wifiDirect?.startContinuousDiscover()
            }
        }
        b.peersContainer.addView(cancelBtn)

        wd.onPeersChanged = onPeersChanged@ { peers ->
            if (found) return@onPeersChanged
            val filtered = peers.filter { it.deviceName.isNotBlank() }
            InAppLogger.d("ShareTarget", "p2p peers: ${filtered.map { "${it.deviceName}/${it.deviceAddress}" }}")
            if (filtered.isNotEmpty()) {
                found = true
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
                    // Сохраняем MAC — для auto-reconnect в следующий раз
                    val savedPeer = Peer(
                        id = peer.id,
                        name = peer.name,
                        ip = peer.ip,
                        mac = mac
                    )
                    AtomController.saveLastPeer(savedPeer)
                    parent.dismiss()
                    Toast.makeText(this@ShareTargetActivity,
                        "Wi-Fi Direct → ${peer.name}", Toast.LENGTH_SHORT).show()
                    FileSenderService.startTransfer(this@ShareTargetActivity, savedPeer, uris)
                    finish()
                } else {
                    InAppLogger.d("ShareTarget", "p2p peer not in list, falling back to ping")
                    // Пробуем GO IP напрямую, если мы клиент
                    val targetIp = if (!conn.isGroupOwner) conn.groupOwnerAddress else null
                    if (targetIp != null) {
                        val fakePeer = Peer(id = "p2p", name = name, ip = targetIp, mac = mac)
                        AtomController.saveLastPeer(fakePeer)
                        parent.dismiss()
                        Toast.makeText(this@ShareTargetActivity,
                            "Wi-Fi Direct → $name", Toast.LENGTH_SHORT).show()
                        FileSenderService.startTransfer(this@ShareTargetActivity, fakePeer, uris)
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
    private fun preflightCheck(parent: BottomSheetDialog, onReady: () -> Unit) {
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


    override fun onDestroy() {
        super.onDestroy()
        // Если continuous был остановлен — возобновим
        AtomController.wifiDirect?.startContinuousDiscover()
        InAppLogger.d("ShareTarget", "onDestroy — continuous resumed")
    }

}
