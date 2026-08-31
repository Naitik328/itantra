package com.sih.itantra.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTtsEngineTest {

    @Test
    fun `blank text yields no audio`() {
        val tts = FakeTtsEngine()
        assertEquals(0, tts.synthesize("   ", Language.HINDI).size)
    }

    @Test
    fun `text yields pcm and longer text yields more of it`() {
        val tts = FakeTtsEngine(sampleRateHz = 16_000)
        val short = tts.synthesize("नमस्ते", Language.HINDI)
        val long = tts.synthesize("नमस्ते ".repeat(20), Language.HINDI)
        assertTrue(short.isNotEmpty())
        assertTrue("longer input should synthesise more samples", long.size > short.size)
    }

    @Test
    fun `synthesize on a cold engine loads it`() {
        val tts = FakeTtsEngine()
        assertFalse(tts.isLoaded)
        tts.synthesize("नमस्ते", Language.HINDI)
        assertTrue(tts.isLoaded)
    }

    @Test
    fun `unload clears the loaded flag`() {
        val tts = FakeTtsEngine()
        tts.load()
        assertTrue(tts.isLoaded)
        tts.unload()
        assertFalse(tts.isLoaded)
    }
}
