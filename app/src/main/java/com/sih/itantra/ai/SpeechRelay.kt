package com.sih.itantra.ai

import android.util.Log
import com.sih.itantra.audio.PlaybackProfile
import com.sih.itantra.audio.VoiceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Per-utterance timings for the metrics HUD, covering the receiver half of the latency budget:
 * from a frame landing to the first spoken sample and the audio finishing.
 */
data class RelayMetrics(
    val lastText: String = "",
    val language: Language = Language.DEFAULT,
    /** Wall-clock spent inside the model turning text into PCM. */
    val synthMillis: Long = 0,
    /** Frame received → PCM ready to play. The "frame → TTS first sample" leg of the budget. */
    val frameToAudioMillis: Long = 0,
    val pcmSamples: Int = 0,
    val sampleRateHz: Int = 0,
    val engineLabel: String = "",
    val resident: ModelResidency.Resident = ModelResidency.Resident.NONE,
    val speaking: Boolean = false,
    val error: String? = null,
)

/**
 * The receiver-side speech path: text off the wire → TTS → speaker.
 *
 * A received frame's text is handed to [speak]; synthesis runs on a single background worker so
 * utterances are spoken one at a time and never overlap, and playback is delegated to the shared
 * [VoiceSession] so it shares the app's audio focus, routing and DND-bypass logic rather than
 * opening a second audio path.
 *
 * ALERTs get the priority the spec demands: an incoming ALERT cancels an in-flight NORMAL
 * utterance — both its synthesis and any audio already playing — and takes the worker for itself,
 * so an alarm is never stuck behind a chatty voice note.
 */
class SpeechRelay(
    private val residency: ModelResidency,
    private val voiceSession: VoiceSession,
    private val scope: CoroutineScope,
) {
    // One worker: synthesis is CPU-heavy and utterances must not interleave. Cancelling the job
    // frees the worker immediately for a higher-priority ALERT.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.Default.limitedParallelism(1)

    private val _metrics = MutableStateFlow(RelayMetrics())
    val metrics: StateFlow<RelayMetrics> = _metrics.asStateFlow()

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var activeIsAlert = false

    /**
     * Speak [text] in [language]. [alert] routes it through the alarm profile and lets it jump
     * ahead of a NORMAL utterance. [receivedAtNanos] anchors the frame→audio latency measurement
     * to when the frame actually arrived, defaulting to now for locally originated speech.
     */
    fun speak(
        text: String,
        language: Language,
        alert: Boolean,
        receivedAtNanos: Long = System.nanoTime(),
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        // Queue-jump: an ALERT pre-empts a NORMAL utterance that's still synthesising or playing.
        if (alert && !activeIsAlert && activeJob?.isActive == true) {
            activeJob?.cancel()
            voiceSession.stopPlayback()
        }

        val job = scope.launch(worker) {
            try {
                val pcm = residency.withTts { engine ->
                    _metrics.update {
                        it.copy(engineLabel = engine.label, resident = residency.resident, error = null)
                    }
                    val t0 = System.nanoTime()
                    val samples = engine.synthesize(trimmed, language)
                    _metrics.update {
                        it.copy(
                            lastText = trimmed,
                            language = language,
                            synthMillis = (System.nanoTime() - t0) / 1_000_000,
                            frameToAudioMillis = (System.nanoTime() - receivedAtNanos) / 1_000_000,
                            pcmSamples = samples.size,
                            sampleRateHz = engine.sampleRateHz,
                            error = if (samples.isEmpty()) {
                                "no audio — is a voice installed for ${language.displayName}?"
                            } else {
                                null
                            },
                        )
                    }
                    samples
                }
                if (pcm.isEmpty()) return@launch

                ensureActive()
                _metrics.update { it.copy(speaking = true) }
                val profile = if (alert) PlaybackProfile.ALERT else PlaybackProfile.VOICE
                voiceSession.play(pcm, profile, residency.tts.sampleRateHz)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Throwable, not Exception: a failed native library load surfaces as
                // UnsatisfiedLinkError (an Error), and letting it escape this coroutine would
                // crash the whole app instead of just failing this one utterance.
                Log.e(TAG, "speak failed", t)
                _metrics.update { it.copy(error = t.message ?: "speech failed") }
            } finally {
                _metrics.update { it.copy(speaking = false) }
            }
        }
        activeJob = job
        activeIsAlert = alert
    }

    private companion object {
        const val TAG = "SpeechRelay"
    }
}
