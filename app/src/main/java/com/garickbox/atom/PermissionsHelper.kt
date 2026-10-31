package com.garickbox.atom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionsHelper {

    fun required(): List<String> {
        val list = mutableListOf<String>()
        val sdk = Build.VERSION.SDK_INT

        // Уведомления (33+)
        if (sdk >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)

        // P2P discovery — базовая локация нужна на всех версиях
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)

        // 33+: nearby devices заменяет location для P2P
        if (sdk >= 33) list.add(Manifest.permission.NEARBY_WIFI_DEVICES)

        // Медиа для сохранения файлов на приёмнике
        when {
            sdk >= 33 -> {
                list.add(Manifest.permission.READ_MEDIA_IMAGES)
                list.add(Manifest.permission.READ_MEDIA_VIDEO)
                list.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            else -> {
                list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        // API<=28: public Downloads без WRITE недоступен
        if (sdk <= 28) {
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        return list
    }

    fun missing(ctx: Context): List<String> {
        return required().filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun allGranted(ctx: Context): Boolean = missing(ctx).isEmpty()

    /** Человеческое имя для диалога */
    fun humanName(perm: String): String = when (perm.substringAfterLast('.')) {
        "POST_NOTIFICATIONS" -> "Уведомления"
        "ACCESS_FINE_LOCATION" -> "Местоположение"
        "NEARBY_WIFI_DEVICES" -> "Устройства поблизости"
        "READ_MEDIA_IMAGES" -> "Доступ к фото"
        "READ_MEDIA_VIDEO" -> "Доступ к видео"
        "READ_MEDIA_AUDIO" -> "Доступ к аудио"
        "READ_EXTERNAL_STORAGE" -> "Чтение файлов"
        "WRITE_EXTERNAL_STORAGE" -> "Запись файлов"
        else -> perm.substringAfterLast('.')
    }
}
