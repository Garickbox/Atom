package com.garickbox.atom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mesh.core.InAppLogger

/**
 * B79: badge counter на иконке через Notification.setNumber().
 * Нативный Android badge API (API 26+), launcher показывает цифру.
 * HyperOS/MIUI, Samsung, Pixel launchers поддерживают.
 * Не требует ShortcutBadger.
 */
object BadgeHelper {
    private const val NOTIF_ID = 9001
    private const val CHANNEL_ID = "badge"
    private const val PREF_COUNT = "badge_count"

    private fun ensureChannel(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Счётчик",
                        NotificationManager.IMPORTANCE_MIN).apply {
                        setShowBadge(true)
                        setSound(null, null)
                        enableVibration(false)
                    })
            }
        } catch (_: Exception) {}
    }

    fun increment(ctx: Context) {
        val prefs = ctx.getSharedPreferences("atom", Context.MODE_PRIVATE)
        val n = prefs.getInt(PREF_COUNT, 0) + 1
        prefs.edit().putInt(PREF_COUNT, n).apply()
        show(ctx, n)
    }

    fun setCount(ctx: Context, n: Int) {
        val prefs = ctx.getSharedPreferences("atom", Context.MODE_PRIVATE)
        prefs.edit().putInt(PREF_COUNT, n).apply()
        if (n <= 0) clear(ctx) else show(ctx, n)
    }

    fun clear(ctx: Context) {
        val prefs = ctx.getSharedPreferences("atom", Context.MODE_PRIVATE)
        val prev = prefs.getInt(PREF_COUNT, 0)
        prefs.edit().putInt(PREF_COUNT, 0).apply()
        try {
            NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
        } catch (_: Exception) {}
        if (prev > 0) InAppLogger.d("Badge", "cleared (was " + prev + ")")
    }

    fun currentCount(ctx: Context): Int =
        ctx.getSharedPreferences("atom", Context.MODE_PRIVATE).getInt(PREF_COUNT, 0)

    private fun show(ctx: Context, n: Int) {
        ensureChannel(ctx)
        try {
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_receive)
                .setContentTitle("Получено файлов: " + n)
                .setContentText("Открой Атом чтобы сбросить")
                .setNumber(n)
                .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setSilent(true)
                .setOngoing(false)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, notif)
        } catch (e: Exception) {
            InAppLogger.d("Badge", "show error: " + e.message)
        }
    }
}
