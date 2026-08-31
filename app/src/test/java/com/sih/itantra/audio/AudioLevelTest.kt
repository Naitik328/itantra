package com.sih.itantra.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLevelTest {

    @Test
    fun `digital silence reports the silence floor rather than negative infinity`() {
        assertEquals(AudioLevel.SILENCE_DBFS, AudioLevel.dbfs(ShortArray(512)), 0f)
    }

    @Test
    fun `a full-scale signal sits at zero dBFS`() {
        val square = ShortArray(512) { if (it % 2 == 0) Short.MAX_VALUE else (-Short.MAX_VALUE).toShort() }
        assertEquals(0f, AudioLevel.dbfs(square), 0.01f)
    }

    /** Halving amplitude is a 6 dB drop — the check that the log maths is right. */
    @Test
    fun `halving the amplitude costs six decibels`() {
        val loud = ShortArray(512) { 8000 }
        val quiet = ShortArray(512) { 4000 }
        assertEquals(6.02f, AudioLevel.dbfs(loud) - AudioLevel.dbfs(quiet), 0.05f)
    }

    @Test
    fun `rms of a constant signal is that constant`() {
        assertEquals(1000.0, AudioLevel.rms(ShortArray(256) { 1000 }), 0.001)
    }

    @Test
    fun `empty input does not divide by zero`() {
        assertEquals(0.0, AudioLevel.rms(ShortArray(0)), 0.0)
        assertEquals(AudioLevel.SILENCE_DBFS, AudioLevel.dbfs(ShortArray(0)), 0f)
    }

    @Test
    fun `meter normalisation spans the floor to full scale`() {
        assertEquals(0f, AudioLevel.normalized(-60f), 0.001f)
        assertEquals(0f, AudioLevel.normalized(-90f), 0.001f)  // clamped, not negative
        assertEquals(1f, AudioLevel.normalized(0f), 0.001f)
        assertEquals(0.5f, AudioLevel.normalized(-30f), 0.001f)
    }
}
