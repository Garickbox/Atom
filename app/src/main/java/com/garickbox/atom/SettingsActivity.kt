package com.garickbox.atom

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.garickbox.atom.databinding.ActivitySettingsBinding
import com.mesh.core.InAppLogger
import com.mesh.core.CompactLog

/**
 * B88: настройки — имя, известные peers, очистка истории/логов, инфо.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TvHelper.isTv(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.nameValue.text = AtomController.myName.ifBlank { "(не задано)" }
        b.nameRow.setOnClickListener {
            startActivity(Intent(this, NameSetupActivity::class.java))
        }

        b.peersCount.text = AtomController.getKnownP2pPeers().size.toString() + " peers"
        b.peersRow.setOnClickListener { showPeersDialog() }

        b.buildValue.text = "build-" + BuildConfig.VERSION_CODE + " · " + BuildConfig.VERSION_NAME
        b.deviceValue.text = android.os.Build.MANUFACTURER + "/" + android.os.Build.MODEL
        b.myIdValue.text = AtomController.myId.take(8) + "…"

        b.clearHistoryRow.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Очистить историю?")
                .setMessage("Удалить все " + TransferHistory.list(this).size + " записей")
                .setPositiveButton("Очистить") { _, _ ->
                    TransferHistory.clear(this)
                    Toast.makeText(this, "История очищена", Toast.LENGTH_SHORT).show()
                    InAppLogger.d("Settings", "history cleared")
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        b.clearLogsRow.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Очистить логи?")
                .setMessage("Удалить atom.log, atom-err.log, atom-compact.log")
                .setPositiveButton("Очистить") { _, _ ->
                    try {
                        InAppLogger.clearAll()
                        CompactLog.clear()
                        Toast.makeText(this, "Логи очищены", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Ошибка: " + e.message, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }
    }

    private fun showPeersDialog() {
        val peers = AtomController.getKnownP2pPeers()
        if (peers.isEmpty()) {
            Toast.makeText(this, "Нет известных peers", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = peers.map { p ->
            p.name + "\n" + p.mac + " · " + ago(p.lastConnectedMs)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Известные peers (" + peers.size + ")")
            .setItems(labels, null)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun ago(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000 -> "сейчас"
            diff < 3_600_000 -> (diff / 60_000).toString() + " мин назад"
            diff < 86_400_000 -> (diff / 3_600_000).toString() + " ч назад"
            else -> (diff / 86_400_000).toString() + " дн назад"
        }
    }
}
