package com.sih.itantra.audio

/**
 * The one place the PCM format is defined. Capture, the VAD gate, the WAV dump, the STT
 * front-end and playback all read these constants, so the pipeline cannot drift out of sync
 * with itself.
 *
 * 16 kHz mono 16-bit is what every model in the stack expects — Silero VAD, the streaming
 * Zipformer and the Piper voices are all trained at this rate, so resampling anywhere in the
 * chain would cost accuracy for nothing.
 */
object AudioSpec {

    const val SAMPLE_RATE_HZ = 16_000
    const val CHANNEL_COUNT = 1
    const val BITS_PER_SAMPLE = 16
    const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

    /**
     * 512 samples = exactly 32 ms at 16 kHz.
     *
     * This is deliberately not a round 20 ms. Silero v5 consumes 512-sample windows at 16 kHz,
     * so sizing the capture frame to match means the VAD gate eats capture frames one-for-one
     * with no repacking buffer between them — the hot path allocates nothing per frame beyond
     * the frame itself.
     */
    const val FRAME_SAMPLES = 512
    const val FRAME_BYTES = FRAME_SAMPLES * BYTES_PER_SAMPLE
    const val FRAME_MS = FRAME_SAMPLES * 1000L / SAMPLE_RATE_HZ

    /** Bytes on the wire per second of audio — the number that motivates the whole project. */
    const val BYTES_PER_SECOND = SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE

    fun millisOf(sampleCount: Int): Long = sampleCount * 1000L / SAMPLE_RATE_HZ

    fun samplesFor(millis: Long): Int = (millis * SAMPLE_RATE_HZ / 1000L).toInt()

    /** Number of whole capture frames needed to cover [millis], rounded up. */
    fun framesFor(millis: Long): Int {
        val samples = samplesFor(millis)
        return (samples + FRAME_SAMPLES - 1) / FRAME_SAMPLES
    }
}
