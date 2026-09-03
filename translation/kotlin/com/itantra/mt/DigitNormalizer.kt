package com.itantra.mt

/**
 * Native-script digit -> ASCII digit translation table, scoped to the
 * scripts this project ships (hi, bn, te; en is already ASCII). Ported from
 * processor.pyx's _digits_translation_table.
 */
object DigitNormalizer {

    private val TABLE: Map<Char, Char> = buildMap {
        val devanagari = "०१२३४५६७८९"
        val bengali = "০১২৩৪৫৬৭৮৯"
        val telugu = "౦౧౨౩౪౫౬౭౮౯"
        for (script in listOf(devanagari, bengali, telugu)) {
            for ((i, c) in script.withIndex()) {
                put(c, '0' + i)
            }
        }
    }

    fun normalize(text: String): String = buildString(text.length) {
        for (c in text) append(TABLE[c] ?: c)
    }
}
