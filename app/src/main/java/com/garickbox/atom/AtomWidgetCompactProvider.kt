package com.garickbox.atom

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.RemoteViews
import com.mesh.core.InAppLogger

class AtomWidgetCompactProvider : AppWidgetProvider() {

    companion object {
        @Volatile private var lastRefresh: Long = 0L

        fun refresh(ctx: Context, force: Boolean = false) {
            try {
                val now = System.currentTimeMillis()
                if (!force && now - lastRefresh < 500L) return
                lastRefresh = now
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, AtomWidgetCompactProvider::class.java))
                if (ids.isEmpty()) return
                val rv = buildRvs(ctx, AtomController.transferState.value)
                mgr.updateAppWidget(ids, rv)
            } catch (e: Exception) {
                InAppLogger.d("WidgetCompact", "refresh: ${e.message}")
            }
        }

        private fun buildRvs(ctx: Context, state: TransferState): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_atom_compact)
            rv.setOnClickPendingIntent(R.id.compact_root, piOpenApp(ctx))

            val ringPct = when (state) {
                is TransferState.Running -> if (state.bytesTotal > 0) (state.bytesSent * 100 / state.bytesTotal).toInt() else 0
                else -> 0
            }
            val ringBmp = AtomWidgetProvider.makeRingBitmap(ctx, ringPct, 200, showPercent = state is TransferState.Running)
            rv.setImageViewBitmap(R.id.ring, ringBmp)

            when (state) {
                is TransferState.Running -> {
                    val emoji = if (state.isReceiver) "📥" else "📤"
                    rv.setTextViewText(R.id.status, "$emoji ${state.fileIndex}/${state.fileCount}")
                    rv.setTextViewText(R.id.btn_action, if (state.isReceiver) "✕ Стоп" else "⏸ Пауза")
                    rv.setOnClickPendingIntent(R.id.btn_action,
                        piService(ctx, if (state.isReceiver) 6101 else 6102,
                            if (state.isReceiver) TransferNotification.ACTION_CANCEL else TransferNotification.ACTION_PAUSE))
                }
                is TransferState.Paused -> {
                    rv.setTextViewText(R.id.status, "⏸ Пауза")
                    rv.setTextViewText(R.id.btn_action, "▶ Продолжить")
                    rv.setOnClickPendingIntent(R.id.btn_action, piService(ctx, 6103, TransferNotification.ACTION_RESUME))
                }
                is TransferState.Success -> {
                    rv.setTextViewText(R.id.status, "✅ Готово")
                    rv.setTextViewText(R.id.btn_action, "📥 Принять")
                    rv.setOnClickPendingIntent(R.id.btn_action, piToggle(ctx))
                }
                is TransferState.Failed -> {
                    rv.setTextViewText(R.id.status, "⚠️ Ошибка")
                    rv.setTextViewText(R.id.btn_action, "✕ Закрыть")
                    rv.setOnClickPendingIntent(R.id.btn_action, piService(ctx, 6104, TransferNotification.ACTION_CANCEL))
                }
                is TransferState.UpdatePending -> {
                    rv.setTextViewText(R.id.status, "⬆️ build-${state.versionCode}")
                    rv.setTextViewText(R.id.btn_action, "Установить")
                    rv.setOnClickPendingIntent(R.id.btn_action, piOpenApp(ctx))
                }
                else -> {
                    val fgs = WidgetReceiveService.isActive
                    if (fgs) {
                        val remain = (WidgetReceiveService.activeUntil - System.currentTimeMillis()) / 1000L
                        rv.setTextViewText(R.id.status, if (remain > 0) "📥 " + (remain / 60) + ":" + "%02d".format(remain % 60) else "📥 Приём")
                        rv.setTextViewText(R.id.btn_action, "⏹ Стоп")
                    } else {
                        rv.setTextViewText(R.id.status, "Готов")
                        rv.setTextViewText(R.id.btn_action, "📥 Принять")
                    }
                    rv.setOnClickPendingIntent(R.id.btn_action, piToggle(ctx))
                }
            }
            return rv
        }

        private fun piService(ctx: Context, code: Int, action: String): PendingIntent {
            val i = Intent(ctx, FileSenderService::class.java).apply { this.action = action }
            return PendingIntent.getForegroundService(ctx, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        private fun piToggle(ctx: Context): PendingIntent {
            val i = Intent(ctx, WidgetReceiveService::class.java).apply { action = WidgetReceiveService.ACTION_TOGGLE }
            return PendingIntent.getForegroundService(ctx, 6105, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        private fun piOpenApp(ctx: Context): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            return PendingIntent.getActivity(ctx, 6106, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        try { appWidgetManager.updateAppWidget(appWidgetIds, buildRvs(context, AtomController.transferState.value)) }
        catch (_: Exception) {}
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "com.garickbox.atom.WIDGET_REFRESH_COMPACT") refresh(context, force = true)
    }
}
