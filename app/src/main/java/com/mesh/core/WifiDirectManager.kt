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
    var onConnected: ((P2PConnection) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onP2pStateChanged: ((Boolean) -> Unit)? = null

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var initialized = false
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
                // Force log group frequency с retry (часто приходит с задержкой)
                scope.launch {
                    for (i in 1..6) {
                        kotlinx.coroutines.delay(1500)
                        try {
                            var logged = false
                            manager?.requestGroupInfo(channel) { group ->
                                if (group != null && group.frequency > 0) {
                                    val band = if (group.frequency > 4000) "5 GHz ✅" else "2.4 GHz ❌"
                                    InAppLogger.d("WifiDirect",
                                        "GROUP freq=${group.frequency} MHz ($band) ssid=${group.networkName} try#$i")
                                    logged = true
                                }
                            }
                            if (logged) break
                        } catch (_: Exception) {}
                    }
                }
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
        // build-32: одна попытка removeGroup → discover. Без retry.
        try {
            manager!!.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    InAppLogger.d("WifiDirect", "removeGroup OK → discover")
                    startDiscover()
                }
                override fun onFailure(reason: Int) {
                    InAppLogger.d("WifiDirect", "removeGroup failed: $reason → discover")
                    startDiscover()
                }
            })
        } catch (e: Exception) {
            InAppLogger.d("WifiDirect", "discoverPeers error: ${e.message}")
            startDiscover()
        }
    }
    @SuppressLint("MissingPermission")
    private fun startDiscover() {
        try {
            manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    InAppLogger.d("WifiDirect", "discover started OK")
                }
                override fun onFailure(reason: Int) {
                    InAppLogger.d("WifiDirect", "discover failed: $reason")
                    if (reason == 2) {
                        onError?.invoke("Wi-Fi Direct занят. Перезагрузите оба телефона.")
                    } else {
                        onError?.invoke("Поиск не запускается. Проверьте Location Services.")
                    }
                }
            })
        } catch (e: Exception) {
            InAppLogger.d("WifiDirect", "startDiscover error: ${e.message}")
        }
    }
    /** Force reset: close channel, re-init. Спасает от вечных BUSY. */
@SuppressLint("MissingPermission")
    fun connect(mac: String) {
        if (manager == null || channel == null) {
            onError?.invoke("Wi-Fi Direct не инициализирован")
            return
        }
        try {
            // build-32 версия: БЕЗ groupOwnerIntent, БЕЗ 5GHz reflection
            // groupOwnerIntent = 15 ломал GO negotiation на HyperOS
            val config = android.net.wifi.p2p.WifiP2pConfig().apply {
                deviceAddress = mac
            }
            InAppLogger.d("WifiDirect", "connect to $mac (no GO intent)")
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
fun cleanup() {
        try {
            receiver?.let { ctx.unregisterReceiver(it) }
        } catch (_: Exception) {}
        receiver = null
        initialized = false
        InAppLogger.d("WifiDirect", "cleanup")
    }

}
