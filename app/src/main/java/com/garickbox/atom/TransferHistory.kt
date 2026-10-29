package com.garickbox.atom

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object TransferHistory {
    private const val PREFS = "atom"
    private const val KEY = "transfer_history"
    private const val MAX_ITEMS = 50

    data class Record(
        val id: String, val peerName: String, val fileName: String,
        val bytes: Long, val durationMs: Long, val status: String,
        val timestamp: Long, val savedPath: String
    )

    fun list(ctx: Context): List<Record> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(Record(
                        o.optString("id"), o.optString("peer"), o.optString("file"),
                        o.optLong("bytes"), o.optLong("dur"), o.optString("status", "success"),
                        o.optLong("ts"), o.optString("saved")
                    ))
                }
            }
        } catch (_: Exception) { emptyList() }
    }

    fun add(ctx: Context, rec: Record) {
        val current = list(ctx).toMutableList()
        current.add(0, rec)
        save(ctx, current.take(MAX_ITEMS))
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    private fun save(ctx: Context, records: List<Record>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id); put("peer", r.peerName); put("file", r.fileName)
                put("bytes", r.bytes); put("dur", r.durationMs); put("status", r.status)
                put("ts", r.timestamp); put("saved", r.savedPath)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
