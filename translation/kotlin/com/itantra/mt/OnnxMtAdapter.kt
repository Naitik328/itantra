package com.itantra.mt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer

/**
 * MT adapter for the IndicTrans2-Distilled ONNX models built by
 * tools/export_indictrans2_onnx.py + tools/quantize_and_verify.py.
 * See docs/ITANTRA_INTEGRATION_SPEC.md #7.4, #6.2 (residency), #6.3 (threads),
 * #6.4 (CPU only).
 *
 * Tokenization is IN-GRAPH (onnxruntime-extensions' SentencepieceTokenizer
 * custom op plus a vocabulary-remap Gather, baked into encoder.onnx at
 * export time -- see tools/tokenizer_graph.py) rather than a Kotlin
 * tokenizer implementation. This resolves what MtTokenizer.kt used to flag
 * as a blocking gap (no first-party Android SentencePiece binding); that
 * file and interface no longer exist. What this adapter needs from Android
 * is the `onnxruntime-extensions-android` AAR, loaded as a custom-op
 * library via [OrtxPackage.getLibraryPath] -- confirmed against the real
 * AAR's classes.jar via `javap` (not guessed), and since confirmed by a
 * collaborator's real-device integration attempt: `registerCustomOpLibrary()`
 * + `getLibraryPath()` both execute without error on real hardware, and
 * the library's native `.so`s declare no `libonnxruntime.so` dependency at
 * all (`llvm-objdump`'d directly) -- it's fully decoupled from whichever
 * ONNX Runtime build is present, which matters a lot if this ever shares
 * a process with sherpa-onnx's own bundled runtime (see
 * translation/TRANSLATION_INTEGRATION_ISSUES.md #1 for a real version-
 * symbol collision that *did* bite in exactly that scenario, and its fix).
 *
 * Everything about the encoder input/decoder-seed/detokenize shapes below
 * was confirmed 2026-09-03 against the real tokenization_indictrans.py,
 * config.json, and end-to-end runs of both directions with real weights
 * (verify_tokenizer_ids() passing exact id matches, and greedy_decode_onnx_
 * embedded() in tools/quantize_and_verify.py producing correct translations
 * -- e.g. "Where is the nearest hospital?" -> "निकटतम अस्पताल कहाँ है",
 * round-tripped back to English correctly). It is not a guess:
 *   - IndicTransTokenizer's vocabulary is a separate fairseq-style
 *     dictionary, not the raw sentencepiece ids -- the encoder embeds a
 *     Gather-based remap table built from the real dict.SRC.json at export
 *     time. Nothing to do here about that; it's inside encoder.onnx.
 *   - Encoder input_ids = [srcTagId, tgtTagId, <sentencepiece-encoded,
 *     remapped text>, eosId] -- both tag ids AND eos come from dict.SRC
 *     (yes, even the target-language tag; it's a second token in the
 *     *source* sequence, not fed to the decoder).
 *   - decoder_input_ids is seeded with ONLY decoder_start_id (config.json's
 *     value -- for these checkpoints it equals eos_id, 2, but that's a fact
 *     about these checkpoints, not assumed here). No separate tgtTagId
 *     token goes to the decoder.
 *   - Detokenizing is a plain string join, not a SentencePiece decode:
 *     `pieces.joinToString("").replace("▁", " ").trim()` over the pieces
 *     named by tgt_vocab.json at each output id. No detokenizer.onnx, no
 *     second custom op.
 *
 * Pivot-only by design, matching the two checkpoints this project actually
 * exports (indictrans_common.py's CHECKPOINTS: en-indic, indic-en) -- see
 * spec #4.1: every STT/TTS language pairs with English, never with each
 * other directly. [translate] rejects indic-to-indic calls rather than
 * silently mistranslating; the orchestrator (not yet built, spec #5.3/#5.4)
 * is what must chain src->en then en->tgt for e.g. Hindi -> Telugu.
 *
 * Bundle layout extends spec #8.4, which shows one encoder/decoder pair
 * under `mt/` -- too small for two directions. This adapter expects:
 *   <modelRoot>/en-indic/encoder.int8.onnx   (raw text + 2 tag ids in, hidden states out)
 *   <modelRoot>/en-indic/decoder.int8.onnx   (ids in, logits out)
 *   <modelRoot>/en-indic/vocab_ids.json      (decoder_start_id, eos_id, lang_tag_ids)
 *   <modelRoot>/en-indic/tgt_vocab.json      (id-indexed piece-string array for detokenizing)
 *   <modelRoot>/indic-en/...                 (same four files)
 * (SHA256SUMS.txt lives alongside each set, written by quantize_and_verify.py.)
 *
 * Decoder has no KV cache (see export_indictrans2_onnx.py's module doc) --
 * every decode step re-runs the decoder over the full prefix so far. This
 * mirrors quantize_and_verify.py's greedy_decode_onnx_embedded exactly; if
 * that reference loop changes, this one must change with it.
 */
