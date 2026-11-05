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

class AtomWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_WIDGET_SEND = "com.garickbox.atom.WIDGET_SEND"
        @Volatile private var lastRefresh: Long = 0L

        fun refresh(ctx: Context) = refreshInternal(ctx, force = false)
        fun refreshNow(ctx: Context) = refreshInternal(ctx, force = true)

        private fun refreshInternal(ctx: Context, force: Boolean) {
            try {
                val now = System.currentTimeMillis()
                if (!force && now - lastRefresh < 500L) return
                lastRefresh = now
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, AtomWidgetProvider::class.java))
                if (ids.isNotEmpty()) {
                    mgr.updateAppWidget(ids, buildRemoteViews(ctx, AtomController.transferState.value))
                }
            } catch (e: Exception) {
                InAppLogger.d("Widget", "refresh error: " + e.message)
            }
            try {
                AtomWidgetCompactProvider.refresh(ctx, force)
            } catch (_: Exception) {}
            try {
                AtomWidgetUltraProvider.refresh(ctx, force)
            } catch (_: Exception) {}
        }

        /** B210: ring bitmap (Canvas.drawArc → Bitmap) — axiom#288 */
        fun makeRingBitmap(ctx: Context, pct: Int, sizePx: Int, showPercent: Boolean): Bitmap {
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val stroke = sizePx * 0.10f
            val pad = stroke / 2f
            val rect = RectF(pad, pad, sizePx - pad, sizePx - pad)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
            }
            paint.color = 0x33FFFFFF.toInt()
            c.drawArc(rect, 0f, 360f, false, paint)
            paint.color = 0xFFFFFFFF.toInt()
            val sweep = 360f * pct.coerceIn(0, 100) / 100f
            if (sweep > 0f) c.drawArc(rect, -90f, sweep, false, paint)
            if (showPercent) {
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = sizePx * 0.24f
                paint.color = 0xFFFFFFFF.toInt()
                paint.isFakeBoldText = true
                val txt = "$pct%"
                c.drawText(txt, sizePx / 2f, sizePx / 2f + paint.textSize * 0.35f, paint)
            }
            return bmp
        }

        private fun buildRemoteViews(ctx: Context, state: TransferState): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_atom)
            rv.setOnClickPendingIntent(R.id.top_row, piOpenApp(ctx))

            // ring
            val pct = when (state) {
                is TransferState.Running -> if (state.bytesTotal > 0) (state.bytesSent * 100 / state.bytesTotal).toInt() else 0
                else -> 0
            }
            val showPct = state is TransferState.Running
            rv.setImageViewBitmap(R.id.ring, makeRingBitmap(ctx, pct, 200, showPct))

            // header buttons: по умолчанию — «📥 Принять» и «📤 Отправить»
            rv.setTextViewText(R.id.btn_primary, "📥")
            rv.setOnClickPendingIntent(R.id.btn_primary, piToggle(ctx))
            rv.setTextViewText(R.id.btn_secondary, "📤")
            rv.setOnClickPendingIntent(R.id.btn_secondary, piOpenApp(ctx))
            rv.setViewVisibility(R.id.progress, View.GONE)

            when (state) {
                is TransferState.Running -> {
                    val icon = if (state.isReceiver) "📥" else "📤"
                    val arrow = if (state.isReceiver) "←" else "→"
                    rv.setTextViewText(R.id.title, "$icon $arrow ${state.peerName}")
                    rv.setTextViewText(R.id.subtitle, "Файл ${state.fileIndex}/${state.fileCount}")
                    rv.setTextViewText(R.id.file_name, state.fileName)
                    val eta = if (state.bytesPerSecond > 0 && state.bytesTotal > state.bytesSent) {
                        val s = (state.bytesTotal - state.bytesSent) / state.bytesPerSecond
                        " · ⏱ ${s / 60}:%02d".format(s % 60)
                    } else ""
                    val hz = AtomController.currentP2pFrequencyHz
                    val band = when {
                        hz >= 5000 -> "5GHz"
                        hz >= 2400 -> "2.4GHz"
                        else -> ""
                    }
                    rv.setTextViewText(R.id.metrics,
                        "⚡ ${fmtSpeed(state.bytesPerSecond)}" +
                            (if (band.isNotEmpty()) " · 📶 $band" else "") + eta)
                    rv.setProgressBar(R.id.progress, 100, pct, false)
                    rv.setViewVisibility(R.id.progress, View.VISIBLE)
                    if (state.isReceiver) {
                        rv.setTextViewText(R.id.btn_primary, "✕")
                        rv.setOnClickPendingIntent(R.id.btn_primary, piService(ctx, 8003, TransferNotification.ACTION_CANCEL))
                        rv.setTextViewText(R.id.btn_secondary, "")
                        rv.setViewVisibility(R.id.btn_secondary, View.GONE)
                    } else {
                        rv.setTextViewText(R.id.btn_primary, "⏸")
                        rv.setOnClickPendingIntent(R.id.btn_primary, piService(ctx, 8001, TransferNotification.ACTION_PAUSE))
                        if (state.fileCount > 1) {
                            rv.setTextViewText(R.id.btn_secondary, "⏭")
                            rv.setOnClickPendingIntent(R.id.btn_secondary, piService(ctx, 8008, TransferNotification.ACTION_SKIP))
                            rv.setViewVisibility(R.id.btn_secondary, View.VISIBLE)
                        } else {
                            rv.setViewVisibility(R.id.btn_secondary, View.GONE)
                        }
                    }
                }
                is TransferState.Paused -> {
                    rv.setTextViewText(R.id.title, "⏸ Пауза · ${state.peerName}")
                    rv.setTextViewText(R.id.subtitle, "Файл ${state.fileIndex}/${state.fileCount}")
                    rv.setTextViewText(R.id.file_name, state.fileName)
                    rv.setTextViewText(R.id.metrics,
                        "${fmtBytes(state.bytesSent)} / ${fmtBytes(state.bytesTotal)}")
                    rv.setProgressBar(R.id.progress, 100, pct, false)
                    rv.setViewVisibility(R.id.progress, View.VISIBLE)
                    rv.setTextViewText(R.id.btn_primary, "▶")
                    rv.setOnClickPendingIntent(R.id.btn_primary, piService(ctx, 8002, TransferNotification.ACTION_RESUME))
                    rv.setTextViewText(R.id.btn_secondary, "✕")
                    rv.setOnClickPendingIntent(R.id.btn_secondary, piService(ctx, 8003, TransferNotification.ACTION_CANCEL))
                    rv.setViewVisibility(R.id.btn_secondary, View.VISIBLE)
                }
                is TransferState.Success -> {
                    rv.setTextViewText(R.id.title, "✅ Готово · ${state.peerName}")
                    rv.setTextViewText(R.id.subtitle, "${state.fileCount} файл · ${fmtBytes(state.bytesTotal)}")
                    rv.setTextViewText(R.id.file_name, "")
                    rv.setTextViewText(R.id.metrics, "")
                    rv.setTextViewText(R.id.btn_primary, "📥")
                    rv.setOnClickPendingIntent(R.id.btn_primary, piToggle(ctx))
                    rv.setTextViewText(R.id.btn_secondary, "📤")
                    rv.setOnClickPendingIntent(R.id.btn_secondary, piOpenApp(ctx))
                    rv.setViewVisibility(R.id.btn_secondary, View.VISIBLE)
                }
                is TransferState.Failed -> {
                    rv.setTextViewText(R.id.title, "⚠️ Ошибка · ${state.peerName}")
                    rv.setTextViewText(R.id.subtitle, state.reason.take(40))
                    rv.setTextViewText(R.id.file_name, state.fileName)
                    rv.setTextViewText(R.id.metrics, "")
                    rv.setTextViewText(R.id.btn_primary, "✕")
                    rv.setOnClickPendingIntent(R.id.btn_primary, piService(ctx, 8003, TransferNotification.ACTION_CANCEL))
                    rv.setTextViewText(R.id.btn_secondary, "")
                    rv.setViewVisibility(R.id.btn_secondary, View.GONE)
                }
                is TransferState.UpdatePending -> {
                    rv.setTextViewText(R.id.title, "⬆️ build-${state.versionCode}")
                    rv.setTextViewText(R.id.subtitle, "Тап для установки")
                    rv.setTextViewText(R.id.file_name, state.fileName)
                    rv.setTextViewText(R.id.metrics, "")
                    rv.setTextViewText(R.id.btn_primary, "⬆️")
                    rv.setOnClickPendingIntent(R.id.btn_primary, piUpdateInstall(ctx, state.apkUri))
                    rv.setTextViewText(R.id.btn_secondary, "")
                    rv.setViewVisibility(R.id.btn_secondary, View.GONE)
                }
                else -> {
                    val recv = WidgetReceiveService.isActive
                    if (recv) {
                        val remain = (WidgetReceiveService.activeUntil - System.currentTimeMillis()) / 1000L
                        rv.setTextViewText(R.id.title, "⚛️ Принимаю")
                        rv.setTextViewText(R.id.subtitle,
                            if (remain > 0) "Осталось ${remain / 60}:%02d".format(remain % 60) else "Активно")
                        rv.setTextViewText(R.id.metrics, "📥 Жду файлы…")
                    } else {
                        val last = AtomController.lastPeer
                        rv.setTextViewText(R.id.title, "⚛️ Атом")
                        rv.setTextViewText(R.id.subtitle,
                            if (last != null && last.name.isNotBlank()) "📱 ${last.name.take(20)}" else "Готов к передаче")
                        rv.setTextViewText(R.id.metrics, "")
                    }
                    rv.setTextViewText(R.id.file_name, "")
                    rv.setTextViewText(R.id.btn_primary, "📥")
                    rv.setOnClickPendingIntent(R.id.btn_primary, piToggle(ctx))
                    rv.setTextViewText(R.id.btn_secondary, "📤")
                    rv.setOnClickPendingIntent(R.id.btn_secondary, piOpenApp(ctx))
                    rv.setViewVisibility(R.id.btn_secondary, View.VISIBLE)
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
            return PendingIntent.getForegroundService(ctx, 8004, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        private fun piUpdateInstall(ctx: Context, apkUri: String): PendingIntent {
            val i = Intent(ctx, MainActivity::class.java).apply {
                action = "com.garickbox.atom.WIDGET_INSTALL"
                putExtra("apk_uri", apkUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            return PendingIntent.getActivity(ctx, 8007, i,
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
            if (b < 1024) return "$b Б"
            if (b < 1024 * 1024) return "%.1f КБ".format(b / 1024.0)
            if (b < 1024 * 1024 * 1024) return "%.1f МБ".format(b / 1024.0 / 1024.0)
            return "%.1f ГБ".format(b / 1024.0 / 1024.0 / 1024.0)
        }
        private fun fmtSpeed(bps: Long): String {
            if (bps <= 0) return "…"
            return "%.1f МБ/с".format(bps / 1024.0 / 1024.0)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        try { appWidgetManager.updateAppWidget(appWidgetIds, buildRemoteViews(context, AtomController.transferState.value)) }
        catch (_: Exception) {}
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "com.garickbox.atom.WIDGET_REFRESH") refreshNow(context)
    }
}
