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

        // Первый запуск — имя
        if (!AtomController.hasName()) {
            startActivity(Intent(this, NameSetupActivity::class.java))
        }

        b.toolbar.inflateMenu(R.menu.main)
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_self_test -> { startActivity(Intent(this, SelfTestActivity::class.java)); true }
                R.id.action_check_update -> { checkUpdate(); true }
                R.id.action_about -> { showAbout(); true }
                else -> false
            }
        }
        b.fab.setOnClickListener { openFilePicker() }

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

    private val filePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val uris = mutableListOf<android.net.Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        }
        data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return@registerForActivityResult
        val share = Intent(this, ShareTargetActivity::class.java).apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            type = data.type ?: "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(share)
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        filePicker.launch(intent)
    }

}
