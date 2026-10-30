package com.garickbox.atom

sealed class TransferState {
    object Idle : TransferState()
    data class Running(
        val transferId: String,
        val peerName: String,
        val fileName: String,
        val bytesSent: Long,
        val bytesTotal: Long,
        val bytesPerSecond: Long = 0L,
        val isReceiver: Boolean = false,
        val fileIndex: Int = 0,
        val fileCount: Int = 0
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
    data class UpdatePending(
        val apkUri: String,
        val versionCode: Int,
        val peerName: String,
        val fileName: String,
        val bytesTotal: Long
    ) : TransferState()
}
