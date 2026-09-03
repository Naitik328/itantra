package com.itantra.config

/**
 * Data classes for languages.json (spec #4.3, the JSON schema copied
 * verbatim into translation/config/languages.json with one addition -- the
 * "mt" block, since the spec's own example predates the MT stage existing).
 * Ship as a JSON asset, not Kotlin constants -- CLAUDE.md #4.1: "Everything
 * language-specific comes from languages.json." Nothing here hardcodes a
 * per-language value; ConfigLoader.kt is the only place that reads the file.
 */

data class SttConfig(
    val model: String,
    val tokens: String,
    val modelType: String,
    val lexicon: String? = null, // English only, spec #7.2.1
)

data class TtsConfig(
    val model: String,
    val tokens: String,
    val espeakVoice: String,
    val lengthScale: Float,
    val speakerId: Int?, // Bengali's Trap 2 (spec #4.2) -- must come from here, never hardcoded
)

/** stt is null for Bengali (no STT model, spec #3.4 receive-only) -- callers must handle this, not crash on it. */
data class LanguageEntry(
    val displayName: String,
    val stt: SttConfig?,
    val tts: TtsConfig?,
)

data class SharedConfig(
    val espeakDataDir: String,
    val numThreads: Int, // spec #6.3 -- shared across STT/TTS/MT, not a per-language value
    val sttSampleRate: Int,
)

/**
 * Which languages one exported MT checkpoint direction actually covers --
 * descriptive metadata (e.g. for a UI language picker to avoid offering an
 * impossible pair), not load-bearing for OnnxMtAdapter itself. The adapter
 * validates against the real vocab_ids.json at runtime regardless; this is
 * config as documentation, not the source of truth for what the model can do.
 */
data class MtDirectionInfo(
    val targets: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
)

data class MtConfig(
    val modelDir: String,
    val maxNewTokens: Int,
    val idleTimeoutMillis: Long,
    val directions: Map<String, MtDirectionInfo>,
)

data class LanguagesConfig(
    val languages: Map<String, LanguageEntry>,
    val shared: SharedConfig,
    val mt: MtConfig,
)
