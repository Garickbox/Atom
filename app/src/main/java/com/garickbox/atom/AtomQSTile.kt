package com.garickbox.atom

import android.app.PendingIntent
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

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        InAppLogger.d("AtomQSTile", "onClick → toggle")
        try {
            val i = Intent(this, WidgetReceiveService::class.java).apply {
                action = WidgetReceiveService.ACTION_TOGGLE
            }
            ContextCompat.startForegroundService(this, i)
        } catch (e: Exception) {
            InAppLogger.d("AtomQSTile", "onClick error: " + e.message)
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            updateTile()
        }, 500L)
    }

    override fun onLongClick(): Boolean {
        // B11.8: long press → file picker (тот же flow, что виджет «📤 Отправить»)
        InAppLogger.d("AtomQSTile", "onLongClick → open MainActivity with SEND action")
        return try {
            val pi = PendingIntent.getActivity(this, 9001,
                Intent(this, MainActivity::class.java).apply {
                    action = AtomWidgetProvider.ACTION_WIDGET_SEND
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                             Intent.FLAG_ACTIVITY_CLEAR_TOP or
                             Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
            true
        } catch (e: Exception) {
            InAppLogger.d("AtomQSTile", "onLongClick error: " + e.message)
            false
        }
    }

    private fun updateTile() {
        try {
            val tile = qsTile ?: return
            val active = WidgetReceiveService.isActive
            tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = "Атом"
            if (active) {
                val remain = (WidgetReceiveService.activeUntil - System.currentTimeMillis()) / 1000L
                if (remain > 0) {
                    tile.subtitle = "Приём · " + (remain / 60) + ":" + "%02d".format(remain % 60)
                } else {
                    tile.subtitle = "Приём"
                }
            } else {
                tile.subtitle = "Приём выкл"
            }
            tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_tile)
            tile.updateTile()
            InAppLogger.d("AtomQSTile", "updateTile active=" + active)
        } catch (e: Exception) {
            InAppLogger.d("AtomQSTile", "updateTile error: " + e.message)
        }
    }
}
