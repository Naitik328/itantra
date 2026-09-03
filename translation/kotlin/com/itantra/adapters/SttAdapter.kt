package com.itantra.adapters

/**
 * docs/ITANTRA_INTEGRATION_SPEC.md #7.1. Interface only -- the real
 * sherpa-onnx-backed implementation (SherpaSttAdapter) lives outside this
 * branch's scope (STT is Shivanshu's area, spec #12). Defined here so
 * Orchestrator.kt can be written and compiled against the real contract
 * instead of a guess.
 */
interface SttAdapter {
    /** @param audio 16 kHz mono float32 in [-1, 1]
     *  @return native-script text, no punctuation, no casing, numbers as words */
    fun transcribe(audio: FloatArray): String
    fun close()
}
