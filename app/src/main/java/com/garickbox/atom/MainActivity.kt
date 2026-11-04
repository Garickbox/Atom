package com.garickbox.atom

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

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
import kotlinx.coroutines.flow.combine

class MainActivity : AppCompatActivity() {
    companion object {
        const val ACTION_SHORTCUT_FIND = "com.garickbox.atom.SHORTCUT_FIND"
        const val ACTION_SHORTCUT_RECEIVE = "com.garickbox.atom.SHORTCUT_RECEIVE"
        const val ACTION_SHORTCUT_SEND_PEER = "com.garickbox.atom.SHORTCUT_SEND_PEER"
        @Volatile var pendingPeerForShareTarget: Peer? = null
    }
    private var pendingShortcutFind = false
    private var pickerShownForPeer = false
    private val qrFilePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) return@registerForActivityResult
        val data = result.data!!
        val uris = mutableListOf<android.net.Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        }
        data.data?.let { uris.add(it) }
        if (uris.isNotEmpty()) {
            val i = Intent(this, QrScanActivity::class.java).apply {
                action = Intent.ACTION_SEND_MULTIPLE
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(i)
        }
    }


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
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (!com.garickbox.atom.TvHelper.isTv(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // B113: при recreate (rotate) — не повторяем intent.action; восстанавливаем pickerShownForPeer
        if (savedInstanceState != null) {
            pendingWidgetSend = false
            pendingShortcutFind = false
            // B114: pendingPeer НЕ сбрасываем — preset должен выжить rotate
            pickerShownForPeer = savedInstanceState.getBoolean("picker_shown", false)
        } else {
            when (intent?.action) {
                AtomWidgetProvider.ACTION_WIDGET_SEND -> pendingWidgetSend = true
                "com.garickbox.atom.WIDGET_INSTALL" -> {
                    // B118: tap «Установить» на виджете UpdatePending
                    val apkUri = intent?.getStringExtra("apk_uri") ?: ""
                    if (apkUri.isNotBlank()) {
                        try {
                            UpdateChecker.installApkFromUriPublic(this, android.net.Uri.parse(apkUri))
                        } catch (e: Exception) {
                            InAppLogger.err("MainActivity", "B118 widget install: ${e.message}")
                        }
                    }
                }
                ACTION_SHORTCUT_FIND -> pendingShortcutFind = true
                ACTION_SHORTCUT_SEND_PEER -> {
                    pendingPeerForShareTarget = Peer(
                        id = intent.getStringExtra("atom_peer_mac") ?: "",
                        name = intent.getStringExtra("atom_peer_name") ?: "?",
                        ip = "",
                        mac = intent.getStringExtra("atom_peer_mac") ?: ""
                    )
                    pickerShownForPeer = false
                }
            }
        }

        setSupportActionBar(b.toolbar)

        // Первый запуск — имя
        if (!AtomController.hasName()) {
            startActivity(Intent(this, NameSetupActivity::class.java))
        } else {
            runOnboarding()
        }

        setupTvIfNeeded()

        b.fab.setOnClickListener { showSendPicker() }
        b.btnHeroShowQr.setOnClickListener {
            try { startActivity(Intent(this, QrShareActivity::class.java)) } catch (e: Exception) {
                InAppLogger.err("MainActivity", "B136: QR: ${e.message}")
            }
        }
        observeLivePeers()

        // Авто-проверка обновления убрана — только manual (меню / кнопка на TV)

        InAppLogger.d("MainActivity", "onCreate done")
    }

    private var batteryPromptChecked = false
    private var onboardingDialogOpen = false  // B110

    /** B77: одноразовый prompt если whitelist нет и ещё не спрашивали. */
    private fun maybePromptBatteryOnce() {
        if (batteryPromptChecked) return
        batteryPromptChecked = true
        try {
            if (BatteryOptimizationHelper.isWhitelisted(this)) return
            val prefs = getSharedPreferences("atom", MODE_PRIVATE)
            if (prefs.getBoolean("battery_prompt_shown", false)) return
            prefs.edit().putBoolean("battery_prompt_shown", true).apply()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("🔋 Фоновая работа")
                .setMessage(
                    "Чтобы большие передачи не рвались при выключенном экране, " +
                    "разреши Атому работать в фоне.\n\n" +
                    "На HyperOS: Батарея → «Без ограничений»"
                )
                .setPositiveButton("Разрешить") { _, _ ->
                    try { BatteryOptimizationHelper.requestWhitelist(this) } catch (_: Exception) {}
                }
                .setNegativeButton("Позже", null)
                .show()
            InAppLogger.d("MainActivity", "B77 battery prompt shown")
        } catch (e: Exception) {
            InAppLogger.d("MainActivity", "B77 battery prompt error: ${e.message}")
        }
    }

    private fun runOnboarding() {
        if (onboardingDialogOpen) return  // B110
        onboardingDialogOpen = true
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            onboardingDialogOpen = false
        }, 2000L)
        OnboardingHelper.next(this) { missing ->
            InAppLogger.d("MainActivity", "onboarding: request ${missing.size} perms")
            try { permissionLauncher.launch(missing.toTypedArray()) } catch (_: Exception) {}
        }
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

            // B119: используем Detailed — отличаем «нет обновления» от «ошибка сети»
            when (val result = UpdateChecker.checkForUpdateDetailed(this@MainActivity)) {
                is UpdateChecker.CheckResult.UpToDate -> {
                    dialog.dismiss()
                    InAppLogger.d("MainActivity", "no update (up-to-date)")
                    Toast.makeText(this@MainActivity,
                        "✅ Актуальная версия build-${BuildConfig.VERSION_CODE}",
                        Toast.LENGTH_SHORT).show()
                }
                is UpdateChecker.CheckResult.Error -> {
                    dialog.dismiss()
                    InAppLogger.d("MainActivity", "check error: ${result.message}")
                    Toast.makeText(this@MainActivity,
                        "⚠️ Не удалось проверить: ${result.message}",
                        Toast.LENGTH_LONG).show()
                }
                is UpdateChecker.CheckResult.Available -> {
                    val release = result.release
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
        if (result.resultCode != RESULT_OK) {
            MainActivity.pendingPeerForShareTarget = null
            pickerShownForPeer = false
            return@registerForActivityResult
        }
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

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) {
            MainActivity.pendingPeerForShareTarget = null
            pickerShownForPeer = false
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
        lifecycleScope.launch {
            val files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                collectTreeFiles(treeUri)
            }
            if (files.isEmpty()) {
                Toast.makeText(this@MainActivity, "Папка пуста", Toast.LENGTH_SHORT).show()
            } else if (files.size > 500) {
                Toast.makeText(this@MainActivity,
                    "Слишком много файлов (500+). Выбери папку поменьше",
                    Toast.LENGTH_LONG).show()
            } else {
                startShareWithPaths(files)
            }
        }
    }

    private fun openFolderPicker() {
        try {
            folderPicker.launch(null)
        } catch (e: Exception) {
            Toast.makeText(this, "Папка недоступна: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun collectTreeFiles(treeUri: android.net.Uri): List<Pair<android.net.Uri, String>> {
        val MAX = 500
        val cap = MAX + 1
        val result = mutableListOf<Pair<android.net.Uri, String>>()
        val rootDocId = try {
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) { return emptyList() }
        val rootName = try {
            var n = "folder"
            val rootUri = android.provider.DocumentsContract
                .buildDocumentUriUsingTree(treeUri, rootDocId)
            contentResolver.query(rootUri, arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ), null, null, null)?.use { c ->
                if (c.moveToFirst()) n = c.getString(0) ?: "folder"
            }
            n
        } catch (_: Exception) { "folder" }

        fun walk(parentUri: android.net.Uri, parentId: String, path: String) {
            if (result.size >= cap) return
            val childrenUri = android.provider.DocumentsContract
                .buildChildDocumentsUriUsingTree(treeUri, parentId)
            try {
                contentResolver.query(childrenUri, arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                ), null, null, null)?.use { c ->
                    while (c.moveToNext() && result.size < cap) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: ""
                        if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                            walk(childrenUri, id, "$path/$name")
                        } else {
                            val docUri = android.provider.DocumentsContract
                                .buildDocumentUriUsingTree(treeUri, id)
                            result.add(docUri to "$path/$name")
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        walk(treeUri, rootDocId, rootName)
        return result
    }

    private fun startShareWithPaths(files: List<Pair<android.net.Uri, String>>) {
        if (files.isEmpty()) return
        val uris = files.map { it.first }
        val relPaths = files.map { it.second }
        val share = Intent(this, ShareTargetActivity::class.java).apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            type = "*/*"
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            putStringArrayListExtra("atom_relative_paths", ArrayList(relPaths))
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
        peerObserverJob?.cancel()
        peerRefreshJob?.cancel()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // B113: guard от showSendPicker на paused/finishing Activity
        if (isFinishing || isDestroyed) {
            InAppLogger.d("MainActivity", "B113: onNewIntent ignored (finishing)")
            return
        }
        when (intent?.action) {
            AtomWidgetProvider.ACTION_WIDGET_SEND -> processWidgetSend()
            "com.garickbox.atom.WIDGET_INSTALL" -> {
                // B118: onNewIntent — Activity уже была открыта
                val apkUri = intent?.getStringExtra("apk_uri") ?: ""
                if (apkUri.isNotBlank()) {
                    try {
                        UpdateChecker.installApkFromUriPublic(this, android.net.Uri.parse(apkUri))
                    } catch (e: Exception) {
                        InAppLogger.err("MainActivity", "B118 widget install: ${e.message}")
                    }
                }
            }
            ACTION_SHORTCUT_FIND -> showSendPicker()
            ACTION_SHORTCUT_SEND_PEER -> {
                pendingPeerForShareTarget = Peer(
                    id = intent.getStringExtra("atom_peer_mac") ?: "",
                    name = intent.getStringExtra("atom_peer_name") ?: "?",
                    ip = "",
                    mac = intent.getStringExtra("atom_peer_mac") ?: ""
                )
                pickerShownForPeer = true
                showSendPicker()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // B113: сохраняем pickerShownForPeer для recreate
        outState.putBoolean("picker_shown", pickerShownForPeer)
    }

    private fun processWidgetSend() {
        lifecycleScope.launch {
            val release = UpdateChecker.checkForUpdate(this@MainActivity)
            if (release != null) {
                InAppLogger.d("MainActivity", "widget_send: update available")
                UpdateChecker.promptAndInstall(this@MainActivity, release)
            } else {
                InAppLogger.d("MainActivity", "widget_send: no update")
                showSendPicker()
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
            R.id.action_history -> {
                startActivity(Intent(this, HistoryActivity::class.java)); true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java)); true
            }
            R.id.action_about -> { showAbout(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private val mediaPicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) startShareForUris(uris, null)
        else { MainActivity.pendingPeerForShareTarget = null; pickerShownForPeer = false }
    }

    private fun showSendPicker() {
        val options = arrayOf(
            "📷  Фото", "🎬  Видео", "📄  Файл", "📁  Папка",
            "📱  Показать QR (принять)", "🔍  Сканировать QR (отправить)"
        )
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 56, 48, 40)
        }
        container.addView(android.widget.TextView(this).apply {
            text = "Что отправить?"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        })
        val acts = arrayOf<() -> Unit>(
            { pickMedia(ActivityResultContracts.PickVisualMedia.ImageOnly) },
            { pickMedia(ActivityResultContracts.PickVisualMedia.VideoOnly) },
            { openFilePicker() },
            { openFolderPicker() },
            { startActivity(Intent(this, QrShareActivity::class.java)) },
            { qrFilePicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }) }
        )
        options.forEachIndexed { i, opt ->
            container.addView(android.widget.TextView(this).apply {
                text = opt; textSize = 16f
                setPadding(16, 36, 16, 36)
                isClickable = true; isFocusable = true
                setOnClickListener { sheet.dismiss(); acts[i]() }
            })
        }
        sheet.setContentView(container)
        sheet.show()
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
                "Выдай их в настройках приложения.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
        runOnboarding()
    }

    override fun onResume() {
        super.onResume()
        // B77: one-shot battery prompt — независим от OnboardingHelper.isDone
        maybePromptBatteryOnce()
        // B115peer #1c: страховка — если discovery был остановлен (шаринг/QR), возобновляем
        try { AtomController.wifiDirect?.startContinuousDiscover() } catch (_: Exception) {}
        // B79: при возврате в Атом — сбросить badge counter
        try { BadgeHelper.clear(this) } catch (_: Exception) {}
        try { AtomQSTile.requestUpdate(this) } catch (_: Exception) {}
        UpdateChecker.checkPending(this)
        if (pendingWidgetSend) {
            pendingWidgetSend = false
            // B112: очищаем intent.action — иначе при повороте повторно запустится
            try { intent?.action = null } catch (_: Exception) {}
            processWidgetSend()
        }
        if (pendingShortcutFind) {
            pendingShortcutFind = false
            try { intent?.action = null } catch (_: Exception) {}
            showSendPicker()
        }
        if (pendingPeerForShareTarget != null && !pickerShownForPeer) {
            pickerShownForPeer = true
            // B114: НЕ очищаем pending — ShareTargetActivity его прочтёт после выбора файла.
            // Null ставит сам ShareTargetActivity после connect.
            try { intent?.action = null } catch (_: Exception) {}
            showSendPicker()
        }
        // B10d: восстановить OTA-диалог если загрузка идёт
        if (UpdateChecker.isDownloading && downloadDialog == null) {
            showDownloadDialog(UpdateChecker.currentRelease)
            attachDownloadListeners()
        } else if (!UpdateChecker.isDownloading) {
            // B78: download не активен, но APK мог быть скачан — предложить установить
            try {
                val ready = UpdateChecker.findDownloadedApk(this)
                if (ready != null) {
                    val key = ready.name + "_" + ready.dateAdded
                    val prefs = getSharedPreferences("atom", MODE_PRIVATE)
                    if (prefs.getString("b78_last_offered_apk", "") != key) {
                        prefs.edit().putString("b78_last_offered_apk", key).apply()
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("⬆️ Обновление готово")
                            .setMessage("Установить " + ready.name + "?")
                            .setPositiveButton("Установить") { _, _ ->
                                try {
                                    UpdateChecker.installApkFromUriPublic(this, ready.uri)
                                } catch (e: Exception) {
                                    Toast.makeText(this, "Ошибка: " + e.message,
                                        Toast.LENGTH_LONG).show()
                                }
                            }
                            .setNegativeButton("Позже", null)
                            .show()
                        InAppLogger.d("MainActivity", "B78: ready APK dialog shown")
                    }
                }
            } catch (_: Exception) {}
        }
        // Тихая проверка всех условий (в лог, без диалогов)
        val issues = PreflightChecker.issues(this)
        InAppLogger.d("MainActivity",
            "preflight (silent): ${PreflightChecker.summary(issues)}")

        runOnboarding()

        if (isTv()) updateTvReceiveButton()

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
        b.historyScroll.visibility = android.view.View.GONE
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
    private var peerObserverJob: kotlinx.coroutines.Job? = null
    private var peerRefreshJob: kotlinx.coroutines.Job? = null

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
                b.emptyText.visibility = android.view.View.GONE
                val arrow = if (state.isReceiver) "📥 Приём от" else "📤 Отправка →"
                b.transferTitle.text = "$arrow ${state.peerName}\n${state.fileName}"
                val pct = if (state.bytesTotal > 0)
                    ((state.bytesSent * 100) / state.bytesTotal).toInt() else 0
                b.transferProgress.progress = pct
                b.transferCircular.setProgressCompat(pct, true)
                b.btnTransferPause.visibility = android.view.View.VISIBLE
                b.btnTransferPause.setOnClickListener { sendActionToSender(TransferNotification.ACTION_PAUSE) }
                b.btnTransferResume.visibility = android.view.View.GONE
                b.btnTransferCancel.visibility = android.view.View.VISIBLE
                b.btnTransferCancel.setOnClickListener { sendActionToSender(TransferNotification.ACTION_CANCEL) }
                val speedText = if (state.bytesPerSecond > 0)
                    "%.1f МБ/с".format(state.bytesPerSecond / 1024.0 / 1024.0)
                    else "…"
                b.transferSpeed.text = speedText
                b.transferDetail.text = buildString {
                    append("${formatBytes(state.bytesSent)} / ${formatBytes(state.bytesTotal)}")
                    append("  ·  $pct%")
                    // B121: индикатор частоты P2P (только если известно)
                    val hz = AtomController.currentP2pFrequencyHz
                    when {
                        hz >= 5000 -> append("  ·  📶 5 GHz")
                        hz >= 2400 -> append("  ·  📶 2.4 GHz")
                        else -> {}
                    }
                }
            }
            is TransferState.Success -> {
                b.transferPanel.visibility = android.view.View.VISIBLE
                b.emptyText.visibility = android.view.View.GONE
                b.transferTitle.text = "✅ Готово"
                b.btnTransferPause.visibility = android.view.View.GONE
                b.btnTransferResume.visibility = android.view.View.GONE
                b.btnTransferCancel.visibility = android.view.View.GONE
                b.transferSpeed.text = ""
                // B115: плюрализация
                val fw = when {
                    state.fileCount % 100 in 11..19 -> "файлов"
                    state.fileCount % 10 == 1 -> "файл"
                    state.fileCount % 10 in 2..4 -> "файла"
                    else -> "файлов"
                }
                b.transferDetail.text = "${state.peerName} · ${state.fileCount} $fw · ${formatBytes(state.bytesTotal)}"
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
                b.emptyText.visibility = android.view.View.GONE
                b.transferTitle.text = "⬆️ Обновление Атома build-${state.versionCode}"
                b.btnTransferPause.visibility = android.view.View.GONE
                b.btnTransferResume.visibility = android.view.View.GONE
                b.btnTransferCancel.visibility = android.view.View.GONE
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
                b.emptyText.visibility = android.view.View.GONE
                b.transferTitle.text = "⏸ Пауза: ${state.fileName}" +
                    (if (state.fileCount > 0) " · файл ${state.fileIndex}/${state.fileCount}" else "")
                b.transferSpeed.text = ""
                b.transferDetail.text = "${state.peerName} · ${formatBytes(state.bytesSent)} / ${formatBytes(state.bytesTotal)} · тап для продолжения"
                b.transferProgress.progress = if (state.bytesTotal > 0) ((state.bytesSent * 100) / state.bytesTotal).toInt() else 0
                b.btnTransferPause.visibility = android.view.View.GONE
                b.btnTransferResume.visibility = android.view.View.VISIBLE
                b.btnTransferResume.setOnClickListener { sendActionToSender(TransferNotification.ACTION_RESUME) }
                b.btnTransferCancel.visibility = android.view.View.VISIBLE
                b.btnTransferCancel.setOnClickListener { sendActionToSender(TransferNotification.ACTION_CANCEL) }
            }
            is TransferState.Failed -> {
                b.transferPanel.visibility = android.view.View.VISIBLE
                b.emptyText.visibility = android.view.View.GONE
                b.transferTitle.text = "⚠️ ${state.peerName}: ${state.reason}"
                b.btnTransferPause.visibility = android.view.View.GONE
                b.btnTransferResume.visibility = android.view.View.GONE
                b.btnTransferCancel.visibility = android.view.View.GONE
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


    private fun observeLivePeers() {
        peerObserverJob?.cancel()
        peerObserverJob = lifecycleScope.launch {
            AtomController.peers.collect { peers -> runOnUiThread { renderLivePeers(peers) } }
        }
        peerRefreshJob?.cancel()
        peerRefreshJob = lifecycleScope.launch {
            while (true) {
                try { kotlinx.coroutines.delay(10_000L) } catch (_: kotlinx.coroutines.CancellationException) { break }
                renderLivePeers(AtomController.peers.value)
            }
        }
    }

    private fun renderP2pExtra(p2pDevices: List<android.net.wifi.p2p.WifiP2pDevice>, udpPeers: List<Peer>) {
        try {
            val udpMacs = udpPeers.map { it.mac }.filter { it.isNotBlank() }.toSet()
            val extra = p2pDevices.filter { it.deviceAddress !in udpMacs && it.deviceName.isNotBlank() }
            for (dev in extra) {
                val card = layoutInflater.inflate(R.layout.item_live_peer, b.livePeersContainer, false)
                card.findViewById<android.widget.TextView>(R.id.peerName).text = "📶 ${dev.deviceName}"
                card.setOnClickListener {
                    try {
                        val peer = Peer(
                            id = "p2p-" + dev.deviceAddress.takeLast(8).replace(":", ""),
                            name = dev.deviceName,
                            ip = "",
                            mac = dev.deviceAddress
                        )
                        showPeerActionSheet(peer)
                        InAppLogger.d("MainActivity",
                            "B142: P2P tap ${dev.deviceName} (${dev.deviceAddress}) → sheet")
                    } catch (e: Exception) {
                        InAppLogger.err("MainActivity", "B142: P2P tap: ${e.message}")
                    }
                }
                b.livePeersContainer.addView(card)
            }
            if (extra.isNotEmpty()) {
                b.liveStatus.visibility = android.view.View.GONE
            }
        } catch (e: Exception) {
            InAppLogger.err("MainActivity", "renderP2pExtra: ${e.message}")
        }
    }

    private fun renderLivePeers(peers: List<Peer>) {
        try {
            val now = System.currentTimeMillis()
            val live = peers.filter { now - it.lastSeenMs < 30_000L && it.name.isNotBlank() }
            b.livePeersContainer.removeAllViews()
            if (live.isEmpty()) {
                b.liveStatus.visibility = android.view.View.VISIBLE
                return
            }
            b.liveStatus.visibility = android.view.View.GONE
            for (peer in live) {
                val card = layoutInflater.inflate(R.layout.item_live_peer, b.livePeersContainer, false)
                card.findViewById<android.widget.TextView>(R.id.peerName).text = peer.name
                card.setOnClickListener {
                    try {
                        InAppLogger.d("MainActivity", "live peer tapped: ${peer.name} @ ${peer.ip}")
                        AtomController.saveKnownP2pPeer(peer.mac, peer.name)
                        showPeerActionSheet(peer)
                    } catch (e: Exception) {
                        InAppLogger.err("MainActivity", "live peer tap: ${e.message}")
                    }
                }
                b.livePeersContainer.addView(card)
            }
        } catch (e: Exception) {
            InAppLogger.err("MainActivity", "renderLivePeers: ${e.message}")
        }
    }

    private fun showPeerActionSheet(peer: Peer) {
        try {
            val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(48, 56, 48, 40)
            }
            container.addView(android.widget.TextView(this).apply {
                text = "Отправить на ${peer.name}"
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 32)
            })
            val opts = arrayOf("📷  Фото", "🎬  Видео", "📄  Файл", "📁  Папка", "📷  Сканировать QR")
            val actions = arrayOf<() -> Unit>(
                { MainActivity.pendingPeerForShareTarget = peer; pickerShownForPeer = true; pickMedia(ActivityResultContracts.PickVisualMedia.ImageOnly) },
                { MainActivity.pendingPeerForShareTarget = peer; pickerShownForPeer = true; pickMedia(ActivityResultContracts.PickVisualMedia.VideoOnly) },
                { MainActivity.pendingPeerForShareTarget = peer; pickerShownForPeer = true; openFilePicker() },
                { MainActivity.pendingPeerForShareTarget = peer; pickerShownForPeer = true; openFolderPicker() },
                { sheet.dismiss(); startQrScanWithActionSheet() }
            )
            opts.forEachIndexed { i, opt ->
                container.addView(android.widget.TextView(this).apply {
                    text = opt
                    textSize = 16f
                    setPadding(16, 36, 16, 36)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { sheet.dismiss(); actions[i]() }
                })
            }
            sheet.setContentView(container)
            sheet.show()
            InAppLogger.d("MainActivity", "B134: peer action sheet for ${peer.name}")
        } catch (e: Exception) {
            InAppLogger.err("MainActivity", "showPeerActionSheet: ${e.message}")
        }
    }

    private fun startQrScanWithActionSheet() {
        try {
            MainActivity.pendingPeerForShareTarget = null
            pickerShownForPeer = false
            val i = Intent(this, QrScanActivity::class.java).apply {
                putExtra(QrScanActivity.EXTRA_PENDING_ACTION_SHEET, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(i)
            InAppLogger.d("MainActivity", "B135: QR-then-pick flow")
        } catch (e: Exception) {
            InAppLogger.err("MainActivity", "startQrScanWithActionSheet: ${e.message}")
        }
    }

    private fun sendActionToSender(action: String) {
        try {
            val i = android.content.Intent(this, FileSenderService::class.java).apply {
                this.action = action
            }
            androidx.core.content.ContextCompat.startForegroundService(this, i)
            InAppLogger.d("MainActivity", "transfer action: $action")
        } catch (e: Exception) {
            InAppLogger.err("MainActivity", "transfer action $action: ${e.message}")
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
            "Принимаю файлы по Wi-Fi Direct или через QR. " +
            "Нажми «Показать QR» на телефоне, чтобы присоединиться."

        b.tvBtnUpdate.setOnClickListener { checkUpdate() }
        b.tvBtnLog.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }
        b.tvBtnSelfTest.setOnClickListener {
            startActivity(Intent(this, SelfTestActivity::class.java))
        }
        b.tvBtnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        b.tvBtnQr.setOnClickListener {
            startActivity(Intent(this, QrShareActivity::class.java))
        }
        b.tvBtnReceive.setOnClickListener { toggleTvReceive() }
        updateTvReceiveButton()
    }

    private fun toggleTvReceive() {
        try {
            WidgetReceiveService.toggle(this)
            b.tvBtnReceive.postDelayed({ updateTvReceiveButton() }, 500L)
            InAppLogger.d("MainActivity", "TV toggle receive")
        } catch (e: Exception) {
            InAppLogger.err("MainActivity", "TV toggle receive: ${e.message}")
        }
    }

    private fun updateTvReceiveButton() {
        try {
            val active = WidgetReceiveService.isActive
            b.tvBtnReceive.text = if (active) "📥  Приём: ВКЛ 10 мин" else "📥  Приём 10 мин"
            b.tvBtnReceive.alpha = if (active) 1.0f else 0.85f
        } catch (_: Exception) {}
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
