package com.garickbox.atom

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.mesh.core.InAppLogger

class NfcHceService : HostApduService() {
    companion object {
        private const val AID_HEX = "F041544F4D"
        private const val AID_LEN = 6
        private val STATUS_OK = byteArrayOf(0x90.toByte(), 0x00)
        private val STATUS_NF = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        @Volatile var currentUri: String = ""
            private set
        @Volatile var active: Boolean = false
            private set
        fun setUri(uri: String) { currentUri = uri; active = true }
        fun clear() { active = false; currentUri = "" }
        val lastApdus = java.util.concurrent.CopyOnWriteArrayList<String>()
    }
    private var selectedFile: Int = 0
    private var ndefMessage: ByteArray = ByteArray(0)

    override fun processCommandApdu(apdu: ByteArray?, extras: Bundle?): ByteArray {
        val result = processApdu(apdu)
        if (apdu != null && apdu.size >= 4) {
            try {
                val hex = apdu.take(12).joinToString("") { "%02X".format(it) }
                val sw = if (result.size >= 2)
                    String.format("%02X%02X", result[result.size-2], result[result.size-1])
                    else "??"
                lastApdus.add("$hex sw=$sw")
                while (lastApdus.size > 5) lastApdus.removeAt(0)
            } catch (_: Exception) {}
        }
        return result
    }

    private fun processApdu(apdu: ByteArray?): ByteArray {
        if (apdu == null || apdu.size < 4) return STATUS_NF
        if (!active) return STATUS_NF
        val cla = apdu[0].toInt() and 0xFF
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        InAppLogger.d("NfcHce", "APDU cla=$cla ins=$ins p1=$p1 p2=$p2")
        if (cla == 0x00 && ins == 0xA4 && p1 == 0x04) return handleSelectAid(apdu)
        if (cla == 0x00 && ins == 0xA4 && p1 == 0x00) {
            if (apdu.size < 7) return STATUS_NF
            val fid = ((apdu[5].toInt() and 0xFF) shl 8) or (apdu[6].toInt() and 0xFF)
            return handleSelectFile(fid)
        }
        if (cla == 0x00 && ins == 0xB0) {
            val off = (p1 shl 8) or p2
            val le = if (apdu.size > 4) apdu[4].toInt() and 0xFF else 0
            return handleRead(off, if (le == 0) 256 else le)
        }
        return STATUS_NF
    }

    private fun handleSelectAid(apdu: ByteArray): ByteArray {
        if (apdu.size < 5 + AID_LEN) return STATUS_NF
        val len = apdu[4].toInt() and 0xFF
        if (len != AID_LEN) return STATUS_NF
        val sb = StringBuilder()
        for (i in 0 until AID_LEN) sb.append(String.format("%02X", apdu[5 + i]))
        if (!sb.toString().equals(AID_HEX, ignoreCase = true)) return STATUS_NF
        selectedFile = 0
        buildNdef()
        return STATUS_OK
    }

    private fun handleSelectFile(fid: Int): ByteArray = when (fid) {
        0xE103 -> { selectedFile = 1; STATUS_OK }
        0xE104 -> { selectedFile = 2; STATUS_OK }
        else -> STATUS_NF
    }

    private fun handleRead(off: Int, le: Int): ByteArray {
        val data: ByteArray = when (selectedFile) {
            1 -> buildCc()
            2 -> {
                val buf = ByteArray(2 + ndefMessage.size)
                buf[0] = ((ndefMessage.size shr 8) and 0xFF).toByte()
                buf[1] = (ndefMessage.size and 0xFF).toByte()
                System.arraycopy(ndefMessage, 0, buf, 2, ndefMessage.size)
                buf
            }
            else -> return STATUS_NF
        }
        if (off > data.size) return STATUS_NF
        val end = minOf(off + le, data.size)
        return data.copyOfRange(off, end) + STATUS_OK
    }

    private fun buildCc(): ByteArray {
        val mlen = if (ndefMessage.isEmpty()) 0 else ndefMessage.size + 2
        val cc = ByteArray(15)
        cc[0] = 0x00; cc[1] = 0x0F
        cc[2] = 0x20
        cc[3] = 0x00; cc[4] = 0x3B
        cc[5] = 0x00; cc[6] = 0x34
        cc[7] = 0x04; cc[8] = 0x06
        cc[9] = 0xE1.toByte(); cc[10] = 0x04
        cc[11] = ((mlen shr 8) and 0xFF).toByte()
        cc[12] = (mlen and 0xFF).toByte()
        cc[13] = 0x00; cc[14] = 0xFF.toByte()
        return cc
    }

    private fun buildNdef() {
        val uri = currentUri
        if (uri.isBlank()) { ndefMessage = ByteArray(0); return }
        val ub = uri.toByteArray(Charsets.UTF_8)
        if (ub.size > 250) { ndefMessage = ByteArray(0); return }
        val rec = ByteArray(5 + ub.size)
        rec[0] = 0xD1.toByte()
        rec[1] = 0x01
        rec[2] = (1 + ub.size).toByte()
        rec[3] = 'U'.code.toByte()
        rec[4] = 0x00
        System.arraycopy(ub, 0, rec, 5, ub.size)
        ndefMessage = rec
        InAppLogger.d("NfcHce", "NDEF uri=$uri len=${rec.size}")
    }

    override fun onDeactivated(reason: Int) {
        InAppLogger.d("NfcHce", "onDeactivated $reason")
        selectedFile = 0
    }
}
