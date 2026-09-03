package com.itantra.mt

/**
 * Port of indic_nlp_library's normalizer classes (Anoop Kunchukuttan, MIT
 * licensed), scoped to the languages this project ships (hi, bn, te) and to
 * the default flags IndicTransToolkit's IndicProcessor actually uses --
 * `IndicNormalizerFactory().get_normalizer(iso_code)` is called with no
 * kwargs, so remove_nuktas/nasals_mode/chandra/vowel-ending normalization
 * (all opt-in in the Python library) are never enabled and are not ported.
 *
 * Every non-ASCII character below is a \u escape naming an exact codepoint,
 * never a pasted glyph -- several of these rules exist specifically to
 * collapse two Unicode representations that *render identically* (a
 * two-part dependent vowel vs. its precomposed form, a nukta letter vs. its
 * decomposed base+nukta pair). Codepoints are cross-checked against
 * indic_normalize.py's DevanagariNormalizer / BengaliNormalizer /
 * TeluguNormalizer (Anoop Kunchukuttan, MIT licensed).
 *
 * What every normalizer does unconditionally (BaseNormalizer.normalize in
 * the Python source):
 *  1. Strip BOM, word joiner, soft hyphen, ZWNJ/ZWJ; ZWSP and NBSP -> space.
 *  2. A Moses-derived punctuation pass (smart quotes/dashes/ellipsis -> ASCII).
 * Then each script subclass fixes composed-nukta letters, canonicalizes the
 * script's own purna-virama codepoint to the shared U+0964/U+0965 danda, and
 * a couple of script-specific two-part vowel / visarga corrections.
 */
object IndicNormalizer {

    private const val BOM = "﻿"
    private const val BOM2 = "￾"
    private const val WORD_JOINER = "⁠"
    private const val SOFT_HYPHEN = "­"
    private const val ZERO_WIDTH_SPACE = "​"
    private const val NO_BREAK_SPACE = " "
    private const val ZWNJ = "‌"
    private const val ZWJ = "‍"

    private fun baseNormalize(text: String): String {
        var t = text
        t = t.replace(BOM, "").replace(BOM2, "")
        t = t.replace(WORD_JOINER, "").replace(SOFT_HYPHEN, "")
        t = t.replace(ZERO_WIDTH_SPACE, " ").replace(NO_BREAK_SPACE, " ")
        t = t.replace(ZWNJ, "").replace(ZWJ, "")
        return normalizePunctuations(t)
    }

    /** NormalizerI._normalize_punctuations -- a small subset of the Moses punct rules. */
    private fun normalizePunctuations(text: String): String {
        var t = text
        t = t.replace(BOM, "")
        t = t.replace("„", "\"").replace("“", "\"").replace("”", "\"")
        t = t.replace("–", "-").replace("—", " - ")
        t = t.replace("´", "'").replace("‘", "'").replace("‚", "'").replace("’", "'")
        t = t.replace("''", "\"").replace("´´", "\"")
        t = t.replace("…", "...")
        return t
    }

    fun normalize(text: String, lang: String): String = when (lang) {
        "hi" -> normalizeDevanagari(text)
        "bn" -> normalizeBengali(text)
        "te" -> normalizeTelugu(text)
        else -> baseNormalize(text)
    }

    // ---- Devanagari (hi) --------------------------------------------------

    private fun normalizeDevanagari(text: String): String {
        var t = baseNormalize(text)
        val nukta = "़"
        t = t.replace("ॲ", "ए") // candra-a (Marathi) -- no-op on plain Hindi text
        t = t.replace("ऩ", "न$nukta")
        t = t.replace("ऱ", "र$nukta")
        t = t.replace("ऴ", "ळ$nukta")
        t = t.replace("क़", "क$nukta")
        t = t.replace("ख़", "ख$nukta")
        t = t.replace("ग़", "ग$nukta")
        t = t.replace("ज़", "ज$nukta")
        t = t.replace("ड़", "ड$nukta")
        t = t.replace("ढ़", "ढ$nukta")
        t = t.replace("फ़", "फ$nukta")
        t = t.replace("य़", "य$nukta")
        t = t.replace('|', '।') // pipe -> danda
        t = Regex("([\\u0900-\\u097f]):").replace(t) { "${it.groupValues[1]}ः" } // colon -> visarga
        return t
    }

    // ---- Bengali (bn) -------------------------------------------------------

    private fun normalizeBengali(text: String): String {
        var t = baseNormalize(text)
        val nukta = "়"
        t = t.replace("ড়", "ড$nukta")
        t = t.replace("ঢ়", "ঢ$nukta")
        t = t.replace("য়", "য$nukta")
        t = t.replace('৤', '।').replace('৥', '॥') // script-local danda -> shared
        t = t.replace('|', '।')
        t = t.replace('৷', '।') // currency numerator four, used as a danda substitute
        t = t.replace("ো", "ো") // two-part dependent vowel -> precomposed O
        t = t.replace("ৌ", "ৌ") // two-part dependent vowel -> precomposed AU
        t = Regex("([\\u0980-\\u09ff]):").replace(t) { "${it.groupValues[1]}ঃ" } // colon -> visarga
        return t
    }

    // ---- Telugu (te) ---------------------------------------------------------

    private fun normalizeTelugu(text: String): String {
        var t = baseNormalize(text)
        t = t.replace('౤', '।').replace('౥', '॥') // script-local danda -> shared
        t = t.replace("ై", "ై") // two-part dependent vowel -> precomposed AI
        t = Regex("([\\u0c00-\\u0c7f]):").replace(t) { "${it.groupValues[1]}ః" } // colon -> visarga
        return t
    }
}
