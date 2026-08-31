package com.sih.itantra.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records load/unload so a test can assert the "never both resident" invariant. */
private class SpyStt : SttEngine {
    override val label = "spy-stt"
    override fun supports(language: Language) = true
    override val partials: Flow<String> = emptyFlow()
    override fun transcribe(pcm16k: ShortArray, language: Language) = ""
    var loaded = false; private set
    override fun load() { loaded = true }
    override fun unload() { loaded = false }
    override val isLoaded get() = loaded
}

private class SpyTts : TtsEngine {
    override val label = "spy-tts"
    override val sampleRateHz = 22_050
    override fun supports(language: Language) = true
    override fun synthesize(text: String, language: Language) = ShortArray(0)
    var loaded = false; private set
    override fun load() { loaded = true }
    override fun unload() { loaded = false }
    override val isLoaded get() = loaded
}

class ModelResidencyTest {

    @Test
    fun `nothing is resident until an engine is acquired`() = runTest {
        val stt = SpyStt(); val tts = SpyTts()
        ModelResidency(stt, tts)
        assertFalse(stt.isLoaded)
        assertFalse(tts.isLoaded)
    }

    @Test
    fun `acquiring tts loads only tts`() = runTest {
        val stt = SpyStt(); val tts = SpyTts()
        val residency = ModelResidency(stt, tts)
        residency.withTts { /* use it */ }
        assertTrue(tts.isLoaded)
        assertFalse(stt.isLoaded)
        assertEquals(ModelResidency.Resident.TTS, residency.resident)
    }

    @Test
    fun `swapping to stt unloads tts — never both at once`() = runTest {
        val stt = SpyStt(); val tts = SpyTts()
        val residency = ModelResidency(stt, tts)

        residency.withTts { assertFalse("stt must not be co-resident", stt.isLoaded) }
        residency.withStt {
            assertFalse("tts must be unloaded before stt loads", tts.isLoaded)
            assertTrue(stt.isLoaded)
        }
        assertEquals(ModelResidency.Resident.STT, residency.resident)
    }

    @Test
    fun `releaseAll frees the resident model`() = runTest {
        val stt = SpyStt(); val tts = SpyTts()
        val residency = ModelResidency(stt, tts)
        residency.withTts { }
        residency.releaseAll()
        assertFalse(tts.isLoaded)
        assertEquals(ModelResidency.Resident.NONE, residency.resident)
    }

    @Test
    fun `re-acquiring the same engine does not reload it`() = runTest {
        val stt = SpyStt()
        var ttsLoads = 0
        val tts = object : TtsEngine {
            override val label = "counting"
            override val sampleRateHz = 22_050
            override fun supports(language: Language) = true
            override fun synthesize(text: String, language: Language) = ShortArray(0)
            var loaded = false
            override fun load() { if (!loaded) ttsLoads++; loaded = true }
            override fun unload() { loaded = false }
            override val isLoaded get() = loaded
        }
        val residency = ModelResidency(stt, tts)
        residency.withTts { }
        residency.withTts { }
        assertEquals("second acquire is a no-op, not a reload", 1, ttsLoads)
    }
}
