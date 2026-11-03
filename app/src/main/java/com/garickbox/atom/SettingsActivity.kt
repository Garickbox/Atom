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

        // B105: batteryValue.text — статус whitelist (пустая строка в XML)
        try {
            val wl = BatteryOptimizationHelper.isWhitelisted(this)
            b.batteryValue.text = if (wl) "✅ Разрешено" else "⚠️ Ограничено"
            b.batteryValue.setTextColor(if (wl)
                android.graphics.Color.parseColor("#2E7D32")
            else android.graphics.Color.parseColor("#F57C00"))
        } catch (_: Exception) {}

        // B94: battery row — статус + кнопка "Разрешить" если не whitelisted
        b.batteryRow.setOnClickListener {
            if (!BatteryOptimizationHelper.isWhitelisted(this)) {
                try {
                    BatteryOptimizationHelper.requestWhitelist(this)
                } catch (e: Exception) {
                    Toast.makeText(this, "Ошибка: " + e.message, Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "✅ Фоновая работа уже разрешена", Toast.LENGTH_SHORT).show()
            }
        }

        b.nameValue.text = AtomController.myName.ifBlank { "(не задано)" }
        b.nameRow.setOnClickListener {
            startActivity(Intent(this, NameSetupActivity::class.java))
        }

        b.peersCount.text = AtomController.getKnownP2pPeers().size.toString()
        b.peersRow.setOnClickListener { showPeersDialog() }

        b.buildValue.text = "build-" + BuildConfig.VERSION_CODE + " · " + BuildConfig.VERSION_NAME
        b.deviceValue.text = android.os.Build.MANUFACTURER + "/" + android.os.Build.MODEL
        b.myIdValue.text = AtomController.myId.take(8) + "…"

        b.forceUpdateRow.setOnClickListener {
            // B120: forceUpdate = переустановка из скачанного APK, БЕЗ fallback на check
            UpdateChecker.setForceReinstall(true)
            try {
                val ready = UpdateChecker.findDownloadedApk(this)
                if (ready != null) {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Переустановить")
                        .setMessage("Найден " + ready.name + ". Установить заново?")
                        .setPositiveButton("Установить") { _, _ ->
                            try {
                                UpdateChecker.installApkFromUriPublic(this, ready.uri)
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(this,
                                    "Ошибка: " + e.message, android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                } else {
                    android.widget.Toast.makeText(this,
                        "Нет скачанного APK — нажми 🔄 в тулбаре",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                UpdateChecker.setForceReinstall(false)
            }
        }

        // B120: ДИАГНОСТИКА
        b.selfTestRow.setOnClickListener {
            startActivity(Intent(this, SelfTestActivity::class.java))
        }
        b.logViewerRow.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }

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
                        // B88.1: прямая очистка файлов (clearAll/clear откачены в B65)
                        val bases = listOfNotNull(
                            getExternalFilesDir("logs"),
                            java.io.File(filesDir, "logs")
                        )
                        var deleted = 0
                        bases.forEach { base ->
                            base.listFiles()?.forEach { f ->
                                if (f.delete()) deleted++
                            }
                        }
                        Toast.makeText(this,
                            "Удалено файлов: " + deleted, Toast.LENGTH_SHORT).show()
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
            .setTitle("Известные устройства (" + peers.size + ")")
            .setItems(labels) { _, which ->
                val peer = peers[which]
                AlertDialog.Builder(this)
                    .setTitle("Удалить ${peer.name}?")
                    .setMessage(peer.mac)
                    .setPositiveButton("Удалить") { _, _ ->
                        AtomController.removeKnownP2pPeer(peer.mac)
                        showPeersDialog()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
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
