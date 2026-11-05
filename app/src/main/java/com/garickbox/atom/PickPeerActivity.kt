package com.garickbox.atom

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
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

/**
 * B185b: PickPeer — выбор получателя из плейлиста.
 * Заменяет ShareTargetActivity для внутреннего flow (галерея → ShareTarget остаётся).
 * Особенности: NFC/QR/Wi-Fi Direct работают с сохранённым плейлистом, не через intent extras.
 */
class PickPeerActivity : AppCompatActivity() {

    companion object {
        @Volatile var sentSuccessfully: Boolean = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TvHelper.isTv(this)) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (PlaylistManager.count == 0) {
            android.widget.Toast.makeText(this, "Плейлист пуст", android.widget.Toast.LENGTH_SHORT).show()
            finish(); return
        }
        setContent { MaterialTheme { PickPeerUi() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PickPeerUi() {
        val peers by AtomController.peers.collectAsState()
        val live = peers.filter { System.currentTimeMillis() - it.lastSeenMs < 30_000L }
        val itemCount = PlaylistManager.count
        val totalSize = PlaylistManager.totalSize

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("📤 Куда отправить?", fontWeight = FontWeight.Bold)
                            Text("$itemCount файлов · ${fmtBytes(totalSize)}", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = { IconButton(onClick = { finish() }) { Text("←", fontSize = 22.sp) } }
                )
            }
        ) { pad ->
            LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(12.dp)) {
                if (live.isNotEmpty()) {
                    item {
                        Text("📡 Устройства в сети", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp))
                    }
                    items(live) { peer ->
                        PeerRow(peer) { sendToLivePeer(peer) }
                    }
                }
                item {
                    Spacer(Modifier.height(16.dp))
                    Text("Способы передачи", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp))
                }
                item {
                    ActionRow("📱", "NFC-касание", "приложи телефоны") { sendViaNfc() }
                }
                item {
                    ActionRow("🔍", "Сканировать QR", "отправить по QR") { sendViaQr() }
                }
                item {
                    ActionRow("📶", "Wi-Fi Direct", "без роутера, 5 GHz") { sendViaWifiDirect() }
                }
            }
        }
    }

    @Composable
    private fun PeerRow(peer: Peer, onClick: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("📱", fontSize = 26.sp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(peer.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(peer.ip, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("→", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    @Composable
    private fun ActionRow(icon: String, title: String, subtitle: String, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 26.sp, modifier = Modifier.size(36.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
    }

    private fun sendToLivePeer(peer: Peer) {
        try {
            val uris = PlaylistManager.items.map { it.uri }
            val paths = PlaylistManager.items.map { it.relativePath }
            PlaylistManager.pendingClearOnSuccess = true
            FileSenderService.startTransfer(this, peer, uris, paths)
            InAppLogger.d("PickPeer", "send to live peer ${peer.name} ${uris.size} files")
            sentSuccessfully = true
            finish()
        } catch (e: Exception) {
            InAppLogger.err("PickPeer", "sendToLivePeer: ${e.message}")
            android.widget.Toast.makeText(this, "Ошибка: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun sendViaNfc() {
        try {
            PlaylistManager.pendingClearOnSuccess = true
            NfcReaderActivity.pendingUris = PlaylistManager.items.map { it.uri }
            NfcReaderActivity.pendingPaths = PlaylistManager.items.map { it.relativePath }
            startActivity(Intent(this, NfcReaderActivity::class.java))
            finish()
        } catch (e: Exception) { InAppLogger.err("PickPeer", "sendViaNfc: ${e.message}") }
    }

    private fun sendViaQr() {
        try {
            val uris = PlaylistManager.items.map { it.uri }
            val paths = PlaylistManager.items.map { it.relativePath ?: "" }
            PlaylistManager.pendingClearOnSuccess = true
            val intent = Intent(this, QrScanActivity::class.java).apply {
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
            startActivity(intent)
            finish()
        } catch (e: Exception) { InAppLogger.err("PickPeer", "sendViaQr: ${e.message}") }
    }

    private fun sendViaWifiDirect() {
        try {
            val uris = PlaylistManager.items.map { it.uri }
            val paths = PlaylistManager.items.map { it.relativePath ?: "" }
            PlaylistManager.pendingClearOnSuccess = true
            val intent = Intent(this, ShareTargetActivity::class.java).apply {
                action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
                type = "*/*"
                if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putStringArrayListExtra("atom_relative_paths", ArrayList(paths))
                putExtra(ShareTargetActivity.EXTRA_FORCE_STEP2, true)
                clipData = android.content.ClipData.newRawUri("", uris.first()).apply {
                    for (i in 1 until uris.size) addItem(android.content.ClipData.Item(uris[i]))
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
            sentSuccessfully = true
            finish()
        } catch (e: Exception) { InAppLogger.err("PickPeer", "sendViaWifiDirect: ${e.message}") }
    }

    private fun fmtBytes(b: Long): String {
        if (b < 1024) return "$b Б"
        if (b < 1024 * 1024) return "%.1f КБ".format(b / 1024.0)
        if (b < 1024 * 1024 * 1024) return "%.1f МБ".format(b / 1024.0 / 1024.0)
        return "%.1f ГБ".format(b / 1024.0 / 1024.0 / 1024.0)
    }
}
