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
                ip = prefs.getString("last_peer_ip", "") ?: ""
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

        // Автоматически делаем устройство видимым через Wi-Fi Direct
        // чтобы второй телефон мог нас найти
        scope.launch {
            delay(3000)
            wd.passiveDiscover()
            InAppLogger.d("AtomController", "auto passive discover triggered")
        }


        InAppLogger.d("AtomController", "init, myId=$myId, myName=$myName")
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
            .apply()
    }

    fun hasName(): Boolean = myName.isNotBlank()
}
