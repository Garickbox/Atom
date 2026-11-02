package com.garickbox.atom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B94: JVM unit-тесты TransferState transitions.
 */
class TransferStateTest {

    @Test
    fun `Idle is singleton`() {
        assertTrue(TransferState.Idle === TransferState.Idle)
    }

    @Test
    fun `Running data class equality`() {
        val a = TransferState.Running(
            transferId = "t1", peerName = "Redmi", fileName = "x.jpg",
            bytesSent = 100, bytesTotal = 1000
        )
        val b = TransferState.Running(
            transferId = "t1", peerName = "Redmi", fileName = "x.jpg",
            bytesSent = 100, bytesTotal = 1000
        )
        assertEquals(a, b)
    }

    @Test
    fun `Running different progress NOT equal`() {
        val a = TransferState.Running(
            transferId = "t1", peerName = "Redmi", fileName = "x.jpg",
            bytesSent = 100, bytesTotal = 1000
        )
        val b = a.copy(bytesSent = 200)
        assertNotEquals(a, b)
    }

    @Test
    fun `Running isReceiver default false`() {
        val r = TransferState.Running(
            "t", "p", "f", 0, 100
        )
        assertEquals(false, r.isReceiver)
        assertEquals(0, r.fileIndex)
        assertEquals(0, r.fileCount)
    }

    @Test
    fun `Paused holds position`() {
        val p = TransferState.Paused(
            transferId = "t1", peerName = "Redmi", fileName = "x.jpg",
            bytesSent = 500, bytesTotal = 1000, fileIndex = 2, fileCount = 5
        )
        assertEquals(500L, p.bytesSent)
        assertEquals(2, p.fileIndex)
        assertEquals(5, p.fileCount)
    }

    @Test
    fun `Failed includes reason`() {
        val f = TransferState.Failed("t1", "Redmi", "x.jpg", "timeout", "192.168.1.5", "hash")
        assertEquals("timeout", f.reason)
        assertEquals("192.168.1.5", f.lastIp)
    }

    @Test
    fun `Success carries count and bytes`() {
        val s = TransferState.Success("Redmi", fileCount = 3, bytesTotal = 5_000_000, durationMs = 1000)
        assertEquals(3, s.fileCount)
        assertEquals(5_000_000L, s.bytesTotal)
    }

    @Test
    fun `UpdatePending holds apk uri and version`() {
        val u = TransferState.UpdatePending(
            apkUri = "content://...", versionCode = 235, peerName = "Redmi",
            fileName = "Atom.apk", bytesTotal = 8_000_000
        )
        assertEquals(235, u.versionCode)
        assertEquals("Atom.apk", u.fileName)
    }

    @Test
    fun `sealed class when exhaustive`() {
        // Compile-time check: if new subclass added, this fails to compile
        val states: List<TransferState> = listOf(
            TransferState.Idle,
            TransferState.Running("t", "p", "f", 0, 100),
            TransferState.Paused("t", "p", "f", 0, 100),
            TransferState.Failed("t", "p", "f", "r", "", ""),
            TransferState.Success("p", 1, 100, 100),
            TransferState.UpdatePending("u", 1, "p", "f", 100)
        )
        states.forEach { s ->
            val name = when (s) {
                is TransferState.Idle -> "idle"
                is TransferState.Running -> "running"
                is TransferState.Paused -> "paused"
                is TransferState.Failed -> "failed"
                is TransferState.Success -> "success"
                is TransferState.UpdatePending -> "update_pending"
            }
            assertTrue(name.isNotEmpty())
        }
    }
}
