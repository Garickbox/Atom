package com.garickbox.atom

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mesh.core.InAppLogger
import kotlinx.coroutines.launch

class FileBrowserActivity : AppCompatActivity() {

    data class Category(
        val id: String, val title: String, val icon: String, val count: Int,
        val subtitle: String = ""
    )

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

    private var pendingPrefsKey: String? = null
    private var pendingTitle: String? = null

    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { pendingPrefsKey = null; pendingTitle = null; return@registerForActivityResult }
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val key = pendingPrefsKey
        val title = pendingTitle ?: "Файлы"
        if (key != null) getSharedPreferences("atom", MODE_PRIVATE).edit().putString(key, uri.toString()).apply()
        startActivity(Intent(this, FileListActivity::class.java).apply {
            putExtra(FileListActivity.EXTRA_TREE_URI, uri.toString())
            putExtra(FileListActivity.EXTRA_TITLE, title)
        })
        pendingPrefsKey = null
        pendingTitle = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TvHelper.isTv(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        setContent {
            MaterialTheme {
                FileBrowserCompose()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun FileBrowserCompose() {
        val cats = remember { loadCategories() }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                ModalDrawerSheet {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "⚛️ Atom",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Text(
                        "Файловый менеджер · P2P",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    cats.forEach { cat ->
                        NavigationDrawerItem(
                            icon = { Text(cat.icon, fontSize = 22.sp) },
                            label = { Text(cat.title, fontWeight = FontWeight.Medium) },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                onCategoryTap(cat)
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("📂 Файлы", fontWeight = FontWeight.Bold)
                                Text("P2P · без облака", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Text("☰", fontSize = 24.sp)
                            }
                        }
                    )
                }
            ) { pad ->
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().padding(pad)
                ) {
                    items(cats) { cat ->
                        CategoryCard(cat) { onCategoryTap(cat) }
                    }
                }
            }
        }
    }

    @Composable
    private fun CategoryCard(cat: Category, onClick: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth().height(140.dp).clickable { onClick() },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(cat.icon, fontSize = 40.sp)
                    if (cat.count > 0) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                cat.count.toString(),
                                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Column {
                    Text(
                        cat.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (cat.subtitle.isNotBlank()) {
                        Text(
                            cat.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    private fun loadCategories(): List<Category> {
        return listOf(
            Category("internal", "Внутренняя", "📱", 0, "любые папки"),
            Category("images", "Фото", "🖼", countMedia(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)),
            Category("video", "Видео", "🎬", countMedia(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI)),
            Category("audio", "Музыка", "🎵", countMedia(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)),
            Category("apk", "APK", "📦", countApk()),
            Category("downloads", "Загрузки", "📥", countDownloads()),
            Category("android", "Android/data", "🔒", 0, "SAF · как X-plore"),
            Category("recent", "Недавние", "🕒", 0, "скоро")
        )
    }

    private fun countMedia(uri: Uri): Int = try {
        contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns._ID), null, null, null)?.use { it.count } ?: 0
    } catch (_: Exception) { 0 }

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
            "internal" -> launchTreePicker("saf_internal_uri", "📱 Внутренняя", "primary:")
            "android" -> launchTreePicker("saf_android_uri", "🔒 Android", "primary:Android")
            else -> android.widget.Toast.makeText(this, "📂 ${cat.title} — скоро (B184b)", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchTreePicker(key: String, title: String, initialDocPath: String) {
        val saved = getSharedPreferences("atom", MODE_PRIVATE).getString(key, null)
        if (saved != null) {
            startActivity(Intent(this, FileListActivity::class.java).apply {
                putExtra(FileListActivity.EXTRA_TREE_URI, saved)
                putExtra(FileListActivity.EXTRA_TITLE, title)
            })
            return
        }
        pendingPrefsKey = key
        pendingTitle = title
        val initialUri = try {
            android.provider.DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", initialDocPath)
        } catch (_: Exception) { null }
        InAppLogger.d("FileBrowser", "launchTreePicker initial=$initialDocPath uri=$initialUri")
        treePicker.launch(initialUri)
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
}
