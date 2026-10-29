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

        const val ACTION_CANCEL_RECEIVE = "com.garickbox.atom.CANCEL_RECEIVE"

        fun start(ctx: Context, fileName: String, totalSize: Long,
                  peerName: String = "?", transferId: String = "") {
            val i = Intent(ctx, FileReceiverService::class.java).apply {
                action = "START"
                putExtra("fileName", fileName)
                putExtra("totalSize", totalSize)
                putExtra("peerName", peerName)
                putExtra("transferId", transferId)
            }
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                InAppLogger.d("FileReceiverService", "start error: ${e.message}")
            }
        }

        fun updateProgress(ctx: Context, fileName: String, written: Long, total: Long) {
            receiverSpeedTracker.onProgress(written)
            val bps = receiverSpeedTracker.bytesPerSecond()
            instance?.updateNotif(fileName, written, total, bps)
            // Обновляем TransferState — для UI в MainActivity
            if (currentTransferId.isNotBlank()) {
                AtomController.transferState.value = TransferState.Running(
                    transferId = currentTransferId,
                    peerName = currentPeerName,
                    fileName = fileName,
                    bytesSent = written,
                    bytesTotal = total,
                    bytesPerSecond = bps,
                    isReceiver = true
                )
            }
        }

        fun setPeerInfo(peerName: String, transferId: String) {
            currentPeerName = peerName
            currentTransferId = transferId
            receiverSpeedTracker.reset()
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, FileReceiverService::class.java))
            } catch (_: Exception) {}
        }

        @Volatile
        private var instance: FileReceiverService? = null
        private val receiverSpeedTracker = SpeedTracker()
        private var currentPeerName: String = "?"
        private var currentTransferId: String = ""
    }

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        try { AtomController.setTransferActive(true) } catch (_: Exception) {}
        super.onCreate()
        instance = this
        // Немедленный startForeground — иначе ANR на Android 12+
        startForeground(NOTIF_ID, buildNotif("Подготовка", 0, 0, 0L))
        wifiLock = PowerLocks.acquireWifi(this, "atom-recv-wifi")
        wakeLock = PowerLocks.acquireWake(this, "atom-recv-wake")
        InAppLogger.d("FileReceiverService", "onCreate, locks acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_RECEIVE) {
            InAppLogger.d("FileReceiverService", "cancel receive action")
            FileReceiver.cancelActiveReceive(this)
            return START_NOT_STICKY
        }
        val fileName = intent?.getStringExtra("fileName") ?: "файл"
        val total = intent?.getLongExtra("totalSize", 0L) ?: 0L
        val peerName = intent?.getStringExtra("peerName") ?: "?"
        val transferId = intent?.getStringExtra("transferId") ?: ""
        setPeerInfo(peerName, transferId)
        InAppLogger.d("FileReceiverService", "start: $fileName ($total b)")
        updateNotif(fileName, 0, total)
        return START_STICKY
    }

    private fun updateNotif(fileName: String, written: Long, total: Long, bps: Long = 0L) {
        try {
            val nm = androidx.core.app.NotificationManagerCompat.from(this)
            nm.notify(NOTIF_ID, buildNotif(fileName, written, total, bps))
        } catch (_: SecurityException) {}
    }

    private fun buildNotif(fileName: String, written: Long, total: Long, bps: Long): Notification {
        val pct = if (total > 0) ((written * 100) / total).toInt() else 0
        val text = buildString {
            if (total > 0) append("${formatBytes(written)} / ${formatBytes(total)}")
            else append(formatBytes(written))
            if (bps > 0) append("  ·  ${formatSpeed(bps)}")
        }
        // Кнопка "Отменить" — PendingIntent на этот же сервис
        val cancelIntent = Intent(this, FileReceiverService::class.java).apply {
            action = ACTION_CANCEL_RECEIVE
        }
        val cancelPi = android.app.PendingIntent.getService(
            this, 3001, cancelIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_TRANSFER)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("📥 Приём: $fileName")
            .setContentText(text)
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отменить", cancelPi)
            .build()
    }

    private fun formatSpeed(bps: Long): String {
        val mbps = bps / 1024.0 / 1024.0
        return "%.1f МБ/с".format(mbps)
    }

    override fun onDestroy() {
        try { AtomController.setTransferActive(false) } catch (_: Exception) {}
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
