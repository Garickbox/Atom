package com.garickbox.atom

import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * B185a: плейлист файлов в памяти. Живёт до Success (transferState) или ручного clear.
 * SnapshotStateList → Compose реактивен (UI перерисовывается при add/remove).
 */
object PlaylistManager {

    data class Item(
        val uri: Uri,
        val name: String,
        val sizeBytes: Long,
        val relativePath: String?,   // для сохранения структуры на приёмнике
        val source: String,          // images|video|audio|atom|termux|saf|direct|app
        val icon: android.graphics.drawable.Drawable? = null,
        val packageName: String? = null  // B185a-fix: для installed_apps (uri=EMPTY, копия лениво)
    )

    private val _items: SnapshotStateList<Item> = mutableStateListOf()
    val items: List<Item> get() = _items
    val count: Int get() = _items.size
    val totalSize: Long get() = _items.sumOf { it.sizeBytes }

    /** Флаг: очистить плейлист при следующем Success (устанавливается при send) */
    @Volatile var pendingClearOnSuccess: Boolean = false

    /** Добавить, если нет дубликата по uri. Возвращает true если добавлено. */
    fun add(item: Item): Boolean {
        if (_items.size >= 500) {
            android.util.Log.w("Playlist", "MAX 500 — skip ${item.name}")
            return false
        }
        if (_items.any { it.uri == item.uri }) return false
        _items.add(item)
        return true
    }

    fun remove(uri: Uri) {
        _items.removeAll { it.uri == uri }
    }

    fun clear() {
        _items.clear()
        pendingClearOnSuccess = false
    }

    fun contains(uri: Uri): Boolean = _items.any { it.uri == uri }
}
