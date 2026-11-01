package com.garickbox.atom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mesh.core.InAppLogger

/**
 * B79: broadcast intents для Tasker/Automate/MacroDroid.
 * Пример:
 *   am broadcast -a com.garickbox.atom.RECEIVE_ON
 *   am broadcast -a com.garickbox.atom.RECEIVE_OFF
 *   am broadcast -a com.garickbox.atom.SEND -e peer_mac "aa:bb:cc:dd:ee:ff"
 *   am broadcast -a com.garickbox.atom.CHECK_UPDATE
 *   am broadcast -a com.garickbox.atom.PING
 *
 * Broadcast только локальные (не exported наружу) — разрешение через
 * android:exported="true" но требуется явный target package или adb.
 */
class AtomCommandReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        InAppLogger.d("AtomCmd", "action=" + action)
        when (action) {
            ACTION_RECEIVE_ON -> {
                try {
                    if (!WidgetReceiveService.isActive) {
                        WidgetReceiveService.toggle(ctx)
                        InAppLogger.d("AtomCmd", "RECEIVE_ON → toggled")
                    }
                } catch (e: Exception) {
                    InAppLogger.d("AtomCmd", "RECEIVE_ON error: " + e.message)
                }
            }
            ACTION_RECEIVE_OFF -> {
                try {
                    if (WidgetReceiveService.isActive) {
                        WidgetReceiveService.toggle(ctx)
                        InAppLogger.d("AtomCmd", "RECEIVE_OFF → toggled")
                    }
                } catch (e: Exception) {
                    InAppLogger.d("AtomCmd", "RECEIVE_OFF error: " + e.message)
                }
            }
            ACTION_SEND -> {
                val mac = intent.getStringExtra("peer_mac") ?: ""
                val name = intent.getStringExtra("peer_name") ?: "?"
                InAppLogger.d("AtomCmd", "SEND mac=" + mac)
                try {
                    val i = Intent(ctx, MainActivity::class.java).apply {
                        this.action = MainActivity.ACTION_SHORTCUT_SEND_PEER
                        putExtra("atom_peer_mac", mac)
                        putExtra("atom_peer_name", name)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(i)
                } catch (e: Exception) {
                    InAppLogger.d("AtomCmd", "SEND error: " + e.message)
                }
            }
            ACTION_CHECK_UPDATE -> {
                try {
                    val r = UpdateChecker.startAutoUpdate(ctx)
                    InAppLogger.d("AtomCmd", "CHECK_UPDATE: " + r)
                } catch (e: Exception) {
                    InAppLogger.d("AtomCmd", "CHECK_UPDATE error: " + e.message)
                }
            }
            ACTION_PING -> {
                InAppLogger.d("AtomCmd", "PING received")
            }
            // B90: broadcast intents extension — read-only, безопасно
            ACTION_STATUS -> {
                try {
                    val reply = org.json.JSONObject().apply {
                        put("build", BuildConfig.VERSION_CODE)
                        put("versionName", BuildConfig.VERSION_NAME)
                        put("device", android.os.Build.MANUFACTURER + "/" + android.os.Build.MODEL)
                        put("myName", AtomController.myName)
                        put("myId", AtomController.myId.take(8))
                        put("transferState", AtomController.transferState.value.javaClass.simpleName)
                        put("knownPeers", AtomController.getKnownP2pPeers().size)
                        put("receiveActive", WidgetReceiveService.isActive)
                    }
                    InAppLogger.d("AtomCmd", "STATUS: " + reply)
                    sendBroadcast(Intent(ACTION_STATUS_RESULT).apply {
                        putExtra("json", reply.toString())
                        setPackage(ctx.packageName)
                    })
                } catch (e: Exception) {
                    InAppLogger.d("AtomCmd", "STATUS error: " + e.message)
                }
            }
            ACTION_PEERS -> {
                try {
                    val peers = AtomController.getKnownP2pPeers()
                    val arr = org.json.JSONArray()
                    peers.forEach { p ->
                        arr.put(org.json.JSONObject().apply {
                            put("name", p.name)
                            put("mac", p.mac)
                            put("lastConnectedMs", p.lastConnectedMs)
                        })
                    }
                    InAppLogger.d("AtomCmd", "PEERS: " + peers.size)
                    sendBroadcast(Intent(ACTION_PEERS_RESULT).apply {
                        putExtra("json", arr.toString())
                        putExtra("count", peers.size)
                        setPackage(ctx.packageName)
                    })
                } catch (e: Exception) {
                    InAppLogger.d("AtomCmd", "PEERS error: " + e.message)
                }
            }
            ACTION_HISTORY -> {
                try {
                    val records = TransferHistory.list(ctx)
                    val limit = intent.getIntExtra("limit", 10)
                    val arr = org.json.JSONArray()
                    records.take(limit).forEach { r ->
                        arr.put(org.json.JSONObject().apply {
                            put("ts", r.timestamp)
                            put("peer", r.peerName)
                            put("file", r.fileName)
                            put("bytes", r.bytes)
                            put("status", r.status)
                            put("savedPath", r.savedPath)
                        })
                    }
                    InAppLogger.d("AtomCmd", "HISTORY: " + records.size + " (sent " + arr.length() + ")")
                    sendBroadcast(Intent(ACTION_HISTORY_RESULT).apply {
                        putExtra("json", arr.toString())
                        putExtra("total", records.size)
                        setPackage(ctx.packageName)
                    })
                } catch (e: Exception) {
                    InAppLogger.d("AtomCmd", "HISTORY error: " + e.message)
                }
            }
        }
    }

    companion object {
        const val ACTION_RECEIVE_ON = "com.garickbox.atom.RECEIVE_ON"
        const val ACTION_RECEIVE_OFF = "com.garickbox.atom.RECEIVE_OFF"
        const val ACTION_SEND = "com.garickbox.atom.SEND"
        const val ACTION_CHECK_UPDATE = "com.garickbox.atom.CHECK_UPDATE"
        const val ACTION_PING = "com.garickbox.atom.PING"
    }
}
