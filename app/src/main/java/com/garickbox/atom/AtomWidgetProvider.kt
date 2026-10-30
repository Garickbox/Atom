package com.garickbox.atom

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.mesh.core.InAppLogger

class AtomWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_SEND = "com.garickbox.atom.WIDGET_SEND"

        // Emoji через Character.toChars — ASCII в исходнике, emoji в рантайме
        private val EMOJI_SEND: String = String(Character.toChars(0x1F4E4))  // send arrow
        private val EMOJI_RECV: String = String(Character.toChars(0x1F4E5))  // recv arrow
        private val ARROW: String = String(Character.toChars(0x2192))       // ->
        private val ATOM: String = String(Character.toChars(0x269B))        // atom
        private val VS16: String = String(Character.toChars(0xFE0F))        // emoji style
        private val PAUSE: String = String(Character.toChars(0x23F8))       // pause
        private val PLAY: String = String(Character.toChars(0x25B6))        // play
        private val XMARK: String = String(Character.toChars(0x2715))       // x

        @Volatile private var lastRefresh: Long = 0L

        fun refresh(ctx: Context) = refreshInternal(ctx, force = false)
        fun refreshNow(ctx: Context) = refreshInternal(ctx, force = true)

        private fun refreshInternal(ctx: Context, force: Boolean) {
            try {
                val now = System.currentTimeMillis()
                if (!force && now - lastRefresh < 1000L) return
                lastRefresh = now
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, AtomWidgetProvider::class.java))
                if (ids.isEmpty()) return
                val state = AtomController.transferState.value
                val rv = buildRemoteViews(ctx, state)
                mgr.updateAppWidget(ids, rv)
            } catch (e: Exception) {
                InAppLogger.d("Widget", "refresh error: " + e.message)
            }
        }

        private fun buildRemoteViews(ctx: Context, state: TransferState): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_atom)

            when (state) {
                is TransferState.Running -> {
                    val title = EMOJI_SEND + " " + ARROW + " " + state.peerName +
                        if (state.fileCount > 0) " · файл " + state.fileIndex + "/" + state.fileCount else ""
                    rv.setTextViewText(R.id.title, title)
                    val pct = if (state.bytesTotal > 0) ((state.bytesSent * 100) / state.bytesTotal).toInt() else 0
                    rv.setTextViewText(R.id.subtitle, pct.toString() + "% · " + fmtSpeed(state.bytesPerSecond))
                    rv.setProgressBar(R.id.progress, 100, pct, false)
                    rv.setViewVisibility(R.id.progress, View.VISIBLE)
                    rv.setTextViewText(R.id.btn_primary, PAUSE + " Пауза")
                    rv.setOnClickPendingIntent(R.id.btn_primary, piService(ctx, 8001, TransferNotification.ACTION_PAUSE))
                    rv.setTextViewText(R.id.btn_secondary, XMARK + " Отменить")
                    rv.setOnClickPendingIntent(R.id.btn_secondary, piService(ctx, 8003, TransferNotification.ACTION_CANCEL))
                }
                is TransferState.Paused -> {
                    rv.setTextViewText(R.id.title, PAUSE + " Пауза · " + state.fileName)
                    val pct = if (state.bytesTotal > 0) ((state.bytesSent * 100) / state.bytesTotal).toInt() else 0
                    rv.setTextViewText(R.id.subtitle, pct.toString() + "% · " + fmtBytes(state.bytesSent) + "/" + fmtBytes(state.bytesTotal))
                    rv.setProgressBar(R.id.progress, 100, pct, false)
                    rv.setViewVisibility(R.id.progress, View.VISIBLE)
                    rv.setTextViewText(R.id.btn_primary, PLAY + " Продолжить")
                    rv.setOnClickPendingIntent(R.id.btn_primary, piService(ctx, 8002, TransferNotification.ACTION_RESUME))
                    rv.setTextViewText(R.id.btn_secondary, XMARK + " Отменить")
                    rv.setOnClickPendingIntent(R.id.btn_secondary, piService(ctx, 8003, TransferNotification.ACTION_CANCEL))
                }
                else -> {
                    val recvOn = WidgetReceiveService.isActive
                    val title = if (recvOn) ATOM + VS16 + " Принимаю"
                                else ATOM + VS16 + " Атом · готов"
                    rv.setTextViewText(R.id.title, title)
                    val sub = if (recvOn) {
                        val remain = (WidgetReceiveService.activeUntil - System.currentTimeMillis()) / 1000L
                        "Осталось " + (remain / 60) + ":" + "%02d".format(remain % 60)
                    } else "Нажми на действие"
                    rv.setTextViewText(R.id.subtitle, sub)
                    rv.setViewVisibility(R.id.progress, View.GONE)
                    rv.setTextViewText(R.id.btn_primary, if (recvOn) EMOJI_RECV + " Активно" else EMOJI_RECV + " Принимать")
                    rv.setOnClickPendingIntent(R.id.btn_primary, piServiceToggle(ctx))
                    rv.setTextViewText(R.id.btn_secondary, EMOJI_SEND + " Отправить")
                    rv.setOnClickPendingIntent(R.id.btn_secondary, piWidgetSend(ctx))
                }
            }

            rv.setOnClickPendingIntent(R.id.top_row, piOpenApp(ctx))
            return rv
        }

        private fun piService(ctx: Context, reqCode: Int, action: String): PendingIntent {
            val i = Intent(ctx, FileSenderService::class.java).apply { this.action = action }
            // B11.4: getForegroundService — иначе при убитом процессе startService
            // заблокирован на Android 8+ (Background Service Restriction).
            return PendingIntent.getForegroundService(ctx, reqCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun piServiceToggle(ctx: Context): PendingIntent {
            val i = Intent(ctx, WidgetReceiveService::class.java).apply {
                action = WidgetReceiveService.ACTION_TOGGLE
            }
            // B11.4: getForegroundService — критично для убитого процесса
            return PendingIntent.getForegroundService(ctx, 8004, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun piWidgetSend(ctx: Context): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).apply {
                action = ACTION_WIDGET_SEND
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            return PendingIntent.getActivity(ctx, 8005, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun piOpenApp(ctx: Context): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            return PendingIntent.getActivity(ctx, 8006, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun fmtBytes(b: Long): String {
            if (b < 1024) return b.toString() + " Б"
            if (b < 1024 * 1024) return "%.1f КБ".format(b / 1024.0)
            if (b < 1024 * 1024 * 1024) return "%.1f МБ".format(b / 1024.0 / 1024.0)
            return "%.1f ГБ".format(b / 1024.0 / 1024.0 / 1024.0)
        }

        private fun fmtSpeed(bps: Long): String {
            if (bps <= 0) return "—"
            return "%.1f МБ/с".format(bps / 1024.0 / 1024.0)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        try {
            val rv = buildRemoteViews(context, AtomController.transferState.value)
            appWidgetManager.updateAppWidget(appWidgetIds, rv)
        } catch (e: Exception) {
            InAppLogger.d("Widget", "onUpdate error: " + e.message)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "com.garickbox.atom.WIDGET_REFRESH") {
            refreshNow(context)
        }
    }
}
