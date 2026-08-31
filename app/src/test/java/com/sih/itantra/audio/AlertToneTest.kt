package com.sih.itantra.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AlertToneTest {

    @Test
    fun `the tone is long enough to register as an alarm`() {
        val tone = AlertTone.generate(repeats = 2)
        assertTrue("tone was only ${AudioSpec.millisOf(tone.size)} ms", AudioSpec.millisOf(tone.size) >= 900L)
    }

    @Test
    fun `repeat count scales the length`() {
        assertEquals(AlertTone.generate(1).size * 3, AlertTone.generate(3).size)
    }

    @Test
    fun `the tone is loud but never clips`() {
        val tone = AlertTone.generate()
        val peak = tone.maxOf { abs(it.toInt()) }

        assertTrue("peak $peak is too quiet for an alarm", peak > 15_000)
        assertTrue("peak $peak would clip", peak < Short.MAX_VALUE.toInt())
    }

    /** A square-edged burst clicks and can distort a phone speaker at full alarm volume. */
    @Test
    fun `the tone ramps in rather than starting at full amplitude`() {
        val tone = AlertTone.generate()
        assertEquals(0, tone[0].toInt())

        val firstMs = tone.take(AudioSpec.samplesFor(2L)).maxOf { abs(it.toInt()) }
        val steady = tone.take(AudioSpec.samplesFor(120L)).maxOf { abs(it.toInt()) }
        assertTrue("no ramp: $firstMs vs $steady", firstMs < steady / 2)
    }

    @Test
    fun `zero repeats produces nothing rather than throwing`() {
        assertEquals(0, AlertTone.generate(repeats = 0).size)
    }
}
