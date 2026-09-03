package com.itantra.mt

/**
 * What OnnxMtAdapter needs from a tokenizer. IndicTrans2 uses two separate
 * SentencePiece models (one per translation direction, per AI4Bharat's
 * IndicTransTokenizer) -- not a shared vocab -- so [OnnxMtAdapter] asks its
 * [MtTokenizerFactory] for a fresh instance per direction, not per language.
 *
 * STOP -- no implementation exists yet. This is a real, unresolved
 * dependency, not a detail to fill in later quietly: Android has no
 * first-party SentencePiece binding, and this project's checkpoints need
 * exact byte-for-byte tokenization to match what they were trained on (a
 * hand-rolled BPE/unigram tokenizer would silently degrade translation
 * quality the same way a wrong `normalize_type` silently degrades STT --
 * see docs/CLAUDE.md #8). Options, to be decided by whoever picks this up:
 *   - Bind Google's sentencepiece C++ library via JNI (no official AAR;
 *     would need building one, or vendoring a community fork).
 *   - Export tokenization *into* the ONNX graph itself using
 *     onnxruntime-extensions' SentencePieceTokenizer custom op -- would
 *     mean reworking tools/export_indictrans2_onnx.py to bundle it, and
 *     adding the onnxruntime-extensions native dependency on Android.
 * Until one of these exists, [OnnxMtAdapter] cannot actually run --
 * everything else in this file is real and independently reviewable, but
 * say so plainly rather than have this look done when it isn't.
 */
interface MtTokenizer {
    val bosId: Int
    val eosId: Int

    /** @return the vocabulary id for an exact token string, e.g. a FLORES tag like "hin_Deva". */
    fun tokenToId(token: String): Int

    fun encode(text: String): IntArray
    fun decode(ids: IntArray): String
}

/** direction: "en-indic" or "indic-en" (see indictrans_common.py's CHECKPOINTS). */
typealias MtTokenizerFactory = (direction: String) -> MtTokenizer
