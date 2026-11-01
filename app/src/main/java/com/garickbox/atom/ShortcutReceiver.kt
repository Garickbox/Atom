package com.garickbox.atom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mesh.core.InAppLogger

class ShortcutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        InAppLogger.d("ShortcutReceiver", "onReceive $action")
        when (action) {
            "com.garickbox.atom.SHORTCUT_RECEIVE" -> {
                try { WidgetReceiveService.toggle(context) } catch (e: Exception) {
                    InAppLogger.d("ShortcutReceiver", "toggle error: ${e.message}")
                }
            }
        }
    }
}
