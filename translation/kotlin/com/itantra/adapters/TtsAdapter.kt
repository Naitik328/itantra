package com.itantra.adapters

/**
 * docs/ITANTRA_INTEGRATION_SPEC.md #7.1. Interface only -- the real
 * sherpa-onnx-backed implementation (SherpaTtsAdapter) lives outside this
 * branch's scope (TTS is Raj's area, spec #12). Defined here so
 * Orchestrator.kt can be written and compiled against the real contract
 * instead of a guess.
 */
interface TtsAdapter {
    /** @return 22050 Hz mono 16-bit PCM */
    fun synthesize(text: String): ByteArray
    fun close()
}
