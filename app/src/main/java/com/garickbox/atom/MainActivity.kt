package com.garickbox.atom

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.Context
import android.app.UiModeManager
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

    // B11: обработка виджета
    private var pendingWidgetSend = false

    // B10d: диалог OTA + listener'ы для восстановления при сворачивании
    private var downloadDialog: androidx.appcompat.app.AlertDialog? = null
    private var downloadProgressBar: android.widget.ProgressBar? = null

    private val downloadProgressListener: (Long, Long) -> Unit = { d, t ->
        try { runOnUiThread { updateDownloadDialog(d, t) } } catch (_: Exception) {}
    }
    private val downloadFinishListener: (Boolean) -> Unit = { success ->
        try {
            runOnUiThread {
                try { downloadDialog?.dismiss() } catch (_: Exception) {}
                downloadDialog = null
                downloadProgressBar = null
                if (!success) {
                    Toast.makeText(this@MainActivity,
                        "Ошибка загрузки обновления",
                        Toast.LENGTH_LONG).show()
                }
            }
        } catch (_: Exception) {}
    }

    private fun attachDownloadListeners() {
        UpdateChecker.addProgressListener(downloadProgressListener)
        UpdateChecker.addFinishListener(downloadFinishListener)
    }
    private fun detachDownloadListeners() {
        UpdateChecker.removeProgressListener(downloadProgressListener)
        UpdateChecker.removeFinishListener(downloadFinishListener)
    }

    private fun showDownloadDialog(release: UpdateChecker.Release?) {
        try { downloadDialog?.dismiss() } catch (_: Exception) {}
        val bar = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            setPadding(64, 48, 64, 48)
        }
        downloadProgressBar = bar
        downloadDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⬇️ Скачиваю ${release?.tag ?: "обновление"}")
            .setView(bar)
            .setCancelable(false)
            .show()
        updateDownloadDialog(UpdateChecker.currentDownloaded, UpdateChecker.currentTotal)
    }

    private fun updateDownloadDialog(d: Long, t: Long) {
        if (t > 0) {
            val pct = ((d * 100) / t).toInt()
            downloadProgressBar?.progress = pct
            downloadDialog?.setTitle("⬇️ Скачиваю ${UpdateChecker.currentRelease?.tag ?: ""} · $pct%")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.garickbox.atom.TvHelper.isTv(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (intent?.action == AtomWidgetProvider.ACTION_WIDGET_SEND) {
            pendingWidgetSend = true
        }

        setSupportActionBar(b.toolbar)

        // Первый запуск — имя
        if (!AtomController.hasName()) {
            startActivity(Intent(this, NameSetupActivity::class.java))
        } else {
            // B11.6: онбординг автозапуска HyperOS (только если имя уже есть)
            maybePromptHyperOsAutostart()
        }

        setupTvIfNeeded()

        b.fab.setOnClickListener { showSendPicker() }

        // Авто-проверка обновления убрана — только manual (меню / кнопка на TV)

        InAppLogger.d("MainActivity", "onCreate done")
    }

    private fun checkUpdate() {
        InAppLogger.d("MainActivity", "manual update check")
        lifecycleScope.launch {
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("🔄 Проверка обновлений")
                .setMessage("Соединяюсь с GitHub…")
                .setNegativeButton("Отмена", null)
                .setCancelable(true)
                .show()

            val release = UpdateChecker.checkForUpdate(this@MainActivity)
            if (release == null) {
                dialog.dismiss()
                InAppLogger.d("MainActivity", "no update")
                Toast.makeText(this@MainActivity,
                    "✅ Актуальная версия build-${BuildConfig.VERSION_CODE}",
                    Toast.LENGTH_SHORT).show()
                return@launch
            }

            InAppLogger.d("MainActivity", "update found: ${release.tag}")

            dialog.dismiss()
            showDownloadDialog(release)
            attachDownloadListeners()
            UpdateChecker.downloadAndInstall(
                activity = this@MainActivity,
                release = release
            )
        }
    }

    private fun isHyperOsFamily(): Boolean {
        return try {
            val brand = (android.os.Build.BRAND ?: "").lowercase()
            val manufacturer = (android.os.Build.MANUFACTURER ?: "").lowercase()
            brand in listOf("xiaomi", "redmi", "poco") ||
                manufacturer in listOf("xiaomi", "redmi", "poco")
        } catch (_: Exception) { false }
    }

    private fun maybePromptHyperOsAutostart() {
        if (!isHyperOsFamily()) return
        val prefs = getSharedPreferences("atom", MODE_PRIVATE)
        if (prefs.getBoolean("hyperos_autostart_prompt_shown", false)) return
        prefs.edit().putBoolean("hyperos_autostart_prompt_shown", true).apply()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Включи Автозапуск")
            .setMessage(
                "На HyperOS / MIUI Атом не сможет запускаться в фоне из виджета и плитки в шторке, " +
                "если не разрешён Автозапуск.\n\n" +
                "Настройки → Приложения → Атом → Автозапуск → Вкл\n\n" +
                "Это одноразовая настройка."
            )
            .setPositiveButton("Открыть настройки") { _, _ ->
                try {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + packageName)
                    ))
                } catch (_: Exception) {}
            }
            .setNegativeButton("Понятно", null)
            .show()
    }

    private fun showAbout() {
        AboutDialog.show(this)
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
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            clipData = android.content.ClipData.newRawUri("", uris.first()).apply {
                for (i in 1 until uris.size) addItem(android.content.ClipData.Item(uris[i]))
            }
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


    override fun onPause() {
        // B10d: отцепить listener'ы, диалог закрыть (загрузка продолжится в UpdateChecker)
        detachDownloadListeners()
        try { downloadDialog?.dismiss() } catch (_: Exception) {}
        downloadDialog = null
        downloadProgressBar = null
        super.onPause()
    }

    override fun onDestroy() {
        detachDownloadListeners()
        try { downloadDialog?.dismiss() } catch (_: Exception) {}
        downloadDialog = null
        downloadProgressBar = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent?.action == AtomWidgetProvider.ACTION_WIDGET_SEND) {
            processWidgetSend()
        }
    }

    private fun processWidgetSend() {
        lifecycleScope.launch {
            val release = UpdateChecker.checkForUpdate(this@MainActivity)
            if (release != null) {
                InAppLogger.d("MainActivity", "widget_send: update available")
                UpdateChecker.promptAndInstall(this@MainActivity, release)
            } else {
                InAppLogger.d("MainActivity", "widget_send: no update")
                openFilePicker()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // На TV меню не нужно — все действия в tvPanel (без дубля «Обновить»)
        if (isTv()) return false
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
            R.id.action_clear_history -> {
                TransferHistory.clear(this)
                renderHistory()
                android.widget.Toast.makeText(this, "История очищена", android.widget.Toast.LENGTH_SHORT).show()
                true
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
        val options = arrayOf("📷  Фото", "🎬  Видео", "📄  Файл", "📁  Папка")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Что отправить?")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickMedia(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    1 -> pickMedia(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    2 -> openFilePicker()
                    3 -> openFolderPicker()
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
        if (pendingWidgetSend) {
            pendingWidgetSend = false
            processWidgetSend()
        }
        // B10d: восстановить OTA-диалог если загрузка идёт
        if (UpdateChecker.isDownloading && downloadDialog == null) {
            showDownloadDialog(UpdateChecker.currentRelease)
            attachDownloadListeners()
        }
        // Тихая проверка всех условий (в лог, без диалогов)
        val issues = PreflightChecker.issues(this)
        InAppLogger.d("MainActivity",
            "preflight (silent): ${PreflightChecker.summary(issues)}")

        // Battery optimization prompt (один раз)
        maybePromptBattery()

        // История скрыта — минимализм

        // Подписка на текущую передачу
        observeTransferState()
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


    private fun renderHistory() {
        val records = TransferHistory.list(this)
        if (records.isEmpty()) {
            b.emptyText.visibility = android.view.View.VISIBLE
            b.historyScroll.visibility = android.view.View.GONE
            return
        }
        b.emptyText.visibility = android.view.View.GONE
        b.historyScroll.visibility = android.view.View.VISIBLE
        b.historyContainer.removeAllViews()
        for (r in records) {
            val row = layoutInflater.inflate(android.R.layout.simple_list_item_2, b.historyContainer, false)
            val t1 = row.findViewById<android.widget.TextView>(android.R.id.text1)
            val t2 = row.findViewById<android.widget.TextView>(android.R.id.text2)
            val icon = when (r.status) {
                "success" -> "✅"
                "failed" -> "❌"
                "cancelled" -> "⚠️"
                else -> "•"
            }
            val arrow = if (r.savedPath.isNotBlank()) "📥" else "📤"
            t1.text = "$icon $arrow ${r.fileName} · ${r.peerName}"
            t2.text = "${formatBytes(r.bytes)} · ${formatTime(r.timestamp)}" +
                (if (r.durationMs > 0) " · ${r.durationMs / 1000} сек" else "") +
                (if (r.savedPath.isNotBlank()) " · ${r.savedPath}" else "")
            b.historyContainer.addView(row)
        }
    }

    private fun formatBytes(b: Long): String {
        if (b < 1024) return "$b Б"
        if (b < 1024 * 1024) return "%.1f КБ".format(b / 1024.0)
        if (b < 1024 * 1024 * 1024) return "%.1f МБ".format(b / 1024.0 / 1024.0)
        return "%.1f ГБ".format(b / 1024.0 / 1024.0 / 1024.0)
    }

    private fun formatTime(ts: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - ts
        return when {
            diff < 60_000 -> "сейчас"
            diff < 3_600_000 -> "${diff / 60_000} мин назад"
            diff < 86_400_000 -> "${diff / 3_600_000} ч назад"
            else -> {
                val df = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault())
                df.format(java.util.Date(ts))
            }
        }
    }

    private fun maybePromptBattery() {
        val prefs = getSharedPreferences("atom", MODE_PRIVATE)
        if (prefs.getBoolean("battery_prompt_shown", false)) return
        if (BatteryOptimizationHelper.isWhitelisted(this)) {
            prefs.edit().putBoolean("battery_prompt_shown", true).apply()
            return
        }
        prefs.edit().putBoolean("battery_prompt_shown", true).apply()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🔋 Фоновая работа")
            .setMessage(
                "Чтобы большие файлы (5+ ГБ) не обрывались, когда экран " +
                "выключен — разреши Атому работать в фоне.\n\n" +
                "Android иначе «усыпляет» приложение через пару минут."
            )
            .setPositiveButton("Разрешить") { _, _ ->
                BatteryOptimizationHelper.requestWhitelist(this)
            }
            .setNegativeButton("Позже", null)
            .show()
    }


    private var transferObserverJob: kotlinx.coroutines.Job? = null

    private fun observeTransferState() {
        transferObserverJob?.cancel()
        transferObserverJob = lifecycleScope.launch {
            AtomController.transferState.collect { state ->
                runOnUiThread { renderTransferPanel(state) }
            }
        }
    }

    private fun renderTransferPanel(state: TransferState) {
        when (state) {
            is TransferState.Running -> {
                b.transferPanel.visibility = android.view.View.VISIBLE
                val arrow = if (state.isReceiver) "📥 Приём от" else "📤 Отправка →"
                b.transferTitle.text = "$arrow ${state.peerName}\n${state.fileName}"
                val pct = if (state.bytesTotal > 0)
                    ((state.bytesSent * 100) / state.bytesTotal).toInt() else 0
                b.transferProgress.progress = pct
                val speedText = if (state.bytesPerSecond > 0)
                    "%.1f МБ/с".format(state.bytesPerSecond / 1024.0 / 1024.0)
                    else "…"
                b.transferSpeed.text = speedText
                b.transferDetail.text = buildString {
                    append("${formatBytes(state.bytesSent)} / ${formatBytes(state.bytesTotal)}")
                    append("  ·  $pct%")
                }
            }
            is TransferState.Success -> {
                b.transferPanel.visibility = android.view.View.VISIBLE
                b.transferTitle.text = "✅ Готово"
                b.transferSpeed.text = ""
                b.transferDetail.text = "${state.peerName} · ${state.fileCount} файл(ов) · ${formatBytes(state.bytesTotal)}"
                b.transferProgress.progress = 100
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(4000)
                    if (AtomController.transferState.value is TransferState.Success) {
                        b.transferPanel.visibility = android.view.View.GONE
                        AtomController.transferState.value = TransferState.Idle
                    }
                }
                renderHistory()
            }
            is TransferState.UpdatePending -> {
                b.transferPanel.visibility = android.view.View.VISIBLE
                b.transferTitle.text = "⬆️ Обновление Атома build-${state.versionCode}"
                b.transferSpeed.text = ""
                b.transferDetail.text = "От ${state.peerName} · тап для установки"
                b.transferProgress.progress = 100
                b.transferPanel.setOnClickListener {
                    try {
                        UpdateChecker.installApkFromUri(
                            this@MainActivity,
                            android.net.Uri.parse(state.apkUri)
                        )
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity,
                            "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    b.transferPanel.setOnClickListener(null)
                    AtomController.transferState.value = TransferState.Idle
                }
                renderHistory()
            }
            is TransferState.Paused -> {
                b.transferPanel.visibility = android.view.View.VISIBLE
                b.transferTitle.text = "⏸ Пауза: ${state.fileName}" +
                    (if (state.fileCount > 0) " · файл ${state.fileIndex}/${state.fileCount}" else "")
                b.transferSpeed.text = ""
                b.transferDetail.text = "${state.peerName} · ${formatBytes(state.bytesSent)} / ${formatBytes(state.bytesTotal)} · тап для продолжения"
                b.transferProgress.progress = if (state.bytesTotal > 0) ((state.bytesSent * 100) / state.bytesTotal).toInt() else 0
                b.transferPanel.setOnClickListener {
                    val i = android.content.Intent(this@MainActivity, FileSenderService::class.java).apply {
                        action = TransferNotification.ACTION_RESUME
                    }
                    androidx.core.content.ContextCompat.startForegroundService(this@MainActivity, i)
                    b.transferPanel.setOnClickListener(null)
                }
            }
            is TransferState.Failed -> {
                b.transferPanel.visibility = android.view.View.VISIBLE
                b.transferTitle.text = "⚠️ ${state.peerName}: ${state.reason}"
                b.transferSpeed.text = ""
                b.transferDetail.text = state.fileName
                b.transferProgress.progress = 0
                renderHistory()
            }
            TransferState.Idle -> {
                b.transferPanel.visibility = android.view.View.GONE
                if (isTv()) {
                    b.tvPanel.visibility = android.view.View.VISIBLE
                } else {
                    b.emptyText.visibility = android.view.View.VISIBLE
                }
            }
        }
    }


    private fun setupTvIfNeeded() {
        if (!isTv()) return
        InAppLogger.d("MainActivity", "TV mode detected — show receiver panel")
        // Скрыть FAB — отправка с TV не работает (нет файлового picker)
        b.fab.visibility = android.view.View.GONE
        // Скрыть emptyText — заменяем на tvPanel
        b.emptyText.visibility = android.view.View.GONE
        // Показать TV-панель
        b.tvPanel.visibility = android.view.View.VISIBLE
        b.tvTitle.text = "Готов к приёму"
        val name = AtomController.myName.ifBlank { "это устройство" }
        b.tvHint.text = "Имя: $name\n\n" +
            "Отправьте файлы с телефона — Атом найдёт это устройство " +
            "в той же Wi-Fi сети или через Wi-Fi Direct."

        b.tvBtnUpdate.setOnClickListener { checkUpdate() }
        b.tvBtnLog.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }
        b.tvBtnSelfTest.setOnClickListener {
            startActivity(Intent(this, SelfTestActivity::class.java))
        }
        b.tvBtnSettings.setOnClickListener {
            startActivity(Intent(this, NameSetupActivity::class.java))
        }
    }

    private fun isTv(): Boolean {
        return try {
            val uiMode = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            val isTvMode = uiMode?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
            val hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            isTvMode || hasLeanback
        } catch (e: Exception) {
            InAppLogger.d("MainActivity", "isTv error: ${e.message}")
            false
        }
    }

}
