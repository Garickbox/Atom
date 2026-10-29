package com.garickbox.atom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

object NotificationHelper {

    const val CHANNEL_TRANSFER = "transfer"
    const val CHANNEL_GENERAL = "general"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TRANSFER, "Передачи", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Прогресс отправки и приёма файлов"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_GENERAL, "Общие", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
}
