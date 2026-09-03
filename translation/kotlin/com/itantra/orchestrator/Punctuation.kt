package com.itantra.orchestrator

/**
 * docs/ITANTRA_INTEGRATION_SPEC.md #5.5. STT physically cannot emit
 * punctuation (not in the CTC vocabularies, CLAUDE.md #8), but IndicTrans2
 * was trained on punctuated text and degrades without it.
 *
 * This is the spec's own "minimum viable approach": append a full stop to
 * each VAD-segmented utterance, since one VAD segment is approximately one
 * sentence. Deliberately not a punctuation-restoration model -- that's
 * D5-blocked (docs/CLAUDE.md #2) and explicitly a fallback only "if the
 * simple approach demonstrably hurts translation quality" (spec #5.5),
 * which hasn't been measured. Don't add one speculatively.
 */
object Punctuation {
    fun restore(sttText: String): String {
        val trimmed = sttText.trim()
        if (trimmed.isEmpty()) return trimmed // spec #8 -- silence returns empty string, not invented words
        return if (trimmed.last() in ".!?") trimmed else "$trimmed."
    }
}
