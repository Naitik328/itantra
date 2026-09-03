package com.itantra.mt

/**
 * Unicode block facts for the Brahmi-derived Indic scripts this project ships
 * (docs/CLAUDE.md #1: Hindi, Telugu, Bengali). Values are the constants from
 * indic_nlp_library's indicnlp/langinfo.py (Anoop Kunchukuttan, MIT licensed) --
 * not invented here. Devanagari/Bengali/Telugu are "coordinated" scripts: the
 * same relative offset within each block names the structurally equivalent
 * letter (e.g. offset 0x15 is "ka" in all three), which is what makes
 * [UnicodeIndicTransliterator] possible without a per-character lookup table.
 */
object IndicScripts {
    /** iso code -> [blockStart, blockEnd] inclusive. */
    val SCRIPT_RANGES: Map<String, IntRange> = mapOf(
        "hi" to 0x0900..0x097f,
        "bn" to 0x0980..0x09ff,
        "te" to 0x0c00..0x0c7f,
    )

    const val COORDINATED_RANGE_START_INCLUSIVE = 0x00
    const val COORDINATED_RANGE_END_INCLUSIVE = 0x6f

    const val HALANTA_OFFSET = 0x4d

    const val DANDA = 0x0964
    const val DOUBLE_DANDA = 0x0965

    fun blockStart(lang: String): Int =
        SCRIPT_RANGES[lang]?.first ?: error("No SCRIPT_RANGES entry for '$lang'")

    fun offset(c: Char, lang: String): Int = c.code - blockStart(lang)

    fun offsetToChar(offset: Int, lang: String): Char = (blockStart(lang) + offset).toChar()

    fun isConsonantOffset(offset: Int): Boolean = offset in 0x15..0x39
}
