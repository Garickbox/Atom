package com.garickbox.atom

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class MainActions(
    val onFabClick: () -> Unit,
    val onAcceptClick: () -> Unit,
    val onPeerSend: (Peer) -> Unit,
    val onPeerShowQr: (Peer) -> Unit,
    val onPeerHistory: (Peer) -> Unit,
    val onPeerForget: (Peer) -> Unit,
    val onTransferAction: (String) -> Unit,
    val onMenuAction: (String) -> Unit,
    val onAcceptTvClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(isTv: Boolean, actions: MainActions) {
    val peers by AtomController.peers.collectAsState()
    val transfer by AtomController.transferState.collectAsState()
    val live = remember(peers) { peers.filter { System.currentTimeMillis() - it.lastSeenMs < 5 * 60_000L && it.name.isNotBlank() } }

    var showEasterEgg by remember { mutableStateOf(false) }
    var peerSheetFor by remember { mutableStateOf<Peer?>(null) }

    Scaffold(
        topBar = { MainTopBar(isTv, actions) },
        floatingActionButton = {
            if (!isTv) TransferFab(
                onClick = actions.onFabClick,
                onLongClick = { showEasterEgg = true }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            if (transfer !is TransferState.Idle) {
                item { TransferCard(transfer, actions) }
            }
            if (isTv) {
                item { TvAcceptPanel(actions.onAcceptTvClick) }
            } else if (transfer is TransferState.Idle) {
                item { HeroBlock(actions.onAcceptClick) }
            }
            item {
                Text("📡 Устройства в сети",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            }
            if (live.isEmpty()) {
                item { EmptyPeersCard() }
            } else {
                items(live) { peer ->
                    PeerCard(peer) { peerSheetFor = peer }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    peerSheetFor?.let { peer ->
        PeerActionSheet(peer,
            onDismiss = { peerSheetFor = null },
            onSend = { peerSheetFor = null; actions.onPeerSend(peer) },
            onShowQr = { peerSheetFor = null; actions.onPeerShowQr(peer) },
            onHistory = { peerSheetFor = null; actions.onPeerHistory(peer) },
            onForget = { peerSheetFor = null; actions.onPeerForget(peer) })
    }

    if (showEasterEgg) {
        EasterEggOverlay(onDismiss = { showEasterEgg = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(isTv: Boolean, actions: MainActions) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("Атом", fontWeight = FontWeight.Bold) },
        actions = {
            IconButton(onClick = { actions.onMenuAction("update") }) { Text("🔄", fontSize = 20.sp) }
            Box {
                IconButton(onClick = { menuOpen = true }) { Text("⋮", fontSize = 22.sp) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("📜 История") }, onClick = { menuOpen = false; actions.onMenuAction("history") })
                    DropdownMenuItem(text = { Text("⚙️ Настройки") }, onClick = { menuOpen = false; actions.onMenuAction("settings") })
                    DropdownMenuItem(text = { Text("ℹ️ О программе") }, onClick = { menuOpen = false; actions.onMenuAction("about") })
                }
            }
        }
    )
}

@Composable
private fun HeroBlock(onAcceptClick: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(32.dp))
        Text("⚛️", fontSize = 72.sp)
        Spacer(Modifier.height(8.dp))
        Text("Атом", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Прямая передача", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onAcceptClick,
            modifier = Modifier.fillMaxWidth().height(64.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("📥  Принять  (QR + NFC)", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TvAcceptPanel(onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📺 Готов к приёму", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("📥  Принять 10 мин  (QR + NFC)", fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun EmptyPeersCard() {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Ищу устройства…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PeerCard(peer: Peer, onClick: () -> Unit) {
    val isLive = System.currentTimeMillis() - peer.lastSeenMs < 30_000L
    val ago = humanAgo(peer.lastSeenMs)
    val bandLabel = if (peer.mac.isNotBlank()) "P2P" else "LAN"
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📱", fontSize = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("📶 $bandLabel · $ago", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PulsingDot(active = isLive)
        }
    }
}

@Composable
private fun PulsingDot(active: Boolean) {
    if (!active) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFFE0E0E0)))
        return
    }
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 0.6f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
        label = "scale"
    )
    Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFF4CAF50).copy(alpha = scale)))
}

