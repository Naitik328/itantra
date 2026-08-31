package com.sih.itantra.ai

/**
 * Turns a line of text into speech PCM. One of the two model seams AI Member 3 plugs into
 * (the other is [SttEngine]); the app depends only on this interface, so a real Piper voice and
 * the [FakeTtsEngine] below are interchangeable at the construction site.
 *
 * Output is always 16-bit signed mono PCM at [sampleRateHz] — the format
 * [com.sih.itantra.audio.AudioPlayer] consumes. The sample rate is a property, not a constant,
 * because voices differ: Piper hi_IN runs at 22050 Hz while the capture path is 16 kHz.
 *
 * [load]/[unload] exist because a resident STT or TTS model is tens of megabytes and the
 * footprint budget allows only one at a time — see [ModelResidency]. Implementations must treat
 * [synthesize] on an unloaded engine as a load-then-synthesize, so callers can't crash on a
 * cold engine.
 */
interface TtsEngine {

    /** Human-readable id for the metrics HUD, e.g. "piper-hi_IN". */
    val label: String

    /** Native sample rate of this voice's PCM output. */
    val sampleRateHz: Int

    /** True if this engine can speak [language]. */
    fun supports(language: Language): Boolean

    /**
     * Synthesize [text] in [language] to 16-bit mono PCM. Blocking and CPU-heavy — never call it
     * on the main thread. Returns an empty array for empty text or an unsupported language rather
     * than throwing, so the playback path degrades to silence instead of a crash.
     */
    fun synthesize(text: String, language: Language): ShortArray

    /** Bring the model into memory. Idempotent. Heavy; call off the main thread. */
    fun load()

    /** Release native memory. Idempotent. After this, [isLoaded] is false until the next [load]. */
    fun unload()

    val isLoaded: Boolean
}

/**
 * A voice that returns a short buzz instead of real speech.
 *
 * It keeps the relay demoable before a real model is installed, and gives the tests a TTS with
 * no native dependency. It "supports" every language so the UI can be exercised in all ten.
 */
class FakeTtsEngine(override val sampleRateHz: Int = 16_000) : TtsEngine {

    override val label: String get() = "fake-tone"

    @Volatile
    override var isLoaded: Boolean = false
        private set

    override fun supports(language: Language): Boolean = true

    override fun load() { isLoaded = true }

    override fun unload() { isLoaded = false }

    override fun synthesize(text: String, language: Language): ShortArray {
        if (text.isBlank()) return ShortArray(0)
        if (!isLoaded) load()
        // Length scales with the text so a longer message plays longer — enough to prove the
        // synth→play path end to end without pretending to be intelligible.
        val durationMs = (200 + text.length * 60).coerceAtMost(4_000)
        val count = sampleRateHz * durationMs / 1000
        val out = ShortArray(count)
        val freq = 220.0
        for (i in out.indices) {
            val t = i.toDouble() / sampleRateHz
            out[i] = (Math.sin(2 * Math.PI * freq * t) * 6000).toInt().toShort()
        }
        return out
    }
}
