package com.sih.itantra.audio

/**
 * Turns a continuous stream of capture frames into discrete utterances — the "endpoint" stage
 * of the pipeline, where a pause becomes a sentence boundary and therefore one frame on the
 * radio link.
 *
 * Pure logic with no Android dependency: it takes frames in and gives utterances back, so the
 * decision rules can be tested against scripted speech/silence patterns instead of by talking
 * at a handset.
 *
 * The rules, and why each exists:
 *  - **pre-roll** — audio from *before* the gate fired is prepended, so the leading consonant
 *    isn't clipped ([PcmRingBuffer] explains this at length).
 *  - **onset debounce** — a single loud frame is a door, not a word. Several consecutive speech
 *    frames are required to open an utterance.
 *  - **hangover** — speech is full of short gaps (stops, plosives). Only a sustained silence
 *    ends the sentence, otherwise every utterance would shatter into fragments.
 *  - **minimum length** — anything shorter than a syllable is a cough; dropping it saves a
 *    wasted STT pass and a wasted frame on a 250 bps link.
 *  - **maximum length** — someone who never pauses must still get their audio sent eventually.
 */
class UtteranceSegmenter(
    private val gate: VoiceActivityGate,
    private val preRollMs: Long = 300L,
    private val onsetFrames: Int = 3,
    private val hangoverMs: Long = 600L,
    private val minUtteranceMs: Long = 250L,
    private val maxUtteranceMs: Long = 15_000L,
    /** Silence deliberately left on the end of an utterance; recognisers endpoint better with it. */
    private val trailingSilenceMs: Long = 120L,
) {

    /** Why an utterance ended — surfaced so the UI can distinguish a pause from a cut-off. */
    enum class EndReason {
        /** A sustained pause: a natural sentence boundary. */
        ENDPOINT,

        /** [maxUtteranceMs] hit while the speaker was still going. */
        MAX_LENGTH,

        /** Push-to-talk released, or capture stopped. */
        MANUAL,
    }

    sealed interface Event {
        /** Nothing to report for this frame. */
        data object None : Event

        /** The gate has just opened; useful for lighting up the UI. */
        data object SpeechStarted : Event

        /** A complete utterance, ready for the recogniser. */
        data class Utterance(
            val samples: ShortArray,
            val durationMs: Long,
            val endReason: EndReason,
        ) : Event {
            // ShortArray uses identity equals; data class equality would be a trap for callers.
            override fun equals(other: Any?): Boolean =
                other is Utterance &&
                    durationMs == other.durationMs &&
                    endReason == other.endReason &&
                    samples.contentEquals(other.samples)

            override fun hashCode(): Int =
                samples.contentHashCode() * 31 + durationMs.hashCode() * 31 + endReason.hashCode()
        }
    }

    private val preRoll = PcmRingBuffer(
        capacitySamples = maxOf(AudioSpec.samplesFor(preRollMs), AudioSpec.FRAME_SAMPLES),
    )

    private val active = ArrayList<ShortArray>()
    private var activeSamples = 0

    private var speaking = false
    private var speechRun = 0
    private var silenceRun = 0

    val isSpeaking: Boolean get() = speaking

    /** Duration of the utterance currently being collected; 0 when idle. */
    val activeDurationMs: Long get() = AudioSpec.millisOf(activeSamples)

    fun accept(frame: ShortArray): Event {
        val speech = gate.isSpeech(frame)

        if (!speaking) {
            preRoll.write(frame)
            speechRun = if (speech) speechRun + 1 else 0

            if (speechRun < onsetFrames) return Event.None

            // Open the utterance. The pre-roll already contains these onset frames, so seeding
            // from it picks up both the history and the speech we just confirmed.
            speaking = true
            silenceRun = 0
            speechRun = 0
            append(preRoll.drain())
            return Event.SpeechStarted
        }

        append(frame)
        silenceRun = if (speech) 0 else silenceRun + 1

        val silenceMs = silenceRun * AudioSpec.FRAME_MS
        return when {
            silenceMs >= hangoverMs -> finish(EndReason.ENDPOINT)
            activeDurationMs >= maxUtteranceMs -> finish(EndReason.MAX_LENGTH)
            else -> Event.None
        }
    }

    /** End the current utterance now — push-to-talk released, or capture stopping. */
    fun flush(): Event = if (speaking) finish(EndReason.MANUAL) else Event.None

    fun reset() {
        gate.reset()
        preRoll.clear()
        active.clear()
        activeSamples = 0
        speaking = false
        speechRun = 0
        silenceRun = 0
    }

    private fun append(samples: ShortArray) {
        if (samples.isEmpty()) return
        active.add(samples)
        activeSamples += samples.size
    }

    private fun finish(reason: EndReason): Event {
        val trimmed = trimTrailingSilence(reason)
        val durationMs = AudioSpec.millisOf(trimmed.size)

        active.clear()
        activeSamples = 0
        speaking = false
        speechRun = 0
        silenceRun = 0
        preRoll.clear()

        // Too short to be a word — drop it rather than spending a model pass and a radio frame.
        if (durationMs < minUtteranceMs) return Event.None

        return Event.Utterance(trimmed, durationMs, reason)
    }

    /**
     * Cut the hangover silence back to [trailingSilenceMs]. The gap that *proved* the sentence
     * ended is not itself worth sending to the recogniser.
     */
    private fun trimTrailingSilence(reason: EndReason): ShortArray {
        val flat = flatten()
        if (reason != EndReason.ENDPOINT) return flat

        val keep = AudioSpec.samplesFor(trailingSilenceMs)
        val silence = silenceRun * AudioSpec.FRAME_SAMPLES
        val drop = (silence - keep).coerceAtLeast(0)
        if (drop <= 0 || drop >= flat.size) return flat
        return flat.copyOf(flat.size - drop)
    }

    private fun flatten(): ShortArray {
        val out = ShortArray(activeSamples)
        var offset = 0
        for (chunk in active) {
            System.arraycopy(chunk, 0, out, offset, chunk.size)
            offset += chunk.size
        }
        return out
    }
}
