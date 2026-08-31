package com.sih.itantra.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSpecTest {

    /**
     * The frame size is load-bearing: Silero v5 consumes exactly 512 samples at 16 kHz, so a
     * change here silently forces a repacking buffer into the hot path.
     */
    @Test
    fun `frame is 512 samples and exactly 32 milliseconds`() {
        assertEquals(512, AudioSpec.FRAME_SAMPLES)
        assertEquals(32L, AudioSpec.FRAME_MS)
        assertEquals(1024, AudioSpec.FRAME_BYTES)
    }

    @Test
    fun `sample and millisecond conversions agree with each other`() {
        assertEquals(16_000, AudioSpec.samplesFor(1000L))
        assertEquals(1000L, AudioSpec.millisOf(16_000))
        assertEquals(300, AudioSpec.samplesFor(300L) / 16)
    }

    @Test
    fun `framesFor rounds up so a request is always fully covered`() {
        assertEquals(1, AudioSpec.framesFor(32L))
        assertEquals(2, AudioSpec.framesFor(33L))   // one frame would be 1 ms short
        assertEquals(10, AudioSpec.framesFor(300L)) // 300 ms is 9.375 frames
    }

    /** The premise of the whole project: raw speech is far too heavy for the link. */
    @Test
    fun `raw pcm is 32 kilobytes per second`() {
        assertEquals(32_000, AudioSpec.BYTES_PER_SECOND)
    }
}
