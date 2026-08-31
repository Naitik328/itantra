package com.sih.itantra.audio

import kotlin.math.abs

/**
 * Decides, frame by frame, whether the microphone is hearing speech.
 *
 * This is one of the seams the AI team plugs into. [UtteranceSegmenter] and the whole capture
 * path depend only on this interface, so swapping the placeholder energy gate below for a real
 * Silero VAD is a one-line change at the construction site and touches nothing else.
 *
 * Implementations are called once per [AudioSpec.FRAME_SAMPLES] frame on the capture thread and
 * must be cheap — this runs even while the device is otherwise idle, and "wins the idle-CPU
 * score" is an explicit project goal.
 */
interface VoiceActivityGate {

    /** @return true if [frame] contains speech. */
    fun isSpeech(frame: ShortArray): Boolean

    /** Forget any adapted state; called when capture starts or the mode changes. */
    fun reset()

    /** Human-readable name for the metrics HUD, e.g. "energy" or "silero-v5". */
    val label: String
}

/**
 * Placeholder gate that thresholds short-term energy against an adaptive noise floor.
 *
 * It exists so hands-free capture, endpointing and the whole utterance path are testable and
 * demoable *before* Silero lands — not because energy detection is good enough to ship. It will
 * happily trigger on a slammed door and miss a whisper, which is precisely why the real model
 * is worth its 1.8 MB. Replace it, don't tune it.
 *
 * The floor adapts quickly on frames judged non-speech and slowly — and only upward — on frames
 * judged speech. Both halves matter: adapting during silence alone would latch the gate open
 * forever once a generator or a vehicle started up, while adapting downward during speech would
 * let a long sentence drag the threshold over its own tail and cut itself off.
 */
class EnergyVoiceActivityGate(
    /** How far above the noise floor a frame must sit to count as speech. */
    private val marginDb: Float = 9f,
    /** Hard floor: nothing quieter than this is speech, however quiet the room is. */
    private val absoluteFloorDbfs: Float = -50f,
    /** Time constant for the noise-floor estimate while the room is quiet. */
    private val adaptSeconds: Float = 1.5f,
    /**
     * How much slower the floor creeps while a frame is being called speech. Sustained noise
     * should become the new background in tens of seconds, not in the second and a half it
     * takes during silence — otherwise a pause for breath would re-baseline on the speaker.
     */
    private val speechAdaptDivisor: Float = 8f,
) : VoiceActivityGate {

    override val label: String get() = "energy"

    private var noiseFloorDb = Float.NaN

    private val alpha: Float
        get() = (AudioSpec.FRAME_MS / (adaptSeconds * 1000f)).coerceIn(0.001f, 1f)

    override fun isSpeech(frame: ShortArray): Boolean {
        val db = AudioLevel.dbfs(frame)

        if (noiseFloorDb.isNaN()) {
            // First frame after a reset seeds the floor and is never speech.
            noiseFloorDb = db
            return false
        }

        val speech = db > noiseFloorDb + marginDb && db > absoluteFloorDbfs
        when {
            !speech -> noiseFloorDb += alpha * (db - noiseFloorDb)

            // Upward only, and slowly: this is the escape hatch from a room that has simply
            // become loud, without letting a burst of speech redefine what silence sounds like.
            db > noiseFloorDb -> noiseFloorDb += (alpha / speechAdaptDivisor) * (db - noiseFloorDb)
        }
        return speech
    }

    override fun reset() {
        noiseFloorDb = Float.NaN
    }

    /** Exposed for the metrics HUD; NaN until the first frame arrives. */
    val estimatedNoiseFloorDbfs: Float get() = noiseFloorDb

    /** True once the floor estimate has settled enough to be worth showing. */
    val hasFloorEstimate: Boolean get() = !noiseFloorDb.isNaN() && abs(noiseFloorDb) < 200f
}

/**
 * A gate that calls everything speech.
 *
 * Push-to-talk needs no voice detection: the user's finger *is* the endpoint decision, and a
 * VAD that second-guesses it would cut people off mid-sentence when they paused to think.
 * Pairing this with [UtteranceSegmenter] reuses the whole pre-roll, length-limit and assembly
 * path for PTT rather than growing a second code path that has to be debugged separately.
 */
class AlwaysSpeechGate : VoiceActivityGate {
    override val label: String get() = "push-to-talk"
    override fun isSpeech(frame: ShortArray): Boolean = true
    override fun reset() = Unit
}
