package com.itantra.mt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.LongBuffer

/**
 * MT adapter for the IndicTrans2-Distilled ONNX models built by
 * tools/export_indictrans2_onnx.py + tools/quantize_and_verify.py.
 * See docs/ITANTRA_INTEGRATION_SPEC.md #7.4, #6.2 (residency), #6.3 (threads),
 * #6.4 (CPU only).
 *
 * NOT RUNNABLE YET: this depends on [MtTokenizer], which has no
 * implementation -- see MtTokenizer.kt's doc comment for why that's a real
 * unresolved dependency and not just an unfilled detail. Everything else
 * here (session lifecycle, tensor construction, the decode loop) is meant
 * to be reviewed and exercised on its own; only wire it to a live
 * `OrtEnvironment` + real model files once a tokenizer exists.
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
 *   <modelRoot>/en-indic/encoder.int8.onnx
 *   <modelRoot>/en-indic/decoder.int8.onnx
 *   <modelRoot>/indic-en/encoder.int8.onnx
 *   <modelRoot>/indic-en/decoder.int8.onnx
 * (SHA256SUMS.txt lives alongside each pair, written by quantize_and_verify.py.)
 *
 * Decoder has no KV cache (see export_indictrans2_onnx.py's module doc) --
 * every decode step re-runs the decoder over the full prefix so far. This
 * mirrors indictrans_common.py's greedy_decode_onnx exactly; if that
 * reference loop changes, this one must change with it.
 */
class OnnxMtAdapter(
    private val env: OrtEnvironment,
    private val modelRoot: File,
    private val tokenizerFactory: MtTokenizerFactory,
    private val numThreads: Int = 4, // spec #6.3 -- shared numThreads, not a per-language value
    private val maxNewTokens: Int = 128, // spec #7.4 -- chat messages are short
    private val idleTimeoutMillis: Long = 30_000, // spec #6.2 tiered residency
) : MtAdapter {

    private class DirectionSession(
        val encoder: OrtSession,
        val decoder: OrtSession,
        val tokenizer: MtTokenizer,
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
        val tokenizer = session.tokenizer

        val pre = IndicProcessor.preprocess(text, srcTag, tgtTag)
        val inputIds = tokenizer.encode(pre.text)

        val (encoderHidden, hiddenSize) = runEncoder(session.encoder, inputIds)
        val tgtTagId = tokenizer.tokenToId(tgtTag)
        val outputIds = greedyDecode(session.decoder, inputIds.size, encoderHidden, hiddenSize, tokenizer, tgtTagId)

        session.touch()
        val decoded = tokenizer.decode(outputIds)
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
            }
            DirectionSession(
                encoder = env.createSession(File(dir, "encoder.int8.onnx").absolutePath, opts),
                decoder = env.createSession(File(dir, "decoder.int8.onnx").absolutePath, opts),
                tokenizer = tokenizerFactory(direction),
            )
        }.also { it.touch() }

    /** @return (flattened encoder_hidden_states, hiddenSize) */
    private fun runEncoder(encoder: OrtSession, inputIds: IntArray): Pair<FloatArray, Int> {
        val srcLen = inputIds.size
        val idsTensor = longTensor(inputIds, longArrayOf(1, srcLen.toLong()))
        val maskTensor = longTensor(IntArray(srcLen) { 1 }, longArrayOf(1, srcLen.toLong()))

        idsTensor.use { ids ->
            maskTensor.use { mask ->
                encoder.run(mapOf("input_ids" to ids, "attention_mask" to mask)).use { result ->
                    val output = firstTensor(result)
                    val shape = output.info.shape // [1, srcLen, hidden]
                    val hidden = shape[2].toInt()
                    val flat = flattenFloat3d(output.floatBuffer, srcLen, hidden)
                    return flat to hidden
                }
            }
        }
    }

    private fun greedyDecode(
        decoder: OrtSession,
        srcLen: Int,
        encoderHidden: FloatArray,
        hiddenSize: Int,
        tokenizer: MtTokenizer,
        tgtTagId: Int,
    ): IntArray {
        val decoderIds = mutableListOf(tokenizer.bosId, tgtTagId)

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
                if (nextId == tokenizer.eosId) return decoderIds.toIntArray()
            }
            return decoderIds.toIntArray()
        } finally {
            hiddenTensor.close()
            maskTensor.close()
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
