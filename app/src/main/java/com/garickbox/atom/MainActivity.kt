package com.garickbox.atom

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.garickbox.atom.databinding.ActivityMainBinding
import com.mesh.core.InAppLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setSupportActionBar(b.toolbar)

        b.toolbar.inflateMenu(R.menu.main)
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_self_test -> { startActivity(Intent(this, SelfTestActivity::class.java)); true }
                R.id.action_check_update -> { checkUpdate(); true }
                R.id.action_about -> { showAbout(); true }
                else -> false
            }
        }
        b.fab.setOnClickListener {
            Toast.makeText(this, "Загрузка файлов появится на Этапе 3", Toast.LENGTH_SHORT).show()
        }

        // OTA через 5 сек
        lifecycleScope.launch {
            delay(5000)
            val release = UpdateChecker.checkForUpdate(this@MainActivity)
            if (release != null) {
                InAppLogger.d("MainActivity", "update available: ${release.tag}")
                UpdateChecker.promptAndInstall(this@MainActivity, release)
            }
        }

        InAppLogger.d("MainActivity", "onCreate done")
    }

    private fun checkUpdate() {
        InAppLogger.d("MainActivity", "manual update check")
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Проверяю обновление…", Toast.LENGTH_SHORT).show()
            val release = UpdateChecker.checkForUpdate(this@MainActivity)
            if (release == null) {
                InAppLogger.d("MainActivity", "no update")
                Toast.makeText(this@MainActivity, "Обновлений нет", Toast.LENGTH_SHORT).show()
            } else {
                InAppLogger.d("MainActivity", "update found: ${release.tag}")
                UpdateChecker.promptAndInstall(this@MainActivity, release)
            }
        }
    }

    private fun showAbout() {
        Toast.makeText(this, "Атом 0.1-mvp\nБыстрая передача файлов", Toast.LENGTH_LONG).show()
    }
}
