package com.garickbox.atom

import android.content.Context
import android.content.SharedPreferences
import com.mesh.core.InAppLogger
import com.mesh.core.MeshNode
import com.mesh.core.WifiDirectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object AtomController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)


    private lateinit var prefs: SharedPreferences
    private var _meshNode: MeshNode? = null
    var wifiDirect: WifiDirectManager? = null
        private set

    val peers = MutableStateFlow<List<Peer>>(emptyList())
    val transferState = MutableStateFlow<TransferState>(TransferState.Idle)

    var myName: String = ""
    var myId: String = ""
    var lastPeer: Peer? = null
    @Volatile var appForeground: Boolean = false

    val meshNode: MeshNode? get() = _meshNode

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("atom", Context.MODE_PRIVATE)
        myId = prefs.getString("my_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("my_id", it).apply()
        }
        myName = prefs.getString("my_name", "") ?: ""
        lastPeer = prefs.getString("last_peer_id", null)?.let {
            Peer(
                id = it,
                name = prefs.getString("last_peer_name", "?") ?: "?",
                ip = prefs.getString("last_peer_ip", "") ?: "",
                mac = prefs.getString("last_peer_mac", "") ?: ""
            )
        }

        val node = MeshNode(ctx.applicationContext).apply {
            this.myId = this@AtomController.myId
            this.myName = this@AtomController.myName
            this.onHello = { info ->
                if (info.peerId != this@AtomController.myId) {
                    val existing = peers.value.toMutableList()
                    existing.removeAll { it.id == info.peerId }
                    existing.add(Peer(info.peerId, info.name, info.ip))
                    peers.value = existing
                }
            }
            this.onFileOffer = { offer ->
                FileReceiver.onOffer(offer, ctx.applicationContext)
            }
            this.onFileCancel = { tid ->
                FileReceiver.onCancel(ctx.applicationContext, tid)
            }
            this.onFilePause = { tid ->
                FileReceiver.onPause(ctx.applicationContext, tid)
            }
        }
        node.start()
        _meshNode = node

        val wd = WifiDirectManager(ctx.applicationContext)
        wd.init()
        // P2P: после подключения клиент шлёт HELLO на IP Group Owner
        wd.onConnected = { conn ->
            InAppLogger.d("AtomController", "p2p connected isGO=${conn.isGroupOwner} goIp=${conn.groupOwnerAddress}")
            if (!conn.isGroupOwner && conn.groupOwnerAddress.isNotBlank()) {
                _meshNode?.sendHello(conn.groupOwnerAddress)
                InAppLogger.d("AtomController", "HELLO → GO ${conn.groupOwnerAddress}")
            }
        }
        wifiDirect = wd

        // B11.2-fix: автосохранение MAC всех найденных/подключённых пиров.
        // Работает всегда: и когда мы инициируем, и когда к нам подключаются.
        wd.persistentOnPeersChanged = { p2pPeers ->
            try {
                p2pPeers.forEach { p ->
                    if (p.deviceAddress.isNotBlank()) {
                        saveKnownP2pPeer(p.deviceAddress, p.deviceName)
                    }
                }
            } catch (e: Exception) {
                InAppLogger.d("AtomController", "persistent peers save error: ${e.message}")
            }
        }

        // Подписываемся на lifecycle: пока Атом открыт — периодически
        // делаем себя видимым через Wi-Fi Direct
        try {
            androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : androidx.lifecycle.DefaultLifecycleObserver {
                    override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                        InAppLogger.d("AtomController", "app foreground → start discover loop")
                        appForeground = true
                        try { AtomQSTile.requestUpdate(owner as? android.content.Context ?: return@onStart) } catch (_: Exception) {}
                        startContinuousP2P()
                    }
                    override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                        InAppLogger.d("AtomController", "app background → stop discover loop")
                        appForeground = false
                        try {
                            val c = owner as? android.content.Context
                            if (c != null) AtomQSTile.requestUpdate(c)
                        } catch (_: Exception) {}
                        stopContinuousP2P()
                    }
                }
            )
        } catch (e: Exception) {
            InAppLogger.d("AtomController", "lifecycle observer error: ${e.message}")
        }


        InAppLogger.d("AtomController", "init, myId=$myId, myName=$myName")

        // B11: обновление виджета при каждом изменении transferState
        try {
            scope.launch {
                transferState.collect {
                    try { AtomWidgetProvider.refresh(ctx.applicationContext) } catch (_: Exception) {}
                    try { AtomQSTile.requestUpdate(ctx.applicationContext) } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            InAppLogger.d("AtomController", "widget watch error: ${e.message}")
        }
    }

    fun saveMyName(name: String) {
        myName = name
        prefs.edit().putString("my_name", name).apply()
        _meshNode?.myName = name
        InAppLogger.d("AtomController", "saveMyName: $name")
    }

    fun saveLastPeer(peer: Peer) {
        lastPeer = peer
        prefs.edit()
            .putString("last_peer_id", peer.id)
            .putString("last_peer_name", peer.name)
            .putString("last_peer_ip", peer.ip)
            .putString("last_peer_mac", peer.mac)
            .apply()
    }

    // B11.2: история известных P2P пиров (Quick Connect)
    data class KnownP2pPeer(
        val mac: String,
        val name: String,
        val lastConnectedMs: Long
    )

    private val knownP2pLock = Any()

    fun getKnownP2pPeers(): List<KnownP2pPeer> {
        synchronized(knownP2pLock) {
            val raw = prefs.getString("known_p2p_peers", null) ?: return emptyList()
            return try {
                val arr = org.json.JSONArray(raw)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val mac = o.optString("mac", "")
                    if (mac.isBlank()) return@mapNotNull null
                    KnownP2pPeer(
                        mac = mac,
                        name = o.optString("name", "?"),
                        lastConnectedMs = o.optLong("ts", 0L)
                    )
                }.sortedByDescending { it.lastConnectedMs }
            } catch (_: Exception) { emptyList() }
        }
    }

    fun saveKnownP2pPeer(mac: String, name: String) {
        if (mac.isBlank()) return
        synchronized(knownP2pLock) {
            val actualName = name.ifBlank { "Устройство ${mac.takeLast(5)}" }
            val current = getKnownP2pPeers().toMutableList()
            // B11.3 dedup: не спамить prefs если MAC тот же и прошло <5 мин
            val existing = current.find { it.mac == mac }
            val now = System.currentTimeMillis()
            if (existing != null && existing.name == actualName &&
                now - existing.lastConnectedMs < 5 * 60 * 1000L) {
                return
            }
            current.removeAll { it.mac == mac }
            current.add(0, KnownP2pPeer(mac, actualName, now))
            val top = current.take(10)
            val arr = org.json.JSONArray()
            top.forEach { p ->
                arr.put(org.json.JSONObject().apply {
                    put("mac", p.mac)
                    put("name", p.name)
                    put("ts", p.lastConnectedMs)
                })
            }
            prefs.edit().putString("known_p2p_peers", arr.toString()).apply()
            InAppLogger.d("AtomController", "known_peer saved: $actualName ($mac)")
        }
    }

    fun hasName(): Boolean = myName.isNotBlank()


    private fun startContinuousP2P() {
        if (_transferActive) {
            com.mesh.core.InAppLogger.d("AtomController", "skip continuous — transfer active")
            return
        }
        InAppLogger.d("AtomController", "app foreground → start continuous P2P discover")
        wifiDirect?.startContinuousDiscover()
    }

    private fun stopContinuousP2P() {
        InAppLogger.d("AtomController", "app background → stop continuous P2P discover")
        wifiDirect?.stopContinuousDiscover()
    }


    private var _transferActive: Boolean = false
    val transferActive: Boolean get() = _transferActive

    fun setTransferActive(active: Boolean) {
        _transferActive = active
        com.mesh.core.InAppLogger.d("AtomController", "transferActive = $active")
    }

}
