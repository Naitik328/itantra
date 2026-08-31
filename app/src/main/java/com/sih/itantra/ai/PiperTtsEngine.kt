package com.sih.itantra.ai

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * A [TtsEngine] backed by a Piper (VITS) voice running on sherpa-onnx / ONNX Runtime Mobile.
 *
 * The model is a standard espeak-phonemised Piper voice, so it needs three things on disk, all
 * staged out of the APK by [ModelInstaller]: the `.onnx` weights, a `tokens.txt` mapping phonemes
 * to ids, and the `espeak-ng-data/` directory the phonemizer reads. Paths are absolute and
 * [OfflineTts] is built with no [android.content.res.AssetManager], so ONNX Runtime mmaps the
 * weights from the filesystem instead of inflating them onto the heap.
 *
 * This voice speaks Hindi only. The relay's other nine languages return empty from [synthesize]
 * until their models are installed, which the playback path renders as silence rather than a
 * crash.
 */
class PiperTtsEngine(
    /**
     * Returns the on-disk directory holding the model, `tokens.txt` and `espeak-ng-data/`. Called
     * once inside [load] — i.e. already on a background thread — so staging the assets out of the
     * APK can happen here without an extra thread hop, and only when a voice is actually needed.
     */
    private val resolveModelDir: () -> File,
    private val modelFile: String,
    private val numThreads: Int = 2,
    private val language: Language = Language.HINDI,
    /** From the model card: matches the values the voice was exported with. */
    private val noiseScale: Float = 0.667f,
    private val noiseScaleW: Float = 0.8f,
    private val lengthScale: Float = 1.0f,
) : TtsEngine {

    override val label: String get() = "piper-${language.espeakVoice}"

    private val lock = Any()

    @Volatile
    private var tts: OfflineTts? = null

    @Volatile
    override var sampleRateHz: Int = 22_050
        private set

    override val isLoaded: Boolean get() = tts != null

    override fun supports(language: Language): Boolean = language == this.language

    override fun load() {
        synchronized(lock) {
            if (tts != null) return
            val modelDir = resolveModelDir()
            val modelPath = File(modelDir, modelFile)
            val tokensPath = File(modelDir, "tokens.txt")
            val dataDir = File(modelDir, "espeak-ng-data")

            // sherpa-onnx aborts the whole process in native code if any of these is missing, so
            // check on the JVM side first and fail with a catchable exception instead of a SIGABRT
            // that closes the app. Covers an interrupted first-run copy or a bad asset path.
            check(modelPath.isFile) { "TTS model missing: $modelPath" }
            check(tokensPath.isFile) { "TTS tokens.txt missing: $tokensPath" }
            check(dataDir.isDirectory && File(dataDir, "phontab").isFile) {
                "espeak-ng-data incomplete: $dataDir"
            }
            Log.i(TAG, "loading model (${modelPath.length()} B) from $modelDir")

            val vits = OfflineTtsVitsModelConfig(
                model = modelPath.absolutePath,
                tokens = tokensPath.absolutePath,
                dataDir = dataDir.absolutePath,
                noiseScale = noiseScale,
                noiseScaleW = noiseScaleW,
                lengthScale = lengthScale,
            )
            val model = OfflineTtsModelConfig(
                vits = vits,
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
            )
            val engine = OfflineTts(config = OfflineTtsConfig(model = model))
            sampleRateHz = engine.sampleRate()
            tts = engine
            Log.i(TAG, "loaded $label at ${sampleRateHz} Hz")
        }
    }

    override fun unload() {
        synchronized(lock) {
            tts?.release()
            tts = null
            Log.i(TAG, "unloaded $label")
        }
    }

    override fun synthesize(text: String, language: Language): ShortArray {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !supports(language)) return ShortArray(0)

        val engine = synchronized(lock) {
            if (tts == null) load()
            tts
        } ?: return ShortArray(0)

        return try {
            val audio = engine.generate(trimmed, /* sid = */ 0, /* speed = */ 1.0f)
            floatToPcm16(audio.samples)
        } catch (e: Throwable) {
            Log.e(TAG, "synthesis failed for '${trimmed.take(40)}'", e)
            ShortArray(0)
        }
    }

    private fun floatToPcm16(samples: FloatArray): ShortArray {
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val scaled = samples[i] * 32767f
            out[i] = when {
                scaled >= 32767f -> Short.MAX_VALUE
                scaled <= -32768f -> Short.MIN_VALUE
                else -> scaled.toInt().toShort()
            }
        }
        return out
    }

    private companion object {
        const val TAG = "PiperTtsEngine"
    }
}
