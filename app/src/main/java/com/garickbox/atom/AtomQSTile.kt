package com.garickbox.atom

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.mesh.core.InAppLogger

class AtomQSTile : TileService() {

    companion object {
        fun requestUpdate(ctx: Context) {
            try {
                TileService.requestListeningState(
                    ctx, ComponentName(ctx, AtomQSTile::class.java)
                )
            } catch (e: Exception) {
                InAppLogger.d("AtomQSTile", "requestUpdate error: " + e.message)
            }
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    // B89.1: TileService НЕ имеет onLongClick — long-press невозможно.
    // File picker = двойной тап (уже реализован в onClick с DOUBLE_TAP_MS).

    override fun onClick() {
        super.onClick()
        val _st = AtomController.transferState.value
        InAppLogger.err("AtomQSTile", "B216: onClick state=${_st.javaClass.simpleName} fgs=${WidgetReceiveService.isActive}")
        // B20.4: fg-only (Атом открыт, FGS нет) → тап запускает FGS
        if (!WidgetReceiveService.isActive && AtomController.appForeground) {
            InAppLogger.d("AtomQSTile", "click → pin FGS (app open)")
            try {
                val i = Intent(this, WidgetReceiveService::class.java).apply {
                    action = WidgetReceiveService.ACTION_TOGGLE
                }
                ContextCompat.startForegroundService(this, i)
            } catch (_: Exception) {}
            handler.postDelayed({ updateTile() }, 500L)
            return
        }
        // B17: click → pause (если Running) или resume (если Paused)
        val ts = AtomController.transferState.value
        if (ts is TransferState.Running) {
            InAppLogger.d("AtomQSTile", "click → pause")
            try {
                val i = Intent(this, FileSenderService::class.java).apply {
                    action = TransferNotification.ACTION_PAUSE
                }
                ContextCompat.startForegroundService(this, i)
            } catch (_: Exception) {}
            handler.postDelayed({ updateTile() }, 500L)
            return
        }
        if (ts is TransferState.Paused) {
            InAppLogger.d("AtomQSTile", "click → resume")
            try {
                val i = Intent(this, FileSenderService::class.java).apply {
                    action = TransferNotification.ACTION_RESUME
                }
                ContextCompat.startForegroundService(this, i)
            } catch (_: Exception) {}
            handler.postDelayed({ updateTile() }, 500L)
            return
        }
        // B118: Success / UpdatePending / Failed → открыть Атом
        if (ts is TransferState.Success || ts is TransferState.UpdatePending ||
            ts is TransferState.Failed) {
            InAppLogger.d("AtomQSTile", "click → open app (terminal state)")
            try {
                val pi = android.app.PendingIntent.getActivity(this, 9002,
                    Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
                startActivityAndCollapse(pi)
            } catch (_: Exception) {}
            return
        }
        // B211: single tap = сразу toggle FGS (axiom#204 — без двойного тапа)
        toggleReceive()
    }

    private fun toggleReceive() {
        try {
            val i = Intent(this, WidgetReceiveService::class.java).apply {
                action = WidgetReceiveService.ACTION_TOGGLE
            }
            ContextCompat.startForegroundService(this, i)
        } catch (e: Exception) {
            InAppLogger.err("AtomQSTile", "B216 toggle error: ${e.javaClass.simpleName}: ${e.message}")
        }
        handler.postDelayed({ updateTile() }, 500L)
    }

    private fun fmtBytes(b: Long): String {
        if (b < 1024) return "$b Б"
        if (b < 1024 * 1024) return "%.1f КБ".format(b / 1024.0)
        if (b < 1024 * 1024 * 1024) return "%.1f МБ".format(b / 1024.0 / 1024.0)
        return "%.1f ГБ".format(b / 1024.0 / 1024.0 / 1024.0)
    }

    private fun updateTile() {
        try {
            val tile = qsTile ?: return
            // B17: приоритет transferState над WidgetReceive
            val ts = AtomController.transferState.value
            // B118: icon = var, ставится в каждой ветке
            var resIcon = R.drawable.ic_qs_tile
            when (ts) {
                is TransferState.Running -> {
                    tile.state = Tile.STATE_ACTIVE
                    val pct = if (ts.bytesTotal > 0)
                        ((ts.bytesSent * 100) / ts.bytesTotal).toInt() else 0
                    tile.label = "Атом · $pct%"
                    val icon = if (ts.isReceiver) "📥" else "📤"
                    tile.subtitle = "$icon " + ts.peerName.take(10) + " · " + pct + "%"
                    resIcon = if (ts.isReceiver) R.drawable.ic_qs_recv else R.drawable.ic_qs_send
                }
                is TransferState.Paused -> {
                    tile.state = Tile.STATE_INACTIVE
                    val pct = if (ts.bytesTotal > 0)
                        ((ts.bytesSent * 100) / ts.bytesTotal).toInt() else 0
                    tile.label = "Атом · пауза"
                    tile.subtitle = "⏸ $pct% · тап продолжить"
                    // B118fix2: TransferState.Paused не содержит isReceiver — нейтральная иконка
                    resIcon = R.drawable.ic_qs_tile
                }
                is TransferState.Success -> {
                    // B118: Success НЕ идёт в else — иначе «Приём · приложение открыто» после отправки
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = "Атом · готово"
                    tile.subtitle = "✅ ${
                        if (ts.fileCount > 0) "${ts.fileCount} файл · ${fmtBytes(ts.bytesTotal)}"
                        else "Отправлено"
                    }"
                    resIcon = R.drawable.ic_qs_success
                }
                is TransferState.Failed -> {
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = "Атом · ошибка"
                    tile.subtitle = "⚠️ ${ts.reason.take(20)}"
                    resIcon = R.drawable.ic_qs_failed
                }
                is TransferState.UpdatePending -> {
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = "Атом · обновление"
                    tile.subtitle = "⬆️ build-${ts.versionCode} · тап установить"
                    resIcon = R.drawable.ic_qs_success
                }
                else -> {
                    val fgs = WidgetReceiveService.isActive
                    val fg = AtomController.appForeground
                    val active = fgs || fg
                    tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    tile.label = "Атом"
                    when {
                        fgs -> {
                            val remain = (WidgetReceiveService.activeUntil - System.currentTimeMillis()) / 1000L
                            tile.subtitle = if (remain > 0)
                                "Приём · " + (remain / 60) + ":" + "%02d".format(remain % 60)
                            else "Приём"
                        }
                        fg -> tile.subtitle = "Приём · приложение открыто"
                        else -> tile.subtitle = "📱 NFC/QR · нажми"
                    }
                    resIcon = R.drawable.ic_qs_tile
                }
            }
            try {
                val sub = tile.subtitle
                tile.stateDescription = if (sub.isNullOrBlank()) "Атом" else sub
            } catch (_: Exception) {}
            tile.icon = Icon.createWithResource(this, resIcon)
            tile.updateTile()
        } catch (e: Exception) {
            InAppLogger.d("AtomQSTile", "updateTile error: " + e.message)
        }
    }
}
