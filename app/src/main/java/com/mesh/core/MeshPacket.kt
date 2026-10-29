package com.mesh.core

import org.json.JSONObject

data class MeshPacket(val type: PacketType, val payload: JSONObject) {

    fun encode(): ByteArray {
        val root = JSONObject()
        root.put("t", type.name)
        root.put("d", payload)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun decode(bytes: ByteArray, len: Int): MeshPacket? {
            return try {
                val json = JSONObject(String(bytes, 0, len, Charsets.UTF_8))
                val t = PacketType.valueOf(json.getString("t"))
                MeshPacket(t, json.optJSONObject("d") ?: JSONObject())
            } catch (_: Exception) { null }
        }
    }
}
