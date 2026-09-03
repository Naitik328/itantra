package com.itantra.mt

/**
 * Port of indic_nlp_library's trivial_tokenize/trivial_detokenize
 * (Anoop Kunchukuttan, MIT licensed). Script-agnostic: it only splits on
 * ASCII punctuation plus the two Indic sentence delimiters (danda U+0964,
 * double danda U+0965), so the same code serves hi/bn/te.
 */
object IndicTokenizer {

    // Python's string.punctuation, exactly -- built as a literal char list
    // (rather than a hand-escaped regex character class) so there's no risk
    // of silently dropping the literal backslash or mis-escaping ']'/'-'.
    private val ASCII_PUNCT: List<Char> = listOf(
        '!', '"', '#', '$', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/',
        ':', ';', '<', '=', '>', '?', '@', '[', '\\', ']', '^', '_', '`', '{', '|', '}', '~',
    )
    private val DANDA_CHARS: List<Char> = listOf('।', '॥')

    private val TOKEN_PATTERN: Regex = (ASCII_PUNCT + DANDA_CHARS)
        .joinToString("|") { Regex.escape(it.toString()) }
        .let { Regex("($it)") }

    // A run of "<digits> <sep> ..." (date/number-like) shouldn't be split on spaces.
    private val NUM_SEQ = Regex("([0-9]+ [,.:/] )+[0-9]+")

    fun tokenize(text: String): List<String> {
        val spaced = TOKEN_PATTERN.replace(text.replace("\t", " ")) { " ${it.value} " }
        val collapsed = spaced.replace(Regex("[ ]+"), " ").trim(' ')
        return rejoinNumberSequences(collapsed).split(" ")
    }

    fun detokenize(text: String): String {
        var s = rejoinNumberSequences(text)

        val leftAttach = listOf('!', '%', ')', ']', '}', ',', '.', ':', ';', '>', '?', '।', '॥')
        val rightAttach = listOf('#', '$', '(', '[', '{', '<', '@')
        val lrAttach = listOf('-', '/', '\\')

        fun classPattern(chars: List<Char>) = chars.joinToString("|") { Regex.escape(it.toString()) }

        s = Regex("[ ](${classPattern(lrAttach)})[ ]").replace(s) { it.groupValues[1] }
        s = Regex("[ ](${classPattern(leftAttach)})").replace(s) { it.groupValues[1] }
        s = Regex("(${classPattern(rightAttach)})[ ]").replace(s) { it.groupValues[1] }

        for (quote in charArrayOf('\'', '"', '`')) {
            val sb = StringBuilder()
            var count = 0
            for (c in s) {
                if (c == quote) {
                    sb.append(if (count % 2 == 0) "@RA" else "@LA")
                    count++
                } else {
                    sb.append(c)
                }
            }
            s = sb.toString()
                .replace("@RA ", quote.toString())
                .replace(" @LA", quote.toString())
                .replace("@RA", quote.toString())
                .replace("@LA", quote.toString())
        }
        return s
    }

    private fun rejoinNumberSequences(text: String): String {
        val sb = StringBuilder()
        var prev = 0
        for (m in NUM_SEQ.findAll(text)) {
            if (m.range.first > prev) {
                sb.append(text, prev, m.range.first)
                sb.append(text.substring(m.range.first, m.range.last + 1).replace(" ", ""))
                prev = m.range.last + 1
            }
        }
        sb.append(text.substring(prev))
        return sb.toString()
    }
}
