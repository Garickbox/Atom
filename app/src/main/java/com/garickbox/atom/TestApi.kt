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
                val pool = Executors.newFixedThreadPool(4)
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
            client.soTimeout = 30_000
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
                    "/wifi-diag" -> cmdWifiDiag(ctx)
                    "/hotspot" -> cmdHotspot(ctx)
                    "/selftest" -> cmdSelftest(ctx)
                    "/components" -> cmdComponents(ctx)
                    "/views" -> cmdViews(ctx, params["layout"] ?: "main")
                    "/file-io" -> cmdFileIo(ctx)
                    "/crash" -> cmdCrash(ctx)
                    else -> "unknown: " + route
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
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val out = PrintWriter(client.getOutputStream(), true)
            out.println("HTTP/1.1 200 OK")
            out.println("Content-Type: text/plain; charset=utf-8")
            out.println("Content-Length: " + bytes.size)
            out.println("Connection: close")
            out.println()
            out.print(body)
            out.flush()
        } catch (_: Exception) {}
    }

    private fun cmdState(ctx: Context): String {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        return buildString {
            appendLine("build=" + BuildConfig.VERSION_CODE)
            appendLine("device=" + android.os.Build.MANUFACTURER + "/" + android.os.Build.MODEL)
            appendLine("android=" + android.os.Build.VERSION.SDK_INT)
            appendLine("wifiOn=" + (wm?.isWifiEnabled == true))
            appendLine("transferState=" + AtomController.transferState.value.javaClass.simpleName)
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

    private fun cmdWifiDiag(ctx: Context): String {
        val sb = StringBuilder()
        try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                ?: return "no WifiManager"
            sb.appendLine("wifiEnabled=" + wm.isWifiEnabled)
            sb.appendLine("wifiState=" + wm.wifiState)
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            if (info != null) {
                sb.appendLine("ssid=" + info.ssid)
                sb.appendLine("supplicantState=" + info.supplicantState)
                sb.appendLine("networkId=" + info.networkId)
                sb.appendLine("rssi=" + info.rssi)
                sb.appendLine("frequency=" + info.frequency)
            }
            sb.appendLine("locationEnabled=" + (try { LocationHelper.isEnabled(ctx) } catch (_: Exception) { false }))
            sb.appendLine("--- AP attempt ---")
            val latch = java.util.concurrent.CountDownLatch(1)
            var ssid = ""; var reason = -1
            try {
                wm.startLocalOnlyHotspot(object : android.net.wifi.WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(r: android.net.wifi.WifiManager.LocalOnlyHotspotReservation) {
                        ssid = r.wifiConfiguration?.SSID?.trim('"') ?: "?"
                        try { r.close() } catch (_: Exception) {}
                        latch.countDown()
                    }
                    override fun onFailed(r: Int) { reason = r; latch.countDown() }
                }, android.os.Handler(android.os.Looper.getMainLooper()))
                latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) { sb.appendLine("exc: " + e.message) }
            if (ssid.isNotBlank()) sb.appendLine("AP_OK ssid=" + ssid)
            else sb.appendLine("AP_FAIL reason=" + reason)
        } catch (e: Exception) { sb.appendLine("err: " + e.message) }
        return sb.toString()
    }

    private fun cmdHotspot(ctx: Context): String {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            ?: return "no WifiManager"
        val latch = java.util.concurrent.CountDownLatch(1)
        var ssid = ""; var reason = -1
        val start = System.currentTimeMillis()
        try {
            wm.startLocalOnlyHotspot(object : android.net.wifi.WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: android.net.wifi.WifiManager.LocalOnlyHotspotReservation) {
                    ssid = r.wifiConfiguration?.SSID?.trim('"') ?: "?"
                    try { r.close() } catch (_: Exception) {}
                    latch.countDown()
                }
                override fun onFailed(r: Int) { reason = r; latch.countDown() }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) { return "exc: " + e.message }
        val dt = System.currentTimeMillis() - start
        return if (ssid.isNotBlank()) "OK ssid=" + ssid + " in " + dt + "ms"
               else "FAIL reason=" + reason + " in " + dt + "ms"
    }

    private fun cmdSelftest(ctx: Context): String {
        val results = kotlinx.coroutines.runBlocking { SelfTestRunner.runAll(ctx) }
        return buildString {
            appendLine(results.count { it.passed }.toString() + "/" + results.size + " passed")
            results.forEach { appendLine((if (it.passed) "OK" else "FAIL") + " " + it.name + ": " + it.detail) }
        }
    }

    private fun cmdComponents(ctx: Context): String {
        return try {
            val pm = ctx.packageManager
            val ai = pm.getPackageInfo(ctx.packageName,
                android.content.pm.PackageManager.GET_ACTIVITIES or
                android.content.pm.PackageManager.GET_SERVICES or
                android.content.pm.PackageManager.GET_RECEIVERS)
            val sb = StringBuilder()
            ai.activities?.forEach { sb.appendLine("A " + it.name.substringAfterLast('.')) }
            ai.services?.forEach { sb.appendLine("S " + it.name.substringAfterLast('.')) }
            ai.receivers?.forEach { sb.appendLine("R " + it.name.substringAfterLast('.')) }
            sb.toString()
        } catch (e: Exception) { "err: " + e.message }
    }

    private fun cmdViews(ctx: Context, layout: String): String {
        val resId = when (layout) {
            "main" -> R.layout.activity_main
            "qr" -> R.layout.activity_qr_share
            else -> return "unknown: " + layout
        }
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
            sb.appendLine("cache: " + f.length() + "B")
            f.delete()
        } catch (e: Exception) { sb.appendLine("cache: " + e.message) }
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
                }
            }
        } catch (e: Exception) { sb.appendLine("MediaStore: " + e.message) }
        return sb.toString()
    }

    private fun cmdCrash(ctx: Context): String {
        return try {
            val base = ctx.getExternalFilesDir("logs") ?: File(ctx.filesDir, "logs")
            val f = File(base, "atom-err.log")
            if (!f.exists()) return "no errors"
            val lines = f.readLines().takeLast(200)
            val bad = lines.filter { it.contains("FATAL", true) || it.contains("Exception", true) }
            "errors=" + bad.size + "/" + lines.size + "\n" + bad.takeLast(10).joinToString("\n")
        } catch (e: Exception) { "err: " + e.message }
    }
}
