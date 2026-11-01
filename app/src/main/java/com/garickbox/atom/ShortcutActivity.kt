package com.garickbox.atom

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.mesh.core.InAppLogger

class ShortcutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        InAppLogger.d("ShortcutActivity", "onCreate action=$action")
        when (action) {
            "com.garickbox.atom.SHORTCUT_RECEIVE" -> {
                try { WidgetReceiveService.toggle(this) } catch (e: Exception) {
                    InAppLogger.d("ShortcutActivity", "toggle error: ${e.message}")
                }
            }
        }
        finish()
    }
}
