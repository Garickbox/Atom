package com.mesh.core
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat

class WifiDirectManager(private val ctx: Context) {

    data class P2PConnection(
        val isGroupOwner: Boolean,
        val groupOwnerAddress: String,
        val groupFormed: Boolean,
        val frequencyHz: Int = 0  // B121: 0 = unknown (API<29 или до connect), иначе 2412/5180 MHz
    )

    var onPeersChanged: ((List<WifiP2pDevice>) -> Unit)? = null
    // B11.2-fix: постоянный слушатель — работает даже когда Activity не открыт
    var persistentOnPeersChanged: ((List<WifiP2pDevice>) -> Unit)? = null
    @Volatile var suppressDiscover: Boolean = false  // B141: skip background discover при transfer
    var onConnected: ((P2PConnection) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onP2pStateChanged: ((Boolean) -> Unit)? = null

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var initialized = false
    private var continuousJob: Job? = null
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )

    fun isP2pEnabled(): Boolean {
        return try {
            val m = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            m != null
        } catch (_: Exception) { false }
    }

    @SuppressLint("MissingPermission")
    fun init() {
        if (initialized) return
        try {
            manager = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (manager == null) {
                InAppLogger.d("WifiDirect", "no WifiP2pManager")
                onError?.invoke("Wi-Fi Direct не поддерживается")
                return
            }
            channel = manager!!.initialize(ctx, Looper.getMainLooper()) {
                InAppLogger.d("WifiDirect", "channel disconnected")
            }
            if (channel == null) {
                InAppLogger.d("WifiDirect", "channel null")
                onError?.invoke("Не удалось инициализировать channel")
                return
            }

            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) = handleIntent(intent)
            }
            receiver = r
            if (Build.VERSION.SDK_INT >= 33) {
                // EXPORTED: системные broadcast'ы WifiP2pManager могут
                // не доставляться NOT_EXPORTED на некоторых OEM
                ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(r, filter)
            }
            initialized = true
            InAppLogger.d("WifiDirect", "initialized")
        } catch (e: Exception) {
            InAppLogger.d("WifiDirect", "init error: ${e.message}")
            onError?.invoke("Ошибка P2P: ${e.message}")
        }
    }

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                InAppLogger.d("WifiDirect", "state changed: enabled=$enabled")
                onP2pStateChanged?.invoke(enabled)
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                InAppLogger.d("WifiDirect", "peers changed")
                try { requestPeers() } catch (e: Exception) {
                    InAppLogger.d("WifiDirect", "B110: requestPeers после dead channel: ${e.javaClass.simpleName}")
                }
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                InAppLogger.d("WifiDirect", "connection changed")
                requestConnectionInfo()
            }
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val d = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                        WifiP2pDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                }
                InAppLogger.d("WifiDirect", "this device: ${d?.deviceName ?: "?"} mac=${d?.deviceAddress ?: "?"}")
            }
        }
    }

    @Volatile private var lastPeerKey: String = ""

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        try {
            manager?.requestPeers(channel) { list ->
                val peers = list?.deviceList?.toList() ?: emptyList()
                // B22-log: логировать ВСЕГДА (не только при смене) — иначе "0" невидим
                InAppLogger.d("WifiDirect",
                    "requestPeers → ${peers.size}: ${peers.map { "${it.deviceName}/${it.deviceAddress}/${it.status}" }}")
                if (peers.isNotEmpty()) {
                    onPeersChanged?.invoke(peers)
                    persistentOnPeersChanged?.invoke(peers)
                }
            }
        } catch (e: Exception) {
            InAppLogger.d("WifiDirect",
                "requestPeers error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        try {
            manager?.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                if (info == null || !info.groupFormed) {
                    InAppLogger.d("WifiDirect", "group not formed")
                    onDisconnected?.invoke()
                    return@requestConnectionInfo
                }
                val ip = info.groupOwnerAddress?.hostAddress ?: ""
                val isGO = info.isGroupOwner
                // B121fix2: WifiP2pInfo НЕ содержит frequency.
                // Реальная частота — WifiP2pGroup.frequency (API 29+).
                if (Build.VERSION.SDK_INT >= 29) {
                    try {
                        manager?.requestGroupInfo(channel) { group ->
                            val hz = group?.frequency ?: 0
                            val bandStr = when {
                                hz >= 5000 -> "5 GHz (${hz} MHz)"
                                hz >= 2400 -> "2.4 GHz (${hz} MHz)"
                                hz > 0 -> "${hz} MHz"
                                else -> "unknown"
                            }
                            InAppLogger.d("WifiDirect",
                                "connected: isGO=$isGO goIp=$ip band=$bandStr")
                            onConnected?.invoke(P2PConnection(
                                isGroupOwner = isGO,
                                groupOwnerAddress = ip,
                                groupFormed = true,
                                frequencyHz = hz
                            ))
                        }
                    } catch (e: Exception) {
                        InAppLogger.d("WifiDirect",
                            "requestGroupInfo error: ${e.message}, freq=0 fallback")
                        onConnected?.invoke(P2PConnection(isGO, ip, true, 0))
                    }
                } else {
                    InAppLogger.d("WifiDirect",
                        "connected: isGO=$isGO goIp=$ip (API<29, freq unknown)")
                    onConnected?.invoke(P2PConnection(isGO, ip, true, 0))
                }
            }
        } catch (e: Exception) {
            InAppLogger.d("WifiDirect", "requestConnectionInfo error: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        if (manager == null || channel == null) {
            onError?.invoke("Wi-Fi Direct не инициализирован")
            return
        }
        try {
            // B16: НЕ вызываем removeGroup перед discover — иначе стирается
            // persistent group profile и framework не может сделать reinvoke
            // (fast reconnect ~1-2s вместо полного цикла 5-15s с GO Negotiation).
            InAppLogger.d("WifiDirect", "discoverPeers: skip removeGroup (keep persistent)")
            startDiscover()
        } catch (e: Exception) {
            InAppLogger.d("WifiDirect", "discoverPeers error: ${e.message}")
            onError?.invoke("Ошибка поиска: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscover() {
        try {
            manager!!.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    InAppLogger.d("WifiDirect", "discover started → requestPeers now")
                    requestPeers()  // B22-log2: сразу, не ждать 12s tick
                }
                override fun onFailure(reason: Int) {
                    InAppLogger.d("WifiDirect", "discover failed: $reason → requestPeers anyway")
                    requestPeers()  // B22-log2: даже fail — что-то есть?
                    onError?.invoke("Поиск не начался (код $reason)")
                }
            })
        } catch (e: Exception) {
            InAppLogger.d("WifiDirect", "startDiscover error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Пассивный discover — делает устройство видимым для других. */
    fun passiveDiscover() {
        if (manager == null || channel == null) return
        InAppLogger.d("WifiDirect", "passive discover starting")
        try {
            manager!!.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    InAppLogger.d("WifiDirect", "passive discover started (we are visible)")
                }
                override fun onFailure(reason: Int) {
                    InAppLogger.d("WifiDirect", "passive discover failed: $reason")
                }
            })
        } catch (e: Exception) {
            InAppLogger.d("WifiDirect", "passiveDiscover error: ${e.javaClass.simpleName}")
        }
    }

