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

        b.searchRow.setOnClickListener { startSearch(parent) }
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
}
