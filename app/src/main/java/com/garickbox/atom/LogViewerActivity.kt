package com.garickbox.atom

import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.garickbox.atom.databinding.ActivityLogViewerBinding
import com.mesh.core.InAppLogger

class LogViewerActivity : AppCompatActivity() {
    private lateinit var b: ActivityLogViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.garickbox.atom.TvHelper.isTv(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        b = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.btnShare.setOnClickListener { shareLog(compact = true) }
        b.btnShare.setOnLongClickListener {
            shareLog(compact = false)
            true
        }
        b.btnSaveToDownloads.setOnClickListener { saveLogToDownloads() }
        b.btnClear.setOnClickListener { clearLog() }
        loadLog()
    }

    private fun loadLog() {
        val file = InAppLogger.getLogFile()
        if (file == null) {
            b.logText.text = "Лог пуст.\n\nЛоги появятся после действий в приложении."
            return
        }
        val text = try { file.readText() } catch (e: Exception) { "Ошибка: ${e.message}" }
        b.logText.text = if (text.isBlank()) "Лог пуст." else text
        b.scrollView.post { b.scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun shareLog(compact: Boolean = true) {
        val file = if (compact) {
            com.mesh.core.CompactLog.getLogFile() ?: InAppLogger.getLogFile()
        } else {
            InAppLogger.getLogFile()
        } ?: run {
            Toast.makeText(this, "Лог пуст", Toast.LENGTH_SHORT).show(); return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Atom log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Поделиться логом"))
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveLogToDownloads() {
        val file = InAppLogger.getLogFile() ?: run {
            Toast.makeText(this, "Лог пуст", Toast.LENGTH_SHORT).show()
            return
        }
        val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val fileName = "atom-" + ts + ".log"
        Toast.makeText(this, "Сохраняю...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = MediaStoreSaver.save(
                this@LogViewerActivity, file, fileName, "text/plain"
            )
            withContext(Dispatchers.Main) {
                if (result.displayPath.isNotBlank()) {
                    Toast.makeText(this@LogViewerActivity,
                        "Сохранено: " + result.displayPath,
                        Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@LogViewerActivity,
                        "Ошибка сохранения лога",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun clearLog() {
        val file = InAppLogger.getLogFile() ?: return
        try {
            file.delete()
            Toast.makeText(this, "Лог очищен", Toast.LENGTH_SHORT).show()
            loadLog()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
