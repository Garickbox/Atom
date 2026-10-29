package com.garickbox.atom

import android.content.Context
import com.mesh.core.InAppLogger
import org.json.JSONArray
import org.json.JSONObject

object TransferHistory {

    private const val PREFS = "atom"
    private const val KEY = "transfer_history"
    private const val MAX_ITEMS = 50

    data class Record(
        val id: String,
        val peerName: String,
        val fileName: String,
        val bytes: Long,
        val durationMs: Long,
        val status: String, // "success" | "failed" | "cancelled"
        val timestamp: Long,
        val savedPath: String  // "" если failed
    )

    fun list(ctx: Context): List<Record> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(Record(
                        id = o.optString("id"),
                        peerName = o.optString("peer"),
                        fileName = o.optString("file"),
                        bytes = o.optLong("bytes"),
                        durationMs = o.optLong("dur"),
                        status = o.optString("status", "success"),
                        timestamp = o.optLong("ts"),
                        savedPath = o.optString("saved")
                    ))
                }
            }
        } catch (e: Exception) {
            InAppLogger.d("History", "read error: ${e.message}")
            emptyList()
        }
    }

    fun add(ctx: Context, rec: Record) {
        val current = list(ctx).toMutableList()
        current.add(0, rec)  // новые сверху
        val trimmed = current.take(MAX_ITEMS)
        save(ctx, trimmed)
        InAppLogger.d("History", "add: ${rec.status} ${rec.fileName} → ${rec.peerName}")
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
        InAppLogger.d("History", "cleared")
    }

    fun delete(ctx: Context, id: String) {
        val current = list(ctx).filter { it.id != id }
        save(ctx, current)
    }

    private fun save(ctx: Context, records: List<Record>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("peer", r.peerName)
                put("file", r.fileName)
                put("bytes", r.bytes)
                put("dur", r.durationMs)
                put("status", r.status)
                put("ts", r.timestamp)
                put("saved", r.savedPath)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
