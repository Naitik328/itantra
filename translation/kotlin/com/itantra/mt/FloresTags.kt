package com.itantra.mt

/**
 * FLORES-200 tag <-> this project's ISO codes (docs/ITANTRA_INTEGRATION_SPEC.md
 * #4.1). IndicTrans2 expects a FLORES tag as the first source token and as
 * the forced first decoder token; languages.json and every adapter use the
 * short ISO codes ("hi", "te", ...). Single source of truth for both
 * directions of that mapping -- extend here, not per-caller, when a fifth
 * language ships.
 */
object FloresTags {

    private val ISO_TO_FLORES = mapOf(
        "en" to "eng_Latn",
        "hi" to "hin_Deva",
        "te" to "tel_Telu",
        "bn" to "ben_Beng",
    )

    private val FLORES_TO_ISO = ISO_TO_FLORES.entries.associate { (k, v) -> v to k }

    fun flores(iso: String): String =
        ISO_TO_FLORES[iso] ?: error("No FLORES tag configured for '$iso'. Add it to FloresTags -- don't guess one.")

    fun iso(flores: String): String =
        FLORES_TO_ISO[flores] ?: error("No ISO code configured for FLORES tag '$flores'. Add it to FloresTags -- don't guess one.")
}
