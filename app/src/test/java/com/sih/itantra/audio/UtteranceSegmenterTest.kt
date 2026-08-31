package com.sih.itantra.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Endpointing decides where one radio frame ends and the next begins, so its rules are worth
 * pinning precisely. A scripted gate stands in for the VAD, which keeps these tests about the
 * segmentation logic rather than about acoustic detection.
 */
class UtteranceSegmenterTest {

    /** Speech is any frame whose samples carry [SPEECH_MARK]; other values are audible silence. */
    private class MarkerGate : VoiceActivityGate {
        override val label: String get() = "marker"
        override fun isSpeech(frame: ShortArray): Boolean = frame.isNotEmpty() && frame[0] == SPEECH_MARK
        override fun reset() = Unit
    }

    private fun frame(value: Short) = ShortArray(AudioSpec.FRAME_SAMPLES) { value }
    private fun speech() = frame(SPEECH_MARK)
    private fun silence() = frame(SILENCE_MARK)

    private fun segmenter(
        preRollMs: Long = 300L,
        onsetFrames: Int = 3,
        hangoverMs: Long = 600L,
        minUtteranceMs: Long = 250L,
        maxUtteranceMs: Long = 15_000L,
        trailingSilenceMs: Long = 120L,
    ) = UtteranceSegmenter(
        gate = MarkerGate(),
        preRollMs = preRollMs,
        onsetFrames = onsetFrames,
        hangoverMs = hangoverMs,
        minUtteranceMs = minUtteranceMs,
        maxUtteranceMs = maxUtteranceMs,
        trailingSilenceMs = trailingSilenceMs,
    )

    // -- onset -------------------------------------------------------------------------------

    @Test
    fun `silence alone never opens an utterance`() {
        val seg = segmenter()
        repeat(50) { assertEquals(UtteranceSegmenter.Event.None, seg.accept(silence())) }
        assertTrue(!seg.isSpeaking)
    }

    @Test
    fun `speech opens only after the onset debounce is satisfied`() {
        val seg = segmenter(onsetFrames = 3)

        assertEquals(UtteranceSegmenter.Event.None, seg.accept(speech()))
        assertEquals(UtteranceSegmenter.Event.None, seg.accept(speech()))
        assertEquals(UtteranceSegmenter.Event.SpeechStarted, seg.accept(speech()))
        assertTrue(seg.isSpeaking)
    }

    /** A door slam is one loud frame. It must not open an utterance. */
    @Test
    fun `an isolated loud frame does not open an utterance`() {
        val seg = segmenter(onsetFrames = 3)

        seg.accept(speech())
        seg.accept(speech())
        assertEquals(UtteranceSegmenter.Event.None, seg.accept(silence()))
        assertEquals(UtteranceSegmenter.Event.None, seg.accept(speech()))
        assertTrue(!seg.isSpeaking)
    }

    // -- pre-roll ----------------------------------------------------------------------------

    /**
     * The whole reason [PcmRingBuffer] exists: the utterance must start with audio recorded
     * *before* the gate fired, or every sentence loses its first consonant.
     */
    @Test
    fun `the utterance begins with audio from before the gate opened`() {
        val seg = segmenter(preRollMs = 300L, onsetFrames = 3)

        repeat(20) { seg.accept(silence()) }
        repeat(5) { seg.accept(speech()) }
        val event = drainUntilUtterance(seg)

        // 300 ms of pre-roll is 4800 samples, of which the 3 onset frames are the newest 1536.
        assertEquals(SILENCE_MARK, event.samples[0])
        assertTrue(
            "expected at least 300 ms of audio, got ${event.durationMs} ms",
            event.durationMs >= 300L,
        )
    }

    // -- endpointing -------------------------------------------------------------------------

    @Test
    fun `a sustained pause ends the utterance`() {
        val seg = segmenter(hangoverMs = 600L)

        repeat(10) { seg.accept(speech()) }
        val event = drainUntilUtterance(seg)

        assertEquals(UtteranceSegmenter.EndReason.ENDPOINT, event.endReason)
        assertTrue(!seg.isSpeaking)
    }

    /** Speech is full of short gaps; only a sustained one is a sentence boundary. */
    @Test
    fun `a brief gap does not split the utterance`() {
        val seg = segmenter(hangoverMs = 600L)

        repeat(5) { seg.accept(speech()) }
        // 600 ms of hangover is ~19 frames; 10 is well short of it.
        repeat(10) { assertEquals(UtteranceSegmenter.Event.None, seg.accept(silence())) }
        repeat(5) { assertEquals(UtteranceSegmenter.Event.None, seg.accept(speech())) }
        assertTrue(seg.isSpeaking)
    }

