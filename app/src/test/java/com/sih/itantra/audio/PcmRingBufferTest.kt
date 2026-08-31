package com.sih.itantra.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmRingBufferTest {

    @Test
    fun `an under-filled buffer returns exactly what went in`() {
        val ring = PcmRingBuffer(10)
        ring.write(shortArrayOf(1, 2, 3))

        assertEquals(3, ring.available)
        assertArrayEquals(shortArrayOf(1, 2, 3), ring.snapshot())
    }

    @Test
    fun `writes wrap and keep the newest samples in chronological order`() {
        val ring = PcmRingBuffer(5)
        ring.write(shortArrayOf(1, 2, 3))
        ring.write(shortArrayOf(4, 5, 6, 7))

        assertEquals(5, ring.available)
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), ring.snapshot())
    }

    /** A write bigger than the whole buffer must keep its tail, not its head. */
    @Test
    fun `an oversized single write keeps only its newest tail`() {
        val ring = PcmRingBuffer(4)
        ring.write(shortArrayOf(1, 2, 3, 4, 5, 6, 7))

        assertArrayEquals(shortArrayOf(4, 5, 6, 7), ring.snapshot())
    }

    @Test
    fun `a write of exactly capacity replaces the contents`() {
        val ring = PcmRingBuffer(3)
        ring.write(shortArrayOf(9, 9, 9))
        ring.write(shortArrayOf(1, 2, 3))

        assertArrayEquals(shortArrayOf(1, 2, 3), ring.snapshot())
    }

    @Test
    fun `snapshot is repeatable and drain is not`() {
        val ring = PcmRingBuffer(4)
        ring.write(shortArrayOf(1, 2))

        assertArrayEquals(shortArrayOf(1, 2), ring.snapshot())
        assertArrayEquals(shortArrayOf(1, 2), ring.snapshot())
        assertArrayEquals(shortArrayOf(1, 2), ring.drain())
        assertEquals(0, ring.available)
        assertArrayEquals(shortArrayOf(), ring.snapshot())
    }

    @Test
    fun `partial writes honour the count argument`() {
        val ring = PcmRingBuffer(8)
        ring.write(shortArrayOf(1, 2, 3, 4), count = 2)

        assertArrayEquals(shortArrayOf(1, 2), ring.snapshot())
    }

    @Test
    fun `duration reflects the sample rate`() {
        val ring = PcmRingBuffer(AudioSpec.samplesFor(300L))
        ring.write(ShortArray(AudioSpec.samplesFor(100L)))

        assertEquals(100L, ring.durationMs)
    }

    @Test
    fun `an empty write is a no-op`() {
        val ring = PcmRingBuffer(4)
        ring.write(shortArrayOf(1))
        ring.write(shortArrayOf())

        assertArrayEquals(shortArrayOf(1), ring.snapshot())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a zero capacity buffer is rejected`() {
        PcmRingBuffer(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a count beyond the source array is rejected`() {
        PcmRingBuffer(8).write(shortArrayOf(1, 2), count = 5)
    }
}
