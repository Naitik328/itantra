package com.sih.itantra.audio

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Streams 16-bit mono PCM into a canonical 44-byte-header RIFF/WAVE file.
 *
 * This exists for the AI team, not for the app. Word-error rate can only be measured against
 * real recordings from the real handsets in real conditions, so every captured utterance can be
 * dumped here and pulled off the device with `adb pull`. Files land in the app's external files
 * directory, which needs no storage permission on any supported API level.
 *
 * The header carries the total length, which isn't known until the last sample is written, so
 * a placeholder goes down first and [close] seeks back to patch the two size fields. A file
 * whose writer was killed before [close] is still readable by most tools — it just reports a
 * zero length.
 */
class WavWriter(val file: File) : Closeable {

    private val out = BufferedOutputStream(FileOutputStream(file))
    private var dataBytes = 0
    private var closed = false

    val bytesWritten: Int get() = dataBytes
    val durationMs: Long get() = AudioSpec.millisOf(dataBytes / AudioSpec.BYTES_PER_SAMPLE)

    init {
        out.write(header(dataBytes = 0))
    }

    fun write(samples: ShortArray, count: Int = samples.size) {
        require(count >= 0 && count <= samples.size) { "count $count out of range for ${samples.size}" }
        check(!closed) { "writer is closed" }
        if (count == 0) return

        val bytes = ByteArray(count * AudioSpec.BYTES_PER_SAMPLE)
        for (i in 0 until count) {
            val s = samples[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()          // little-endian, low byte first
            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        out.write(bytes)
        dataBytes += bytes.size
    }

    override fun close() {
        if (closed) return
        closed = true
        out.flush()
        out.close()

        // Patch the two length fields now that the total is known.
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(4)
            raf.write(intLe(36 + dataBytes))   // RIFF chunk size
            raf.seek(40)
            raf.write(intLe(dataBytes))        // data chunk size
        }
    }

    private fun header(dataBytes: Int): ByteArray {
        val byteRate = AudioSpec.SAMPLE_RATE_HZ * AudioSpec.CHANNEL_COUNT * AudioSpec.BYTES_PER_SAMPLE
        val blockAlign = AudioSpec.CHANNEL_COUNT * AudioSpec.BYTES_PER_SAMPLE
        return "RIFF".toByteArray(Charsets.US_ASCII) +
            intLe(36 + dataBytes) +
            "WAVE".toByteArray(Charsets.US_ASCII) +
            "fmt ".toByteArray(Charsets.US_ASCII) +
            intLe(16) +                                   // PCM fmt chunk size
            shortLe(1) +                                  // format 1 = uncompressed PCM
            shortLe(AudioSpec.CHANNEL_COUNT) +
            intLe(AudioSpec.SAMPLE_RATE_HZ) +
            intLe(byteRate) +
            shortLe(blockAlign) +
            shortLe(AudioSpec.BITS_PER_SAMPLE) +
            "data".toByteArray(Charsets.US_ASCII) +
            intLe(dataBytes)
    }

    private fun intLe(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun shortLe(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
    )

    companion object {
        const val HEADER_BYTES = 44
    }
}
