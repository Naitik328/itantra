package com.sih.itantra

import android.app.Application
import com.sih.itantra.ai.ModelInstaller
import com.sih.itantra.ai.ModelResidency
import com.sih.itantra.ai.NoStt
import com.sih.itantra.ai.PiperTtsEngine
import com.sih.itantra.ai.SpeechRelay
import com.sih.itantra.ai.SttEngine
import com.sih.itantra.ai.TtsEngine
import com.sih.itantra.audio.VoiceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns the objects whose lifetime is the process, not a screen.
 *
 * The voice session and the AI engines in particular must outlive the Activity: rotating the
 * phone or dropping the screen off mid-sentence has to leave the microphone open and a model
 * resident, which they cannot do if they hang off a ViewModel that is torn down with its host.
 */
class ITantraApp : Application() {

    /** Cancelled only when the process dies, which is the point. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val voiceSession: VoiceSession by lazy { VoiceSession(applicationContext, appScope) }

    private val modelInstaller: ModelInstaller by lazy { ModelInstaller(applicationContext) }

    /**
     * The Hindi Piper voice. The model is staged out of the APK the first time it loads — on a
     * background worker inside [SpeechRelay], never here — so app startup stays cheap and a phone
     * that only ever receives never pays for the copy until a frame arrives.
     */
    val ttsEngine: TtsEngine by lazy {
        PiperTtsEngine(
            resolveModelDir = {
                modelInstaller.install(TTS_HI_ASSET_DIR, TTS_HI_TARGET_DIR, TTS_HI_VERSION)
            },
            modelFile = TTS_HI_MODEL_FILE,
        )
    }

    /** No recogniser yet — the far phone types today. The seam is here for AI Member 3. */
    private val sttEngine: SttEngine = NoStt

    val residency: ModelResidency by lazy { ModelResidency(sttEngine, ttsEngine) }

    val speechRelay: SpeechRelay by lazy { SpeechRelay(residency, voiceSession, appScope) }

    override fun onCreate() {
        super.onCreate()
        // Pre-stage the ~60 MB model out of the APK onto disk so the first received message
        // doesn't pay the copy inline. This does not load the model — no RAM is spent on it until
        // a frame actually arrives and [SpeechRelay] calls into the resident TTS.
        appScope.launch(Dispatchers.IO) {
            runCatching { modelInstaller.install(TTS_HI_ASSET_DIR, TTS_HI_TARGET_DIR, TTS_HI_VERSION) }
        }
    }

    companion object {
        private const val TTS_HI_ASSET_DIR = "tts/hi"
        private const val TTS_HI_TARGET_DIR = "models/tts-hi"
        private const val TTS_HI_MODEL_FILE = "hi_IN-finetune-medium.onnx"

        /** Bump when the bundled Hindi model changes, to force a re-stage on next launch. */
        private const val TTS_HI_VERSION = "hi_IN-finetune-medium-v3-tokens"
    }
}
