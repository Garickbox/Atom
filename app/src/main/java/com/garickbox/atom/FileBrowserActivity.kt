package com.garickbox.atom

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.garickbox.atom.databinding.ActivityFileBrowserBinding
import com.mesh.core.InAppLogger

class FileBrowserActivity : AppCompatActivity() {

    data class Category(val id: String, val title: String, val icon: String, val count: Int, val subtitle: String = "")

    private lateinit var b: ActivityFileBrowserBinding

    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) sendToShareTarget(uris, "image/*")
    }
    private val videoPicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) sendToShareTarget(uris, "video/*")
    }
    private val audioPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != RESULT_OK || r.data == null) return@registerForActivityResult
        val uris = mutableListOf<Uri>()
        r.data?.clipData?.let { c -> for (i in 0 until c.itemCount) uris.add(c.getItemAt(i).uri) }
        r.data?.data?.let { uris.add(it) }
        if (uris.isNotEmpty()) sendToShareTarget(uris, "audio/*")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TvHelper.isTv(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        b = ActivityFileBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }
        loadCategories()
    }

    override fun onResume() {
        super.onResume()
        loadCategories()
    }

    private fun loadCategories() {
        val cats = mutableListOf<Category>()
        cats += Category("images", "Фото", "🖼", countMedia(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        cats += Category("video", "Видео", "🎬", countMedia(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI))
        cats += Category("audio", "Музыка", "🎵", countMedia(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI))
        cats += Category("apk", "APK", "📦", countApk())
        cats += Category("downloads", "Загрузки", "📥", countDownloads())
        cats += Category("recent", "Недавние", "🕒", 0, "Скоро (B184b)")

        b.recycler.layoutManager = LinearLayoutManager(this)
        b.recycler.adapter = Adapter(cats) { cat -> onCategoryTap(cat) }
        b.loading.visibility = View.GONE
    }

    private fun countMedia(uri: Uri): Int {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns._ID), null, null, null)?.use { it.count } ?: 0
        } catch (_: Exception) { 0 }
    }

    private fun countApk(): Int {
        if (android.os.Build.VERSION.SDK_INT < 29) return 0
        return try {
            contentResolver.query(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.MediaColumns._ID),
                "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("%.apk"), null
            )?.use { it.count } ?: 0
        } catch (_: Exception) { 0 }
    }

    private fun countDownloads(): Int {
        if (android.os.Build.VERSION.SDK_INT < 29) return 0
        return countMedia(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI)
    }

    private fun onCategoryTap(cat: Category) {
        InAppLogger.d("FileBrowser", "tap ${cat.id} count=${cat.count}")
        when (cat.id) {
            "images" -> imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            "video" -> videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            "audio" -> audioPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"; putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            })
            else -> android.widget.Toast.makeText(this, "📂 ${cat.title} — скоро (B184b)", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendToShareTarget(uris: List<Uri>, mime: String) {
        val intent = Intent(this, ShareTargetActivity::class.java).apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            type = mime
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = android.content.ClipData.newRawUri("", uris.first()).apply {
                for (i in 1 until uris.size) addItem(android.content.ClipData.Item(uris[i]))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
        finish()
    }

    private class Adapter(val items: List<Category>, val onClick: (Category) -> Unit) : RecyclerView.Adapter<Adapter.VH>() {
        class VH(val v: View) : RecyclerView.ViewHolder(v) {
            val icon: TextView = v.findViewById(R.id.cat_icon)
            val title: TextView = v.findViewById(R.id.cat_title)
            val subtitle: TextView = v.findViewById(R.id.cat_subtitle)
            val count: TextView = v.findViewById(R.id.cat_count)
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_file_category, parent, false))
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val c = items[pos]
            h.icon.text = c.icon
            h.title.text = c.title
            h.subtitle.text = c.subtitle
            h.count.text = if (c.count > 0) c.count.toString() else ""
            h.v.setOnClickListener { onClick(c) }
        }
    }
}
