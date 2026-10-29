package com.garickbox.atom

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mesh.core.InAppLogger
import com.mesh.core.PowerLocks

class FileReceiverService : Service() {

    companion object {
        private const val NOTIF_ID = 2002

        fun start(ctx: Context, fileName: String, totalSize: Long) {
            val i = Intent(ctx, FileReceiverService::class.java).apply {
                action = "START"
                putExtra("fileName", fileName)
                putExtra("totalSize", totalSize)
            }
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                InAppLogger.d("FileReceiverService", "start error: ${e.message}")
            }
        }

        fun updateProgress(ctx: Context, fileName: String, written: Long, total: Long) {
            // Через static instance — без нового Intent (быстро)
            instance?.updateNotif(fileName, written, total)
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, FileReceiverService::class.java))
            } catch (_: Exception) {}
        }

        @Volatile
        private var instance: FileReceiverService? = null
    }

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Немедленный startForeground — иначе ANR на Android 12+
        startForeground(NOTIF_ID, buildNotif("Подготовка", 0, 0))
        wifiLock = PowerLocks.acquireWifi(this, "atom-recv-wifi")
        wakeLock = PowerLocks.acquireWake(this, "atom-recv-wake")
        InAppLogger.d("FileReceiverService", "onCreate, locks acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fileName = intent?.getStringExtra("fileName") ?: "файл"
        val total = intent?.getLongExtra("totalSize", 0L) ?: 0L
        InAppLogger.d("FileReceiverService", "start: $fileName ($total b)")
        updateNotif(fileName, 0, total)
        return START_STICKY
    }

    private fun updateNotif(fileName: String, written: Long, total: Long) {
        try {
            val nm = androidx.core.app.NotificationManagerCompat.from(this)
            nm.notify(NOTIF_ID, buildNotif(fileName, written, total))
        } catch (_: SecurityException) {}
    }

    private fun buildNotif(fileName: String, written: Long, total: Long): Notification {
        val pct = if (total > 0) ((written * 100) / total).toInt() else 0
        val text = if (total > 0) "${formatBytes(written)} / ${formatBytes(total)}"
                   else formatBytes(written)
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_TRANSFER)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("📥 Приём: $fileName")
            .setContentText(text)
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    override fun onDestroy() {
        instance = null
        PowerLocks.releaseWifi(wifiLock)
        PowerLocks.release(wakeLock)
        InAppLogger.d("FileReceiverService", "onDestroy, locks released")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun formatBytes(b: Long): String {
        if (b < 1024) return "$b Б"
        if (b < 1024 * 1024) return "%.1f КБ".format(b / 1024.0)
        if (b < 1024 * 1024 * 1024) return "%.1f МБ".format(b / 1024.0 / 1024.0)
        return "%.1f ГБ".format(b / 1024.0 / 1024.0 / 1024.0)
    }
}
