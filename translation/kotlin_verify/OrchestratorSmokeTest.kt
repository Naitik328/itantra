/**
 * Runtime smoke test for ConfigLoader + Orchestrator's pivot-routing logic.
 * Run via verify.sh, alongside SmokeTest.kt. Uses fakes for Stt/Tts/Mt
 * (real adapters don't exist on this branch, or -- for MT -- need a native
 * ONNX Runtime library this desktop host can't run, see SmokeTest.kt's doc
 * comment) so what's actually being checked is real: does languages.json
 * parse correctly, and does Orchestrator sequence/route correctly.
 */
import com.itantra.adapters.SttAdapter
import com.itantra.adapters.TtsAdapter
import com.itantra.config.ConfigLoader
import com.itantra.mt.MtAdapter
import com.itantra.orchestrator.Orchestrator
import java.io.File

private class FakeStt(private val output: String) : SttAdapter {
    override fun transcribe(audio: FloatArray): String = output
    override fun close() {}
}

private class FakeTts : TtsAdapter {
    override fun synthesize(text: String): ByteArray = text.toByteArray()
    override fun close() {}
}

/** Records calls so the test can assert exactly what Orchestrator asked for. */
private class RecordingMtAdapter : MtAdapter {
    val calls = mutableListOf<Triple<String, String, String>>()
    override fun translate(text: String, sourceLang: String, targetLang: String): String {
        calls.add(Triple(text, sourceLang, targetLang))
        if (sourceLang == targetLang) return text
        return "[$sourceLang->$targetLang] $text"
    }
    override fun close() {}
}

fun main() {
    var failures = 0
    fun check(label: String, actual: Any?, expected: Any?) {
        val ok = actual == expected
        println("${if (ok) "OK  " else "FAIL"} $label -> $actual" + if (!ok) " (expected $expected)" else "")
        if (!ok) failures++
    }

    // 1. Real languages.json actually parses (the file this branch ships, not a fixture).
    val configFile = File("translation/config/languages.json")
    val config = ConfigLoader.parse(configFile.readText())
    check("parsed 4 languages", config.languages.size, 4)
    check("bn has no STT (spec #3.4)", config.languages["bn"]?.stt, null)
    check("bn has TTS with sid=12 (spec #4.2 Trap 2)", config.languages["bn"]?.tts?.speakerId, 12)
    check("en STT has a lexicon (spec #7.2.1)", config.languages["en"]?.stt?.lexicon, "lexicon.txt")
    check("mt.modelDir", config.mt.modelDir, "mt")
    check("shared.numThreads", config.shared.numThreads, 4)

    // 2. Orchestrator send path: non-English sender -> pivot through MT(src, en).
    val mt = RecordingMtAdapter()
    val orch = Orchestrator(
        config = config,
        filesDir = File("/nonexistent"), // never touched: mtAdapter is injected, not built from filesDir here
        ortEnvironment = ai.onnxruntime.OrtEnvironment.getEnvironment(),
        sttAdapters = mapOf("hi" to FakeStt("अस्पताल कहाँ है"), "te" to FakeStt("ఆసుపత్రి")),
        ttsAdapters = mapOf("hi" to FakeTts(), "te" to FakeTts()),
        mtAdapter = mt,
    )

    val outgoing = orch.prepareOutgoingMessage("hi", FloatArray(0))
    check("send: punctuation added (spec #5.5)", outgoing.originalText, "अस्पताल कहाँ है.")
    check("send: pivoted through MT(hi, en)", mt.calls.last(), Triple("अस्पताल कहाँ है.", "hi", "en"))
    check("send: pivotText carries MT output", outgoing.pivotText, "[hi->en] अस्पताल कहाँ है.")

    // 3. Receive path: receiver shares sender's language -> use original, skip MT entirely.
    mt.calls.clear()
    val payloadSameLang = Orchestrator.IncomingPayload(originalLang = "hi", originalText = "अस्पताल कहाँ है.", pivotText = "[hi->en] अस्पताल कहाँ है.")
    val displaySame = orch.resolveDisplayText(payloadSameLang, receiverLang = "hi")
    check("receive: same-language receiver reuses original (spec #5.4)", displaySame, "अस्पताल कहाँ है.")
    check("receive: same-language path made no MT call", mt.calls.size, 0)

    // 4. Receive path: different receiver language -> MT(en, target).
    val displayDiff = orch.resolveDisplayText(payloadSameLang, receiverLang = "te")
    check("receive: cross-language pivots through MT(en, te)", mt.calls.last(), Triple("[hi->en] अस्पताल कहाँ है.", "en", "te"))
    check("receive: cross-language output", displayDiff, "[en->te] [hi->en] अस्पताल कहाँ है.")

    // 5. TTS: wired language returns bytes, unwired returns null instead of throwing.
    check("tts wired for hi", orch.synthesizeIfRequested("hello", "hi") != null, true)
    check("tts not wired for bn (no adapter injected in this test)", orch.synthesizeIfRequested("hello", "bn"), null)

    // 6. Fail loudly on a language with no STT (spec #5.1 principle 4), not a silent wrong-answer.
    var threw = false
    try {
        orch.prepareOutgoingMessage("bn", FloatArray(0))
    } catch (e: IllegalStateException) {
        threw = true
    }
    check("send from bn (no STT) fails loudly", threw, true)

    println("\n${if (failures == 0) "ALL CHECKS PASSED" else "$failures CHECK(S) FAILED"}")
    if (failures > 0) kotlin.system.exitProcess(1)
}
