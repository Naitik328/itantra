package com.itantra.mt

/**
 * "Do not translate" placeholder wrap/restore -- the piece named explicitly
 * in docs/CLAUDE.md #5 task 12 ("number/URL placeholder substitution") and
 * spec #7.4. Ported from IndicTransToolkit's processor.pyx (VarunGumma,
 * part of the AI4Bharat IndicTrans2 tooling).
 *
 * Deliberate simplification vs. the Python source: that implementation
 * threads placeholder maps through a shared Queue so preprocess_batch/
 * postprocess_batch can be called across a whole batch asynchronously. This
 * project translates one chat message at a time through a synchronous
 * adapter call, so the map is just returned from [wrap] and passed back into
 * [restore] directly -- same behaviour, no shared mutable queue to get out
 * of sync if calls ever interleave.
 */
object Placeholders {

    private val EMAIL_PATTERN = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}")
    private val URL_PATTERN = Regex(
        "\\b(?<![\\w/.])(?:(?:https?|ftp)://)?(?:(?:[\\w-]+\\.)+(?!\\.))(?:[\\w/\\-?#&=%.]+)+(?!\\.\\w+)\\b"
    )
    private val NUMERAL_PATTERN = Regex(
        "(~?\\d+\\.?\\d*\\s?%?\\s?-?\\s?~?\\d+\\.?\\d*\\s?%|~?\\d+%|\\d+[-/.,:']\\d+[-/.,:'+]\\d+(?:\\.\\d+)?|\\d+[-/.:'+]\\d+(?:\\.\\d+)?)"
    )
    private val OTHER_PATTERN = Regex("[A-Za-z0-9]*[#|@]\\w+")

    // Garbled transliterations of "<ID>" that IndicTrans2 (this exact model
    // family) has been observed to produce for a placeholder token it failed
    // to pass through untouched. Ported verbatim from processor.pyx's
    // _INDIC_FAILURE_CASES since this is the same model family; keep in sync
    // if upstream extends the list, and add real observed failures from our
    // own exported checkpoint once we have them (spec #3.2 "measure, don't
    // guess" applies here too).
    private val INDIC_FAILURE_CASES = listOf(
        "آی ڈی ", "ꯑꯥꯏꯗꯤ", "आईडी", "आई . डी . ", "आई . डी .", "आई. डी. ", "आई. डी.",
        "आय. डी. ", "आय. डी.", "आय . डी . ", "आय . डी .आइ . डी . ", "आइ . डी .",
        "आइ. डी. ", "आइ. डी.", "ऐटि", "آئی ڈی ", "ᱟᱭᱰᱤ ᱾", "आयडी", "ऐडि", "आइडि", "ᱟᱭᱰᱤ",
    )

    data class WrapResult(val text: String, val placeholders: Map<String, String>)

    fun wrap(text: String): WrapResult {
        var t = text
        val placeholderMap = LinkedHashMap<String, String>()
        var serial = 1

        for (pattern in listOf(EMAIL_PATTERN, URL_PATTERN, NUMERAL_PATTERN, OTHER_PATTERN)) {
            val matches = pattern.findAll(t).map { it.value }.toSet()
            for (match in matches) {
                if (pattern === URL_PATTERN && match.replace(".", "").length < 4) continue
                if (pattern === NUMERAL_PATTERN &&
                    match.replace(" ", "").replace(".", "").replace(":", "").length < 4
                ) continue

                val base = "<ID$serial>"
                for (variant in placeholderVariants(serial)) {
                    placeholderMap[variant] = match
                }
                t = t.replace(match, base)
                serial++
            }
        }

        t = t.replace(Regex("\\s+"), " ").replace(">/", ">").replace("]/", "]")
        return WrapResult(t, placeholderMap)
    }

    fun restore(text: String, placeholders: Map<String, String>): String {
        var t = text
        for ((placeholder, original) in placeholders) {
            t = t.replace(placeholder, original)
        }
        return t
    }

    private fun placeholderVariants(serial: Int): List<String> {
        val variants = mutableListOf(
            "<ID$serial>", "< ID$serial >", "[ID$serial]", "[ ID$serial ]", "[ID $serial]",
            "<ID$serial]", "< ID$serial]", "<ID$serial ]",
            "<id$serial>", "< id$serial >", "[id$serial]", "[ id$serial ]", "[id $serial]",
            "<id$serial]", "< id$serial]", "<id$serial ]",
        )
        for (case in INDIC_FAILURE_CASES) {
            variants += listOf(
                "<$case$serial>", "< $case$serial >", "< $case $serial >",
                "<$case $serial]", "< $case $serial ]",
                "[$case$serial]", "[$case $serial]", "[ $case$serial ]", "[ $case $serial ]",
                "$case $serial", "$case$serial",
            )
        }
        return variants
    }
}
