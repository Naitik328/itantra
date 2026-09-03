package com.itantra.mt

/**
 * Kotlin port of IndicTransToolkit's IndicProcessor (processor.pyx,
 * VarunGumma / AI4Bharat, MIT licensed) -- the preprocessing IndicTrans2
 * requires and the postprocessing that undoes it. Referenced from
 * docs/CLAUDE.md #4 task 12 and docs/ITANTRA_INTEGRATION_SPEC.md #7.4,
 * #3.5 ("MT quality degrades badly without it").
 *
 * Scoped to this project's four languages (en, hi, te, bn; see
 * docs/CLAUDE.md #1) rather than IndicTrans2's full 22+. Extending to a
 * fifth language means adding a FLORES tag here and a normalizer/tokenizer
 * branch in IndicNormalizer/IndicScripts -- not touching this file's logic.
 *
 * API shape deliberately differs from the Python source: the original
 * exposes batch methods (preprocess_batch/postprocess_batch) with a shared
 * Queue threading placeholder maps between them, built for offline corpus
 * translation. This project translates one chat message at a time through
 * OnnxMtAdapter, so [preprocess] returns the placeholder map directly and
 * [postprocess] takes it as a parameter -- same transformation, no shared
 * mutable state to get out of sync.
 *
 * Pipeline this implements (see _preprocess/_postprocess in processor.pyx):
 *   preprocess:  punctuation normalization -> digit normalization ->
 *                DNT placeholder wrapping -> (English: Moses normalize +
 *                tokenize) or (Indic: script normalize -> trivial tokenize
 *                -> transliterate to Devanagari) -> prepend "<src> <tgt>" tag
 *   postprocess: restore placeholders -> (English: Moses detokenize) or
 *                (Indic: transliterate from Devanagari -> trivial detokenize)
 */
object IndicProcessor {

    /** FLORES-200 tag -> this project's internal ISO code (spec #4.1 tags). */
    private val FLORES_TO_ISO = mapOf(
        "eng_Latn" to "en",
        "hin_Deva" to "hi",
        "tel_Telu" to "te",
        "ben_Beng" to "bn",
    )

    data class PreprocessResult(val text: String, val placeholders: Map<String, String>)

    /**
     * @param srcLangTag FLORES tag, e.g. "hin_Deva"
     * @param tgtLangTag FLORES tag of the translation target -- prepended as
     *   the model's language-pair prompt, per IndicTrans2's input format.
     * @param isTarget true when preprocessing target-side text that will NOT
     *   be fed to the encoder (e.g. building a reference for local scoring);
     *   normal inference calls should leave this false.
     */
    fun preprocess(text: String, srcLangTag: String, tgtLangTag: String, isTarget: Boolean = false): PreprocessResult {
        val isoLang = FLORES_TO_ISO[srcLangTag]
            ?: error("No FLORES tag configured for '$srcLangTag'. Add it to IndicProcessor.FLORES_TO_ISO -- don't guess one.")

        var sent = puncNorm(text)
        val digitsNormalized = DigitNormalizer.normalize(sent)
        val wrapped = Placeholders.wrap(digitsNormalized)
        sent = wrapped.text

        val processed = if (isoLang == "en") {
            val normalized = EnglishTextNormalizer.normalizePunctuation(sent.trim())
            EnglishTextNormalizer.tokenize(normalized).joinToString(" ")
        } else {
            val normalized = IndicNormalizer.normalize(sent.trim(), isoLang)
            val tokenized = IndicTokenizer.tokenize(normalized).joinToString(" ")
            UnicodeIndicTransliterator.transliterate(tokenized, isoLang, "hi")
                .replace(" ् ", "्") // matches processor.pyx's post-transliteration halant fixup
        }.trim()

        val finalText = if (!isTarget) "$srcLangTag $tgtLangTag $processed" else processed
        return PreprocessResult(finalText, wrapped.placeholders)
    }

    fun postprocess(modelOutput: String, tgtLangTag: String, placeholders: Map<String, String>): String {
        val isoLang = FLORES_TO_ISO[tgtLangTag]
            ?: error("No FLORES tag configured for '$tgtLangTag'. Add it to IndicProcessor.FLORES_TO_ISO -- don't guess one.")

        var sent = modelOutput
        sent = Placeholders.restore(sent, placeholders)

        return if (tgtLangTag == "eng_Latn") {
            EnglishTextNormalizer.detokenize(sent.split(" "))
        } else {
            val backTransliterated = UnicodeIndicTransliterator.transliterate(sent, "hi", isoLang)
            IndicTokenizer.detokenize(backTransliterated)
        }
    }

    /**
     * processor.pyx's _punc_norm: a small Moses-derived punctuation pass
     * applied to ALL languages before either the English or Indic branch --
     * distinct from (and in addition to) IndicNormalizer's own punctuation
     * pass, which only runs on the Indic branch. Both exist in the upstream
     * pipeline; this keeps them both to match its actual behaviour.
     *
     * Order matters and matches _punc_norm exactly: the 14-rule
     * PUNC_REPLACEMENTS pass runs to completion first, THEN the four
     * follow-up regexes below. Two PUNC_REPLACEMENTS entries ("nº " -> "nº "
     * and " ºC" -> " ºC") and one follow-up-adjacent rule (", " -> ", ")
     * are identity substitutions in the Python source (replacement equals
     * the match) and are omitted here as true no-ops.
     */
    private val PUNC_REPLACEMENTS: List<Pair<Regex, String>> = listOf(
        Regex("\r") to "",
        Regex("\\(\\s*") to "(",
        Regex("\\s*\\)") to ")",
        Regex("\\s:\\s?") to ":",
        Regex("\\s;\\s?") to ";",
        Regex("[`´‘‚’]") to "'",
        Regex("[„“”«»]") to "\"",
        Regex("[–—]") to "-",
        Regex("\\.\\.\\.") to "...",
        Regex(" %") to "%",
    )

    private fun puncNorm(text: String): String {
        var t = text
        for ((pattern, replacement) in PUNC_REPLACEMENTS) {
            t = pattern.replace(t, replacement)
        }
        t = Regex(" [?!;]").replace(t) { it.value.trim() } // last PUNC_REPLACEMENTS rule, in position
        t = t.replace(Regex("[ ]{2,}"), " ")
        t = Regex("\\) ([.!:?;,])").replace(t) { ")${it.groupValues[1]}" }
        t = Regex("(\\d) %").replace(t) { "${it.groupValues[1]}%" }
        t = Regex("\"([,.]+)").replace(t) { "${it.groupValues[1]}\"" }
        t = Regex("(\\d) (\\d)").replace(t) { "${it.groupValues[1]}.${it.groupValues[2]}" }
        return t.trim()
    }
}
