package com.garickbox.atom

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.location.LocationManagerCompat
import com.mesh.core.InAppLogger

object LocationHelper {

    /** Системный тумблер геолокации включён? */
    fun isEnabled(ctx: Context): Boolean {
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE)
                as? android.location.LocationManager ?: return false
            LocationManagerCompat.isLocationEnabled(lm)
        } catch (e: Exception) {
            InAppLogger.d("Location", "isEnabled error: ${e.message}")
            false
        }
    }

    /** Открыть системные настройки геолокации. */
    fun openSettings(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (e: Exception) {
            InAppLogger.d("Location", "openSettings error: ${e.message}")
        }
    }
}
