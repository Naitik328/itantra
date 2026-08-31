package com.sih.itantra.audio

/**
 * Fixed-capacity circular buffer of PCM samples that always holds the *most recent*
 * [capacitySamples], overwriting the oldest.
 *
 * Its job is pre-roll. A voice-activity gate can only decide a frame was speech after it has
 * seen it, so by the time hands-free capture says "speech started" the first syllable is
 * already in the past. Keeping a few hundred milliseconds of history means the utterance handed
 * to the recogniser begins slightly *before* the onset, instead of clipping the leading
 * consonant and costing word-error rate on every single sentence.
 *
 * Safe to write from the capture thread and read from another; the whole class is trivially
 * cheap to lock at 16 kHz mono.
 */
class PcmRingBuffer(val capacitySamples: Int) {

    init {
        require(capacitySamples > 0) { "capacity must be positive, was $capacitySamples" }
    }

    private val buffer = ShortArray(capacitySamples)
    private val lock = Any()

    /** Index the next sample will be written to. */
    private var writeIndex = 0

    /** How many samples are currently valid, saturating at [capacitySamples]. */
    private var filled = 0

    val available: Int get() = synchronized(lock) { filled }

    val durationMs: Long get() = AudioSpec.millisOf(available)

    fun write(source: ShortArray, count: Int = source.size) {
        require(count >= 0 && count <= source.size) { "count $count out of range for ${source.size}" }
        if (count == 0) return

        synchronized(lock) {
            if (count >= capacitySamples) {
                // The write alone overflows the buffer: only its newest tail can survive.
                System.arraycopy(source, count - capacitySamples, buffer, 0, capacitySamples)
                writeIndex = 0
                filled = capacitySamples
                return
            }
            val untilEnd = minOf(count, capacitySamples - writeIndex)
            System.arraycopy(source, 0, buffer, writeIndex, untilEnd)
            val wrapped = count - untilEnd
            if (wrapped > 0) {
                System.arraycopy(source, untilEnd, buffer, 0, wrapped)
            }
            writeIndex = (writeIndex + count) % capacitySamples
            filled = minOf(filled + count, capacitySamples)
        }
    }

    /** Contents in chronological order, oldest first. Does not modify the buffer. */
    fun snapshot(): ShortArray = synchronized(lock) {
        val out = ShortArray(filled)
        if (filled == 0) return out
        val start = (writeIndex - filled + capacitySamples) % capacitySamples
        val untilEnd = minOf(filled, capacitySamples - start)
        System.arraycopy(buffer, start, out, 0, untilEnd)
        if (filled > untilEnd) {
            System.arraycopy(buffer, 0, out, untilEnd, filled - untilEnd)
        }
        return out
    }

    /** [snapshot] followed by [clear], as one atomic step. */
    fun drain(): ShortArray = synchronized(lock) {
        val out = snapshot()
        writeIndex = 0
        filled = 0
        return out
    }

    fun clear() = synchronized(lock) {
        writeIndex = 0
        filled = 0
    }
}
