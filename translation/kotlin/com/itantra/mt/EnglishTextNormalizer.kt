package com.itantra.mt

/**
 * English side of preprocessing/postprocessing.
 *
 * [normalizePunctuation] is a faithful port of sacremoses' MosesPunctNormalizer
 * (default config: lang="en", penn=true, norm_quote_commas=true, norm_numbers=true
 * -- exactly what IndicTransToolkit's processor.pyx calls). It's a fixed, small
 * rule list, so porting it in full was cheap and worth doing exactly.
 *
 * [tokenize]/[detokenize] are NOT a full port of MosesTokenizer/MosesDetokenizer.
 * The real ones carry a several-hundred-entry per-language "non-breaking
 * prefix" abbreviation list (Dr., Mr., U.S., etc.) and protect URLs/numbers
 * during escaping -- real engineering, but disproportionate to this app's
 * input: short, single-sentence chat messages, not documents. What's here
 * covers the common cases (splitting punctuation off words, contractions,
 * quotes) and is deliberately scoped down. If English MT quality is
 * hurt by mis-tokenized abbreviations or decimals, come back and port the
 * real non-breaking-prefix table -- don't guess now (same principle as the
 * KV-cache decision in export_indictrans2_onnx.py).
 */
object EnglishTextNormalizer {

    private val EXTRA_WHITESPACE: List<Pair<Regex, String>> = listOf(
        Regex("\r") to "",
        Regex("\\(") to " (",
        Regex("\\)") to ") ",
        Regex(" +") to " ",
        Regex("\\) ([.!:?;,])") to ")$1",
        Regex("\\( ") to "(",
        Regex(" \\)") to ")",
        Regex("(\\d) %") to "$1%",
        Regex(" :") to ":",
        Regex(" ;") to ";",
    )

    private val NORMALIZE_UNICODE_IF_NOT_PENN: List<Pair<Regex, String>> = listOf(
        Regex("`") to "'",
        Regex("''") to " \" ",
    )

    private val NORMALIZE_UNICODE: List<Pair<Regex, String>> = listOf(
        Regex("„") to "\"",
        Regex("“") to "\"",
        Regex("”") to "\"",
        Regex("–") to "-",
        Regex("—") to " - ",
        Regex(" +") to " ",
        Regex("´") to "'",
        Regex("([a-zA-Z])‘([a-zA-Z])") to "$1'$2",
        Regex("([a-zA-Z])’([a-zA-Z])") to "$1'$2",
        Regex("‘") to "'",
        Regex("‚") to "'",
        Regex("’") to "'",
        Regex("''") to "\"",
        Regex("´´") to "\"",
        Regex("…") to "...",
    )

    private val FRENCH_QUOTES: List<Pair<Regex, String>> = listOf(
        Regex(" « ") to "\"",
        Regex("« ") to "\"",
        Regex("«") to "\"",
        Regex(" » ") to "\"",
        Regex(" »") to "\"",
        Regex("»") to "\"",
    )

    private val HANDLE_PSEUDO_SPACES: List<Pair<Regex, String>> = listOf(
        Regex(" %") to "%",
        Regex("nº ") to "nº ",
        Regex(" :") to ":",
        Regex(" ºC") to " ºC",
        Regex(" cm") to " cm",
        Regex(" \\?") to "?",
        Regex(" !") to "!",
        Regex(" ;") to ";",
        Regex(", ") to ", ",
        Regex(" +") to " ",
    )

    // lang == "en": quote-comma rule
    private val EN_QUOTATION_FOLLOWED_BY_COMMA: List<Pair<Regex, String>> = listOf(
        Regex("\"([,.]+)") to "$1\"",
    )

    // lang not in {de,es,cz,cs,fr}: the "OTHER" number rule
    private val OTHER_DIGIT_NBSP_DIGIT: List<Pair<Regex, String>> = listOf(
        Regex("(\\d) (\\d)") to "$1.$2",
    )

    private val SUBSTITUTIONS: List<Pair<Regex, String>> =
        EXTRA_WHITESPACE + NORMALIZE_UNICODE_IF_NOT_PENN + NORMALIZE_UNICODE +
            FRENCH_QUOTES + HANDLE_PSEUDO_SPACES + EN_QUOTATION_FOLLOWED_BY_COMMA + OTHER_DIGIT_NBSP_DIGIT

    fun normalizePunctuation(text: String): String {
        var t = text
        for ((pattern, replacement) in SUBSTITUTIONS) {
            t = pattern.replace(t, replacement)
        }
        return t.trim()
    }

    // ---- reduced tokenizer/detokenizer (see class doc) ------------------

    private val CONTRACTION_SUFFIXES = listOf(
        "n't", "'re", "'ve", "'ll", "'d", "'s", "'m",
    )

    private val PUNCT_SPLIT = Regex("([.,!?;:\"()\\[\\]{}])")

    fun tokenize(text: String): List<String> {
        var t = " $text "
        for (suffix in CONTRACTION_SUFFIXES) {
            t = Regex("(?i)(\\w)(${Regex.escape(suffix)})\\b").replace(t) { "${it.groupValues[1]} ${it.groupValues[2]}" }
        }
        t = PUNCT_SPLIT.replace(t) { " ${it.value} " }
        return t.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    }

    fun detokenize(tokens: List<String>): String {
        val sb = StringBuilder()
        val noSpaceBefore = setOf(".", ",", "!", "?", ";", ":", ")", "]", "}", "n't", "'re", "'ve", "'ll", "'d", "'s", "'m")
        val noSpaceAfter = setOf("(", "[", "{")
        var prev: String? = null
        for (tok in tokens) {
            val needsSpace = sb.isNotEmpty() && tok !in noSpaceBefore && prev !in noSpaceAfter
            if (needsSpace) sb.append(' ')
            sb.append(tok)
            prev = tok
        }
        return sb.toString()
    }
}
