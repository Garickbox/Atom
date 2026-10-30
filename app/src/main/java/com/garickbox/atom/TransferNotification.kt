package com.garickbox.atom

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object TransferNotification {

    const val ACTION_CANCEL = "com.garickbox.atom.CANCEL_TRANSFER"
    const val ACTION_RESUME = "com.garickbox.atom.RESUME_TRANSFER"
    const val ACTION_PAUSE = "com.garickbox.atom.PAUSE_TRANSFER"
    const val EXTRA_TRANSFER_ID = "transfer_id"
    const val EXTRA_FILE_NAME = "file_name"

    fun buildRunning(
        ctx: Context,
        transferId: String,
        peerName: String,
        fileName: String,
        bytesSent: Long,
        bytesTotal: Long,
        bytesPerSecond: Long = 0L,
        fileIndex: Int = 0,
        fileCount: Int = 0
    ): Notification {
        val pct = if (bytesTotal > 0) ((bytesSent * 100) / bytesTotal).toInt() else 0
        val cancelIntent = Intent(ctx, FileSenderService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_TRANSFER_ID, transferId)
        }
        val cancelPi = PendingIntent.getService(
            ctx, transferId.hashCode(), cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = Intent(ctx, FileSenderService::class.java).apply {
            action = ACTION_PAUSE
            putExtra(EXTRA_TRANSFER_ID, transferId)
        }
        val pausePi = PendingIntent.getService(
            ctx, transferId.hashCode() + 3, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, NotificationHelper.CHANNEL_TRANSFER)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(
                "📤 Отправка → $peerName" +
                if (fileCount > 0) " · файл $fileIndex/$fileCount" else ""
            )
            .setContentText(buildString {
                append("$fileName ${formatBytes(bytesSent)} из ${formatBytes(bytesTotal)}")
                if (bytesPerSecond > 0) append("  ·  ${formatSpeed(bytesPerSecond)}")
            })
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setDeleteIntent(cancelPi)
            .addAction(android.R.drawable.ic_media_pause, "Пауза", pausePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отменить", cancelPi)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    fun buildPaused(
        ctx: Context,
        transferId: String,
        peerName: String,
        fileName: String,
        bytesSent: Long,
        bytesTotal: Long,
        fileIndex: Int = 0,
        fileCount: Int = 0
    ): Notification {
        val pct = if (bytesTotal > 0) ((bytesSent * 100) / bytesTotal).toInt() else 0
        val resumeIntent = Intent(ctx, FileSenderService::class.java).apply {
            action = ACTION_RESUME
            putExtra(EXTRA_TRANSFER_ID, transferId)
        }
        val resumePi = PendingIntent.getService(
            ctx, transferId.hashCode() + 4, resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = Intent(ctx, FileSenderService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_TRANSFER_ID, transferId)
        }
        val cancelPi = PendingIntent.getService(
            ctx, transferId.hashCode() + 5, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, NotificationHelper.CHANNEL_TRANSFER)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(
                "⏸ Пауза → $peerName" +
                if (fileCount > 0) " · файл $fileIndex/$fileCount" else ""
            )
            .setContentText("$fileName ${formatBytes(bytesSent)} из ${formatBytes(bytesTotal)} · $pct%")
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_play, "Продолжить", resumePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отменить", cancelPi)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    fun buildFailed(
        ctx: Context,
        transferId: String,
        peerName: String,
        fileName: String,
        reason: String
    ): Notification {
        val resumeIntent = Intent(ctx, FileSenderService::class.java).apply {
            action = ACTION_RESUME
            putExtra(EXTRA_TRANSFER_ID, transferId)
        }
        val resumePi = PendingIntent.getService(
            ctx, transferId.hashCode() + 1, resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = Intent(ctx, FileSenderService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_TRANSFER_ID, transferId)
        }
        val cancelPi = PendingIntent.getService(
            ctx, transferId.hashCode() + 2, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, NotificationHelper.CHANNEL_TRANSFER)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("⚠️ Передача прервана")
            .setContentText("$fileName — $reason")
            .setOngoing(false)
            .setAutoCancel(false)
            .addAction(android.R.drawable.stat_sys_upload, "Продолжить", resumePi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отменить", cancelPi)
            .build()
    }

    fun buildSuccess(
        ctx: Context,
        peerName: String,
        fileCount: Int,
        bytesTotal: Long,
        durationMs: Long
    ): Notification {
        val speed = if (durationMs > 0) (bytesTotal * 1000 / durationMs) else 0L
        return NotificationCompat.Builder(ctx, NotificationHelper.CHANNEL_TRANSFER)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("✅ Отправлено: $fileCount файл(ов)")
            .setContentText("$peerName · ${formatBytes(bytesTotal)} · ${formatBytes(speed)}/с")
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
    }

    private val lastKeys = java.util.concurrent.ConcurrentHashMap<Int, String>()

    fun update(ctx: Context, id: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(ctx).notify(id, notification)
        } catch (_: SecurityException) {}
    }

    /** Пропускает notify если title+text не изменились. Для прогресс-пути. */
    fun updateIfChanged(ctx: Context, id: Int, notification: Notification) {
        val title = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text  = notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val key = title + "|" + text
        if (lastKeys[id] == key) return
        lastKeys[id] = key
        update(ctx, id, notification)
    }

    fun resetCache(id: Int) { lastKeys.remove(id) }

    fun cancel(ctx: Context, id: Int) {
        NotificationManagerCompat.from(ctx).cancel(id)
    }

    private fun formatBytes(b: Long): String {
        if (b < 1024) return "$b Б"
        if (b < 1024 * 1024) return "%.1f КБ".format(b / 1024.0)
        if (b < 1024 * 1024 * 1024) return "%.1f МБ".format(b / 1024.0 / 1024.0)
        return "%.1f ГБ".format(b / 1024.0 / 1024.0 / 1024.0)
    }

    private fun formatSpeed(bps: Long): String {
        if (bps <= 0) return "—"
        return "%.1f МБ/с".format(bps / 1024.0 / 1024.0)
    }

}
