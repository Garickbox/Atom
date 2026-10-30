package com.garickbox.atom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionsHelper {

    /** Разрешения которые Атом реально запрашивает. */
    fun required(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // B11.3: API<=28 — public Downloads без этого недоступен
        if (Build.VERSION.SDK_INT <= 28) {
            list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return list
    }

    fun missing(ctx: Context): List<String> {
        return required().filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun allGranted(ctx: Context): Boolean = missing(ctx).isEmpty()
}
