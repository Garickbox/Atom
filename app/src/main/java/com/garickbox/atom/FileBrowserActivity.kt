package com.garickbox.atom

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

    data class Category(val id: String, val title: String, val icon: String, val count: Int, val subtitle: String = "")

    private var pendingPrefsKey: String? = null
    private var pendingTitle: String? = null
    private var pendingInitialUri: String? = null

    private val treePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { pendingPrefsKey = null; pendingTitle = null; pendingInitialUri = null; return@registerForActivityResult }
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val key = pendingPrefsKey
        val title = pendingTitle ?: "Файлы"
        if (key != null) getSharedPreferences("atom", MODE_PRIVATE).edit().putString(key, uri.toString()).apply()
        startActivity(Intent(this, FileListActivity::class.java).apply {
            putExtra(FileListActivity.EXTRA_TREE_URI, uri.toString())
            putExtra(FileListActivity.EXTRA_TITLE, title)
            putExtra(FileListActivity.EXTRA_MODE, "tree")
        })
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        pendingPrefsKey = null; pendingTitle = null; pendingInitialUri = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TvHelper.isTv(this)) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContent { MaterialTheme { BrowserUi() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun BrowserUi() {
        val cats = remember { loadCategories() }
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                ModalDrawerSheet {
                    Spacer(Modifier.height(24.dp))
                    Text("⚛️ Atom", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp))
                    Text("Файлы · P2P · без облака", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    cats.forEach { cat ->
                        NavigationDrawerItem(
                            icon = { Text(cat.icon, fontSize = 22.sp) },
                            label = { Text(cat.title, fontWeight = FontWeight.Medium) },
                            selected = false,
                            onClick = { scope.launch { drawerState.close() }; onCategoryTap(cat) },
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
                                Text("${cats.sumOf { it.count }} объектов · P2P", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) { Text("☰", fontSize = 24.sp) }
                        }
                    )
                }
            ) { pad ->
                LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    modifier = Modifier.fillMaxSize().padding(pad)
                ) {
                    items(cats) { cat ->
                        CategoryRow(cat) { onCategoryTap(cat) }
                    }
                }
            }
        }
    }

    @Composable
    private fun CategoryRow(cat: Category, onClick: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(cat.icon, fontSize = 26.sp, modifier = Modifier.size(36.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(cat.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (cat.subtitle.isNotBlank()) {
                    Text(cat.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (cat.count > 0) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                    Text(cat.count.toString(), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
    }

    private fun loadCategories(): List<Category> {
        val pm = packageManager
        val hasTermux = try { pm.getPackageInfo("com.termux", 0); true } catch (_: Exception) { false }
        val cats = mutableListOf<Category>()
        cats += Category("internal", "Внутренняя память", "📱", 0, "любые папки")
        cats += Category("atom", "Папка Atom", "⚛️", 0, "Download/Atom")
        cats += Category("images", "Фото", "🖼", countMedia(MediaStoreKind.IMAGES))
        cats += Category("video", "Видео", "🎬", countMedia(MediaStoreKind.VIDEO))
        cats += Category("audio", "Музыка", "🎵", countMedia(MediaStoreKind.AUDIO))
        cats += Category("apk", "Приложения", "📦", countUserApps(pm))
        cats += Category("downloads", "Загрузки", "📥", 0, "папки")
        if (hasTermux) cats += Category("termux", "Termux", "📟", 0, "~/.termux")
        return cats
    }

    private fun countMedia(kind: MediaStoreKind): Int {
        val uri = when (kind) {
            MediaStoreKind.IMAGES -> android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaStoreKind.VIDEO -> android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            MediaStoreKind.AUDIO -> android.provider.MediaStore.Audio.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        return try { contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns._ID), null, null, null)?.use { it.count } ?: 0 }
        catch (_: Exception) { 0 }
    }

    private fun countUserApps(pm: PackageManager): Int {
        return try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA).count { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
        } catch (_: Exception) { 0 }
    }

    private fun onCategoryTap(cat: Category) {
        InAppLogger.d("FileBrowser", "tap ${cat.id} count=${cat.count}")
        when (cat.id) {
            "internal" -> {
                if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:$packageName")))
                    } catch (_: Exception) {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                    android.widget.Toast.makeText(this, "Разреши «Доступ ко всем файлам» → вернись", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    startActivity(Intent(this, FileListActivity::class.java).apply {
                        putExtra(FileListActivity.EXTRA_MODE, "direct")
                        putExtra(FileListActivity.EXTRA_TITLE, "📱 Внутренняя")
                    })
                }
            }
            "atom" -> launchTreePicker("saf_atom_uri", "⚛️ Папка Atom", "primary:Download/Atom")
            "termux" -> launchTreePicker("saf_termux_uri", "📟 Termux", "com.termux.documents:")
            "apk" -> {
                startActivity(Intent(this, FileListActivity::class.java).apply {
                    putExtra(FileListActivity.EXTRA_MODE, "installed_apps")
                    putExtra(FileListActivity.EXTRA_TITLE, "📦 Приложения")
                })
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
            "downloads" -> launchTreePicker("saf_downloads_uri", "📥 Загрузки", "primary:Download")
            "images", "video", "audio" -> {
                startActivity(Intent(this, FileListActivity::class.java).apply {
                    putExtra(FileListActivity.EXTRA_MODE, cat.id)
                    putExtra(FileListActivity.EXTRA_TITLE, cat.title)
                })
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
            else -> android.widget.Toast.makeText(this, "📂 ${cat.title} — скоро", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchTreePicker(key: String, title: String, initialDocPath: String) {
        val saved = getSharedPreferences("atom", MODE_PRIVATE).getString(key, null)
        if (saved != null) {
            startActivity(Intent(this, FileListActivity::class.java).apply {
                putExtra(FileListActivity.EXTRA_TREE_URI, saved)
                putExtra(FileListActivity.EXTRA_TITLE, title)
                putExtra(FileListActivity.EXTRA_MODE, "tree")
            })
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            return
        }
        pendingPrefsKey = key; pendingTitle = title
        val initialUri = try {
            if (initialDocPath.startsWith("com.termux.documents:")) {
                Uri.parse("content://com.termux.documents/")
            } else {
                android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", initialDocPath)
            }
        } catch (_: Exception) { null }
        InAppLogger.d("FileBrowser", "launchTreePicker initial=$initialDocPath")
        treePicker.launch(initialUri)
    }

    enum class MediaStoreKind { IMAGES, VIDEO, AUDIO }
}
