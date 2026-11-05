package com.garickbox.atom

import android.net.Uri
import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ShareActions(
    val onSendToPeer: (Peer) -> Unit,
    val onSendToP2pDevice: (WifiP2pDevice) -> Unit,
    val onStartP2pSearch: () -> Unit,
    val onSendViaNfc: () -> Unit,
    val onSendViaQr: () -> Unit,
    val onShowQr: () -> Unit,
    val onCancel: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTargetScreen(
    uris: List<Uri>,
    livePeers: List<Peer>,
    p2pPeers: List<WifiP2pDevice>,
    p2pSearching: Boolean,
    actions: ShareActions,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val fileCount = uris.size

    ModalBottomSheet(onDismissRequest = actions.onCancel, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("📤", fontSize = 28.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Отправить", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("$fileCount ${pluralFiles(fileCount)}", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()

            if (livePeers.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SectionLabel("📡 Устройства в сети")
                livePeers.forEach { peer ->
                    LivePeerRow(peer) { actions.onSendToPeer(peer) }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            } else {
                Spacer(Modifier.height(8.dp))
                InfoCard()
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            }

            if (p2pSearching || p2pPeers.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SectionLabel("📶 Wi-Fi Direct")
                if (p2pSearching) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Ищу через Wi-Fi Direct…", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                p2pPeers.forEach { device ->
                    P2pDeviceRow(device) { actions.onSendToP2pDevice(device) }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel("Другие способы")

            ActionRow("📱", "NFC-касание", "на другом уже открыто 📥 Принять", actions.onSendViaNfc)
            ActionRow("🔍", "Сканировать QR", "сканируй QR с экрана получателя", actions.onSendViaQr)
            ActionRow("📶", "Wi-Fi Direct", "получатель: Атом → 📥 Принять", actions.onStartP2pSearch)

            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = actions.onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Отмена")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun LivePeerRow(peer: Peer, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("📱", fontSize = 24.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("📶 P2P · ${ago(peer.lastSeenMs)}", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("→", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun P2pDeviceRow(device: WifiP2pDevice, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("📶", fontSize = 24.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(device.deviceName.ifBlank { "?" }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(device.deviceAddress, fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        }
        Text("→", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ActionRow(emoji: String, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f))
}

private fun ago(ts: Long): String {
    val d = System.currentTimeMillis() - ts
    return when {
        d < 30_000 -> "сейчас"
        d < 60_000 -> "${d / 1000} сек"
        d < 3_600_000 -> "${d / 60_000} мин"
        else -> "${d / 3_600_000} ч"
    }
}

@Composable
private fun InfoCard() {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        )
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ℹ️", fontSize = 22.sp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Получатель должен открыть Атом",
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("→ 📥 Принять (покажет QR и NFC)",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun pluralFiles(n: Int): String = when {
    n % 100 in 11..19 -> "файлов"
    n % 10 == 1 -> "файл"
    n % 10 in 2..4 -> "файла"
    else -> "файлов"
}
