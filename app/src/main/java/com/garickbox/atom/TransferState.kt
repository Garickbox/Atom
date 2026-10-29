package com.garickbox.atom

sealed class TransferState {
    object Idle : TransferState()
    data class Running(
        val transferId: String,
        val peerName: String,
        val fileName: String,
        val bytesSent: Long,
        val bytesTotal: Long
    ) : TransferState()
    data class Failed(
        val transferId: String,
        val peerName: String,
        val fileName: String,
        val reason: String,
        val lastIp: String,
        val lastHash: String
    ) : TransferState()
    data class Success(
        val peerName: String,
        val fileCount: Int,
        val bytesTotal: Long,
        val durationMs: Long
    ) : TransferState()
}
