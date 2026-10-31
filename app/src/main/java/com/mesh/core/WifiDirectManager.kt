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
        val groupFormed: Boolean
    )

    var onPeersChanged: ((List<WifiP2pDevice>) -> Unit)? = null
    // B11.2-fix: постоянный слушатель — работает даже когда Activity не открыт
    var persistentOnPeersChanged: ((List<WifiP2pDevice>) -> Unit)? = null
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
                requestPeers()
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
                val key = peers.map { "${it.deviceName}/${it.deviceAddress}/${it.status}" }.sorted().joinToString(",")
                if (key != lastPeerKey) {
                    lastPeerKey = key
                    InAppLogger.d("WifiDirect",
                        "requestPeers → ${peers.size} peer(s): ${peers.map { "${it.deviceName}/${it.deviceAddress}/${it.status}" }}")
                }
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
                val conn = P2PConnection(
                    isGroupOwner = info.isGroupOwner,
                    groupOwnerAddress = ip,
                    groupFormed = true
                )
                InAppLogger.d("WifiDirect",
                    "connected: isGO=${conn.isGroupOwner} goIp=$ip")
                onConnected?.invoke(conn)
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
                    InAppLogger.d("WifiDirect", "discover started")
                }
                override fun onFailure(reason: Int) {
                    InAppLogger.d("WifiDirect", "discover failed: $reason")
                    onError?.invoke("Не удалось начать поиск (код $reason)")
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
            // Как build-37: без reflection, без groupOwnerIntent.
            // Framework сам выбирает band. Если повезёт — 5 GHz (лотерея).
            val config = android.net.wifi.p2p.WifiP2pConfig().apply {
                deviceAddress = mac
            }
            InAppLogger.d("WifiDirect", "connect to $mac (B16: reinvoke-if-profile-exists)")
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
@SuppressLint("MissingPermission")
    /**
     * B16: явное «забыть группу» — стирает persistent profile.
     * Вызывать только из кнопки «Отключиться / Забыть устройство», НЕ из flow.
     */
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
    fun startContinuousDiscover() {
        if (continuousJob?.isActive == true) return
        continuousJob = scope.launch {
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
