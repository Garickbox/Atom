package com.garickbox.atom

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object TransferHistory {
    private val lock = Any()

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

    /** B107: import CSV → merge (skip duplicate id, cap MAX_ITEMS) */
    fun importCsv(ctx: Context, csvText: String): Int {
        val lines = csvText.split("\n").filter { it.isNotBlank() }
        if (lines.size < 2) return 0
        val header = lines[0].split(",")
        val idx = header.associate { it.trim() to header.indexOf(it) }
        val incoming = mutableListOf<Record>()
        for (i in 1 until lines.size) {
            val f = parseCsvLine(lines[i])
            if (f.size < 7) continue
            try {
                incoming.add(Record(
                    id = f[0], peerName = f[1], fileName = f[2],
                    bytes = f[3].toLongOrNull() ?: 0L,
                    status = f[4],
                    durationMs = f[5].toLongOrNull() ?: 0L,
                    timestamp = parseTimestamp(f[6]),
                    savedPath = ""
                ))
            } catch (_: Exception) {}
        }
        if (incoming.isEmpty()) return 0
        synchronized(lock) {
            val current = list(ctx).toMutableList()
            val existing = current.map { it.id }.toHashSet()
            var added = 0
            for (rec in incoming) {
                if (rec.id in existing) continue
                current.add(rec); added++
            }
            if (added > 0) {
                current.sortByDescending { it.timestamp }
                save(ctx, current.take(MAX_ITEMS))
                com.mesh.core.InAppLogger.err("TransferHistory",
                    "B107: importCsv added=$added total=${current.size}")
            }
            return added
        }
    }

    private fun parseTimestamp(f: String): Long {
        val num = f.toLongOrNull()
        if (num != null) return num
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .parse(f)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) { System.currentTimeMillis() }
    }

    /** naive CSV parse (не поддерживает multiline values) */
    private fun parseCsvLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
        }
        out.add(sb.toString())
        return out
    }

    fun add(ctx: Context, rec: Record) {
        // B101i-v3: err() вместо d() — гарантированная запись (без whitelist)
        try {
            synchronized(lock) {
                val current = list(ctx).toMutableList()
                current.add(0, rec)
                val top = current.take(MAX_ITEMS)
                save(ctx, top)
                com.mesh.core.InAppLogger.err("TransferHistory",
                    "B101i-v3: added '${rec.fileName}' status=${rec.status} total=${top.size}")
            }
        } catch (e: Throwable) {
            com.mesh.core.InAppLogger.err("TransferHistory",
                "B101i-v3: EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun clear(ctx: Context) {
        synchronized(lock) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        }
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
        // B101i: commit() вместо apply() — гарантия disk-write до OTA process-kill
        val ok = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).commit()
        if (!ok) {
            com.mesh.core.InAppLogger.err("TransferHistory",
                "B101i: commit FAILED (${records.size} records)")
        }
    }
}
