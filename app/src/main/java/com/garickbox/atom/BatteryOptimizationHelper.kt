package com.garickbox.atom

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.mesh.core.InAppLogger

object BatteryOptimizationHelper {
    fun isWhitelisted(ctx: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < 23) return true
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (_: Exception) { false }
    }

    @SuppressLint("BatteryLife")
    fun requestWhitelist(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${activity.packageName}")))
        } catch (e: Exception) {
            InAppLogger.d("Battery", "error: ${e.message}")
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {}
        }
    }
}
