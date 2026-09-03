package com.itantra.config

import org.json.JSONObject

/** Parses languages.json (spec #4.3) into [LanguagesConfig]. CLAUDE.md #5 Phase 1, task 1. */
object ConfigLoader {

    fun parse(json: String): LanguagesConfig {
        val root = JSONObject(json)
        return LanguagesConfig(
            languages = parseLanguages(root.getJSONObject("languages")),
            shared = parseShared(root.getJSONObject("shared")),
            mt = parseMt(root.getJSONObject("mt")),
        )
    }

    private fun parseLanguages(obj: JSONObject): Map<String, LanguageEntry> =
        obj.keys().asSequence().associateWith { iso ->
            val lang = obj.getJSONObject(iso)
            LanguageEntry(
                displayName = lang.getString("displayName"),
                stt = if (lang.isNull("stt")) null else parseStt(lang.getJSONObject("stt")),
                tts = if (lang.isNull("tts")) null else parseTts(lang.getJSONObject("tts")),
            )
        }

    private fun parseStt(obj: JSONObject): SttConfig = SttConfig(
        model = obj.getString("model"),
        tokens = obj.getString("tokens"),
        modelType = obj.getString("modelType"),
        lexicon = obj.optString("lexicon", null),
    )

    private fun parseTts(obj: JSONObject): TtsConfig = TtsConfig(
        model = obj.getString("model"),
        tokens = obj.getString("tokens"),
        espeakVoice = obj.getString("espeakVoice"),
        lengthScale = obj.getDouble("lengthScale").toFloat(),
        speakerId = if (obj.isNull("speakerId")) null else obj.getInt("speakerId"),
    )

    private fun parseShared(obj: JSONObject): SharedConfig = SharedConfig(
        espeakDataDir = obj.getString("espeakDataDir"),
        numThreads = obj.getInt("numThreads"),
        sttSampleRate = obj.getInt("sttSampleRate"),
    )

    private fun parseMt(obj: JSONObject): MtConfig {
        val directionsObj = obj.getJSONObject("directions")
        val directions = directionsObj.keys().asSequence().associateWith { direction ->
            val d = directionsObj.getJSONObject(direction)
            MtDirectionInfo(
                targets = d.optJSONArray("targets")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
                sources = d.optJSONArray("sources")?.let { arr -> List(arr.length()) { arr.getString(it) } } ?: emptyList(),
            )
        }
        return MtConfig(
            modelDir = obj.getString("modelDir"),
            maxNewTokens = obj.getInt("maxNewTokens"),
            idleTimeoutMillis = obj.getLong("idleTimeoutMillis"),
            directions = directions,
        )
    }
}
