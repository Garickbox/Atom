package com.garickbox.atom

import android.content.Intent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import com.mesh.core.InAppLogger
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    companion object {
        const val ACTION_SHORTCUT_FIND = "com.garickbox.atom.SHORTCUT_FIND"
        const val ACTION_SHORTCUT_RECEIVE = "com.garickbox.atom.SHORTCUT_RECEIVE"
        const val ACTION_SHORTCUT_SEND_PEER = "com.garickbox.atom.SHORTCUT_SEND_PEER"
        const val ACTION_WIDGET_SEND = "com.garickbox.atom.WIDGET_SEND"
        @Volatile var pendingPeerForShareTarget: Peer? = null

        /** B202: returnToMain — после startTransfer перекинуть user на главный */
        fun returnToMain(ctx: android.content.Context) {
            try {
                ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            } catch (_: Exception) {}
        }
    }

    private lateinit var composeRoot: ComposeView

    private var pendingWidgetSend = false
    private var pendingShortcutFind = false
    private var permissionAsked = false
    private var batteryPromptChecked = false
    private var onboardingDialogOpen = false

    // OTA диалог
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
                    Toast.makeText(this@MainActivity, "Ошибка загрузки обновления", Toast.LENGTH_LONG).show()
                }
            }
        } catch (_: Exception) {}
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys.toList()
        if (denied.isEmpty()) {
            InAppLogger.d("MainActivity", "all permissions granted")
        } else {
            InAppLogger.d("MainActivity", "permissions denied: $denied")
            Toast.makeText(this,
                "Без этих разрешений Атом не сможет находить устройства.\n" +
                "Выдай их в настройках приложения.", Toast.LENGTH_LONG).show()
        }
        runOnboarding()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (!TvHelper.isTv(this)) {
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        setContentView(R.layout.activity_main)
        composeRoot = findViewById(R.id.composeRoot)

        if (savedInstanceState != null) {
            pendingWidgetSend = false
            pendingShortcutFind = false
        } else {
            when (intent?.action) {
                ACTION_WIDGET_SEND -> pendingWidgetSend = true
                "com.garickbox.atom.WIDGET_INSTALL" -> {
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
                }
            }
        }

        if (!AtomController.hasName()) {
            startActivity(Intent(this, NameSetupActivity::class.java))
        } else {
            runOnboarding()
        }

        composeRoot.setContent {
            MaterialTheme {
                MainScreen(isTv = isTv(), actions = buildMainActions())
            }
        }
        InAppLogger.d("MainActivity", "B204 Compose onCreate done")
    }

    private fun buildMainActions(): MainActions = MainActions(
        onFabClick = {
            startActivity(Intent(this, FileBrowserActivity::class.java))
        },
        onAcceptClick = {
            try { startActivity(Intent(this, QrShareActivity::class.java)) } catch (_: Exception) {}
        },
        onPeerSend = { peer ->
            MainActivity.pendingPeerForShareTarget = peer
            try { startActivity(Intent(this, FileBrowserActivity::class.java)) } catch (_: Exception) {}
        },
        onPeerShowQr = { _ ->
            try { startActivity(Intent(this, QrShareActivity::class.java)) } catch (_: Exception) {}
        },
        onPeerHistory = { _ ->
            try { startActivity(Intent(this, HistoryActivity::class.java)) } catch (_: Exception) {}
        },
        onPeerForget = { peer ->
            try { AtomController.removeKnownP2pPeer(peer.mac) } catch (_: Exception) {}
            Toast.makeText(this, "Забыто: ${peer.name}", Toast.LENGTH_SHORT).show()
        },
        onTransferAction = { action -> sendActionToSender(action) },
        onMenuAction = { name ->
            when (name) {
                "update" -> checkUpdate()
                "history" -> startActivity(Intent(this, HistoryActivity::class.java))
                "settings" -> startActivity(Intent(this, SettingsActivity::class.java))
                "about" -> AboutDialog.show(this)
            }
        },
        onAcceptTvClick = { toggleTvReceive() }
    )

    private fun runOnboarding() {
        if (onboardingDialogOpen) return
        onboardingDialogOpen = true
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            onboardingDialogOpen = false
        }, 2000L)
        OnboardingHelper.next(this) { missing ->
            InAppLogger.d("MainActivity", "onboarding: request ${missing.size} perms")
            try { permissionLauncher.launch(missing.toTypedArray()) } catch (_: Exception) {}
        }
    }

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
                .setMessage("Чтобы большие передачи не рвались при выключенном экране, " +
                    "разреши Атому работать в фоне.\n\nНа HyperOS: Батарея → «Без ограничений»")
                .setPositiveButton("Разрешить") { _, _ ->
                    try { BatteryOptimizationHelper.requestWhitelist(this) } catch (_: Exception) {}
                }
                .setNegativeButton("Позже", null)
                .show()
        } catch (_: Exception) {}
    }

    private fun checkUpdate() {
        InAppLogger.d("MainActivity", "manual update check")
        lifecycleScope.launch {
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("🔄 Проверка обновлений")
                .setMessage("Соединяюсь с GitHub…")
                .setNegativeButton("Отмена", null)
                .setCancelable(true).show()
            when (val result = UpdateChecker.checkForUpdateDetailed(this@MainActivity)) {
                is UpdateChecker.CheckResult.UpToDate -> {
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity,
                        "✅ Актуальная версия build-${BuildConfig.VERSION_CODE}", Toast.LENGTH_SHORT).show()
                }
                is UpdateChecker.CheckResult.Error -> {
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity,
                        "⚠️ Не удалось проверить: ${result.message}", Toast.LENGTH_LONG).show()
                }
                is UpdateChecker.CheckResult.Available -> {
                    val release = result.release
                    dialog.dismiss()
                    showDownloadDialog(release)
                    attachDownloadListeners()
                    UpdateChecker.downloadAndInstall(activity = this@MainActivity, release = release)
                }
            }
        }
    }

    private fun showDownloadDialog(release: UpdateChecker.Release?) {
        try { downloadDialog?.dismiss() } catch (_: Exception) {}
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0; setPadding(64, 48, 64, 48)
        }
        downloadProgressBar = bar
        downloadDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⬇️ Скачиваю ${release?.tag ?: "обновление"}")
            .setView(bar).setCancelable(false).show()
        updateDownloadDialog(UpdateChecker.currentDownloaded, UpdateChecker.currentTotal)
    }

    private fun updateDownloadDialog(d: Long, t: Long) {
        if (t > 0) {
            val pct = ((d * 100) / t).toInt()
            downloadProgressBar?.progress = pct
            downloadDialog?.setTitle("⬇️ Скачиваю ${UpdateChecker.currentRelease?.tag ?: ""} · $pct%")
        }
    }

    private fun attachDownloadListeners() {
        UpdateChecker.addProgressListener(downloadProgressListener)
        UpdateChecker.addFinishListener(downloadFinishListener)
    }
    private fun detachDownloadListeners() {
        UpdateChecker.removeProgressListener(downloadProgressListener)
        UpdateChecker.removeFinishListener(downloadFinishListener)
    }

    override fun onPause() {
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
        setIntent(intent)
        if (isFinishing || isDestroyed) return
        when (intent.action) {
            ACTION_WIDGET_SEND -> processWidgetSend()
            "com.garickbox.atom.WIDGET_INSTALL" -> {
                val apkUri = intent.getStringExtra("apk_uri") ?: ""
                if (apkUri.isNotBlank()) {
                    try {
                        UpdateChecker.installApkFromUriPublic(this, android.net.Uri.parse(apkUri))
                    } catch (e: Exception) {
                        InAppLogger.err("MainActivity", "B118 widget install: ${e.message}")
                    }
                }
            }
            ACTION_SHORTCUT_FIND -> {
                startActivity(Intent(this, FileBrowserActivity::class.java))
            }
            ACTION_SHORTCUT_SEND_PEER -> {
                pendingPeerForShareTarget = Peer(
                    id = intent.getStringExtra("atom_peer_mac") ?: "",
                    name = intent.getStringExtra("atom_peer_name") ?: "?",
                    ip = "",
                    mac = intent.getStringExtra("atom_peer_mac") ?: ""
                )
                startActivity(Intent(this, FileBrowserActivity::class.java))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    private fun processWidgetSend() {
        lifecycleScope.launch {
            val release = UpdateChecker.checkForUpdate(this@MainActivity)
            if (release != null) {
                UpdateChecker.promptAndInstall(this@MainActivity, release)
            } else {
                InAppLogger.d("MainActivity", "widget_send → FileBrowser")
                startActivity(Intent(this@MainActivity, FileBrowserActivity::class.java))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        maybePromptBatteryOnce()
        try { AtomController.wifiDirect?.startContinuousDiscover() } catch (_: Exception) {}
        try { BadgeHelper.clear(this) } catch (_: Exception) {}
        try { AtomQSTile.requestUpdate(this) } catch (_: Exception) {}
        UpdateChecker.checkPending(this)

        if (pendingWidgetSend) {
            pendingWidgetSend = false
            try { intent?.action = null } catch (_: Exception) {}
            processWidgetSend()
        }
        if (pendingShortcutFind) {
            pendingShortcutFind = false
            try { intent?.action = null } catch (_: Exception) {}
            startActivity(Intent(this, FileBrowserActivity::class.java))
        }

        if (UpdateChecker.isDownloading && downloadDialog == null) {
            showDownloadDialog(UpdateChecker.currentRelease)
            attachDownloadListeners()
        } else if (!UpdateChecker.isDownloading) {
            try {
                val ready = UpdateChecker.findDownloadedApk(this)
                if (ready != null) {
                    val key = ready.name + "_" + ready.dateAdded
                    val prefs = getSharedPreferences("atom", MODE_PRIVATE)
                    if (prefs.getString("b78_last_offered_apk", "") != key) {
                        prefs.edit().putString("b78_last_offered_apk", key).apply()
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("⬆️ Обновление готово")
                            .setMessage("Установить ${ready.name}?")
                            .setPositiveButton("Установить") { _, _ ->
                                try { UpdateChecker.installApkFromUriPublic(this, ready.uri) }
                                catch (e: Exception) { Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show() }
                            }
                            .setNegativeButton("Позже", null).show()
                    }
                }
            } catch (_: Exception) {}
        }

        val issues = PreflightChecker.issues(this)
        InAppLogger.d("MainActivity", "preflight (silent): ${PreflightChecker.summary(issues)}")
        runOnboarding()

        if (permissionAsked) return
        if (!AtomController.hasName()) return
        val missing = PermissionsHelper.missing(this)
        if (missing.isEmpty()) { permissionAsked = true; return }
        permissionAsked = true
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun sendActionToSender(action: String) {
        try {
            val i = Intent(this, FileSenderService::class.java).apply { this.action = action }
            androidx.core.content.ContextCompat.startForegroundService(this, i)
        } catch (e: Exception) {
            InAppLogger.err("MainActivity", "transfer action $action: ${e.message}")
        }
    }

    private fun toggleTvReceive() {
        try {
            WidgetReceiveService.toggle(this)
            val state = if (WidgetReceiveService.isActive) "ВКЛ" else "ВЫКЛ"
            Toast.makeText(this, "Приём: $state (10 мин)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            InAppLogger.err("MainActivity", "TV toggle receive: ${e.message}")
        }
    }

    private fun isTv(): Boolean = TvHelper.isTv(this)
}
