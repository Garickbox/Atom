package com.garickbox.atom

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import android.view.MenuItem
import android.view.Menu
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

        b.fab.setOnClickListener { showSendPicker() }

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


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_check_update -> { checkUpdate(); true }
            R.id.action_self_test -> {
                startActivity(Intent(this, SelfTestActivity::class.java)); true
            }
            R.id.action_view_log -> {
                startActivity(Intent(this, LogViewerActivity::class.java)); true
            }
            R.id.action_about -> { showAbout(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private val mediaPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) startShareForUris(uris, null)
    }

    private fun showSendPicker() {
        val options = arrayOf("📷  Фото", "🎬  Видео", "📄  Файл")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Что отправить?")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickMedia(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    1 -> pickMedia(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    2 -> openFilePicker()
                }
            }
            .show()
    }

    private fun pickMedia(type: ActivityResultContracts.PickVisualMedia.VisualMediaType) {
        try {
            mediaPicker.launch(PickVisualMediaRequest(type))
        } catch (e: Exception) {
            InAppLogger.d("MainActivity", "PickVisualMedia failed: ${e.message}")
            openFilePicker()
        }
    }

    private fun startShareForUris(uris: List<android.net.Uri>, mime: String?) {
        if (uris.isEmpty()) return
        val share = Intent(this, ShareTargetActivity::class.java).apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            type = mime ?: "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(share)
    }


    // ═══════════════════════════════════════════════════════════
    //  Permissions: запрос при первом запуске
    // ═══════════════════════════════════════════════════════════
    private var permissionAsked = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys.toList()
        if (denied.isEmpty()) {
            InAppLogger.d("MainActivity", "all permissions granted")
        } else {
            InAppLogger.d("MainActivity", "permissions denied: $denied")
            android.widget.Toast.makeText(
                this,
                "Без этих разрешений Атом не сможет находить устройства.\n" +
                "Выдайте их в настройках приложения.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        UpdateChecker.checkPending(this)
        // Тихая проверка геолокации — только в лог, без диалогов
        if (!LocationHelper.isEnabled(this)) {
            InAppLogger.d("MainActivity", "location disabled (silent check)")
        } else {
            InAppLogger.d("MainActivity", "location enabled (silent check)")
        }
        if (permissionAsked) return
        if (!AtomController.hasName()) return  // сначала имя
        val missing = PermissionsHelper.missing(this)
        if (missing.isEmpty()) {
            permissionAsked = true
            return
        }
        permissionAsked = true
        InAppLogger.d("MainActivity", "requesting permissions: $missing")
        permissionLauncher.launch(missing.toTypedArray())
    }

}
