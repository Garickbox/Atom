package com.garickbox.atom

import android.content.Context
import com.mesh.core.InAppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * B70: TestApi БЕЗ вызовов startLocalOnlyHotspot. /hotspot /wifi-diag
 * /hotspot-lifecycle НЕ реализованы сознательно — они ломают framework
 * stale reservation. Hotspot только через QrShareActivity (axiom#70).
 */
object TestApi {
    private const val PORT = 47823
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start(ctx: Context) {
        if (running) return
        running = true
        scope.launch {
            try {
                server = ServerSocket(PORT)
                InAppLogger.d("TestApi", "listening 0.0.0.0:" + PORT)
                val pool = Executors.newFixedThreadPool(8)
                while (running) {
                    val client = try { server?.accept() } catch (_: Exception) { break } ?: break
                    pool.submit { handle(client, ctx) }
                }
            } catch (e: Exception) {
                InAppLogger.err("TestApi", "start: " + e.message)
                running = false
            }
        }
    }

    private fun handle(client: Socket, ctx: Context) {
        try {
            client.soTimeout = 8_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val line = reader.readLine() ?: return
            val parts = line.split(" ")
            if (parts.size < 2) return respond(client, "bad")
            val path = parts[1]
            val (route, params) = parsePath(path)
            val body = try {
                when (route) {
                    "/ping" -> "pong"
                    "/state" -> cmdState(ctx)
                    "/log" -> cmdLog(params["name"] ?: "atom", ctx)
                    "/components" -> cmdComponents(ctx)
                    "/views" -> cmdViews(ctx, params["layout"] ?: "main")
                    "/file-io" -> cmdFileIo(ctx)
                    "/crash" -> cmdCrash(ctx)
                    "/selftest" -> cmdSelftest(ctx)
                    "/update" -> try { UpdateChecker.startAutoUpdate(ctx) }
                        catch (e: Exception) { "err: " + (e.message ?: "?") }
                    "/prefs" -> cmdPrefs(ctx)
                    "/permissions" -> cmdPermissions(ctx)
                    "/history" -> cmdHistory(ctx)
                    "/channels" -> cmdChannels(ctx)
                    "/shortcuts" -> cmdShortcuts(ctx)
                    "/widgets" -> cmdWidgets(ctx)
                    "/find_apk" -> cmdFindApk(ctx)
                    "/badge" -> cmdBadge(ctx, params["action"] ?: "get", params["n"]?.toIntOrNull() ?: 0)
                    "/nfc" -> cmdNfc(ctx)
                    "/nfc-log" -> cmdNfcLog(ctx)
                    "/p2p-group" -> cmdP2pGroup(ctx)
                    "/p2p-peers" -> cmdP2pPeers(ctx)
                    "/p2p-connection" -> cmdP2pConnection(ctx)
                    "/net-stat" -> cmdNetStat(ctx)
                    "/verbose" -> cmdVerbose(params["on"])
                    else -> "unknown: " + route + " (no /hotspot by axiom#70)"
                }
            } catch (e: Throwable) {
                "EXCEPTION " + e.javaClass.simpleName + ": " + (e.message ?: "?")
            }
            respond(client, body)
        } catch (_: Exception) {} finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun parsePath(path: String): Pair<String, Map<String, String>> {
        val q = path.indexOf('?')
        if (q < 0) return path to emptyMap()
        val route = path.substring(0, q)
        val params = path.substring(q + 1).split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0] to java.net.URLDecoder.decode(kv[1], "UTF-8") else null
        }.toMap()
        return route to params
    }

    private fun respond(client: Socket, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        var out: PrintWriter? = null
        try {
            out = PrintWriter(client.getOutputStream(), true)
            out.println("HTTP/1.1 200 OK")
            out.println("Content-Type: text/plain; charset=utf-8")
            out.println("Content-Length: " + bytes.size)
            out.println("Connection: close")
            out.println()
            out.print(body)
            out.flush()
        } catch (_: Exception) {
        } finally {
            try { out?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun cmdP2pGroup(ctx: Context): String {
        return try { AtomController.wifiDirect?.dumpGroupInfo() ?: "wifiDirect=null" }
            catch (e: Exception) { "err: " + e.message }
    }

    private fun cmdP2pPeers(ctx: Context): String {
        return try { AtomController.wifiDirect?.dumpPeers() ?: "wifiDirect=null" }
            catch (e: Exception) { "err: " + e.message }
    }

    private fun cmdP2pConnection(ctx: Context): String {
        return try { AtomController.wifiDirect?.dumpConnection() ?: "wifiDirect=null" }
            catch (e: Exception) { "err: " + e.message }
    }

    private fun cmdNetStat(ctx: Context): String {
        return try {
            val sb = StringBuilder()
            sb.appendLine("═══ NetworkInterfaces ═══")
            try {
                java.net.NetworkInterface.getNetworkInterfaces().asSequence()
                    .filter { it.isUp }
                    .forEach { ni ->
                        sb.append(ni.name + " up=" + ni.isUp)
                        val addrs = ni.inetAddresses.asSequence().map { it.hostAddress }.toList()
                        sb.appendLine(" addrs=" + addrs.joinToString(","))
                    }
            } catch (e: Exception) { sb.appendLine("ni err: " + e.message) }
            sb.appendLine()
            sb.appendLine("═══ /proc/net/arp ═══")
            try {
                java.io.File("/proc/net/arp").readLines().take(15).forEach { sb.appendLine(it) }
            } catch (e: Exception) { sb.appendLine("arp err: " + e.message) }
            sb.appendLine()
            sb.appendLine("═══ TCP listeners (47822, 47823) ═══")
            try {
                java.io.File("/proc/net/tcp").readLines()
                    .filter { it.contains(":BACE", true) || it.contains(":BACF", true) || it.contains(":BACE0") }
                    .take(5).forEach { sb.appendLine(it) }
            } catch (e: Exception) { sb.appendLine("tcp err: " + e.message) }
            sb.toString()
        } catch (e: Exception) { "err: " + e.message }
    }

    private fun cmdNfcLog(ctx: Context): String {
        return try {
            val f = com.mesh.core.InAppLogger.getNfcLogFile()
            if (f == null) "(empty)"
            else {
                val c = f.readText()
                if (c.length > 100_000) c.takeLast(100_000) else c
            }
        } catch (e: Exception) { "err: " + e.message }
    }

    private fun cmdNfc(ctx: Context): String {
        return try {
            val nm = ctx.getSystemService(Context.NFC_SERVICE) as? android.nfc.NfcManager
            val adapter = nm?.defaultAdapter
            buildString {
                appendLine("supported=" + (adapter != null))
                appendLine("enabled=" + (adapter?.isEnabled == true))
                appendLine("hceActive=" + NfcHceService.active)
                appendLine("hceUri=" + NfcHceService.currentUri.take(80))
                appendLine("lastApdus=" + NfcHceService.lastApdus.takeLast(5).joinToString(" | "))
            }
        } catch (e: Exception) { "err: " + e.message }
    }

    private fun cmdState(ctx: Context): String {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val perms = try { PermissionsHelper.missing(ctx) } catch (_: Exception) { emptyList() }
        val freeMb = (ctx.getExternalFilesDir(null)?.usableSpace ?: 0L) / 1024 / 1024
        return buildString {
            appendLine("build=" + BuildConfig.VERSION_CODE)
            appendLine("device=" + android.os.Build.MANUFACTURER + "/" + android.os.Build.MODEL)
            appendLine("android=" + android.os.Build.VERSION.SDK_INT)
            appendLine("wifiOn=" + (wm?.isWifiEnabled == true))
            appendLine("battery_whitelist=" + (pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true))
            appendLine("perms_missing=" + perms.size + " " + perms.map { it.substringAfterLast('.') })
            appendLine("free_mb=" + freeMb)
            appendLine("transferState=" + AtomController.transferState.value.javaClass.simpleName)
            appendLine("known_peers=" + AtomController.getKnownP2pPeers().size)
            appendLine("live_peers=" + AtomController.peers.value.size)
            appendLine("verbose=" + com.mesh.core.InAppLogger.isVerbose())
        }
    }

    private fun cmdLog(name: String, ctx: Context): String {
        return try {
            val base = ctx.getExternalFilesDir("logs") ?: File(ctx.filesDir, "logs")
            val f = File(base, name + ".log")
            if (!f.exists()) return "no " + name + ".log"
            val c = f.readText()
            if (c.length > 200_000) c.takeLast(200_000) else c
        } catch (e: Exception) { "err: " + e.message }
    }

    private fun cmdComponents(ctx: Context): String {
        return try {
            val pm = ctx.packageManager
            val ai = pm.getPackageInfo(ctx.packageName,
                android.content.pm.PackageManager.GET_ACTIVITIES or
                android.content.pm.PackageManager.GET_SERVICES or
                android.content.pm.PackageManager.GET_RECEIVERS)
            val sb = StringBuilder()
            ai.activities?.forEach { sb.appendLine("A " + it.name.substringAfterLast('.') + " exp=" + it.exported) }
            ai.services?.forEach { sb.appendLine("S " + it.name.substringAfterLast('.') + " exp=" + it.exported) }
            ai.receivers?.forEach { sb.appendLine("R " + it.name.substringAfterLast('.') + " exp=" + it.exported) }
            sb.toString()
        } catch (e: Exception) { "err: " + e.message }
    }

    private fun cmdViews(ctx: Context, layout: String): String {
        val resId = when (layout) {
            "main" -> R.layout.activity_main
            "qr" -> R.layout.activity_qr_share
            "qr_scan" -> R.layout.activity_qr_scan
            "widget" -> R.layout.widget_atom
            else -> return "unknown: " + layout
        }
        // B140: MaterialCardView не инфлейтится в ContextThemeWrapper standalone.
        // /views — диагностика, не критичный путь. Тест UI — через скриншот/визуальный протокол.
        return try {
            val themed = android.view.ContextThemeWrapper(ctx, R.style.Theme_Atom_Main)
            val root = android.view.LayoutInflater.from(themed).inflate(resId, null)
            val sb = StringBuilder("layout=" + layout + " root=" + root.javaClass.simpleName + "\n")
            fun walk(v: android.view.View, depth: Int) {
                val idName = if (v.id != android.view.View.NO_ID) {
                    try { ctx.resources.getResourceEntryName(v.id) } catch (_: Exception) { "?" }
                } else ""
                if (idName.isNotEmpty()) {
                    sb.append("  ".repeat(depth))
                    sb.append(idName + " [" + v.javaClass.simpleName + "] vis=" + v.visibility)
                    sb.appendLine()
                }
                if (v is android.view.ViewGroup) {
                    for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
                }
            }
            walk(root, 0)
            sb.toString()
        } catch (e: Exception) { "err: " + e.javaClass.simpleName + ": " + e.message }
    }

    private fun cmdFileIo(ctx: Context): String {
        val sb = StringBuilder()
        try {
            val f = File(ctx.cacheDir, "test.bin")
            f.writeBytes(ByteArray(1024))
            sb.appendLine("cache: " + f.length() + "B ok=" + (f.length() == 1024L))
            f.delete()
        } catch (e: Exception) { sb.appendLine("cache: FAIL " + e.message) }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val resolver = ctx.contentResolver
                val v = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "test_" + System.currentTimeMillis() + ".bin")
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/Atom/test/")
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(ByteArray(1024)) }
                    resolver.delete(uri, null, null)
                    sb.appendLine("MediaStore: OK")
                } else sb.appendLine("MediaStore: insert=null")
            }
        } catch (e: Exception) { sb.appendLine("MediaStore: FAIL " + e.message) }
        return sb.toString()
    }

    private fun cmdCrash(ctx: Context): String {
        return try {
            val base = ctx.getExternalFilesDir("logs") ?: File(ctx.filesDir, "logs")
            val f = File(base, "atom-err.log")
            if (!f.exists()) return "no atom-err.log (чисто)"
            val lines = f.readLines().takeLast(200)
            val bad = lines.filter { it.contains("FATAL", true) || it.contains("Exception", true) }
            "errors=" + bad.size + "/" + lines.size + "\n" + bad.takeLast(10).joinToString("\n")
        } catch (e: Exception) { "err: " + e.message }
    }

    private fun cmdPrefs(ctx: Context): String {
        return try {
            val p = ctx.getSharedPreferences("atom", Context.MODE_PRIVATE)
            val keys = p.all.keys.sorted()
            val sb = StringBuilder("prefs=" + keys.size + "\n")
            keys.forEach { k ->
                val v = p.all[k]
                val shown = when (v) {
                    is Boolean -> v.toString()
                    is Int -> v.toString()
                    is Long -> if (k.contains("_at") || k.contains("ms"))
                        (System.currentTimeMillis() - v).toString() + "ms_ago"
                        else v.toString()
                    is String -> if (k.contains("token") || k.contains("password")) "***"
                        else if (v.length > 40) v.take(40) + "…" else v
                    else -> "?"
                }
                sb.appendLine("  " + k + "=" + shown)
            }
            sb.toString()
        } catch (e: Exception) { "err: " + e.message }
    }

    private fun cmdPermissions(ctx: Context): String {
        return try {
            val pm = ctx.packageManager
            val ai = pm.getPackageInfo(ctx.packageName,
                android.content.pm.PackageManager.GET_PERMISSIONS)
            val sb = StringBuilder()
            ai.requestedPermissions?.forEach { perm ->
                val short = perm.substringAfterLast('.')
                val granted = try {
                    android.content.pm.PackageManager.PERMISSION_GRANTED ==
                        ctx.checkSelfPermission(perm)
                } catch (_: Exception) { false }
                sb.appendLine(short + " granted=" + granted)
            }
            sb.toString()
        } catch (e: Exception) { "err: " + e.message }
    }

    private fun cmdHistory(ctx: Context): String {
        return try {
            val records = TransferHistory.list(ctx)
            val sb = StringBuilder("records=" + records.size + "\n")
            records.take(20).forEach { r ->
                sb.appendLine(r.status + " " + r.fileName + " " + r.bytes + "B from " + r.peerName)
            }
            sb.toString()
        } catch (e: Exception) { "err: " + e.message }
    }

    private fun cmdChannels(ctx: Context): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT < 26) return "API<26 skip"
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            val sb = StringBuilder()
            nm.notificationChannels?.forEach { ch ->
                sb.appendLine(ch.id + " imp=" + ch.importance + " badge=" + ch.canShowBadge())
            }
            sb.toString()
        } catch (e: Exception) { "err: " + e.message }
    }

    private fun cmdShortcuts(ctx: Context): String {
        return try {
            val sm = androidx.core.content.pm.ShortcutManagerCompat.getShortcuts(
                ctx, androidx.core.content.pm.ShortcutManagerCompat.FLAG_MATCH_DYNAMIC)
            "dynamic=" + sm.size + "\n" + sm.joinToString("\n") {
                "  " + it.shortLabel + " (" + it.id + ")"
            }
        } catch (e: Exception) { "err: " + e.message }
    }

    private fun cmdWidgets(ctx: Context): String {
        return try {
            val pm = ctx.packageManager
            val ai = pm.getPackageInfo(ctx.packageName,
                android.content.pm.PackageManager.GET_RECEIVERS)
            val sb = StringBuilder()
            ai.receivers?.filter { it.name.contains("Widget") }?.forEach { r ->
                sb.appendLine(r.name.substringAfterLast('.') + " enabled=" + r.enabled)
            }
            if (sb.isEmpty()) sb.appendLine("(no widget receivers)")
            sb.toString()
        } catch (e: Exception) { "err: " + e.message }
    }

    /** B81 axiom#85: проверка findDownloadedApk — null если APK уже установлен. */
    private fun cmdFindApk(ctx: Context): String {
        return try {
            val savedVc = ctx.getSharedPreferences("atom", Context.MODE_PRIVATE)
                .getInt("last_downloaded_apk_vc", 0)
            val currentVc = BuildConfig.VERSION_CODE
            val ready = UpdateChecker.findDownloadedApk(ctx)
            buildString {
                appendLine("savedVc=" + savedVc + " currentVc=" + currentVc)
                appendLine("verdict=" + (if (savedVc > currentVc) "UPDATE_AVAILABLE" else
                    if (savedVc == 0) "NO_APK" else "ALREADY_INSTALLED"))
                if (ready != null) {
                    appendLine("APK_READY name=" + ready.name + " date=" + ready.dateAdded)
                } else {
                    appendLine("APK_READY=null (правильно если savedVc<=currentVc)")
                }
            }
        } catch (e: Exception) { "err: " + e.message }
    }

    /** B101: toggle verbose (axiom#26 — без этого TV-логи невидимы) */
    private fun cmdVerbose(onParam: String?): String {
        return try {
            val on = when (onParam?.lowercase()) {
                null, "1", "true", "on", "yes" -> true
                "0", "false", "off", "no" -> false
                else -> true
            }
            com.mesh.core.InAppLogger.setVerbose(on)
            "verbose=" + com.mesh.core.InAppLogger.isVerbose()
        } catch (e: Exception) { "err: " + e.message }
    }

    /** B81: управление badge counter. action=get|set|clear */
    private fun cmdBadge(ctx: Context, action: String, n: Int): String {
        return try {
            when (action) {
                "get" -> "badge_count=" + BadgeHelper.currentCount(ctx)
                "set" -> {
                    BadgeHelper.setCount(ctx, n)
                    "badge_count=" + BadgeHelper.currentCount(ctx) + " (set " + n + ")"
                }
                "clear" -> {
                    BadgeHelper.clear(ctx)
                    "badge_count=" + BadgeHelper.currentCount(ctx) + " (cleared)"
                }
                "incr" -> {
                    BadgeHelper.increment(ctx)
                    "badge_count=" + BadgeHelper.currentCount(ctx) + " (incremented)"
                }
                else -> "unknown action: " + action + " (get|set|clear|incr)"
            }
        } catch (e: Exception) { "err: " + e.message }
    }

    private fun cmdSelftest(ctx: Context): String {
        return try {
            val results = kotlinx.coroutines.runBlocking { SelfTestRunner.runAll(ctx) }
            val ok = results.count { it.passed }
            buildString {
                appendLine(ok.toString() + "/" + results.size + " passed")
                results.forEach { appendLine((if (it.passed) "OK" else "FAIL") + " " + it.name + ": " + it.detail) }
            }
        } catch (e: Exception) { "err: " + e.message }
    }
}
