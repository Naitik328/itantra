package com.sih.itantra.wifidirect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire frame is the frozen contract between the app, the LoRa firmware and the AI team —
 * once it ships, a silent change here breaks two other codebases that cannot see this one.
 * These tests pin the byte layout, the CRC variant and every rejection path, so a regression
 * shows up here rather than as a mystery on the radio link.
 */
class PacketTest {

    // ---------------------------------------------------------------------------------------
    // Byte layout — the part other teams implement against
    // ---------------------------------------------------------------------------------------

    /**
     * Golden frame. Every byte is asserted literally so the layout can be read straight off
     * this test and handed to the firmware author:
     *
     *   0x10  version 1 (hi nibble) | type NORMAL (lo nibble)
     *   0x01  src  = 1
     *   0xFF  dst  = broadcast
     *   0x03  lang = 3
     *   0x2A  seq  = 42
     *   0x02  len  = 2
     *   'O''K' payload
     *   0xDB5E CRC-16/CCITT-FALSE, big-endian, over the preceding 8 bytes
     */
    @Test
    fun `encode produces the exact documented byte layout`() {
        val frame = Packet.encode(
            type = Packet.TYPE_NORMAL,
            src = 1,
            dst = Packet.BROADCAST_DST,
            lang = 3,
            seq = 42,
            text = "OK",
        )

        val expected = byteArrayOf(
            0x10, 0x01, 0xFF.toByte(), 0x03, 0x2A, 0x02,
            'O'.code.toByte(), 'K'.code.toByte(),
            0xDB.toByte(), 0x5E,
        )
        assertArrayEquals(expected, frame)
    }

    @Test
    fun `frame size is header plus payload plus crc`() {
        val frame = Packet.encode(Packet.TYPE_NORMAL, 1, 2, 0, 0, "hello")
        assertEquals(Packet.HEADER_LEN + 5 + Packet.CRC_LEN, frame.size)
    }

    @Test
    fun `version and type share the first byte`() {
        val alert = Packet.encode(Packet.TYPE_ALERT, 1, 2, 0, 0, "x")
        assertEquals(Packet.VERSION, (alert[0].toInt() and 0xF0) ushr 4)
        assertEquals(Packet.TYPE_ALERT, alert[0].toInt() and 0x0F)
    }

    @Test
    fun `crc is written big-endian`() {
        val frame = Packet.encode(Packet.TYPE_NORMAL, 1, 2, 0, 0, "OK")
        val body = Packet.HEADER_LEN + 2
        val crc = Packet.crc16(frame, 0, body)
        assertEquals(((crc ushr 8) and 0xFF).toByte(), frame[body])
        assertEquals((crc and 0xFF).toByte(), frame[body + 1])
    }

    // ---------------------------------------------------------------------------------------
    // CRC-16/CCITT-FALSE
    // ---------------------------------------------------------------------------------------

