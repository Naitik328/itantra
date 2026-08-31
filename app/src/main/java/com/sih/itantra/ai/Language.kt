package com.sih.itantra.ai

/**
 * The languages the relay can carry, and the single source of truth for the frame's `lang` byte.
 *
 * The wire format spends one byte on language ([com.sih.itantra.wifidirect.Packet]); this enum
 * pins which integer means which language so both ends agree. The [code] is what travels on the
 * wire — never the ordinal, so reordering this enum can't silently repoint every in-flight frame
 * at the wrong voice.
 *
 * [espeakVoice] is the espeak-ng voice id Piper models phonemize against. Only [HINDI] has a
 * bundled model today; the rest are declared so the selector, the frame byte and the download
 * manager all have their slot the moment AI Member 1 hands over each model.
 */
enum class Language(
    /** Value written to the frame's `lang` byte. Stable across releases. */
    val code: Int,
    /** Endonym shown in the language chip and selector. */
    val displayName: String,
    /** espeak-ng voice id used by the Piper phonemizer. */
    val espeakVoice: String,
) {
    HINDI(0, "हिन्दी", "hi"),
    BENGALI(1, "বাংলা", "bn"),
    TAMIL(2, "தமிழ்", "ta"),
    TELUGU(3, "తెలుగు", "te"),
    MARATHI(4, "मराठी", "mr"),
    GUJARATI(5, "ગુજરાતી", "gu"),
    KANNADA(6, "ಕನ್ನಡ", "kn"),
    MALAYALAM(7, "മലയാളം", "ml"),
    PUNJABI(8, "ਪੰਜਾਬੀ", "pa"),
    ODIA(9, "ଓଡ଼ିଆ", "or");

    companion object {
        /** The relay's default until the user picks otherwise — the one language we ship a voice for. */
        val DEFAULT = HINDI

        /**
         * Resolve a wire `lang` byte back to a language, falling back to [DEFAULT] for any code we
         * don't recognise so a malformed or future-versioned frame is still spoken, not dropped.
         */
        fun fromCode(code: Int): Language = entries.firstOrNull { it.code == code } ?: DEFAULT
    }
}
