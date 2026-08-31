package com.sih.itantra.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Turns captured speech PCM into text. The inbound counterpart to [TtsEngine], and the seam the
 * streaming Zipformer / IndicConformer will drop into.
 *
 * No STT model is bundled yet — on the sending phone the user types the message today. This
 * interface is defined now anyway so the wiring from [com.sih.itantra.audio.VoiceSession.utterances]
 * to [com.sih.itantra.wifidirect.WifiDirectManager.sendText] is written once, against a stable
 * shape, and swapping [NoStt] for a real recogniser is a one-line change.
 *
 * Input PCM is 16-bit mono at [com.sih.itantra.audio.AudioSpec.SAMPLE_RATE_HZ] — the rate every
 * STT model in the stack is trained at, which is why capture is fixed there.
 */
interface SttEngine {

    /** Human-readable id for the metrics HUD, e.g. "zipformer-hi". */
    val label: String

    /** True if this engine can transcribe [language]. */
    fun supports(language: Language): Boolean

    /**
     * Partial hypotheses as the utterance is still being spoken — the live-transcript demo
     * moment. Emits growing prefixes; the final value is the same string [transcribe] returns.
     * A non-streaming engine may leave this empty and only produce a final result.
     */
    val partials: Flow<String>

    /**
     * Transcribe a complete utterance to its final text. Blocking and CPU-heavy — never call on
     * the main thread. Returns empty for silence or an unsupported language.
     */
    fun transcribe(pcm16k: ShortArray, language: Language): String

    fun load()
    fun unload()
    val isLoaded: Boolean
}

/**
 * The placeholder recogniser: it hears nothing.
 *
 * It lets the capture→send path compile and run before a model exists — [transcribe] returns
 * empty, so hands-free capture produces no phantom messages, and the user drives sending by
 * typing instead. Replace it, don't extend it.
 */
object NoStt : SttEngine {
    override val label: String get() = "none"
    override fun supports(language: Language): Boolean = false
    override val partials: Flow<String> = emptyFlow()
    override fun transcribe(pcm16k: ShortArray, language: Language): String = ""
    override fun load() = Unit
    override fun unload() = Unit
    override val isLoaded: Boolean get() = false
}
