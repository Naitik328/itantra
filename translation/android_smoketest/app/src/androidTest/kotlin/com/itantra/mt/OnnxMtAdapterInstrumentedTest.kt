package com.itantra.mt

import ai.onnxruntime.OrtEnvironment
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The actual "does this run on a real Android device" test -- everything
 * before this point (tools/quantize_and_verify.py, translation/kotlin_verify/)
 * validated the model, the tokenizer, and the Kotlin logic separately, on a
 * desktop. This is the first place all three run together, for real,
 * through onnxruntime-extensions' actual Android native library.
 *
 * Needs model files already on the device -- see ../../../../push_models.sh.
 * Reads from context.getExternalFilesDir("mt"), i.e.
 * /sdcard/Android/data/com.itantra.mttest/files/mt/<direction>/...
 *
 * Expected outputs are the exact strings already verified against real
 * weights on desktop (translation/translation_state.md's "Telugu and
 * Bengali verified" section, tools/test_sentences/*.tsv) -- if this test
 * disagrees with those, something about the Android build (native lib ABI,
 * R8 stripping, a JNI mismatch) is the suspect, not the model or tokenizer
 * design, both already independently confirmed correct.
 */
class OnnxMtAdapterInstrumentedTest {

    private lateinit var env: OrtEnvironment
    private lateinit var adapter: OnnxMtAdapter

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelRoot = context.getExternalFilesDir("mt")
            ?: error("No external files dir -- is external storage available on this device/emulator?")
        env = OrtEnvironment.getEnvironment()
        adapter = OnnxMtAdapter(env, modelRoot)
    }

    @After
    fun tearDown() {
        adapter.close()
    }

    @Test
    fun en_to_hi_translates_correctly() {
        val result = adapter.translate("Where is the nearest hospital?", "en", "hi")
        assertTrue("expected 'अस्पताल' (hospital) in '$result'", result.contains("अस्पताल"))
    }

    @Test
    fun hi_to_en_round_trips_correctly() {
        val result = adapter.translate("अस्पताल कहाँ है?", "hi", "en")
        assertTrue("expected 'hospital' in '$result'", result.lowercase().contains("hospital"))
    }

    @Test
    fun en_to_te_produces_real_telugu_script() {
        val result = adapter.translate("Where is the nearest hospital?", "en", "te")
        // Same word verified on desktop (translation_state.md): "ఆసుపత్రి".
        assertTrue("expected Telugu-script 'ఆసుపత్రి' in '$result'", result.contains("ఆసుపత్రి"))
    }
}
