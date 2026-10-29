package com.mesh.core

import org.json.JSONObject

data class HelloPayload(
    val peerId: String,
    val name: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("peer_id", peerId)
        put("name", name)
    }

    companion object {
        fun fromJson(o: JSONObject): HelloPayload = HelloPayload(
            o.optString("peer_id", ""),
            o.optString("name", "?")
        )
    }
}
