package com.garickbox.atom

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.app.UiModeManager

object TvHelper {
    fun isTv(ctx: Context): Boolean {
        return try {
            val uiMode = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            val isTvMode = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
            val hasLeanback = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            isTvMode || hasLeanback
        } catch (_: Exception) {
            false
        }
    }
}
