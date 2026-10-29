package com.mesh.core

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
    var onConnected: ((P2PConnection) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onP2pStateChanged: ((Boolean) -> Unit)? = null

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var initialized = false
    private var continuousJob: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
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

    @SuppressLint("MissingPermission")
    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        try {
            manager?.requestPeers(channel) { list ->
                val peers = list?.deviceList?.toList() ?: emptyList()
                InAppLogger.d("WifiDirect",
                    "requestPeers → ${peers.size} peer(s): ${peers.map { "${it.deviceName}/${it.deviceAddress}/${it.status}" }}")
                if (peers.isNotEmpty()) {
                    onPeersChanged?.invoke(peers)
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
            // removeGroup перед новым поиском — иначе залипает
            manager!!.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    InAppLogger.d("WifiDirect", "removed group before discover")
                    startDiscover()
                }
                override fun onFailure(reason: Int) {
                    InAppLogger.d("WifiDirect", "removeGroup failed: $reason (продолжаем)")
                    startDiscover()
                }
            })
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
            val config = android.net.wifi.p2p.WifiP2pConfig().apply {
                deviceAddress = mac
            }
            InAppLogger.d("WifiDirect", "connect to $mac")
            manager!!.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    InAppLogger.d("WifiDirect", "connect initiated")
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

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { InAppLogger.d("WifiDirect", "group removed") }
                override fun onFailure(reason: Int) {
                    InAppLogger.d("WifiDirect", "removeGroup failed: $reason")
                }
            })
        } catch (_: Exception) {}
    }

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
            kotlinx.coroutines.delay(500)
            while (true) {
                try {
                    doDiscoverTick()
                } catch (e: Exception) {
                    InAppLogger.d("WifiDirect", "tick error: ${e.javaClass.simpleName}: ${e.message}")
                }
                kotlinx.coroutines.delay(12_000)
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
                    InAppLogger.d("WifiDirect", "discover tick ok → requestPeers")
                    // ВАЖНО: сразу запрашиваем peers, не ждём broadcast
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
