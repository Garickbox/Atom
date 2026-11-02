package com.mesh.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B94: JVM unit-тесты mesh protocol. Ловят баги encode/decode до билда.
 */
class MeshPacketTest {

    @Test
    fun `encode-decode roundtrip HELLO`() {
        val payload = HelloPayload("peer-123", "Test Device").toJson()
        val original = MeshPacket(PacketType.HELLO, payload)
        val bytes = original.encode()
        val decoded = MeshPacket.decode(bytes, bytes.size)

        assertNotNull(decoded)
        assertEquals(PacketType.HELLO, decoded!!.type)
        assertEquals("peer-123", decoded.payload.optString("peer_id"))
        assertEquals("Test Device", decoded.payload.optString("name"))
    }

    @Test
    fun `encode-decode roundtrip FILE_OFFER full payload`() {
        val offer = JSONObject().apply {
            put("tid", "abc-123")
            put("hash", "deadbeef")
            put("name", "test.jpg")
            put("size", 1048576L)
            put("peer_id", "peer-1")
            put("peer_name", "Redmi")
            put("resume_offset", 512L)
            put("relative_path", "Photos/2024/")
        }
        val original = MeshPacket(PacketType.FILE_OFFER, offer)
        val bytes = original.encode()
        val decoded = MeshPacket.decode(bytes, bytes.size)

        assertNotNull(decoded)
        assertEquals(PacketType.FILE_OFFER, decoded!!.type)
        assertEquals("abc-123", decoded.payload.optString("tid"))
        assertEquals("test.jpg", decoded.payload.optString("name"))
        assertEquals(1048576L, decoded.payload.optLong("size"))
        assertEquals(512L, decoded.payload.optLong("resume_offset"))
        assertEquals("Photos/2024/", decoded.payload.optString("relative_path"))
    }

    @Test
    fun `encode-decode roundtrip FILE_ACCEPT`() {
        val payload = JSONObject().apply {
            put("tid", "xyz-789")
            put("offset", 2048L)
        }
        val bytes = MeshPacket(PacketType.FILE_ACCEPT, payload).encode()
        val decoded = MeshPacket.decode(bytes, bytes.size)
        assertEquals(PacketType.FILE_ACCEPT, decoded!!.type)
        assertEquals("xyz-789", decoded.payload.optString("tid"))
        assertEquals(2048L, decoded.payload.optLong("offset"))
    }

    @Test
    fun `decode returns null on malformed json`() {
        val bad = "not a json".toByteArray()
        assertNull(MeshPacket.decode(bad, bad.size))
    }

    @Test
    fun `decode returns null on unknown packet type`() {
        val bad = """{"t":"UNKNOWN_TYPE","d":{}}""".toByteArray()
        assertNull(MeshPacket.decode(bad, bad.size))
    }

    @Test
    fun `decode returns empty payload on missing d field`() {
        val bytes = """{"t":"HELLO"}""".toByteArray()
        val decoded = MeshPacket.decode(bytes, bytes.size)
        assertNotNull(decoded)
        assertEquals(PacketType.HELLO, decoded!!.type)
        assertTrue(decoded.payload.length() == 0)
    }

    @Test
    fun `all packet types encode successfully`() {
        PacketType.values().forEach { type ->
            val bytes = MeshPacket(type, JSONObject().put("test", "value")).encode()
            assertTrue("type $type failed", bytes.isNotEmpty())
            val decoded = MeshPacket.decode(bytes, bytes.size)
            assertEquals(type, decoded?.type)
        }
    }
}
