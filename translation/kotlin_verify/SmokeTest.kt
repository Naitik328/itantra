/**
 * Runtime smoke test for the .kt files under translation/kotlin/com/itantra/mt
 * -- run via verify.sh, not part of any Gradle build yet (no Android module exists on
 * this branch). Exercises the pure-logic paths (IndicProcessor preprocess/
 * postprocess, transliteration, placeholder wrap/restore, vocab_ids.json /
 * tgt_vocab.json parsing, detokenize) that don't need an ONNX Runtime
 * native library. Does NOT exercise OnnxMtAdapter's actual encoder/decoder
 * session calls -- those need onnxruntime-extensions' native .so, which is
 * only published for Android ABIs (arm64-v8a/armeabi-v7a/x86/x86_64 built
 * against Android's bionic libc), not this desktop Linux glibc host. That
 * gap is real and tracked in translation/translation_state.md, not silently
 * skipped.
 */
import com.itantra.mt.*

fun main() {
    var failures = 0
    fun check(label: String, actual: Any?, expected: Any?) {
        val ok = actual == expected
        println("${if (ok) "OK  " else "FAIL"} $label -> $actual" + if (!ok) " (expected $expected)" else "")
        if (!ok) failures++
    }

    // 1. IndicProcessor.preprocess: English -> tokenized, tagged text.
    val pre1 = IndicProcessor.preprocess("Where is the nearest hospital?", "eng_Latn", "hin_Deva")
    println("preprocess(en): '${pre1.text}'")
    check("en preprocess starts with tags", pre1.text.startsWith("eng_Latn hin_Deva"), true)

    // 2. IndicProcessor.preprocess: Hindi -> Devanagari-pivot text (should stay Devanagari, hi->hi transliteration is identity).
    val pre2 = IndicProcessor.preprocess("अस्पताल कहाँ है?", "hin_Deva", "eng_Latn")
    println("preprocess(hi): '${pre2.text}'")
    check("hi preprocess starts with tags", pre2.text.startsWith("hin_Deva eng_Latn"), true)

    // 3. IndicProcessor.preprocess: Telugu -> should transliterate to Devanagari pivot internally.
    val pre3 = IndicProcessor.preprocess("ఆసుపత్రి ఎక్కడ ఉంది?", "tel_Telu", "eng_Latn")
    println("preprocess(te): '${pre3.text}'")
    // Telugu chars (0C00-0C7F) should NOT appear in the pivoted output -- it should be transliterated to Devanagari.
    val hasTeluguChar = pre3.text.any { it.code in 0x0C00..0x0C7F }
    check("te preprocess has no Telugu chars (pivoted to Devanagari)", hasTeluguChar, false)
    val hasDevanagariChar = pre3.text.any { it.code in 0x0900..0x097F }
    check("te preprocess produced Devanagari chars", hasDevanagariChar, true)

    // 4. Placeholder wrap/restore round trip.
    val wrapped = Placeholders.wrap("Visit https://example.com/path or email me@test.com, it costs 45.50%")
    println("placeholders wrapped: '${wrapped.text}'  (${wrapped.placeholders.size} placeholder-string variants recorded)")
    check("placeholder wrap hid the url", wrapped.text.contains("example.com"), false)
    val restored = Placeholders.restore(wrapped.text, wrapped.placeholders)
    check("placeholder restore recovers url", restored.contains("example.com"), true)

    // 5. Round trip: preprocess then postprocess an English sentence (identity-ish check on structure).
    val post = IndicProcessor.postprocess("नमस्ते दुनिया", "hin_Deva", emptyMap())
    println("postprocess(hi, no placeholders): '$post'")
    check("hi postprocess non-empty", post.isNotBlank(), true)

    // 6. vocab_ids.json / tgt_vocab.json parsing, matching OnnxMtAdapter's private logic shape
    // (re-implemented here since loadVocabIds/loadTgtVocab are private -- this validates the same
    // org.json calls compile+run correctly against real JSON, which is the actual risk surface).
    val vocabIdsJson = """{"decoder_start_id": 2, "eos_id": 2, "lang_tag_ids": {"eng_Latn": 4, "hin_Deva": 15}}"""
    val json = org.json.JSONObject(vocabIdsJson)
    val tags = json.getJSONObject("lang_tag_ids")
    val langTagIds = tags.keys().asSequence().associateWith { tags.getInt(it) }
    check("vocab_ids decoder_start_id", json.getInt("decoder_start_id"), 2)
    check("vocab_ids lang_tag_ids size", langTagIds.size, 2)
    check("vocab_ids hin_Deva id", langTagIds["hin_Deva"], 15)

    val tgtVocabJson = """["<s>", "<pad>", "</s>", "<unk>", "▁hello", "▁world"]"""
    val array = org.json.JSONArray(tgtVocabJson)
    val tgtVocab = List(array.length()) { array.getString(it) }
    check("tgt_vocab size", tgtVocab.size, 6)
    check("tgt_vocab piece", tgtVocab[4], "▁hello")

    // 7. Detokenize logic (same shape as OnnxMtAdapter.detokenize, re-implemented here since private).
    fun detokenize(vocab: List<String>, ids: IntArray, eosId: Int): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id == eosId) continue
            if (id in vocab.indices) sb.append(vocab[id])
        }
        return sb.toString().replace("▁", " ").trim()
    }
    val detok = detokenize(tgtVocab, intArrayOf(4, 5, 2), eosId = 2)
    println("detokenize result: '$detok'")
    check("detokenize joins pieces correctly", detok, "hello world")

    // 8. FloresTags round trip.
    check("FloresTags iso(hin_Deva)", FloresTags.iso("hin_Deva"), "hi")
    check("FloresTags flores(hi)", FloresTags.flores("hi"), "hin_Deva")

    println("\n${if (failures == 0) "ALL CHECKS PASSED" else "$failures CHECK(S) FAILED"}")
    if (failures > 0) kotlin.system.exitProcess(1)
}