class OnnxMtAdapter(
    private val env: OrtEnvironment,
    private val modelRoot: File,
    private val numThreads: Int = 4, // spec #6.3 -- shared numThreads, not a per-language value
    private val maxNewTokens: Int = 128, // spec #7.4 -- chat messages are short
    private val idleTimeoutMillis: Long = 30_000, // spec #6.2 tiered residency
    // Opt-in stage timing (model load / encoder / decode loop / detokenize),
    // null by default -- no cost, no Log dependency, when not supplied.
    // Added to investigate a real on-device report of MT adding ~1.5s of
    // latency to the STT/TTS round trip (translation/TRANSLATION_INTEGRATION_
    // ISSUES.md). Desktop timing via the C++ CLI test bench
    // (translation/cli_testbench/) already shows the decode loop costing
    // 85-391ms for just 5-8 output tokens on a fast x86 desktop with no KV
    // cache (export_indictrans2_onnx.py's module doc flagged this exact
    // tradeoff as something to revisit "if on-device benchmarking shows
    // this is actually too slow" -- this is that benchmarking, on-device).
    // Wire this to Log.d/your telemetry of choice; a caller not supplying
    // it pays nothing extra.
    private val onTiming: ((stage: String, durationMs: Long) -> Unit)? = null,
) : MtAdapter {

    private inline fun <T> timed(stage: String, block: () -> T): T {
        if (onTiming == null) return block()
        val start = System.nanoTime()
        val result = block()
        onTiming.invoke(stage, (System.nanoTime() - start) / 1_000_000)
        return result
    }

    private data class VocabIds(
        val decoderStartId: Int,
        val eosId: Int,
        val langTagIds: Map<String, Int>, // FLORES tag -> dict.SRC-space id
    )

    private class DirectionSession(
        val encoder: OrtSession,
        val decoder: OrtSession,
        val vocab: VocabIds,
        val tgtVocab: List<String>, // id-indexed piece strings, for detokenizing this direction's output
    ) {
        var lastUsedAtMillis: Long = System.currentTimeMillis()
        fun touch() {
            lastUsedAtMillis = System.currentTimeMillis()
        }

        fun close() {
            encoder.close()
            decoder.close()
        }
    }

    private val sessions = mutableMapOf<String, DirectionSession>()

    override fun translate(text: String, sourceLang: String, targetLang: String): String {
        if (sourceLang == targetLang) return text
        require(sourceLang == "en" || targetLang == "en") {
            "OnnxMtAdapter only translates to/from English (the pivot). Got " +
                "$sourceLang -> $targetLang; the orchestrator must chain " +
                "$sourceLang->en then en->$targetLang instead of calling this directly."
        }

        val direction = if (sourceLang == "en") "en-indic" else "indic-en"
        val srcTag = FloresTags.flores(sourceLang)
        val tgtTag = FloresTags.flores(targetLang)

        val session = sessionFor(direction)
        val vocab = session.vocab
        val srcTagId = vocab.langTagIds[srcTag]
            ?: error("No lang_tag_ids entry for '$srcTag' in $direction/vocab_ids.json -- this direction can't take $sourceLang as source.")
        val tgtTagId = vocab.langTagIds[tgtTag]
            ?: error("No lang_tag_ids entry for '$tgtTag' in $direction/vocab_ids.json -- this direction can't decode into $targetLang.")

        val pre = IndicProcessor.preprocess(text, srcTag, tgtTag)
        val (encoderHidden, srcLen, hiddenSize) = timed("encoder") {
            runEncoder(session.encoder, pre.text, srcTagId, tgtTagId, vocab.eosId)
        }
        val outputIds = timed("decode loop") {
            greedyDecode(session.decoder, srcLen, encoderHidden, hiddenSize, vocab)
        }
        onTiming?.invoke("decode steps", (outputIds.size - 1).toLong()) // -1: seed token isn't a generated step
        val decoded = timed("detokenize") { detokenize(session.tgtVocab, outputIds, vocab.eosId) }

        session.touch()
        return IndicProcessor.postprocess(decoded, tgtTag, pre.placeholders)
    }

    /** Call periodically from ModelLifecycle (spec #6.2) -- blocked on D3, not built yet. */
    fun evictIdle() {
        val now = System.currentTimeMillis()
        val stale = sessions.filterValues { now - it.lastUsedAtMillis > idleTimeoutMillis }.keys
        for (key in stale) {
            sessions.remove(key)?.close()
        }
    }

    override fun close() {
        for (session in sessions.values) session.close()
        sessions.clear()
    }

    private fun sessionFor(direction: String): DirectionSession =
        sessions.getOrPut(direction) {
            timed("model load ($direction, first use only)") {
                val dir = File(modelRoot, direction)
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(numThreads) // spec #6.3
                    // CPU only -- spec #6.4: NNAPI is untested and known to partition
                    // VITS/Conformer graphs poorly; no execution provider is added here.
                    registerCustomOpLibrary(OrtxPackage.getLibraryPath()) // ai.onnx.contrib tokenizer op
                }
                DirectionSession(
                    encoder = env.createSession(File(dir, "encoder.int8.onnx").absolutePath, opts),
                    decoder = env.createSession(File(dir, "decoder.int8.onnx").absolutePath, opts),
                    vocab = loadVocabIds(File(dir, "vocab_ids.json")),
                    tgtVocab = loadTgtVocab(File(dir, "tgt_vocab.json")),
                )
            }
        }.also { it.touch() }

    private fun loadVocabIds(path: File): VocabIds {
        val json = JSONObject(path.readText())
        val tags = json.getJSONObject("lang_tag_ids")
        val langTagIds = tags.keys().asSequence().associateWith { tags.getInt(it) }
        return VocabIds(
            decoderStartId = json.getInt("decoder_start_id"),
            eosId = json.getInt("eos_id"),
            langTagIds = langTagIds,
        )
    }

    private fun loadTgtVocab(path: File): List<String> {
        val array = JSONArray(path.readText())
        return List(array.length()) { array.getString(it) }
    }

    /** @return (flattened encoder_hidden_states, srcLen, hiddenSize) */
    private fun runEncoder(encoder: OrtSession, text: String, srcTagId: Int, tgtTagId: Int, eosId: Int): Triple<FloatArray, Int, Int> {
        OnnxTensor.createTensor(env, arrayOf(text)).use { textTensor ->
            scalarLongTensor(srcTagId).use { srcTagTensor ->
                scalarLongTensor(tgtTagId).use { tgtTagTensor ->
                    scalarLongTensor(eosId).use { eosTensor ->
                        val inputs = mapOf(
                            "raw_text" to textTensor,
                            "src_tag_id" to srcTagTensor,
                            "tgt_tag_id" to tgtTagTensor,
                            "eos_id_const" to eosTensor,
                        )
                        encoder.run(inputs).use { result ->
                            val output = firstTensor(result) // only output: encoder_hidden_states
                            // attention_mask is NOT a merged-graph output (onnx.compose drops any
                            // tokenizer-bridge output consumed via io_map during the export-time
                            // merge -- confirmed empirically, see tokenizer_graph.py) -- srcLen
                            // comes from this shape instead, same as quantize_and_verify.py does.
                            val shape = output.info.shape // [1, srcLen, hidden]
                            val srcLen = shape[1].toInt()
                            val hidden = shape[2].toInt()
                            val flat = flattenFloat3d(output.floatBuffer, srcLen, hidden)
                            return Triple(flat, srcLen, hidden)
                        }
                    }
                }
            }
        }
    }

    private fun greedyDecode(
        decoder: OrtSession,
        srcLen: Int,
        encoderHidden: FloatArray,
        hiddenSize: Int,
        vocab: VocabIds,
    ): IntArray {
        // Seeded with ONLY decoder_start_id -- no separate target-tag token.
        // The target language is already encoded in the source sequence
        // (see class doc). Confirmed by reproducing model.generate()'s
        // output token-for-token with this exact seed.
        val decoderIds = mutableListOf(vocab.decoderStartId)

        // encoder_hidden_states / encoder_attention_mask are identical on every
        // decode step (only decoder_input_ids grows) -- build them once.
        val hiddenTensor = OnnxTensor.createTensor(
            env,
            java.nio.FloatBuffer.wrap(encoderHidden),
            longArrayOf(1, srcLen.toLong(), hiddenSize.toLong()),
        )
        val maskTensor = longTensor(IntArray(srcLen) { 1 }, longArrayOf(1, srcLen.toLong()))

        try {
            repeat(maxNewTokens) {
                val curLen = decoderIds.size
                val nextId = longTensor(decoderIds.toIntArray(), longArrayOf(1, curLen.toLong())).use { ids ->
                    decoder.run(
                        mapOf(
                            "decoder_input_ids" to ids,
                            "encoder_hidden_states" to hiddenTensor,
                            "encoder_attention_mask" to maskTensor,
                        )
                    ).use { result ->
                        val logits = firstTensor(result) // [1, curLen, vocab]
                        argmaxLastStep(logits.floatBuffer, curLen)
                    }
                }
                decoderIds.add(nextId)
                if (nextId == vocab.eosId) return decoderIds.toIntArray()
            }
            return decoderIds.toIntArray()
        } finally {
            hiddenTensor.close()
            maskTensor.close()
        }
    }

    /**
     * IndicTransTokenizer.convert_tokens_to_string, exactly -- a plain
     * string join, not a SentencePiece decode (see class doc). Skips the
     * seed token (decoder_start_id, never real content) and eos.
     */
    private fun detokenize(tgtVocab: List<String>, ids: IntArray, eosId: Int): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == eosId) continue
            if (id in tgtVocab.indices) sb.append(tgtVocab[id])
        }
        return sb.toString().replace("▁", " ").trim()
    }

    private fun scalarLongTensor(value: Int): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(value.toLong())), longArrayOf(1))

    private fun longTensor(values: IntArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(values.size) { values[it].toLong() }), shape)

    private fun firstTensor(result: OrtSession.Result): OnnxTensor {
        val value: OnnxValue = result.iterator().next().value
        return value as OnnxTensor
    }

    private fun flattenFloat3d(buffer: java.nio.FloatBuffer, seqLen: Int, hidden: Int): FloatArray {
        val out = FloatArray(seqLen * hidden)
        buffer.rewind()
        buffer.get(out)
        return out
    }

    /** argmax over the vocab dimension at the last position of a [1, curLen, vocab] logits tensor. */
    private fun argmaxLastStep(buffer: java.nio.FloatBuffer, curLen: Int): Int {
        buffer.rewind()
        val total = buffer.remaining()
        val vocab = total / curLen
        val lastStepStart = (curLen - 1) * vocab
        var bestId = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (v in 0 until vocab) {
            val score = buffer.get(lastStepStart + v)
            if (score > bestScore) {
                bestScore = score
                bestId = v
            }
        }
        return bestId
    }
}
