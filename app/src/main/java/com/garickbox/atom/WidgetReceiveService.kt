package com.garickbox.atom

import android.app.Notification
import android.app.PendingIntent
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

class WidgetReceiveService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.garickbox.atom.WIDGET_RECEIVE_TOGGLE"
        const val ACTION_STOP = "com.garickbox.atom.WIDGET_RECEIVE_STOP"
        const val NOTIF_ID = 7001
        const val DURATION_MS = 10 * 60 * 1000L

        private val EMOJI_RECV: String = String(Character.toChars(0x1F4E5))

        @Volatile var isActive: Boolean = false
            private set
        @Volatile var activeUntil: Long = 0L
            private set

        fun toggle(ctx: Context) {
            val i = Intent(ctx, WidgetReceiveService::class.java).apply { action = ACTION_TOGGLE }
            try { ContextCompat.startForegroundService(ctx, i) } catch (e: Exception) {
                InAppLogger.d("WidgetReceive", "toggle error: " + e.message)
            }
        }
    }

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // B11.4 ANR guard: если сервис стартовал без action (START_STICKY
        // после ребута или аномальный restart) — обязаны вызвать startForeground
        // в течение 5 сек, иначе RemoteServiceException crash.
        if (intent?.action == null) {
            InAppLogger.d("WidgetReceive", "onStartCommand intent=null → ANR guard")
            try { startForeground(NOTIF_ID, buildNotif()) } catch (_: Exception) {}
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_TOGGLE -> {
                if (isActive) {
                    InAppLogger.d("WidgetReceive", "already active — stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                isActive = true
                activeUntil = System.currentTimeMillis() + DURATION_MS
                startForeground(NOTIF_ID, buildNotif())
                try { AtomController.wifiDirect?.startContinuousDiscover() } catch (_: Exception) {}
                wifiLock = PowerLocks.acquireWifi(this, "atom-widget-recv-wifi")
                wakeLock = PowerLocks.acquireWake(this, "atom-widget-recv-wake")
                InAppLogger.d("WidgetReceive", "ON until " + activeUntil)
                scheduleAutoStop()
                AtomWidgetProvider.refreshNow(this)
            }
            ACTION_STOP -> {
                InAppLogger.d("WidgetReceive", "manual stop from notification")
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun scheduleAutoStop() {
        Thread {
            try {
                val remain = activeUntil - System.currentTimeMillis()
                if (remain > 0) Thread.sleep(remain)
            } catch (_: Exception) {}
            try { stopSelf() } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }

    override fun onDestroy() {
        isActive = false
        activeUntil = 0L
        try { AtomController.wifiDirect?.stopContinuousDiscover() } catch (_: Exception) {}
        PowerLocks.releaseWifi(wifiLock)
        PowerLocks.release(wakeLock)
        InAppLogger.d("WidgetReceive", "OFF")
        try { AtomWidgetProvider.refreshNow(this) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotif(): Notification {
        val stopPi = PendingIntent.getService(
            this, 7002,
            Intent(this, WidgetReceiveService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_WIDGET)
            .setSmallIcon(R.drawable.ic_notification_receive)
            .setContentTitle(EMOJI_RECV + " Атом принимает")
            .setContentText("Можно отправить файл · осталось")
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(activeUntil)
            .setShowWhen(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Выключить", stopPi)
            .build()
    }
}