    /** The published check value for CRC-16/CCITT-FALSE: poly 0x1021, init 0xFFFF, no reflection. */
    @Test
    fun `crc16 matches the standard check vector`() {
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0x29B1, Packet.crc16(data, 0, data.size))
    }

    @Test
    fun `crc16 honours offset and length`() {
        val padded = "xx123456789yy".toByteArray(Charsets.US_ASCII)
        assertEquals(0x29B1, Packet.crc16(padded, 2, 9))
    }

    // ---------------------------------------------------------------------------------------
    // Round trips
    // ---------------------------------------------------------------------------------------

    @Test
    fun `round trip preserves every header field and the text`() {
        val frame = Packet.encode(Packet.TYPE_ALERT, 7, 9, 5, 200, "flood warning")
        val decoded = assertDecodes(frame)

        assertEquals(Packet.VERSION, decoded.version)
        assertEquals(Packet.TYPE_ALERT, decoded.type)
        assertEquals(7, decoded.src)
        assertEquals(9, decoded.dst)
        assertEquals(5, decoded.lang)
        assertEquals(200, decoded.seq)
        assertEquals("flood warning", decoded.text)
    }

    /** src/dst/seq are unsigned on the wire; a naive `toInt()` would surface these as negatives. */
    @Test
    fun `high byte values survive as unsigned`() {
        val frame = Packet.encode(Packet.TYPE_NORMAL, 0xFE, Packet.BROADCAST_DST, 0xFF, 0xFF, "x")
        val decoded = assertDecodes(frame)

        assertEquals(0xFE, decoded.src)
        assertEquals(Packet.BROADCAST_DST, decoded.dst)
        assertEquals(0xFF, decoded.lang)
        assertEquals(0xFF, decoded.seq)
    }

    /** ACKs carry no payload — the smallest legal frame on the link. */
    @Test
    fun `empty payload encodes to a bare header and crc`() {
        val frame = Packet.encode(Packet.TYPE_ACK, 1, 2, 0, 17, "")
        assertEquals(Packet.HEADER_LEN + Packet.CRC_LEN, frame.size)

        val decoded = assertDecodes(frame)
        assertEquals(Packet.TYPE_ACK, decoded.type)
        assertEquals(17, decoded.seq)
        assertEquals("", decoded.text)
        assertEquals(0, decoded.payload.size)
    }

    @Test
    fun `non-ascii text round trips as utf-8`() {
        val text = "नमस्ते दुनिया"
        val decoded = assertDecodes(Packet.encode(Packet.TYPE_NORMAL, 1, 2, 4, 1, text))
        assertEquals(text, decoded.text)
    }

    // ---------------------------------------------------------------------------------------
    // Payload bounds
    // ---------------------------------------------------------------------------------------

    @Test
    fun `payload of exactly max size is accepted`() {
        val text = "a".repeat(Packet.MAX_PAYLOAD)
        val frame = Packet.encode(Packet.TYPE_NORMAL, 1, 2, 0, 0, text)

        assertEquals(Packet.MAX_PAYLOAD, frame[5].toInt() and 0xFF)
        assertEquals(text, assertDecodes(frame).text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `payload one byte over max is rejected`() {
        Packet.encode(Packet.TYPE_NORMAL, 1, 2, 0, 0, "a".repeat(Packet.MAX_PAYLOAD + 1))
    }

    /**
     * The limit is UTF-8 bytes, not characters. 83 Devanagari characters are only 83 chars but
     * 249 bytes, so this must be rejected even though the string looks short.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `payload limit counts utf-8 bytes not characters`() {
        Packet.encode(Packet.TYPE_NORMAL, 1, 2, 4, 0, "न".repeat(83))
    }

    // ---------------------------------------------------------------------------------------
    // Rejection paths — a corrupt frame must be dropped, never shown as garbage
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a single flipped payload bit fails the crc`() {
        val frame = Packet.encode(Packet.TYPE_NORMAL, 1, 2, 0, 0, "hello")
        frame[Packet.HEADER_LEN] = (frame[Packet.HEADER_LEN].toInt() xor 0x01).toByte()
        assertNull(Packet.decode(frame))
    }

    @Test
    fun `a flipped header bit fails the crc`() {
        val frame = Packet.encode(Packet.TYPE_NORMAL, 1, 2, 0, 0, "hello")
        frame[3] = (frame[3].toInt() xor 0x08).toByte() // corrupt lang
        assertNull(Packet.decode(frame))
    }

    @Test
    fun `a corrupted crc trailer is rejected`() {
        val frame = Packet.encode(Packet.TYPE_NORMAL, 1, 2, 0, 0, "hello")
        frame[frame.lastIndex] = (frame[frame.lastIndex].toInt() xor 0xFF).toByte()
        assertNull(Packet.decode(frame))
    }

    @Test
    fun `a truncated frame is rejected`() {
        val frame = Packet.encode(Packet.TYPE_NORMAL, 1, 2, 0, 0, "hello")
        assertNull(Packet.decode(frame.copyOf(frame.size - 1)))
    }

    @Test
    fun `a frame with trailing junk is rejected`() {
        val frame = Packet.encode(Packet.TYPE_NORMAL, 1, 2, 0, 0, "hello")
        assertNull(Packet.decode(frame + byteArrayOf(0x00)))
    }

    /** len must agree with the buffer, otherwise a reader could be walked off the end. */
    @Test
    fun `a frame whose len field disagrees with its size is rejected`() {
        val frame = Packet.encode(Packet.TYPE_NORMAL, 1, 2, 0, 0, "hello")
        frame[5] = 99
        assertNull(Packet.decode(frame))
    }

    @Test
    fun `buffers shorter than a header are rejected`() {
        assertNull(Packet.decode(ByteArray(0)))
        assertNull(Packet.decode(ByteArray(Packet.HEADER_LEN)))
        assertNull(Packet.decode(ByteArray(Packet.HEADER_LEN + Packet.CRC_LEN - 1)))
    }

    /**
     * A stream reader frames packets by trusting byte 5 before it has seen the CRC. Every
     * possible len value must therefore describe a buffer size that decode() also accepts,
     * so that a truthful sender never gets its frames dropped.
     */
    @Test
    fun `every payload length up to the maximum round trips`() {
        for (len in 0..Packet.MAX_PAYLOAD) {
            val text = "a".repeat(len)
            val frame = Packet.encode(Packet.TYPE_NORMAL, 1, 2, 0, len and 0xFF, text)

            assertEquals(Packet.HEADER_LEN + len + Packet.CRC_LEN, frame.size)
            assertEquals(len, frame[5].toInt() and 0xFF)

            val decoded = Packet.decode(frame)
            assertNotNull("len=$len failed to decode", decoded)
            assertEquals(text, decoded!!.text)
        }
    }

    /** Types 3..15 are reserved; decode must surface them intact for the caller to ignore. */
    @Test
    fun `reserved types decode rather than throw`() {
        val decoded = assertDecodes(Packet.encode(15, 1, 2, 0, 0, "future"))
        assertEquals(15, decoded.type)
    }

    private fun assertDecodes(frame: ByteArray): Packet.Frame {
        val decoded = Packet.decode(frame)
        assertTrue("expected a valid frame, got null", decoded != null)
        return decoded!!
    }
}
