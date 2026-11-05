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
    @Volatile private var autoStopGen: Int = 0  // B74: generation for autoStop threads
    // B20.3: ticker — виджет/QS Tile раз в секунду пока приём активен
    private val tickerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tickerRunnable = object : Runnable {
        override fun run() {
            if (!isActive) return
            try { AtomWidgetCompactProvider.refreshNow(this@WidgetReceiveService) } catch (_: Exception) {}
            try { AtomQSTile.requestUpdate(this@WidgetReceiveService) } catch (_: Exception) {}
            tickerHandler.postDelayed(this, 1000L)
        }
    }
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        InAppLogger.d("WidgetReceive", "onStartCommand entered, action=" + (intent?.action ?: "null"))
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
                    // B11.5: debounce — если активен <1.5 сек, это двойной тап, игнорируем
                    val activeFor = DURATION_MS - (activeUntil - System.currentTimeMillis())
                    if (activeFor in 0L until 1500L) {
                        InAppLogger.d("WidgetReceive",
                            "toggle debounce — ignore quick re-tap (" + activeFor + "ms)")
                        return START_STICKY
                    }
                    InAppLogger.d("WidgetReceive",
                        "already active — stopping (after " + activeFor + "ms)")
                    stopSelf()
                    return START_NOT_STICKY
                }
                isActive = true
                autoStopGen++  // B74: инвалидирует старые autoStop-потоки
                activeUntil = System.currentTimeMillis() + DURATION_MS
                // B114: try/catch — SecurityException на revocation POST_NOTIFICATIONS
                try {
                    startForeground(NOTIF_ID, buildNotif())
                } catch (e: Exception) {
                    InAppLogger.err("WidgetReceive", "B114: startForeground fail: ${e.message}")
                    isActive = false
                    activeUntil = 0L
                    stopSelf()
                    return START_NOT_STICKY
                }
                try { AtomController.wifiDirect?.startContinuousDiscover() } catch (_: Exception) {}
                wifiLock = PowerLocks.acquireWifi(this, "atom-widget-recv-wifi")
                wakeLock = PowerLocks.acquireWake(this, "atom-widget-recv-wake")
                InAppLogger.d("WidgetReceive", "ON until " + activeUntil)
                scheduleAutoStop()
                AtomWidgetCompactProvider.refreshNow(this)
                try { AtomQSTile.requestUpdate(this) } catch (_: Exception) {}
                tickerHandler.removeCallbacks(tickerRunnable)
                tickerHandler.post(tickerRunnable)
            }
            ACTION_STOP -> {
                InAppLogger.d("WidgetReceive", "manual stop from notification")
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun scheduleAutoStop() {
        // B74 code-review#7: gen-counter — старый поток не должен убивать новую сессию
        val myGen = autoStopGen
        val startedAt = System.currentTimeMillis()
        Thread {
            try {
                while (true) {
                    // B108: hard limit 2h — предотвращает вечный WakeLock при зависшем isBusy
                    if (System.currentTimeMillis() - startedAt > 2 * 60 * 60 * 1000L) {
                        InAppLogger.d("WidgetReceive", "B108: hard limit 2h → stopSelf")
                        break
                    }
                    if (myGen != autoStopGen) return@Thread
                    val remain = activeUntil - System.currentTimeMillis()
                    if (remain > 0) Thread.sleep(remain)
                    if (myGen != autoStopGen) return@Thread
                    val receiving = try { FileReceiver.isBusy } catch (_: Exception) { false }
                    val sending = try { AtomController.transferActive } catch (_: Exception) { false }
                    if (receiving || sending) {
                        InAppLogger.d("WidgetReceive",
                            "autoStop: transfer active (r=$receiving s=$sending), extend 60s")
                        activeUntil = System.currentTimeMillis() + 60_000L
                        try { AtomWidgetCompactProvider.refreshNow(this) } catch (_: Exception) {}
                        try { AtomQSTile.requestUpdate(this) } catch (_: Exception) {}
                        continue
                    }
                    break
                }
            } catch (_: Exception) {}
            if (myGen != autoStopGen) {
                InAppLogger.d("WidgetReceive",
                    "B74: autoStop gen mismatch (my=$myGen now=$autoStopGen) → skip stopSelf")
                return@Thread
            }
            try { stopSelf() } catch (_: Exception) {}
        }.apply { isDaemon = true }.start()
    }

    override fun onDestroy() {
        tickerHandler.removeCallbacks(tickerRunnable)
        isActive = false
        activeUntil = 0L
        val stillBusy = try { FileReceiver.isBusy } catch (_: Exception) { false }
        if (!stillBusy) {
            try { AtomController.wifiDirect?.stopContinuousDiscover() } catch (_: Exception) {}
        } else {
            InAppLogger.d("WidgetReceive", "onDestroy: receiver busy — keep discover")
        }
        PowerLocks.releaseWifi(wifiLock)
        PowerLocks.release(wakeLock)
        InAppLogger.d("WidgetReceive", "OFF")
        try { AtomWidgetCompactProvider.refreshNow(this) } catch (_: Exception) {}
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
            .setContentText("Можно отправлять файлы — я жду")
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