    @Test
    fun `the pause that proved the end is trimmed off the audio`() {
        val seg = segmenter(hangoverMs = 320L, trailingSilenceMs = 64L)

        repeat(10) { seg.accept(speech()) }
        val event = drainUntilUtterance(seg)

        // 10 speech frames plus the pre-roll, plus at most the kept 64 ms of trailing silence —
        // the other ~256 ms of hangover must have been cut.
        val trailing = event.samples.takeLast(AudioSpec.FRAME_SAMPLES * 3)
        assertTrue(
            "expected most of the hangover to be trimmed",
            trailing.count { it == SILENCE_MARK } <= AudioSpec.samplesFor(96L),
        )
    }

    @Test
    fun `a speaker who never pauses is cut at the maximum length`() {
        val seg = segmenter(maxUtteranceMs = 500L)

        var event: UtteranceSegmenter.Event = UtteranceSegmenter.Event.None
        repeat(100) {
            if (event !is UtteranceSegmenter.Event.Utterance) event = seg.accept(speech())
        }

        val utterance = event as UtteranceSegmenter.Event.Utterance
        assertEquals(UtteranceSegmenter.EndReason.MAX_LENGTH, utterance.endReason)
        assertTrue(utterance.durationMs >= 500L)
    }

    // -- length filtering ---------------------------------------------------------------------

    /** A cough costs an STT pass and a frame on a 250 bps link. Drop it. */
    @Test
    fun `an utterance below the minimum length is discarded`() {
        val seg = segmenter(
            preRollMs = 0L,
            onsetFrames = 1,
            hangoverMs = 64L,
            minUtteranceMs = 250L,
            trailingSilenceMs = 0L,
        )

        seg.accept(speech())
        seg.accept(silence())
        assertEquals(UtteranceSegmenter.Event.None, seg.accept(silence()))
        assertTrue(!seg.isSpeaking)
    }

    // -- manual control -----------------------------------------------------------------------

    @Test
    fun `flush emits the in-flight utterance as manual`() {
        val seg = segmenter()

        repeat(10) { seg.accept(speech()) }
        val event = seg.flush() as UtteranceSegmenter.Event.Utterance

        assertEquals(UtteranceSegmenter.EndReason.MANUAL, event.endReason)
        assertTrue(!seg.isSpeaking)
    }

    @Test
    fun `flush while idle emits nothing`() {
        assertEquals(UtteranceSegmenter.Event.None, segmenter().flush())
    }

    /** A manual end keeps every sample: the user chose that boundary, not the detector. */
    @Test
    fun `flush does not trim trailing audio`() {
        val seg = segmenter(hangoverMs = 10_000L, trailingSilenceMs = 0L)

        repeat(5) { seg.accept(speech()) }
        repeat(5) { seg.accept(silence()) }
        val event = seg.flush() as UtteranceSegmenter.Event.Utterance

        assertEquals(SILENCE_MARK, event.samples.last())
    }

    @Test
    fun `reset abandons the in-flight utterance`() {
        val seg = segmenter()

        repeat(10) { seg.accept(speech()) }
        seg.reset()

        assertTrue(!seg.isSpeaking)
        assertEquals(0L, seg.activeDurationMs)
        assertEquals(UtteranceSegmenter.Event.None, seg.flush())
    }

    @Test
    fun `consecutive utterances are independent`() {
        val seg = segmenter()

        repeat(10) { seg.accept(speech()) }
        val first = drainUntilUtterance(seg)
        repeat(10) { seg.accept(speech()) }
        val second = drainUntilUtterance(seg)

        assertEquals(UtteranceSegmenter.EndReason.ENDPOINT, first.endReason)
        assertEquals(UtteranceSegmenter.EndReason.ENDPOINT, second.endReason)
    }

    private fun drainUntilUtterance(seg: UtteranceSegmenter): UtteranceSegmenter.Event.Utterance {
        repeat(200) {
            val event = seg.accept(silence())
            if (event is UtteranceSegmenter.Event.Utterance) return event
        }
        throw AssertionError("no utterance was emitted within 200 frames of silence")
    }

    private companion object {
        const val SPEECH_MARK: Short = 2000
        const val SILENCE_MARK: Short = 300
    }
}
