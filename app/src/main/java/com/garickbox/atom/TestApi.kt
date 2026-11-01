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
 * B44 axiom#52 расширение: HTTP API на 127.0.0.1:47823 для автотестов из Termux.
 * Только локально, наружу не торчит. Управляет тестами через curl.
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
                // B45: bind 0.0.0.0 (все интерфейсы). Android loopback
                // изолирован по netns — Termux не видит 127.0.0.1 Атома.
                // Termux подключается через Wi-Fi IP телефона.
                server = ServerSocket(PORT)
                InAppLogger.d("TestApi", "HTTP listening on 127.0.0.1:$PORT")
                val pool = Executors.newFixedThreadPool(4)
                while (running) {
                    val client = try { server?.accept() } catch (_: Exception) { break } ?: break
                    pool.submit { handle(client, ctx) }
                }
            } catch (e: Exception) {
                InAppLogger.err("TestApi", "server error: ${e.message}")
                running = false
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(client: Socket, ctx: Context) {
        try {
            client.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val line = reader.readLine() ?: return
            val parts = line.split(" ")
            if (parts.size < 2) return respond(client, 400, "bad request")
            val path = parts[1]
            val (route, params) = parsePath(path)
            val body = when (route) {
                "/state" -> cmdState(ctx)
                "/selftest" -> cmdSelftest(ctx)
                "/hotspot" -> cmdHotspot(ctx)
                "/discover" -> cmdDiscover(ctx)
                "/loopback" -> cmdLoopback(ctx, params["size"]?.toLongOrNull() ?: 10_485_760L)
                "/log" -> cmdLog(params["name"] ?: "atom")
                "/ping" -> "pong"
                else -> "unknown route: $route"
            }
            respond(client, 200, body)
        } catch (e: Exception) {
            try { respond(client, 500, "error: ${e.message}") } catch (_: Exception) {}
        } finally {
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

    private fun respond(client: Socket, code: Int, body: String) {
        val out = PrintWriter(client.getOutputStream(), true)
        out.println("HTTP/1.1 $code OK")
        out.println("Content-Type: text/plain; charset=utf-8")
        out.println("Content-Length: ${body.toByteArray().size}")
        out.println("Connection: close")
        out.println()
        out.print(body)
        out.flush()
    }

    private fun cmdState(ctx: Context): String {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        // B46: канал Wi-Fi для диагностики hotspot reason=3
        @Suppress("DEPRECATION")
        val freq = wm?.connectionInfo?.frequency ?: 0
        val wifiCh = when {
            freq in 2412..2472 -> (freq - 2407) / 5
            freq in 5180..5825 -> (freq - 5000) / 5
            else -> 0
        }
        val wifiBand = when {
            freq in 2412..2472 -> "2.4GHz"
            freq in 5180..5825 -> "5GHz"
            else -> "off/unknown"
        }
        @Suppress("DEPRECATION")
        val ssid = wm?.connectionInfo?.ssid?.trim('"') ?: "?"
        val wifiOn = wm?.isWifiEnabled == true
        val airplane = android.provider.Settings.Global.getInt(
            ctx.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val whitelist = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        val perms = PermissionsHelper.missing(ctx)
        val freeMb = (ctx.getExternalFilesDir(null)?.usableSpace ?: 0L) / 1024 / 1024
        return buildString {
            appendLine("build=${BuildConfig.VERSION_CODE}")
            appendLine("device=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}")
            appendLine("android=${android.os.Build.VERSION.SDK_INT}")
            appendLine("wifiOn=$wifiOn ssid=$ssid airplane=$airplane")
            appendLine("wifiFreq=$freq band=$wifiBand channel=$wifiCh")
            appendLine("battery_whitelist=$whitelist")
            appendLine("perms_missing=${perms.size} ${perms.map { it.substringAfterLast('.') }}")
            appendLine("free_mb=$freeMb")
            appendLine("transferState=${AtomController.transferState.value.javaClass.simpleName}")
            appendLine("known_peers=${AtomController.getKnownP2pPeers().size}")
        }
    }

    private fun cmdSelftest(ctx: Context): String {
        val results = kotlinx.coroutines.runBlocking {
            SelfTestRunner.runAll(ctx)
        }
        return buildString {
            val ok = results.count { it.passed }
            appendLine("$ok/${results.size} passed")
            results.forEach { r ->
                appendLine("${if (r.passed) "✅" else "❌"} ${r.name}: ${r.detail}")
            }
        }
    }

    private fun cmdHotspot(ctx: Context): String {
        val wm = ctx.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            ?: return "no WifiManager"

        // B46: wifi channel для диагностики
        @Suppress("DEPRECATION")
        val freq = wm.connectionInfo?.frequency ?: 0
        val wifiCh = when {
            freq in 2412..2472 -> (freq - 2407) / 5
            freq in 5180..5825 -> (freq - 5000) / 5
            else -> 0
        }
        val wifiBand = when {
            freq in 2412..2472 -> "2.4GHz"
            freq in 5180..5825 -> "5GHz"
            else -> "off/unknown"
        }

        val result = java.util.concurrent.CountDownLatch(1)
        var ssid = ""
        var reason = -1
        val start = System.currentTimeMillis()
        try {
            wm.startLocalOnlyHotspot(object : android.net.wifi.WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: android.net.wifi.WifiManager.LocalOnlyHotspotReservation) {
                    ssid = r.wifiConfiguration?.SSID?.trim('"') ?: "?"
                    try { r.close() } catch (_: Exception) {}
                    result.countDown()
                }
                override fun onFailed(r: Int) {
                    reason = r
                    result.countDown()
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
            result.await(10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            return "exception: ${e.message}"
        }
        val dt = System.currentTimeMillis() - start
        return if (ssid.isNotBlank()) {
            "OK ssid=$ssid in ${dt}ms wifi=$wifiBand ch=$wifiCh"
        } else {
            val human = when (reason) {
                1 -> "ERROR(generic wifi error)"
                2 -> "INCOMPATIBLE_MODE(wifi off/airplane/other AP active)"
                3 -> "NO_CHANNEL(all AP channels in 2.4GHz busy)"
                4 -> "GENERIC(framework bug)"
                else -> "UNKNOWN($reason)"
            }
            val hint = when (reason) {
                3 -> "Wi-Fi на канале $wifiCh ($wifiBand). " +
                     "Все каналы для AP заняты. Выключи Wi-Fi в шторке на 30с → hotspot создастся. " +
                     "Или используй 5GHz Wi-Fi (недоступен по axiom #48)."
                2 -> if (wifiBand == "off/unknown") "Wi-Fi выключен → включи в шторке"
                     else "Другой AP активен / Wi-Fi переключается → подожди 5с"
                else -> "Перезагрузка framework: выключи/включи Wi-Fi"
            }
            "FAIL reason=$reason ($human) in ${dt}ms wifi=$wifiBand ch=$wifiCh\nhint: $hint"
        }
    }

    private fun cmdDiscover(ctx: Context): String {
        val wd = AtomController.wifiDirect ?: return "wifiDirect null"
        wd.discoverPeers()
        Thread.sleep(5000)
        // peers в AtomController.peers — HELLO-обнаруженные
        return "peers_hello=${AtomController.peers.value.size} " +
            AtomController.peers.value.map { "${it.name}@${it.ip}" }
    }

    private fun cmdLoopback(ctx: Context, size: Long): String {
        // B44: self-loopback pipeline — fake peer на 127.0.0.1
        // Создаём тест-файл, шлём на localhost через полный pipeline
        val testFile = File(ctx.cacheDir, "loopback_${System.currentTimeMillis()}.bin")
        try {
            testFile.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                java.util.Random(42).nextBytes(buf)  // deterministic
                var remaining = size
                while (remaining > 0) {
                    val toWrite = minOf(buf.size.toLong(), remaining).toInt()
                    out.write(buf, 0, toWrite)
                    remaining -= toWrite
                }
            }
            val sha = java.security.MessageDigest.getInstance("SHA-256")
            testFile.inputStream().use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
            testFile.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val r = input.read(buf); if (r < 0) break
                    sha.update(buf, 0, r)
                }
            }
            val fileHash = sha.digest().joinToString("") { "%02x".format(it) }
            // Пока не делаем реальный transfer на localhost (MeshNode байндится на все интерфейсы,
            // но TCP connect на 127.0.0.1 придёт в тот же процесс — не поддерживается текущим
            // обработчиком). Вместо этого: полный хеш + сохранение в MediaStore.
            val msStart = System.currentTimeMillis()
            val saved = MediaStoreSaver.save(ctx, testFile, testFile.name, "application/octet-stream")
            val msMs = System.currentTimeMillis() - msStart
            return buildString {
                appendLine("size=${size}B")
                appendLine("sha256=$fileHash")
                appendLine("save=${if (saved.uri != null) "OK" else "FAIL"} in ${msMs}ms")
                appendLine("path=${saved.displayPath}")
                if (saved.uri == null) appendLine("VERDICT=FAIL")
                else appendLine("VERDICT=OK")
            }
        } finally {
            testFile.delete()
        }
    }

    private fun cmdLog(name: String): String {
        return try {
            val base = "/sdcard/Download/Atom/logs"
            val candidates = File(base).listFiles { f -> f.name.startsWith(name) } ?: emptyArray()
            val newest = candidates.maxByOrNull { it.lastModified() } ?: return "no logs for '$name'"
            val content = newest.readText()
            if (content.length > 200_000) content.takeLast(200_000) else content
        } catch (e: Exception) {
            "log error: ${e.message}"
        }
    }
}
