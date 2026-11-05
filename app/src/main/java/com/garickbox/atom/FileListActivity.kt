package com.garickbox.atom

import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.decode.VideoFrameDecoder
import com.mesh.core.InAppLogger

class FileListActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_TREE_URI = "tree_uri"
        const val EXTRA_TITLE = "title"
        const val EXTRA_MODE = "mode"  // tree | images | video | audio | installed_apps | direct
        const val EXTRA_DIRECT_PATH = "direct_path"
    }

    data class Row(val uri: Uri, val name: String, val isDir: Boolean, val size: Long,
                   val relPath: String, val docId: String, val icon: android.graphics.drawable.Drawable? = null)

    private var currentMode: String = "tree"
    private var currentTreeUri: Uri? = null
    private var currentParentId: String = ""
    private var currentTitle: String = "Файлы"
    private var currentPath: String = ""

    // B185a: selected заменён на PlaylistManager (singleton, живёт между экранами)
    private var items by mutableStateOf<List<Row>>(emptyList())
    private var stack by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var reloadTrigger by mutableStateOf(0)
    private var searchQuery by mutableStateOf("")
    private var sortMode by mutableStateOf("name")
    private var sortMenuExpanded by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TvHelper.isTv(this)) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        currentMode = intent.getStringExtra(EXTRA_MODE) ?: "tree"
        currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Файлы"
        val treeStr = intent.getStringExtra(EXTRA_TREE_URI)
        if (currentMode == "tree") {
            if (treeStr.isNullOrBlank()) { finish(); return }
            currentTreeUri = Uri.parse(treeStr)
            currentParentId = try { DocumentsContract.getTreeDocumentId(currentTreeUri!!) } catch (_: Exception) { finish(); return }
        }
        if (currentMode == "direct") {
            currentParentId = intent.getStringExtra(EXTRA_DIRECT_PATH) ?: "/sdcard"
            currentPath = currentParentId
        }
        // B184c: reset search + sort mode default
        searchQuery = ""
        sortMode = if (currentMode == "tree" || currentMode == "direct") "name" else "date"
        setContent { MaterialTheme { ListUi() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ListUi() {
        LaunchedEffect(reloadTrigger) { items = loadItems() }
        val searchResults by remember(items, searchQuery, currentMode, currentParentId) {
            derivedStateOf {
                if (searchQuery.isBlank()) emptyList()
                else performRecursiveSearch(searchQuery)
            }
        }
        val filtered = remember(items, searchQuery, searchResults) {
            if (searchQuery.isBlank()) items else searchResults
        }
        val sorted = remember(filtered, sortMode) {
            when (sortMode) {
                "date" -> filtered
                "size" -> filtered.sortedWith(compareByDescending<Row> { it.isDir }.thenByDescending { it.size })
                "name" -> filtered.sortedWith(compareByDescending<Row> { it.isDir }.thenBy { it.name.lowercase() })
                "name_desc" -> filtered.sortedWith(compareByDescending<Row> { it.isDir }.thenByDescending { it.name.lowercase() })
                else -> filtered
            }
        }
        var searchExpanded by remember { mutableStateOf(false) }
        val totalSize by remember { derivedStateOf { PlaylistManager.totalSize } }
        val itemCount by remember { derivedStateOf { PlaylistManager.count } }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("📁 $currentTitle", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (currentPath.isNotBlank()) Text(currentPath, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    navigationIcon = { IconButton(onClick = { goBack() }) { Text("←", fontSize = 22.sp) } },
                    actions = {
                        IconButton(onClick = { searchExpanded = !searchExpanded; if (!searchExpanded) searchQuery = "" }) {
                            Text("🔍", fontSize = 20.sp)
                        }
                        if (currentMode == "tree" || currentMode == "direct") {
                            Box {
                                IconButton(onClick = { sortMenuExpanded = true }) { Text("⇅", fontSize = 22.sp) }
                                DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                                    val nameLabel = if (sortMode == "name" || sortMode == "name_desc") {
                                        if (sortMode == "name") "По имени (А→Я)" else "По имени (Я→А)"
                                    } else "По имени"
                                    DropdownMenuItem(
                                        text = { Text(nameLabel) },
                                        onClick = {
                                            sortMode = if (sortMode == "name") "name_desc" else "name"
                                            sortMenuExpanded = false
                                        })
                                    DropdownMenuItem(
                                        text = { Text("По дате") },
                                        onClick = { sortMode = "date"; sortMenuExpanded = false })
                                    DropdownMenuItem(
                                        text = { Text("По размеру") },
                                        onClick = { sortMode = "size"; sortMenuExpanded = false })
                                }
                            }
                        }
                    }
                )
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = itemCount > 0,
                    enter = slideInVertically(initialOffsetY = { it }) + androidx.compose.animation.fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + androidx.compose.animation.fadeOut()
                ) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, tonalElevation = 4.dp) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Выбрано: $itemCount", fontWeight = FontWeight.Bold)
                                Text(fmtBytes(totalSize), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(onClick = { sendSelected() }) { Text("📤 Отправить") }
                        }
                    }
                }
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = searchExpanded,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        placeholder = { Text("Поиск по имени…", fontSize = 14.sp) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) { Text("✕", fontSize = 18.sp) }
                            }
                        },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
                    )
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 80.dp)) {
                    items(sorted) { row -> RowUi(row) }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun RowUi(row: Row) {
        val isChecked = PlaylistManager.contains(row.uri)
        val ctx = this@FileListActivity
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(
                    onClick = { onTap(row) },
                    onLongClick = { if (row.isDir) showFolderSheet(row) }
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                row.isDir -> Text("📁", fontSize = 22.sp, modifier = Modifier.size(32.dp))
                row.icon != null -> {
                    val bmp = remember(row.uri) { row.icon.toBitmap().asImageBitmap() }
                    androidx.compose.foundation.Image(
                        bitmap = bmp,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                        contentScale = ContentScale.Fit
                    )
                }
                // B184f: AVI/MPEG/WMV → иконка (VideoFrameDecoder не умеет)
                row.name.substringAfterLast('.', "").lowercase() in listOf("jpg","jpeg","png","gif","webp","heic","avif","mp4","mkv","mov","webm","3gp","m4v") -> {
                    val isVideo = row.name.substringAfterLast('.', "").lowercase() in
                        listOf("mp4","mkv","mov","webm","3gp","m4v")
                    val model = if (isVideo) {
                        ImageRequest.Builder(this@FileListActivity)
                            .data(row.uri)
                            .decoderFactory { result, options, _ ->
                                VideoFrameDecoder(result.source, options)
                            }
                            .build()
                    } else row.uri
                    AsyncImage(
                        model = model,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(36.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    )
                }
                else -> Text(mimeIcon(row.name), fontSize = 22.sp, modifier = Modifier.size(32.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = if (row.isDir) FontWeight.Medium else FontWeight.Normal)
                val sub = when {
                    searchQuery.isNotBlank() && row.relPath.isNotBlank() && row.relPath != row.name ->
                        row.relPath
                    row.isDir -> "папка"
                    else -> fmtBytes(row.size)
                }
                Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!row.isDir) Checkbox(checked = isChecked, onCheckedChange = { onTap(row) }, modifier = Modifier.size(40.dp))
            else if (isChecked || PlaylistManager.items.any { it.relativePath?.startsWith(row.relPath + "/") == true }) {
                Text("✓", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
    }

    private fun mimeIcon(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "heic" -> "🖼"
            "mp4", "mkv", "mov", "avi", "webm" -> "🎬"
            "mp3", "m4a", "flac", "wav", "ogg" -> "🎵"
            "apk" -> "📦"
            "pdf", "doc", "docx", "txt" -> "📄"
            "zip", "rar", "7z", "tar", "gz" -> "🗜"
            else -> "📄"
        }
    }

    private fun onTap(row: Row) {
        if (row.isDir) {
            stack = stack + (currentParentId to currentPath)
            currentParentId = row.docId
            currentPath = row.relPath
            // B185a: НЕ очищаем плейлист при навигации
            reloadTrigger++
        } else {
            if (PlaylistManager.contains(row.uri)) {
                PlaylistManager.remove(row.uri)
            } else {
                val added = PlaylistManager.add(PlaylistManager.Item(
                    uri = row.uri, name = row.name, sizeBytes = row.size,
                    relativePath = row.relPath, source = currentMode, icon = row.icon))
                if (!added) android.widget.Toast.makeText(this, "Уже в плейлисте или лимит 500", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showFolderSheet(row: Row) {
        val files = countFilesInFolder(row)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📁 ${row.name}")
            .setItems(arrayOf("📂 Открыть", "➕ Добавить в плейлист ($files файлов)")) { _, which ->
                when (which) {
                    0 -> onTap(row)
                    1 -> addFolderToPlaylist(row)
                }
            }
            .show()
    }

    private fun countFilesInFolder(row: Row): Int {
        return try {
            val treeUri = currentTreeUri ?: return 0
            var n = 0
            fun walk(parentId: String) {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
                contentResolver.query(childrenUri, arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val mime = c.getString(1) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(id) else n++
                    }
                }
            }
            walk(row.docId)
            n
        } catch (_: Exception) { 0 }
    }

    private fun addFolderToPlaylist(row: Row) {
        val treeUri = currentTreeUri ?: return
        var added = 0
        fun walk(parentId: String, path: String) {
            try {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
                contentResolver.query(childrenUri, arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: ""
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        val rel = if (path.isBlank()) name else "$path/$name"
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(id, rel)
                        else {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                            if (PlaylistManager.add(PlaylistManager.Item(
                                uri = docUri, name = name, sizeBytes = size,
                                relativePath = row.name + "/" + rel, source = currentMode)))
                                added++
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        walk(row.docId, "")
        android.widget.Toast.makeText(this, "Добавлено $added файлов из ${row.name}", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun goBack() {
        if (stack.isNotEmpty()) {
            val (pid, pPath) = stack.last()
            stack = stack.dropLast(1)
            currentParentId = pid
            currentPath = pPath
            // B185a: не очищаем плейлист
            reloadTrigger++
        } else finish()
    }

    override fun onBackPressed() {
        if (stack.isNotEmpty()) goBack() else super.onBackPressed()
    }

    private fun performRecursiveSearch(query: String): List<Row> {
        val q = query.lowercase()
        val out = mutableListOf<Row>()
        when (currentMode) {
            "tree" -> {
                val treeUri = currentTreeUri ?: return emptyList()
                val rootDocId = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (_: Exception) { return emptyList() }
                fun walk(parentId: String, path: String) {
                    try {
                        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
                        contentResolver.query(childrenUri, arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                            DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                            while (c.moveToNext()) {
                                val id = c.getString(0) ?: continue
                                val name = c.getString(1) ?: continue
                                val mime = c.getString(2) ?: ""
                                val size = if (c.isNull(3)) 0L else c.getLong(3)
                                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                                val rel = if (path.isBlank()) name else "$path/$name"
                                if (name.lowercase().contains(q)) {
                                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                                    out.add(Row(docUri, name, isDir, size, rel, id))
                                }
                                if (isDir && out.size < 500) walk(id, rel)
                            }
                        }
                    } catch (_: Exception) {}
                }
                walk(rootDocId, "")
            }
            "direct" -> {
                val base = java.io.File(currentParentId)
                base.walkTopDown().take(2000).forEach { f ->
                    if (f.name.lowercase().contains(q)) {
                        out.add(Row(Uri.fromFile(f), f.name, f.isDirectory, if (f.isFile) f.length() else 0L, f.absolutePath, f.absolutePath))
                    }
                }
            }
            "images", "video", "audio" -> {
                val uri = when (currentMode) {
                    "images" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    "video" -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val sel = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
                contentResolver.query(uri, arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.RELATIVE_PATH), sel, arrayOf("%$query%"), null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1) ?: continue
                        val size = if (c.isNull(2)) 0L else c.getLong(2)
                        val relPath = if (c.isNull(3)) "" else (c.getString(3) ?: "")
                        val fileUri = ContentUris.withAppendedId(uri, id)
                        val relFile = if (relPath.isBlank()) name else relPath.trimEnd('/') + "/" + name
                        out.add(Row(fileUri, name, false, size, relFile, id.toString()))
                    }
                }
            }
            "installed_apps" -> items.filter { it.name.lowercase().contains(q) }
        }
        return out
    }

    private fun loadItems(): List<Row> {
        return try {
            when (currentMode) {
                "tree" -> loadTree()
                "direct" -> loadDirect(currentParentId)
                "installed_apps" -> loadInstalledApps()
                "images" -> loadMediaStore("images")
                "video" -> loadMediaStore("video")
                "audio" -> loadMediaStore("audio")
                else -> emptyList()
            }
        } catch (e: Exception) {
            InAppLogger.err("FileList", "loadItems: ${e.message}")
            emptyList()
        }
    }

    private fun loadTree(): List<Row> {
        val treeUri = currentTreeUri ?: return emptyList()
        val out = mutableListOf<Row>()
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentParentId)
            contentResolver.query(childrenUri, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    val rel = if (currentPath.isBlank()) name else "$currentPath/$name"
                    out.add(Row(docUri, name, isDir, size, rel, id))
                }
            }
        } catch (e: Exception) { InAppLogger.err("FileList", "loadTree: ${e.message}") }
        out.sortWith(compareByDescending<Row> { it.isDir }.thenBy { it.name.lowercase() })
        return out
    }

    private fun loadDirect(parentPath: String): List<Row> {
        val out = mutableListOf<Row>()
        try {
            val dir = java.io.File(parentPath)
            dir.listFiles()?.forEach { f ->
                val uri = Uri.fromFile(f)
                val rel = f.absolutePath
                out.add(Row(uri, f.name, f.isDirectory, if (f.isFile) f.length() else 0L, rel, f.absolutePath))
            }
        } catch (e: Exception) { InAppLogger.err("FileList", "loadDirect: ${e.message}") }
        out.sortWith(compareByDescending<Row> { it.isDir }.thenBy { it.name.lowercase() })
        return out
    }

    private val apkCacheDir by lazy { java.io.File(cacheDir, "apks").apply { mkdirs() } }

    // B184f: loadInstalledApps БЕЗ copy — только метаданные. Copy → лениво в sendSelected()
    private fun loadInstalledApps(): List<Row> {
        val out = mutableListOf<Row>()
        val pm = packageManager
        try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach { app ->
                if ((app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) return@forEach
                val label = pm.getApplicationLabel(app).toString()
                val icon = pm.getApplicationIcon(app)
                val apkFile = java.io.File(app.sourceDir)
                if (!apkFile.exists()) return@forEach
                // uri = EMPTY, docId = packageName → copy будет в sendSelected
                out.add(Row(
                    uri = Uri.EMPTY,
                    name = label,
                    isDir = false,
                    size = apkFile.length(),
                    relPath = label.replace("/", "_") + ".apk",
                    docId = "pkg:" + app.packageName,
                    icon = icon
                ))
            }
            InAppLogger.d("FileList", "installed_apps: ${out.size} user apps (no copy)")
        } catch (e: Exception) { InAppLogger.err("FileList", "loadInstalledApps: ${e.message}") }
        out.sortBy { it.name.lowercase() }
        return out
    }

    private fun loadMediaStore(mode: String): List<Row> {
        val out = mutableListOf<Row>()
        val uri = when (mode) {
            "images" -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            "video" -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            "audio" -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> return emptyList()
        }
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        try {
            contentResolver.query(uri, arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.RELATIVE_PATH), null, null, sort)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val size = if (c.isNull(2)) 0L else c.getLong(2)
                    val relPath = if (c.isNull(3)) "" else (c.getString(3) ?: "")
                    val fileUri = ContentUris.withAppendedId(uri, id)
                    val relFile = if (relPath.isBlank()) name else relPath.trimEnd('/') + "/" + name
                    out.add(Row(fileUri, name, false, size, relFile, id.toString()))
                }
            }
        } catch (e: Exception) { InAppLogger.err("FileList", "loadMediaStore($mode): ${e.message}") }
        return out
    }

    private fun sendSelected() {
        if (PlaylistManager.count == 0) return
        PlaylistManager.pendingClearOnSuccess = true
        val rows = PlaylistManager.items.toList()
        // B184f: lazy copy APK (только выбранные, не все 81)
        val finalUris = mutableListOf<Uri>()
        for (r in rows) {
            val u = if (currentMode == "installed_apps" && r.docId.startsWith("pkg:")) {
                val pkg = r.docId.removePrefix("pkg:")
                try {
                    val src = java.io.File(packageManager.getApplicationInfo(pkg, 0).sourceDir)
                    val dst = java.io.File(apkCacheDir, pkg + ".apk")
                    if (!dst.exists() || dst.length() != src.length()) {
                        src.copyTo(dst, overwrite = true)
                    }
                    androidx.core.content.FileProvider.getUriForFile(
                        this, "$packageName.fileprovider", dst)
                } catch (e: Exception) {
                    InAppLogger.err("FileList", "copy $pkg: ${e.message}")
                    android.widget.Toast.makeText(this, "Ошибка копирования $pkg", android.widget.Toast.LENGTH_SHORT).show()
                    continue
                }
            } else r.uri
            if (u != Uri.EMPTY) finalUris.add(u)
        }
        if (finalUris.isEmpty()) return
        val uris = finalUris
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
        InAppLogger.d("FileList", "send ${uris.size} files")
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        // B185a: НЕ finish — после cancel в ShareTarget вернёмся сюда с сохранённым плейлистом
    }

    private fun fmtBytes(b: Long): String {
        if (b < 1024) return "$b Б"
        if (b < 1024 * 1024) return "%.1f КБ".format(b / 1024.0)
        if (b < 1024 * 1024 * 1024) return "%.1f МБ".format(b / 1024.0 / 1024.0)
        return "%.1f ГБ".format(b / 1024.0 / 1024.0 / 1024.0)
    }
}
