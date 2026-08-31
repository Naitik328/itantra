package com.sih.itantra.wifidirect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketSplitTest {

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test
    fun `short text stays a single chunk`() {
        val chunks = Packet.splitUtf8("नमस्ते", Packet.MAX_PAYLOAD)
        assertEquals(listOf("नमस्ते"), chunks)
    }

    @Test
    fun `empty text yields no chunks`() {
        assertTrue(Packet.splitUtf8("", Packet.MAX_PAYLOAD).isEmpty())
    }

    @Test
    fun `every chunk fits the byte cap`() {
        val sentence = "यह एक लंबा हिंदी वाक्य है जिसे कई फ्रेम में बांटना होगा ताकि रेडियो लिंक पर भेजा जा सके "
        val long = sentence.repeat(6)
        val cap = 60
        val chunks = Packet.splitUtf8(long, cap)
        assertTrue("expected multiple frames", chunks.size > 1)
        for (c in chunks) {
            assertTrue("chunk '$c' is ${utf8(c)} B, over cap $cap", utf8(c) <= cap)
        }
    }

    @Test
    fun `reassembling the chunks preserves the words`() {
        val text = "the quick brown fox jumps over the lazy dog again and again"
        val chunks = Packet.splitUtf8(text, 12)
        // Word order and content survive; only the whitespace at split points may differ.
        assertEquals(text.split(" "), chunks.joinToString(" ").split(" "))
    }

    @Test
    fun `a single word longer than the cap is hard-split, never dropped`() {
        val word = "a".repeat(500) // no spaces to break on
        val chunks = Packet.splitUtf8(word, Packet.MAX_PAYLOAD)
        assertTrue(chunks.size > 1)
        for (c in chunks) assertTrue(utf8(c) <= Packet.MAX_PAYLOAD)
        assertEquals(500, chunks.sumOf { it.length })
    }

    @Test
    fun `multibyte characters are never cut in half`() {
        // 40 Devanagari chars ~ 120 bytes; a cap of 50 forces splits mid-run.
        val text = "क".repeat(40)
        val chunks = Packet.splitUtf8(text, 50)
        // If any chunk had a partial character, decoding it back would not round-trip.
        for (c in chunks) {
            assertEquals(c, String(c.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
        assertEquals(40, chunks.sumOf { it.length })
    }
}
