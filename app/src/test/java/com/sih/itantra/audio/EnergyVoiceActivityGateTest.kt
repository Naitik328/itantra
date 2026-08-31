package com.sih.itantra.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyVoiceActivityGateTest {

    private fun frame(amplitude: Short) = ShortArray(AudioSpec.FRAME_SAMPLES) { amplitude }

    /** The first frame only seeds the noise floor; calling it speech would fire on start-up. */
    @Test
    fun `the first frame after a reset is never speech`() {
        assertFalse(EnergyVoiceActivityGate().isSpeech(frame(20_000)))
    }

    @Test
    fun `a steady quiet room never reads as speech`() {
        val gate = EnergyVoiceActivityGate()
        repeat(200) { assertFalse(gate.isSpeech(frame(30))) }
    }

    @Test
    fun `a loud frame over a quiet floor reads as speech`() {
        val gate = EnergyVoiceActivityGate()
        repeat(50) { gate.isSpeech(frame(30)) }

        assertTrue(gate.isSpeech(frame(6000)))
    }

    /**
     * Nothing below the absolute floor is speech, however quiet the room. Without this a gate
     * in a silent room would trigger on its own dither.
     */
    @Test
    fun `a marginally louder frame in near-silence stays below the absolute floor`() {
        val gate = EnergyVoiceActivityGate()
        repeat(50) { gate.isSpeech(frame(1)) }

        // 20 is far above the floor in relative terms but only about -64 dBFS.
        assertFalse(gate.isSpeech(frame(20)))
    }

    /** The floor may creep during speech, but never fast enough to cut off a long sentence. */
    @Test
    fun `a long sentence is not cut off by floor drift`() {
        val gate = EnergyVoiceActivityGate()
        repeat(50) { gate.isSpeech(frame(30)) }

        // Six seconds of unbroken speech — longer than any single sentence.
        repeat(200) { gate.isSpeech(frame(6000)) }

        assertTrue("the speaker stopped being detected mid-sentence", gate.isSpeech(frame(6000)))
    }

    /**
     * Without this the gate latches: it only adapts on frames it already called silence, so a
     * generator starting up next to the phone would read as speech forever and hands-free
     * capture would never endpoint again.
     */
    @Test
    fun `a sustained noise eventually becomes the new background`() {
        val gate = EnergyVoiceActivityGate()
        repeat(50) { gate.isSpeech(frame(30)) }
        val quietFloor = gate.estimatedNoiseFloorDbfs

        // ~19 seconds of steady hiss: loud at first, then simply the room.
        repeat(600) { gate.isSpeech(frame(400)) }

        assertTrue(gate.estimatedNoiseFloorDbfs > quietFloor)
        assertFalse(gate.isSpeech(frame(400)))
    }

    /** Real speech must still cut through, even once the background has risen. */
    @Test
    fun `speech is still detected over a raised background`() {
        val gate = EnergyVoiceActivityGate()
        repeat(600) { gate.isSpeech(frame(400)) }

        assertTrue(gate.isSpeech(frame(9000)))
    }

    @Test
    fun `reset forgets the adapted floor`() {
        val gate = EnergyVoiceActivityGate()
        repeat(50) { gate.isSpeech(frame(30)) }
        assertTrue(gate.hasFloorEstimate)

        gate.reset()

        assertFalse(gate.hasFloorEstimate)
        assertFalse(gate.isSpeech(frame(20_000)))
    }

    @Test
    fun `the push-to-talk gate passes everything`() {
        val gate = AlwaysSpeechGate()
        assertTrue(gate.isSpeech(ShortArray(AudioSpec.FRAME_SAMPLES)))
    }
}
