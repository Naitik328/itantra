package com.itantra.orchestrator

import ai.onnxruntime.OrtEnvironment
import com.itantra.adapters.SttAdapter
import com.itantra.adapters.TtsAdapter
import com.itantra.config.LanguagesConfig
import com.itantra.mt.MtAdapter
import com.itantra.mt.OnnxMtAdapter
import java.io.File

/**
 * docs/ITANTRA_INTEGRATION_SPEC.md #5.1-#5.4. Sequences the pipeline and
 * owns all per-language config -- adapters isolate model quirks, this
 * class knows none of them (spec #5.1 principle 1). VAD and crypto/
 * transport are explicitly out of scope here: VAD is unowned (D2, spec
 * #3.1) and crypto/transport wrap this class's output, they don't sit
 * inside the STT->MT->TTS sequencing this file owns.
 *
 * STT/TTS adapters are constructor-injected, not built here -- their real
 * (sherpa-onnx-backed) implementations don't exist on this branch (STT/TTS
 * are Shivanshu's/Raj's areas, spec #12). A caller with those adapters
 * wires them in; this class only needs the [SttAdapter]/[TtsAdapter]
 * interfaces (spec #7.1) to sequence correctly. The [MtAdapter], by
 * contrast, IS built on this branch (OnnxMtAdapter) and is constructed
 * directly from [config], which is the actual "wire MT into config and the
 * orchestrator" this class exists to do.
 *
 * Language selection is always explicit, never auto-detected (spec #4.5,
 * CLAUDE.md #4.5) -- every method here takes the language as a caller-
 * supplied parameter, never guesses one.
 */
class Orchestrator(
    private val config: LanguagesConfig,
    private val filesDir: File,
    ortEnvironment: OrtEnvironment,
    private val sttAdapters: Map<String, SttAdapter>,
    private val ttsAdapters: Map<String, TtsAdapter>,
    private val mtAdapter: MtAdapter = OnnxMtAdapter(
        env = ortEnvironment,
        modelRoot = File(filesDir, config.mt.modelDir),
        numThreads = config.shared.numThreads, // spec #6.3 -- shared, not per-language
        maxNewTokens = config.mt.maxNewTokens,
        idleTimeoutMillis = config.mt.idleTimeoutMillis,
    ),
) {
    /** Payload design, spec #5.4: pivot (English) text travels with the original -- avoids a lossy round trip for a receiver who shares the sender's language, and enables "show original." */
    data class OutgoingMessage(
        val originalLang: String,
        val originalText: String,
        val pivotText: String,
    )

    /** What arrives over the wire (post-decrypt; encryption/transport wrap this class, spec #5.3/#5.4, not built here). */
    data class IncomingPayload(
        val originalLang: String,
        val originalText: String,
        val pivotText: String,
    )

    /** Send path, spec #5.3 (VAD segmentation happens before this call -- D2, unowned). */
    fun prepareOutgoingMessage(senderLang: String, audio: FloatArray): OutgoingMessage {
        val stt = sttAdapters[senderLang]
            ?: error("No STT adapter for '$senderLang' -- languages.json/wiring problem, not a runtime edge case to route around.")

        val rawText = stt.transcribe(audio)
        val punctuated = Punctuation.restore(rawText) // spec #5.5 -- STT emits none, IndicTrans2 expects it
        val pivotText = mtAdapter.translate(punctuated, senderLang, "en")

        return OutgoingMessage(originalLang = senderLang, originalText = punctuated, pivotText = pivotText)
    }

    /** Receive path (text only), spec #5.4. */
    fun resolveDisplayText(payload: IncomingPayload, receiverLang: String): String =
        if (receiverLang == payload.originalLang) {
            payload.originalText // avoids a lossy pivot round trip when sender and receiver share a language
        } else {
            mtAdapter.translate(payload.pivotText, "en", receiverLang)
        }

    /** "(if voice requested) TTS", spec #5.4 -- null if this language has no TTS adapter wired, not an error. */
    fun synthesizeIfRequested(text: String, lang: String): ByteArray? =
        ttsAdapters[lang]?.synthesize(text)

    /** Call periodically -- spec #6.2 tiered residency. Full ModelLifecycle is D3-blocked (docs/CLAUDE.md #2); this only forwards to what OnnxMtAdapter already does for itself. */
    fun evictIdleModels() {
        (mtAdapter as? OnnxMtAdapter)?.evictIdle()
    }

    fun close() {
        mtAdapter.close()
        for (adapter in sttAdapters.values) adapter.close()
        for (adapter in ttsAdapters.values) adapter.close()
    }
}
