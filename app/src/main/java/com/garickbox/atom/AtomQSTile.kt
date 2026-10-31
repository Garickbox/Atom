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
        private const val DOUBLE_TAP_MS = 500L

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

    private var lastTapAt: Long = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingToggle: Runnable? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
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
        val now = System.currentTimeMillis()
        val isDoubleTap = (now - lastTapAt) < DOUBLE_TAP_MS
        lastTapAt = now

        if (isDoubleTap) {
            // Двойной тап = file picker
            pendingToggle?.let { handler.removeCallbacks(it) }
            pendingToggle = null
            InAppLogger.d("AtomQSTile", "double-tap → file picker")
            openFilePicker()
            return
        }

        // Одиночный тап = toggle, но ждём DOUBLE_TAP_MS чтобы убедиться, что не двойной
        pendingToggle?.let { handler.removeCallbacks(it) }
        pendingToggle = Runnable {
            InAppLogger.d("AtomQSTile", "single-tap confirmed → toggle")
            toggleReceive()
            pendingToggle = null
        }
        handler.postDelayed(pendingToggle!!, DOUBLE_TAP_MS)
    }

    private fun toggleReceive() {
        try {
            val i = Intent(this, WidgetReceiveService::class.java).apply {
                action = WidgetReceiveService.ACTION_TOGGLE
            }
            ContextCompat.startForegroundService(this, i)
        } catch (e: Exception) {
            InAppLogger.d("AtomQSTile", "toggle error: " + e.message)
        }
        handler.postDelayed({ updateTile() }, 500L)
    }

    private fun openFilePicker() {
        try {
            val pi = android.app.PendingIntent.getActivity(
                this, 9001,
                Intent(this, MainActivity::class.java).apply {
                    action = AtomWidgetProvider.ACTION_WIDGET_SEND
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                },
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } catch (e: Exception) {
            InAppLogger.d("AtomQSTile", "openFilePicker error: " + e.message)
        }
    }

    private fun updateTile() {
        try {
            val tile = qsTile ?: return
            // B17: приоритет transferState над WidgetReceive
            val ts = AtomController.transferState.value
            when (ts) {
                is TransferState.Running -> {
                    tile.state = Tile.STATE_ACTIVE
                    tile.label = "Атом"
                    val pct = if (ts.bytesTotal > 0)
                        ((ts.bytesSent * 100) / ts.bytesTotal).toInt() else 0
                    tile.subtitle = "📤 " + ts.peerName.take(10) + " · " + pct + "%"
                }
                is TransferState.Paused -> {
                    tile.state = Tile.STATE_INACTIVE
                    tile.label = "Атом"
                    val pct = if (ts.bytesTotal > 0)
                        ((ts.bytesSent * 100) / ts.bytesTotal).toInt() else 0
                    tile.subtitle = "⏸ " + pct + "% · тап продолжить"
                }
                else -> {
                    val active = WidgetReceiveService.isActive
                    tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    tile.label = "Атом"
                    if (active) {
                        val remain = (WidgetReceiveService.activeUntil - System.currentTimeMillis()) / 1000L
                        if (remain > 0) {
                            tile.subtitle = "Приём · " + (remain / 60) + ":" + "%02d".format(remain % 60)
                        } else tile.subtitle = "Приём"
                    } else {
                        tile.subtitle = "Тап: приём · 2×тап: отправить"
                    }
                }
            }
            tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_tile)
            tile.updateTile()
        } catch (e: Exception) {
            InAppLogger.d("AtomQSTile", "updateTile error: " + e.message)
        }
    }
}
