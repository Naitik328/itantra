package com.sih.itantra.audio

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * These files are handed to the AI team and opened in Python. A header this code gets subtly
 * wrong would show up as a mysterious WER regression rather than as an error, so the bytes are
 * asserted directly.
 */
class WavWriterTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `header declares 16 kHz mono 16-bit pcm`() {
        val file = write(ShortArray(0))
        val bytes = file.readBytes()

        assertEquals(WavWriter.HEADER_BYTES, bytes.size)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals(16, intLe(bytes, 16))          // PCM fmt chunk length
        assertEquals(1, shortLe(bytes, 20))         // format 1 = uncompressed
        assertEquals(1, shortLe(bytes, 22))         // mono
        assertEquals(16_000, intLe(bytes, 24))      // sample rate
        assertEquals(32_000, intLe(bytes, 28))      // byte rate
        assertEquals(2, shortLe(bytes, 32))         // block align
        assertEquals(16, shortLe(bytes, 34))        // bits per sample
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
    }

    @Test
    fun `close patches both length fields`() {
        val samples = ShortArray(100) { it.toShort() }
        val bytes = write(samples).readBytes()

        assertEquals(WavWriter.HEADER_BYTES + 200, bytes.size)
        assertEquals(36 + 200, intLe(bytes, 4))   // RIFF size
        assertEquals(200, intLe(bytes, 40))       // data size
    }

    @Test
    fun `an empty recording still produces a valid zero-length file`() {
        val bytes = write(ShortArray(0)).readBytes()

        assertEquals(36, intLe(bytes, 4))
        assertEquals(0, intLe(bytes, 40))
    }

    @Test
    fun `samples are stored little-endian and survive a round trip`() {
        val samples = shortArrayOf(0, 1, -1, 256, Short.MAX_VALUE, Short.MIN_VALUE)
        val bytes = write(samples).readBytes()

        val decoded = ShortArray(samples.size) { i ->
            val lo = bytes[WavWriter.HEADER_BYTES + i * 2].toInt() and 0xFF
            val hi = bytes[WavWriter.HEADER_BYTES + i * 2 + 1].toInt() and 0xFF
            ((hi shl 8) or lo).toShort()
        }
        assertEquals(samples.toList(), decoded.toList())

        // Spot-check the byte order explicitly rather than only through the decoder.
        assertEquals(0x00, bytes[WavWriter.HEADER_BYTES + 6].toInt() and 0xFF) // 256 low byte
        assertEquals(0x01, bytes[WavWriter.HEADER_BYTES + 7].toInt() and 0xFF) // 256 high byte
    }

    @Test
    fun `successive writes append`() {
        val file = temp.newFile("append.wav")
        WavWriter(file).use {
            it.write(shortArrayOf(1, 2))
            it.write(shortArrayOf(3, 4, 5))
        }
        assertEquals(10, intLe(file.readBytes(), 40))
    }

    @Test
    fun `partial writes honour the count argument`() {
        val file = temp.newFile("partial.wav")
        WavWriter(file).use { it.write(shortArrayOf(1, 2, 3, 4), count = 2) }

        assertEquals(4, intLe(file.readBytes(), 40))
    }

    @Test
    fun `reported duration matches the sample rate`() {
        val file = temp.newFile("duration.wav")
        val writer = WavWriter(file)
        writer.use { it.write(ShortArray(AudioSpec.samplesFor(250L))) }

        assertEquals(250L, writer.durationMs)
    }

    @Test
    fun `closing twice is harmless`() {
        val file = temp.newFile("twice.wav")
        val writer = WavWriter(file)
        writer.write(shortArrayOf(1, 2, 3))
        writer.close()
        writer.close()

        assertEquals(6, intLe(file.readBytes(), 40))
    }

    @Test(expected = IllegalStateException::class)
    fun `writing after close is rejected`() {
        val writer = WavWriter(temp.newFile("closed.wav"))
        writer.close()
        writer.write(shortArrayOf(1))
    }

    private fun write(samples: ShortArray): File {
        val file = temp.newFile("test-${samples.size}.wav")
        WavWriter(file).use { it.write(samples) }
        return file
    }

    private fun intLe(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or
            ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun shortLe(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
}
