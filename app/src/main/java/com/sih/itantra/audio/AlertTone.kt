package com.sih.itantra.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * Synthesises the ALERT attention tone as PCM.
 *
 * An ALERT frame carries text, and the receiving phone will eventually speak it. But an
 * emergency message that begins with a synthetic voice reading a sentence buries the most
 * important half-second of the interaction. A rising two-tone burst in front of the speech is
 * instantly recognisable as an alarm, and it also gives the loudspeaker time to physically ramp
 * up before the first word.
 *
 * Generated rather than shipped as an asset: a few lines of arithmetic beats a WAV in the APK,
 * and it stays in perfect sync with [AudioSpec] if the sample rate ever changes.
 */
object AlertTone {

    private const val LOW_HZ = 740.0    // F#5
    private const val HIGH_HZ = 988.0   // B5
    private const val BEEP_MS = 180L
    private const val GAP_MS = 90L
    private const val AMPLITUDE = 0.55

    /** @param repeats how many low-high pairs to sound. */
    fun generate(repeats: Int = 2): ShortArray {
        val beep = AudioSpec.samplesFor(BEEP_MS)
        val gap = AudioSpec.samplesFor(GAP_MS)
        val out = ShortArray(repeats * 2 * (beep + gap))

        var offset = 0
        repeat(repeats) {
            offset = writeTone(out, offset, LOW_HZ, beep) + gap
            offset = writeTone(out, offset, HIGH_HZ, beep) + gap
        }
        return out
    }

    private fun writeTone(out: ShortArray, offset: Int, hz: Double, samples: Int): Int {
        val step = 2.0 * PI * hz / AudioSpec.SAMPLE_RATE_HZ
        // Short linear ramps at both ends; a square-edged tone burst clicks audibly and can
        // distort a small phone speaker driven at full alarm volume.
        val ramp = AudioSpec.samplesFor(8L).coerceAtMost(samples / 2)
        for (i in 0 until samples) {
            val index = offset + i
            if (index >= out.size) return out.size
            val envelope = when {
                i < ramp -> i.toDouble() / ramp
                i >= samples - ramp -> (samples - i).toDouble() / ramp
                else -> 1.0
            }
            out[index] = (sin(step * i) * AMPLITUDE * envelope * Short.MAX_VALUE).toInt().toShort()
        }
        return offset + samples
    }
}
