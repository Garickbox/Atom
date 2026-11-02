package com.garickbox.atom

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.garickbox.atom.databinding.ActivityHistoryBinding
import com.mesh.core.InAppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * B87: список 50 последних передач из TransferHistory.
 * Тап → открыть файл (если savedPath) или диалог с деталями.
 */
class HistoryActivity : AppCompatActivity() {
    private lateinit var b: ActivityHistoryBinding
    // B91: фильтр all|success|failed
    private var currentFilter: String = "all"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TvHelper.isTv(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        b = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }
        loadHistory()
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.history, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_filter_all -> { currentFilter = "all"; loadHistory(); true }
            R.id.action_filter_success -> { currentFilter = "success"; loadHistory(); true }
            R.id.action_filter_failed -> { currentFilter = "failed"; loadHistory(); true }
            R.id.action_export_csv -> { exportToCsv(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun exportToCsv() {
        val records = TransferHistory.list(this)
        if (records.isEmpty()) {
            Toast.makeText(this, "История пуста", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val csv = StringBuilder()
            csv.appendLine("timestamp,peer,file,bytes,status,duration_ms,saved_path")
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            records.forEach { r ->
                csv.append(fmt.format(Date(r.timestamp))).append(',')
                csv.append(escapeCsv(r.peerName)).append(',')
                csv.append(escapeCsv(r.fileName)).append(',')
                csv.append(r.bytes).append(',')
                csv.append(r.status).append(',')
                csv.append(r.durationMs).append(',')
                csv.append(escapeCsv(r.savedPath)).appendLine()
            }
            val fileName = "history-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv"
            val tempFile = java.io.File(cacheDir, fileName)
            tempFile.writeText(csv.toString())
            val result = MediaStoreSaver.save(this, tempFile, fileName, "text/csv")
            tempFile.delete()
            if (result.uri != null || result.displayPath.isNotBlank()) {
                Toast.makeText(this,
                    "CSV сохранён: " + result.displayPath, Toast.LENGTH_LONG).show()
                InAppLogger.d("History", "B88 CSV export: " + records.size + " records")
            } else {
                Toast.makeText(this, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: " + e.message, Toast.LENGTH_LONG).show()
            InAppLogger.err("History", "B88 CSV: " + (e.message ?: "?"))
        }
    }

    private fun escapeCsv(s: String): String {
        if (s.isBlank()) return ""
        return if (s.contains(',') || s.contains('"') || s.contains('\n'))
            "\"" + s.replace("\"", "\"\"") + "\""
        else s
    }

    private fun loadHistory() {
        val all = TransferHistory.list(this)
        val records = when (currentFilter) {
            "success" -> all.filter { it.status == "success" }
            "failed" -> all.filter { it.status == "failed" || it.status == "skipped" }
            else -> all
        }
        InAppLogger.d("History", "B91 filter=" + currentFilter + " records=" + records.size + "/" + all.size)
        if (records.isEmpty()) {
            b.emptyText.visibility = View.VISIBLE
            b.recycler.visibility = View.GONE
            return
        }
        b.emptyText.visibility = View.GONE
        b.recycler.visibility = View.VISIBLE
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = HistoryAdapter(records) { rec -> onItemTap(rec) }
    }

    private fun onItemTap(rec: TransferHistory.Record) {
        if (rec.savedPath.isNotBlank()) {
            // Попытка открыть файл (для сохранённых из Download/Atom)
            try {
                val uri = Uri.fromFile(java.io.File(rec.savedPath))
                val mime = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(rec.savedPath.substringAfterLast('.', "").lowercase())
                    ?: "*/*"
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                Toast.makeText(this, "Не открывается: " + e.message, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this,
                rec.peerName + " · " + rec.status + " · " + rec.bytes + " B",
                Toast.LENGTH_LONG).show()
        }
    }

    private class HistoryAdapter(
        val items: List<TransferHistory.Record>,
        val onTap: (TransferHistory.Record) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {
        private val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

        class VH(val row: View) : RecyclerView.ViewHolder(row) {
            val title: TextView = row.findViewById(android.R.id.text1)
            val subtitle: TextView = row.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = items[pos]
            val icon = when (r.status) {
                "success" -> "✅"
                "failed" -> "❌"
                "skipped" -> "⏭"
                "corrupted" -> "⚠️"
                else -> "•"
            }
            val dir = if (r.savedPath.isNotBlank()) "📥" else "📤"
            h.title.text = "$icon $dir ${r.fileName}"
            h.subtitle.text = "${r.peerName} · ${fmtBytes(r.bytes)} · ${fmt.format(Date(r.timestamp))}" +
                (if (r.savedPath.isNotBlank()) "\n${r.savedPath}" else "")
            h.row.setOnClickListener { onTap(r) }
        }

        private fun fmtBytes(b: Long): String {
            if (b < 1024) return "$b Б"
            if (b < 1024 * 1024) return "%.1f КБ".format(b / 1024.0)
            if (b < 1024 * 1024 * 1024) return "%.1f МБ".format(b / 1024.0 / 1024.0)
            return "%.1f ГБ".format(b / 1024.0 / 1024.0 / 1024.0)
        }
    }
}
