package com.itantra.mt

/** docs/ITANTRA_INTEGRATION_SPEC.md #7.1 -- the common adapter interface. */
interface MtAdapter {
    fun translate(text: String, sourceLang: String, targetLang: String): String
    fun close()
}