@Composable
private fun TransferCard(state: TransferState, actions: MainActions) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(4.dp)) {
        Box(
            Modifier.background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                        Color.Transparent
                    )
                )
            )
        ) {
            Column(Modifier.padding(16.dp)) {
                when (state) {
                    is TransferState.Running -> RunningBlock(state, actions)
                    is TransferState.Paused -> PausedBlock(state, actions)
                    is TransferState.Success -> SuccessBlock(state)
                    is TransferState.Failed -> FailedBlock(state, actions)
                    is TransferState.UpdatePending -> UpdateBlock(state)
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun RunningBlock(s: TransferState.Running, actions: MainActions) {
    val ctx = LocalContext.current
    val pct = if (s.bytesTotal > 0) (s.bytesSent * 100 / s.bytesTotal).toInt() else 0
    val animPct by animateFloatAsState(targetValue = pct.toFloat(), animationSpec = tween(400), label = "pct")
    val targetSpeedColor = when {
        s.bytesPerSecond >= 15L * 1024 * 1024 -> Color(0xFF2E7D32)
        s.bytesPerSecond >= 5L * 1024 * 1024 -> MaterialTheme.colorScheme.primary
        s.bytesPerSecond > 0 -> Color(0xFFF57C00)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val speedColor by animateColorAsState(targetSpeedColor, animationSpec = tween(400), label = "speedColor")
    val eta = remember(s.bytesPerSecond, s.bytesSent, s.bytesTotal) {
        if (s.bytesPerSecond > 0 && s.bytesTotal > s.bytesSent) {
            val sec = (s.bytesTotal - s.bytesSent) / s.bytesPerSecond
            "%d:%02d".format(sec / 60, sec % 60)
        } else "…"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            PulseEmoji(if (s.isReceiver) "📥" else "📤")
            Spacer(Modifier.width(6.dp))
            Text("${if (s.isReceiver) "←" else "→"} ${s.peerName}",
                fontWeight = FontWeight.Bold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { actions.onTransferAction(TransferNotification.ACTION_PAUSE) }) { Text("⏸") }
        if (!s.isReceiver && s.fileCount > 1) {
            IconButton(onClick = { actions.onTransferAction(TransferNotification.ACTION_SKIP) }) { Text("⏭") }
        }
        IconButton(onClick = { actions.onTransferAction(TransferNotification.ACTION_CANCEL) }) { Text("✕") }
    }
    Spacer(Modifier.height(8.dp))
    Text("📦 Файл ${s.fileIndex} из ${s.fileCount} · ${s.fileName}",
        fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(12.dp))
    ShimmerBar(progress = animPct / 100f, active = true)
    Spacer(Modifier.height(8.dp))
    Row {
        Text("${fmtBytes(s.bytesSent)} / ${fmtBytes(s.bytesTotal)}", fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("$pct%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(8.dp))
    Row {
        Text("⚡ ${fmtSpeed(s.bytesPerSecond)}", fontSize = 13.sp, color = speedColor, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(12.dp))
        val hz = AtomController.currentP2pFrequencyHz
        when {
            hz >= 5000 -> Text("📶 5 GHz", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            hz >= 2400 -> Text("📶 2.4 GHz", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        Text("⏱ $eta", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ShimmerBar(progress: Float, active: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(
        initialValue = -1f, targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "shift"
    )
    val p = progress.coerceIn(0f, 1f)
    val glowColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
    val trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    Box(
        Modifier.fillMaxWidth().height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(trackColor)
            .drawBehind {
                drawRoundRect(
                    color = glowColor,
                    size = size.copy(width = size.width * p),
                    cornerRadius = CornerRadius(5.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
    ) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(p)
                .clip(RoundedCornerShape(5.dp))
                .background(
                    Brush.horizontalGradient(
                        0f to MaterialTheme.colorScheme.primary,
                        0.5f to MaterialTheme.colorScheme.tertiary,
                        1f to MaterialTheme.colorScheme.primary,
                        startX = shift * 500f, endX = shift * 500f + 500f
                    )
                )
        )
        if (active && p < 1f) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        0f to Color.Transparent,
                        0.45f to Color.White.copy(alpha = 0.25f),
                        0.55f to Color.White.copy(alpha = 0.25f),
                        1f to Color.Transparent,
                        start = Offset(shift * 600f, 0f),
                        end = Offset(shift * 600f + 300f, 0f)
                    )
                )
            )
        }
    }
}

@Composable
private fun PulseEmoji(emoji: String) {
    val transition = rememberInfiniteTransition(label = "pulseEmoji")
    val scaleV by transition.animateFloat(
        initialValue = 0.9f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "scale"
    )
    Text(emoji, fontSize = 18.sp, modifier = Modifier.scale(scaleV))
}

@Composable
private fun Confetti() {
    val transition = rememberInfiniteTransition(label = "confetti")
    val progress by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "progress"
    )
    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
        val w = size.width
        val h = size.height
        val colors = listOf(
            Color(0xFFE91E63), Color(0xFFFFC107), Color(0xFF4CAF50),
            Color(0xFF2196F3), Color(0xFF9C27B0)
        )
        for (i in 0 until 30) {
            val startX = ((i * 73) % w.toInt()).toFloat()
            val driftX = ((i % 5) - 2) * 40f
            val x = startX + driftX * progress
            val y = -10f + progress * h
            val alpha = (1f - progress).coerceIn(0f, 1f)
            drawCircle(
                color = colors[i % colors.size].copy(alpha = alpha),
                radius = 4f + (i % 3),
                center = Offset(x, y)
            )
        }
    }
}

@Composable
private fun PausedBlock(s: TransferState.Paused, actions: MainActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("⏸ Пауза · ${s.peerName}", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = { actions.onTransferAction(TransferNotification.ACTION_RESUME) }) { Text("▶") }
        IconButton(onClick = { actions.onTransferAction(TransferNotification.ACTION_CANCEL) }) { Text("✕") }
    }
    Spacer(Modifier.height(8.dp))
    Text("📦 Файл ${s.fileIndex} из ${s.fileCount} · ${s.fileName}", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(8.dp))
    val pct = if (s.bytesTotal > 0) (s.bytesSent * 100 / s.bytesTotal).toInt() else 0
    Text("${fmtBytes(s.bytesSent)} / ${fmtBytes(s.bytesTotal)} · $pct%", fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SuccessBlock(s: TransferState.Success) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(Unit) {
        try {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(150)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } catch (_: Exception) {}
    }
    Box(Modifier.fillMaxWidth()) {
        Confetti()
        Column {
            Text("✅ Готово · ${s.peerName}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text("${s.fileCount} файл(ов) · ${fmtBytes(s.bytesTotal)}", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FailedBlock(s: TransferState.Failed, actions: MainActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("⚠️ Ошибка · ${s.peerName}", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
        IconButton(onClick = { actions.onTransferAction(TransferNotification.ACTION_CANCEL) }) { Text("✕") }
    }
    Spacer(Modifier.height(4.dp))
    Text(s.reason, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun UpdateBlock(s: TransferState.UpdatePending) {
    Text("⬆️ Обновление Атома build-${s.versionCode}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
    Spacer(Modifier.height(4.dp))
    Text("От ${s.peerName} · тап в уведомлении для установки", fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransferFab(onClick: () -> Unit, onLongClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Box(
        Modifier.size(56.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            ),
        contentAlignment = Alignment.Center
    ) { Text("📂", fontSize = 24.sp) }
}

@Composable
private fun EasterEggOverlay(onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⚛️", fontSize = 120.sp)
            Spacer(Modifier.height(16.dp))
            Text("Atom v0.1-mvp", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("build-${BuildConfig.VERSION_CODE}", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text("Сделано с ⚛️ и ☕", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeerActionSheet(
    peer: Peer,
    onDismiss: () -> Unit,
    onSend: () -> Unit, onShowQr: () -> Unit,
    onHistory: () -> Unit, onForget: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("📱 ${peer.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(peer.ip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            ActionRow("📤", "Отправить файлы", onSend)
            ActionRow("📥", "Показать QR для приёма", onShowQr)
            ActionRow("📜", "История с этим устройством", onHistory)
            ActionRow("🗑", "Забыть устройство", onForget)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ActionRow(emoji: String, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 15.sp)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
}

private fun humanAgo(ts: Long): String {
    val d = System.currentTimeMillis() - ts
    return when {
        d < 30_000 -> "сейчас"
        d < 60_000 -> "${d / 1000} сек назад"
        d < 3_600_000 -> "${d / 60_000} мин назад"
        d < 86_400_000 -> "${d / 3_600_000} ч назад"
        else -> "давно"
    }
}

private fun fmtBytes(b: Long): String {
    if (b < 1024) return "$b Б"
    if (b < 1024 * 1024) return "%.1f КБ".format(b / 1024.0)
    if (b < 1024 * 1024 * 1024) return "%.1f МБ".format(b / 1024.0 / 1024.0)
    return "%.1f ГБ".format(b / 1024.0 / 1024.0 / 1024.0)
}

private fun fmtSpeed(bps: Long): String {
    if (bps <= 0) return "…"
    return "%.1f МБ/с".format(bps / 1024.0 / 1024.0)
}
