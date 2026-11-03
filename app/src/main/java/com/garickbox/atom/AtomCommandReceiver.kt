package com.garickbox.atom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mesh.core.InAppLogger

/**
 * B90: broadcast intents для Tasker/Automate/MacroDroid.
 *
 * adb:
 *   am broadcast -a com.garickbox.atom.RECEIVE_ON
 *   am broadcast -a com.garickbox.atom.STATUS
 *   am broadcast -a com.garickbox.atom.PEERS
 *   am broadcast -a com.garickbox.atom.HISTORY -e limit 5
 *
 * Результат read-only в *_RESULT broadcast (setPackage = только внутри app):
 *   STATUS_RESULT / PEERS_RESULT / HISTORY_RESULT, extra "json".
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
                if (mac.isBlank()) {
                    try {
                        val i = Intent(ctx, MainActivity::class.java).apply {
                            this.action = MainActivity.ACTION_SHORTCUT_FIND
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(i)
                        InAppLogger.d("AtomCmd", "SEND: empty mac → open picker")
                    } catch (e: Exception) {
                        InAppLogger.err("AtomCmd", "SEND empty: ${e.message}")
                    }
                    return
                }
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
            ACTION_SEND_URI -> {
                try {
                    val uriStr = intent.getStringExtra("uri") ?: ""
                    if (uriStr.isBlank()) {
                        InAppLogger.err("AtomCmd", "SEND_URI: empty uri")
                    } else {
                        val last = AtomController.lastPeer
                        if (last == null || last.ip.isBlank()) {
                            InAppLogger.err("AtomCmd", "SEND_URI: no last peer")
                        } else {
                            FileSenderService.startTransfer(ctx, last,
                                listOf(android.net.Uri.parse(uriStr)))
                            InAppLogger.d("AtomCmd", "SEND_URI → ${last.name} $uriStr")
                        }
                    }
                } catch (e: Exception) {
                    InAppLogger.err("AtomCmd", "SEND_URI error: ${e.message}")
                }
            }
            ACTION_SCHEDULE_RECEIVE -> {
                try {
                    val on = intent.getBooleanExtra("on", true)
                    if (on && !WidgetReceiveService.isActive) WidgetReceiveService.toggle(ctx)
                    else if (!on && WidgetReceiveService.isActive) WidgetReceiveService.toggle(ctx)
                    InAppLogger.d("AtomCmd", "SCHEDULE_RECEIVE on=$on")
                } catch (e: Exception) {
                    InAppLogger.err("AtomCmd", "SCHEDULE_RECEIVE error: ${e.message}")
                }
            }
            ACTION_SET_VERBOSE -> {
                try {
                    val on = intent.getBooleanExtra("on", true)
                    com.mesh.core.InAppLogger.setVerbose(on)
                    InAppLogger.d("AtomCmd", "SET_VERBOSE on=$on")
                    val out = Intent(ACTION_SET_VERBOSE_RESULT).apply {
                        putExtra("verbose", on)
                    }
                    ctx.sendBroadcast(out)
                } catch (e: Exception) {
                    InAppLogger.d("AtomCmd", "SET_VERBOSE error: " + e.message)
                }
            }
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
                    // B91: без setPackage — Tasker/Automate ловят через Intent Received
                    val out = Intent(ACTION_STATUS_RESULT).apply {
                        putExtra("json", reply.toString())
                    }
                    ctx.sendBroadcast(out)
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
                    // B91: без setPackage
                    val out = Intent(ACTION_PEERS_RESULT).apply {
                        putExtra("json", arr.toString())
                        putExtra("count", peers.size)
                    }
                    ctx.sendBroadcast(out)
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
                    // B91: без setPackage
                    val out = Intent(ACTION_HISTORY_RESULT).apply {
                        putExtra("json", arr.toString())
                        putExtra("total", records.size)
                    }
                    ctx.sendBroadcast(out)
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
        const val ACTION_STATUS = "com.garickbox.atom.STATUS"
        const val ACTION_PEERS = "com.garickbox.atom.PEERS"
        const val ACTION_HISTORY = "com.garickbox.atom.HISTORY"
        const val ACTION_SET_VERBOSE = "com.garickbox.atom.SET_VERBOSE"
        const val ACTION_SEND_URI = "com.garickbox.atom.SEND_URI"
        const val ACTION_SCHEDULE_RECEIVE = "com.garickbox.atom.SCHEDULE_RECEIVE"
        const val ACTION_SET_VERBOSE_RESULT = "com.garickbox.atom.SET_VERBOSE_RESULT"
        const val ACTION_STATUS_RESULT = "com.garickbox.atom.STATUS_RESULT"
        const val ACTION_PEERS_RESULT = "com.garickbox.atom.PEERS_RESULT"
        const val ACTION_HISTORY_RESULT = "com.garickbox.atom.HISTORY_RESULT"
    }
}
