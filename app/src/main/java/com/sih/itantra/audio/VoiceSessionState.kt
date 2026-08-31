package com.sih.itantra.audio

/** How the user starts and stops talking. */
enum class CaptureMode {
    /** Hold the button. The finger defines the utterance; no voice detection involved. */
    PUSH_TO_TALK,

    /** Mic stays open, the VAD gate decides. Sentences are cut at natural pauses. */
    HANDS_FREE,
}

/** Why capture is not currently running, when it should be. */
enum class CaptureHalt {
    NONE,

    /** Another app took audio focus transiently — a call, a navigation prompt, an alarm. */
    INTERRUPTED,

    /** Focus lost for good, or the mic was taken. The user has to start again. */
    STOPPED,
}

/**
 * Everything the voice UI renders, except the level meter — that updates 31 times a second and
 * lives in its own flow so it doesn't recompose the rest of the screen with it.
 */
data class VoiceSessionState(
    val mode: CaptureMode = CaptureMode.PUSH_TO_TALK,
    val capturing: Boolean = false,
    /** True while the segmenter is inside an utterance. */
    val speaking: Boolean = false,
    val halt: CaptureHalt = CaptureHalt.NONE,
    val route: AudioRoute = AudioRoute.AUTO,
    val routeLabel: String = "Speaker",
    val playing: Boolean = false,
    /** Name of the active voice-activity gate, for the metrics HUD. */
    val gateLabel: String = "energy",
    val utteranceCount: Int = 0,
    val lastUtteranceMs: Long = 0L,
    val lastEndReason: String? = null,
    /** Dump every captured utterance to a WAV for the AI team. */
    val archiveEnabled: Boolean = false,
    val archivedClips: Int = 0,
    val archivedBytes: Long = 0L,
    /**
     * Play each captured utterance straight back. With no STT or TTS in the build yet, this is
     * what proves the whole mic → ring buffer → segmenter → speaker path actually works.
     */
    val echoEnabled: Boolean = true,
    val error: String? = null,
) {
    val isPushToTalk: Boolean get() = mode == CaptureMode.PUSH_TO_TALK
}
