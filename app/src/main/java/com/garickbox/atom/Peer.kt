package com.garickbox.atom

data class Peer(
    val id: String,
    val name: String,
    val ip: String,
    val mac: String = "",
    val lastSeenMs: Long = System.currentTimeMillis()
)
