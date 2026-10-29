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
    private var pendingLocationCallback: (() -> Unit)? = null

    override fun onResume() {
        super.onResume()
        val cb = pendingLocationCallback ?: return
        if (LocationHelper.isEnabled(this)) {
            InAppLogger.d("ShareTarget", "location enabled after prompt, continuing")
            pendingLocationCallback = null
            cb.invoke()
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
            ensureLocation { startSearch(parent) }
        }
        b.wifiDirectRow.setOnClickListener {
            ensureLocation { startWifiDirectFlow(parent) }
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
                b.emptyText.visibility = View.VISIBLE
                b.emptyText.text = getString(R.string.share_nobody)
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

    private fun onPeerSelected(peer: Peer) {
        InAppLogger.d("ShareTargetActivity", "peer selected: ${peer.name} (${peer.ip})")
        dialog?.dismiss()
        Toast.makeText(this, "Отправка ${uris.size} файл(ов) → ${peer.name}", Toast.LENGTH_SHORT).show()
        FileSenderService.startTransfer(this, peer, uris)
        finish()
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

        val b = SheetSearchBinding.inflate(LayoutInflater.from(this))
        parent.setContentView(b.root)
        b.spinner.visibility = View.VISIBLE
        b.emptyText.visibility = View.GONE

        var timedOut = false
        val timeoutJob = lifecycleScope.launch {
            delay(8000)
            if (!timedOut) {
                timedOut = true
                b.spinner.visibility = View.GONE
                if (b.peersContainer.childCount == 0) {
                    b.emptyText.visibility = View.VISIBLE
                    b.emptyText.text = "Wi-Fi Direct никого не нашёл.\n" +
                        "Проверь: Location Services включены, Wi-Fi Direct включён в настройках."
                }
            }
        }

        wd.onPeersChanged = onPeersChanged@ { peers ->
            if (timedOut) return@onPeersChanged
            val filtered = peers.filter { it.deviceName.isNotBlank() }
            InAppLogger.d("ShareTarget", "p2p peers: ${filtered.map { "${it.deviceName}/${it.deviceAddress}" }}")
            if (filtered.isNotEmpty()) {
                timedOut = true
                timeoutJob.cancel()
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
                    parent.dismiss()
                    Toast.makeText(this@ShareTargetActivity,
                        "Wi-Fi Direct → ${peer.name}", Toast.LENGTH_SHORT).show()
                    FileSenderService.startTransfer(this@ShareTargetActivity, peer, uris)
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
     * Проверка геолокации. Если выключена — диалог с объяснением и кнопкой "Включить".
     * После возврата с настроек onResume продолжит flow автоматически.
     */
    private fun ensureLocation(onOk: () -> Unit) {
        if (LocationHelper.isEnabled(this)) {
            onOk()
            return
        }
        InAppLogger.d("ShareTarget", "location disabled, prompting user")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📍 Геолокация выключена")
            .setMessage(
                "Android требует включённую геолокацию, чтобы искать " +
                "устройства поблизости по Wi-Fi. Без неё Wi-Fi Direct " +
                "не найдёт второй телефон.\n\n" +
                "Включи её — и мы продолжим автоматически."
            )
            .setPositiveButton("Включить") { _, _ ->
                pendingLocationCallback = onOk
                LocationHelper.openSettings(this)
            }
            .setNegativeButton("Всё равно искать") { _, _ ->
                InAppLogger.d("ShareTarget", "user chose to search without location")
                onOk()
            }
            .setCancelable(true)
            .show()
    }

}