@SuppressLint("MissingPermission")
    fun connect(mac: String) {
        if (manager == null || channel == null) {
            onError?.invoke("Wi-Fi Direct не инициализирован")
            return
        }
        try {
            // B121: prefer 5 GHz (API 29+). Framework может игнорировать, если 5 GHz занят
            val config = if (Build.VERSION.SDK_INT >= 29) {
                // B121fix2: Builder.setDeviceAddress ожидает MacAddress, не String
                android.net.wifi.p2p.WifiP2pConfig.Builder()
                    .setDeviceAddress(android.net.MacAddress.fromString(mac))
                    .setGroupOperatingBand(android.net.wifi.p2p.WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                android.net.wifi.p2p.WifiP2pConfig().apply {
                    deviceAddress = mac
                }
            }
            InAppLogger.d("WifiDirect", "connect to $mac (B121: prefer 5GHz on API29+)")
            manager!!.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    InAppLogger.d("WifiDirect", "connect initiated (system decides fresh vs reinvoke)")
                }
                override fun onFailure(reason: Int) {
                    InAppLogger.d("WifiDirect", "connect failed: $reason")
                    onError?.invoke("Connect failed (код $reason)")
                }
            })
        } catch (e: Exception) {
            InAppLogger.d("WifiDirect", "connect error: ${e.message}")
            onError?.invoke("Ошибка connect: ${e.message}")
        }
    }

    /**
     * Пробует выставить 5 GHz в WifiP2pConfig через reflection.
     * 3 стратегии: getMethod (public) → getDeclaredMethod (hidden) → Builder (API 30+).
     */
    /**
     * B16: явное «забыть группу» — стирает persistent profile.
     * Вызывать только из кнопки «Отключиться / Забыть устройство», НЕ из flow.
     */
    @SuppressLint("MissingPermission")
    fun forgetAllGroups() {
        // B166a: fire-and-forget (для Settings «забыть»)
        try {
            channel?.let { ch ->
                manager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { InAppLogger.d("WifiDirect", "B166a: removeGroup OK") }
                    override fun onFailure(reason: Int) { InAppLogger.d("WifiDirect", "B166a: removeGroup fail $reason") }
                })
            }
        } catch (_: Exception) {}
    }

    /**
     * B168: async removeGroup + callback + 3s timeout fallback.
     * HyperOS needs 2-3s to actually tear down — fixed delay(500) insufficient.
     */
    @SuppressLint("MissingPermission")
    fun forgetAllGroupsAndThen(onDone: () -> Unit) {
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finishOnce() {
            if (finished.compareAndSet(false, true)) {
                InAppLogger.d("WifiDirect", "B168: forgetAll done → continue")
                onDone()
            }
        }
        try {
            val ch = channel
            if (ch == null) { finishOnce(); return }
            manager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { InAppLogger.d("WifiDirect", "B168: removeGroup OK"); finishOnce() }
                override fun onFailure(reason: Int) { InAppLogger.d("WifiDirect", "B168: removeGroup fail $reason"); finishOnce() }
            })
            // Timeout: если callback не пришёл за 3s
            scope.launch {
                kotlinx.coroutines.delay(3000L)
                if (!finished.get()) {
                    InAppLogger.d("WifiDirect", "B168: forgetAll timeout 3s — continue anyway")
                    finishOnce()
                }
            }
        } catch (e: Exception) {
            InAppLogger.err("WifiDirect", "B168 forgetAll: ${e.message}")
            finishOnce()
        }
    }

    /** B168: cancel any pending connect before retry */
    @SuppressLint("MissingPermission")
    fun cancelPendingConnect() {
        try {
            manager?.cancelConnect(channel, null)
            InAppLogger.d("WifiDirect", "B168: cancelConnect sent")
        } catch (_: Exception) {}
    }

    fun disconnect() {
        try {
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { InAppLogger.d("WifiDirect", "group removed (forget)") }
                override fun onFailure(reason: Int) {
                    InAppLogger.d("WifiDirect", "removeGroup failed: $reason")
                }
            })
        } catch (_: Exception) {}
    }

    /** Alias для явного «забыть устройство». Сейчас = disconnect(). */
    fun forgetGroup() = disconnect()

    /**
     * Постоянный discovery: каждые 12 сек вызываем discoverPeers + requestPeers.
     * Нужно потому что WIFI_P2P_PEERS_CHANGED_ACTION приходит ТОЛЬКО при
     * изменении списка — если peers=0 всегда, broadcast не приходит вообще.
     * Принудительный requestPeers решает проблему.
     */
    /**
     * B18.1: принудительный fresh connect — removeGroup + reconnect.
     * Вызывается когда reinvoke persistent group не сработал за 3 сек
     * (framework висит в status=INVITED, но соединение не формируется).
     */
    fun forceFreshConnect(mac: String) {
        if (manager == null || channel == null) return
        InAppLogger.d("WifiDirect", "forceFreshConnect: removeGroup + reconnect $mac")
        try {
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    InAppLogger.d("WifiDirect", "forceFresh: removeGroup OK, reconnect in 500ms")
                    scope.launch {
                        kotlinx.coroutines.delay(500L)
                        connect(mac)
                    }
                }
                override fun onFailure(reason: Int) {
                    InAppLogger.d("WifiDirect", "forceFresh: removeGroup failed $reason, reconnect anyway")
                    scope.launch {
                        kotlinx.coroutines.delay(500L)
                        connect(mac)
                    }
                }
            })
        } catch (e: Exception) {
            InAppLogger.d("WifiDirect", "forceFreshConnect error: ${e.message}")
        }
    }

    fun startContinuousDiscover() {
        if (continuousJob?.isActive == true) return
        continuousJob = scope.launch {
            InAppLogger.d("WifiDirect", "continuousDiscover RUN (doDiscoverTick every 12s)")
            InAppLogger.d("WifiDirect", "continuousDiscover started")
            delay(500)
            while (true) {
                try {
                    doDiscoverTick()
                } catch (e: Exception) {
                    InAppLogger.d("WifiDirect", "tick error: ${e.javaClass.simpleName}: ${e.message}")
                }
                delay(12_000)
            }
        }
    }

    fun stopContinuousDiscover() {
        continuousJob?.cancel()
        continuousJob = null
        InAppLogger.d("WifiDirect", "continuousDiscover stopped")
    }

    @SuppressLint("MissingPermission")
    private fun doDiscoverTick() {
        if (suppressDiscover) {
            InAppLogger.d("WifiDirect", "B141: skip discover — transfer active")
            return
        }
        try {
            manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    // ВАЖНО: сразу запрашиваем peers — requestPeers сам залогирует изменения
                    requestPeers()
                }
                override fun onFailure(reason: Int) {
                    InAppLogger.d("WifiDirect", "discover tick fail: $reason → requestPeers anyway")
                    requestPeers()
                }
            })
        } catch (e: Exception) {
            InAppLogger.d("WifiDirect", "doDiscoverTick error: ${e.message}")
        }
    }

    fun cleanup() {
        try {
            receiver?.let { ctx.unregisterReceiver(it) }
        } catch (_: Exception) {}
        receiver = null
        initialized = false
        InAppLogger.d("WifiDirect", "cleanup")
    }
}
