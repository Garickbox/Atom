package com.garickbox.atom

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.garickbox.atom.databinding.ActivityFileListBinding
import com.mesh.core.InAppLogger

class FileListActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TREE_URI = "tree_uri"
        const val EXTRA_TITLE = "title"
    }

    data class Row(val docId: String, val uri: Uri, val name: String, val isDir: Boolean, val size: Long, val relPath: String)

    private lateinit var b: ActivityFileListBinding
    private lateinit var treeUri: Uri
    private lateinit var rootDocId: String
    private var currentParentId: String = ""
    private var currentPath: String = ""
    private val stack = ArrayDeque<Pair<String, String>>()
    private val selected = LinkedHashMap<String, Row>()  // docId -> Row

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TvHelper.isTv(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        b = ActivityFileListBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { goBack() }

        val treeStr = intent.getStringExtra(EXTRA_TREE_URI)
        if (treeStr.isNullOrBlank()) { finish(); return }
        treeUri = Uri.parse(treeStr)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Файлы"
        b.toolbar.title = title
        rootDocId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { finish(); return }
        currentParentId = rootDocId
        currentPath = ""

        b.fabSend.setOnClickListener { sendSelected() }
        loadChildren(currentParentId, currentPath)
    }

    private fun loadChildren(parentId: String, path: String) {
        currentParentId = parentId
        currentPath = path
        val items = mutableListOf<Row>()
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            contentResolver.query(childrenUri, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
            ), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    val rel = if (path.isBlank()) name else "$path/$name"
                    items.add(Row(id, docUri, name, isDir, size, rel))
                }
            }
        } catch (e: Exception) {
            InAppLogger.err("FileList", "loadChildren: ${e.message}")
        }
        items.sortWith(compareByDescending<Row> { it.isDir }.thenBy { it.name.lowercase() })
        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = FileAdapter(items) { row -> onRowTap(row) }
        updateFab()
    }

    private fun onRowTap(row: Row) {
        if (row.isDir) {
            stack.addLast(currentParentId to currentPath)
            loadChildren(row.docId, row.relPath)
        } else {
            if (selected.containsKey(row.docId)) {
                selected.remove(row.docId)
            } else {
                selected[row.docId] = row
            }
            b.recycler.adapter?.notifyDataSetChanged()
            updateFab()
        }
    }

    private fun goBack() {
        if (stack.isNotEmpty()) {
            val (pid, ppath) = stack.removeLast()
            loadChildren(pid, ppath)
        } else {
            finish()
        }
    }

    override fun onBackPressed() {
        if (stack.isNotEmpty()) goBack() else super.onBackPressed()
    }

    private fun updateFab() {
        if (selected.isEmpty()) {
            b.fabSend.visibility = View.GONE
        } else {
            b.fabSend.visibility = View.VISIBLE
            val total = selected.values.sumOf { it.size }
            b.fabSend.text = "📤 Отправить (${selected.size}) · ${fmtBytes(total)}"
        }
    }

    private fun sendSelected() {
        if (selected.isEmpty()) return
        val rows = selected.values.toList()
        val uris = rows.map { it.uri }
        val paths = rows.map { it.relPath }
        val intent = Intent(this, ShareTargetActivity::class.java).apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            type = "*/*"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            putStringArrayListExtra("atom_relative_paths", ArrayList(paths))
            clipData = android.content.ClipData.newRawUri("", uris.first()).apply {
                for (i in 1 until uris.size) addItem(android.content.ClipData.Item(uris[i]))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        InAppLogger.d("FileList", "send ${uris.size} files: ${rows.map { it.relPath }}")
        startActivity(intent)
        finish()
    }

    private inner class FileAdapter(val items: List<Row>, val onTap: (Row) -> Unit) : RecyclerView.Adapter<FileAdapter.VH>() {
        inner class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val icon: TextView = v.findViewById(R.id.row_icon)
            val name: TextView = v.findViewById(R.id.row_name)
            val subtitle: TextView = v.findViewById(R.id.row_subtitle)
            val check: CheckBox = v.findViewById(R.id.row_check)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_file_row, parent, false))
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val r = items[pos]
            h.icon.text = if (r.isDir) "📁" else "📄"
            h.name.text = r.name
            h.subtitle.text = if (r.isDir) "папка" else fmtBytes(r.size)
            h.check.visibility = if (r.isDir) View.GONE else View.VISIBLE
            h.check.isChecked = selected.containsKey(r.docId)
            h.check.setOnClickListener { onTap(r) }
            h.v.setOnClickListener { onTap(r) }
        }
    }

    private fun fmtBytes(b: Long): String {
        if (b < 1024) return "$b Б"
        if (b < 1024 * 1024) return "%.1f КБ".format(b / 1024.0)
        if (b < 1024 * 1024 * 1024) return "%.1f МБ".format(b / 1024.0 / 1024.0)
        return "%.1f ГБ".format(b / 1024.0 / 1024.0 / 1024.0)
    }
}
