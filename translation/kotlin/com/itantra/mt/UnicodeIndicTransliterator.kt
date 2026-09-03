package com.itantra.mt

/**
 * Port of indic_nlp_library's UnicodeIndicTransliterator (Anoop Kunchukuttan,
 * MIT licensed) -- offset-based transliteration between "coordinated" Brahmi
 * scripts: Devanagari, Bengali, Telugu (and others this project doesn't ship)
 * share the same relative layout within their Unicode blocks, so converting
 * script A -> B is just re-basing each codepoint's block offset.
 *
 * Why this exists at all: IndicTrans2's own preprocessing pipeline
 * transliterates every Indic-script sentence to Devanagari before feeding the
 * model, and transliterates the model's Devanagari output back to the
 * target script afterwards (see IndicProcessor.kt). This is not a display
 * nicety -- skipping it silently changes what text the model actually sees.
 */
object UnicodeIndicTransliterator {

    fun transliterate(text: String, srcLang: String, tgtLang: String): String {
        val srcRange = IndicScripts.SCRIPT_RANGES[srcLang]
        val tgtRange = IndicScripts.SCRIPT_RANGES[tgtLang]
        if (srcRange == null || tgtRange == null) return text

        val sb = StringBuilder(text.length)
        for (c in text) {
            val offset = c.code - srcRange.first
            val inCoordinatedRange = offset in IndicScripts.COORDINATED_RANGE_START_INCLUSIVE..IndicScripts.COORDINATED_RANGE_END_INCLUSIVE
            val isDanda = c.code == IndicScripts.DANDA || c.code == IndicScripts.DOUBLE_DANDA
            sb.append(if (inCoordinatedRange && !isDanda) (tgtRange.first + offset).toChar() else c)
        }
        return sb.toString()
    }
}
