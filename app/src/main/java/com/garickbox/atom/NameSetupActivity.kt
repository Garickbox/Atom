package com.garickbox.atom

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.garickbox.atom.databinding.ActivityNameSetupBinding
import com.mesh.core.InAppLogger

class NameSetupActivity : AppCompatActivity() {

    private lateinit var b: ActivityNameSetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.garickbox.atom.TvHelper.isTv(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        b = ActivityNameSetupBinding.inflate(layoutInflater)
        setContentView(b.root)

        val existing = AtomController.myName
        b.nameInput.setText(if (existing.isNotBlank()) existing else defaultDeviceName())

        b.continueBtn.setOnClickListener {
            val name = b.nameInput.text?.toString()?.trim() ?: ""
            if (name.isBlank()) {
                Toast.makeText(this, "Введите имя", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (name.length > 20) {
                Toast.makeText(this, "Максимум 20 символов", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AtomController.saveMyName(name)
            InAppLogger.d("NameSetup", "name set: $name")
            finish()
        }
    }

    private fun defaultDeviceName(): String {
        try {
            val globalName = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            if (!globalName.isNullOrBlank()) return globalName
        } catch (_: Exception) {}
        try {
            val secureName = Settings.Secure.getString(contentResolver, "device_name")
            if (!secureName.isNullOrBlank()) return secureName
        } catch (_: Exception) {}
        return android.os.Build.MODEL ?: "Атом"
    }
}
