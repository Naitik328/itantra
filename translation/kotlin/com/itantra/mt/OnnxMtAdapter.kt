package com.itantra.mt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer

/**
 * MT adapter for the IndicTrans2-Distilled ONNX models built by
 * tools/export_indictrans2_onnx.py + tools/quantize_and_verify.py.
 * See docs/ITANTRA_INTEGRATION_SPEC.md #7.4, #6.2 (residency), #6.3 (threads),
 * #6.4 (CPU only).
 *
 * Tokenization is IN-GRAPH (onnxruntime-extensions' SentencepieceTokenizer /
 * SentencepieceDecoder custom ops, baked into encoder.onnx / detokenizer.onnx
 * at export time -- see tools/tokenizer_graph.py) rather than a Kotlin
 * tokenizer implementation. This resolves what MtTokenizer.kt used to flag
 * as a blocking gap (no first-party Android SentencePiece binding); that
 * file and interface no longer exist. What this adapter now needs from
 * Android is just the `onnxruntime-extensions-android` AAR, loaded as a
 * custom-op library via [OrtxPackage.getLibraryPath] -- import path and
 * exact API not verified against the real AAR (no Kotlin toolchain in this
 * environment); confirm on first compile.
 *
 * Correctness of the baked-in tokenizer settings (add_bos/add_eos/
 * fairseq_vocab_shift) is NOT something this file can guarantee --
 * tools/quantize_and_verify.py's verify_tokenizer_ids() checks them against
 * the real HF tokenizer at export time and fails the export if they're
 * wrong. Only ship model files that passed that check.
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
 *   <modelRoot>/en-indic/encoder.int8.onnx     (raw text in, hidden states out)
 *   <modelRoot>/en-indic/decoder.int8.onnx     (ids in, logits out)
 *   <modelRoot>/en-indic/detokenizer.onnx      (ids in, text out)
 *   <modelRoot>/en-indic/vocab_ids.json        (decoder_start_id, eos_id, lang_tag_ids)
 *   <modelRoot>/indic-en/...                   (same four files)
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
) : MtAdapter {

    private data class VocabIds(
        val decoderStartId: Int,
        val eosId: Int,
        val langTagIds: Map<String, Int>, // FLORES tag -> vocab id
    )

    private class DirectionSession(
        val encoder: OrtSession,
        val decoder: OrtSession,
        val detokenizer: OrtSession,
        val vocab: VocabIds,
    ) {
        var lastUsedAtMillis: Long = System.currentTimeMillis()
        fun touch() {
            lastUsedAtMillis = System.currentTimeMillis()
        }

        fun close() {
            encoder.close()
            decoder.close()
            detokenizer.close()
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
        val tgtTagId = vocab.langTagIds[tgtTag]
            ?: error("No lang_tag_ids entry for '$tgtTag' in $direction/vocab_ids.json -- this direction can't decode into $targetLang.")

        val pre = IndicProcessor.preprocess(text, srcTag, tgtTag)
        val (encoderHidden, srcLen, hiddenSize) = runEncoder(session.encoder, pre.text)
        val outputIds = greedyDecode(session.decoder, srcLen, encoderHidden, hiddenSize, vocab, tgtTagId)
        val decoded = runDetokenizer(session.detokenizer, outputIds)

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
            val dir = File(modelRoot, direction)
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(numThreads) // spec #6.3
                // CPU only -- spec #6.4: NNAPI is untested and known to partition
                // VITS/Conformer graphs poorly; no execution provider is added here.
                registerCustomOpLibrary(OrtxPackage.getLibraryPath()) // ai.onnx.contrib tokenizer ops
            }
            DirectionSession(
                encoder = env.createSession(File(dir, "encoder.int8.onnx").absolutePath, opts),
                decoder = env.createSession(File(dir, "decoder.int8.onnx").absolutePath, opts),
                detokenizer = env.createSession(File(dir, "detokenizer.onnx").absolutePath, opts),
                vocab = loadVocabIds(File(dir, "vocab_ids.json")),
            )
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

    /** @return (flattened encoder_hidden_states, srcLen, hiddenSize) */
    private fun runEncoder(encoder: OrtSession, text: String): Triple<FloatArray, Int, Int> {
        OnnxTensor.createTensor(env, arrayOf(text)).use { textTensor ->
            encoder.run(mapOf("raw_text" to textTensor)).use { result ->
                val output = firstTensor(result)
                val shape = output.info.shape // [1, srcLen, hidden] -- srcLen is only known now,
                val srcLen = shape[1].toInt()  // decided by the in-graph tokenizer, not by us.
                val hidden = shape[2].toInt()
                val flat = flattenFloat3d(output.floatBuffer, srcLen, hidden)
                return Triple(flat, srcLen, hidden)
            }
        }
    }

    private fun greedyDecode(
        decoder: OrtSession,
        srcLen: Int,
        encoderHidden: FloatArray,
        hiddenSize: Int,
        vocab: VocabIds,
        tgtTagId: Int,
    ): IntArray {
        val decoderIds = mutableListOf(vocab.decoderStartId, tgtTagId)

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

    private fun runDetokenizer(detokenizer: OrtSession, ids: IntArray): String {
        longTensor(ids, longArrayOf(1, ids.size.toLong())).use { idsTensor ->
            detokenizer.run(mapOf("ids" to idsTensor)).use { result ->
                val value = result.iterator().next().value
                @Suppress("UNCHECKED_CAST")
                return ((value as OnnxTensor).value as Array<String>)[0]
            }
        }
    }

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
