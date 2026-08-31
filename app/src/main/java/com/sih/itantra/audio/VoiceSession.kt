package com.sih.itantra.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** A complete captured utterance, on its way to the recogniser. */
data class Utterance(
    val samples: ShortArray,
    val durationMs: Long,
    val endReason: UtteranceSegmenter.EndReason,
    val capturedAt: Long,
    /** Non-null when the archive was on and the dump succeeded. */
    val wavFile: File?,
) {
    override fun equals(other: Any?): Boolean =
        other is Utterance && capturedAt == other.capturedAt && samples.contentEquals(other.samples)

    override fun hashCode(): Int = samples.contentHashCode() * 31 + capturedAt.hashCode()
}

/**
 * The audio pipeline, assembled: focus → microphone → segmenter → utterance, plus playback in
 * the other direction.
 *
 * This is where section B stops and section C will pick up. [utterances] is the hand-off point:
 * today the UI can echo them back to prove the path works, and when the STT engine arrives it
 * subscribes to exactly this flow and nothing else in here has to change.
 *
 * Lives for the life of the process (see `ITantraApp`) rather than a screen, so rotating the
 * phone mid-sentence doesn't drop the microphone.
 */
class VoiceSession(
    private val appContext: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {

    private val capture = AudioCapture()
    private val player = AudioPlayer(appContext)
    private val router = AudioRouter(appContext)
    private val archive = CaptureArchive(appContext)

    private val _state = MutableStateFlow(VoiceSessionState())
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    /**
     * Input level in dBFS, separate from [state] on purpose: it changes every 32 ms, and
     * folding it into the main state object would recompose the whole screen at 31 Hz.
     */
    private val _level = MutableStateFlow(AudioLevel.SILENCE_DBFS)
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _utterances = MutableSharedFlow<Utterance>(extraBufferCapacity = 8)
    val utterances: SharedFlow<Utterance> = _utterances.asSharedFlow()

    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    /** True when capture was suspended by a phone call and should resume by itself. */
    private var resumeAfterInterruption = false

    private val focus = AudioFocusController(
        context = appContext,
        onTransientLoss = {
            if (_state.value.capturing) {
                resumeAfterInterruption = true
                stopCaptureInternal(CaptureHalt.INTERRUPTED)
            }
        },
        onLoss = {
            resumeAfterInterruption = false
            stopCaptureInternal(CaptureHalt.STOPPED)
        },
        onRegain = {
            if (resumeAfterInterruption) {
                resumeAfterInterruption = false
                startCapture()
            }
        },
    )

    // -- configuration ------------------------------------------------------------------------

    fun setMode(mode: CaptureMode) {
        if (_state.value.mode == mode) return
        val wasCapturing = _state.value.capturing
        if (wasCapturing) stopCapture()
        _state.update { it.copy(mode = mode) }
        if (wasCapturing && mode == CaptureMode.HANDS_FREE) startCapture()
    }

    fun setRoute(route: AudioRoute) {
        router.apply(route)
        _state.update { it.copy(route = route, routeLabel = router.describe(route)) }
    }

    fun setArchiveEnabled(enabled: Boolean) {
        _state.update { it.copy(archiveEnabled = enabled) }
        if (enabled) refreshArchiveStats()
    }

    fun setEchoEnabled(enabled: Boolean) = _state.update { it.copy(echoEnabled = enabled) }

    fun clearArchive() {
        val removed = archive.clear()
        Log.d(TAG, "cleared $removed archived clips")
        refreshArchiveStats()
    }

    fun archiveDirectory(): File? = archive.directory

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // -- capture ------------------------------------------------------------------------------

    fun startCapture() {
        if (captureJob?.isActive == true) return

        if (!hasMicPermission()) {
            _state.update { it.copy(error = "Microphone permission is required.") }
            return
        }
        if (!focus.requestForCapture()) {
            _state.update { it.copy(error = "Another app is using the microphone.") }
            return
        }

        val mode = _state.value.mode
        val segmenter = buildSegmenter(mode)

        _state.update {
            it.copy(
                capturing = true,
                speaking = false,
                halt = CaptureHalt.NONE,
                error = null,
                gateLabel = if (mode == CaptureMode.PUSH_TO_TALK) "push-to-talk" else "energy",
            )
        }

        captureJob = scope.launch {
            capture.frames()
                .catch { e ->
                    Log.w(TAG, "capture failed", e)
                    _state.update { it.copy(error = e.message ?: "Microphone error") }
                }
                .collect { frame -> onFrame(frame, segmenter) }
        }
    }

    /** Stop capture, emitting whatever utterance was in flight. */
    fun stopCapture() {
        resumeAfterInterruption = false
        stopCaptureInternal(CaptureHalt.NONE)
    }

    private fun stopCaptureInternal(halt: CaptureHalt) {
        val job = captureJob ?: run {
            if (halt != CaptureHalt.NONE) _state.update { it.copy(halt = halt) }
            return
        }
        captureJob = null
        scope.launch {
            // The flush has to happen before the collector dies, so it is handled in onFrame's
            // owner: cancel first, then flush the segmenter we still hold a reference to.
            runCatching { job.cancelAndJoin() }
            pendingFlush?.invoke()
            pendingFlush = null
            focus.abandon()
            _level.value = AudioLevel.SILENCE_DBFS
            _state.update { it.copy(capturing = false, speaking = false, halt = halt) }
        }
    }

    /** Set while a capture is running so [stopCaptureInternal] can close the open utterance. */
    private var pendingFlush: (() -> Unit)? = null

    private fun buildSegmenter(mode: CaptureMode): UtteranceSegmenter {
        val segmenter = when (mode) {
            // The press and release are the boundaries; onset debounce would only add latency.
            CaptureMode.PUSH_TO_TALK -> UtteranceSegmenter(
                gate = AlwaysSpeechGate(),
                onsetFrames = 1,
                minUtteranceMs = 200L,
            )

            CaptureMode.HANDS_FREE -> UtteranceSegmenter(gate = EnergyVoiceActivityGate())
        }
        pendingFlush = { handleEvent(segmenter.flush()) }
        return segmenter
    }

    private fun onFrame(frame: ShortArray, segmenter: UtteranceSegmenter) {
        _level.value = AudioLevel.dbfs(frame)
        handleEvent(segmenter.accept(frame))
    }

    private fun handleEvent(event: UtteranceSegmenter.Event) {
        when (event) {
            is UtteranceSegmenter.Event.None -> Unit

            is UtteranceSegmenter.Event.SpeechStarted -> _state.update { it.copy(speaking = true) }

            is UtteranceSegmenter.Event.Utterance -> {
                val wav = if (_state.value.archiveEnabled) archive.write(event.samples) else null

                _state.update {
                    it.copy(
                        speaking = false,
                        utteranceCount = it.utteranceCount + 1,
                        lastUtteranceMs = event.durationMs,
                        lastEndReason = event.endReason.name.lowercase().replace('_', ' '),
                    )
                }
                if (wav != null) refreshArchiveStats()

                val utterance = Utterance(
                    samples = event.samples,
                    durationMs = event.durationMs,
                    endReason = event.endReason,
                    capturedAt = System.currentTimeMillis(),
                    wavFile = wav,
                )
                scope.launch { _utterances.emit(utterance) }

                if (_state.value.echoEnabled) {
                    play(event.samples, PlaybackProfile.VOICE)
                }
            }
        }
    }

    // -- playback -----------------------------------------------------------------------------

    /**
     * Play PCM out of the speaker. An ALERT interrupts anything already playing. [sampleRateHz]
     * defaults to the capture rate but is passed explicitly for synthesised speech, which runs at
     * the voice's own rate (Piper hi_IN = 22050 Hz).
     */
    fun play(
        samples: ShortArray,
        profile: PlaybackProfile,
        sampleRateHz: Int = AudioSpec.SAMPLE_RATE_HZ,
    ) {
        if (profile == PlaybackProfile.ALERT) {
            player.stop()
            playbackJob?.cancel()
        } else if (playbackJob?.isActive == true) {
            return // don't stack voice notes on top of each other
        }

        playbackJob = scope.launch {
            _state.update { it.copy(playing = true) }
            focus.requestForPlayback(alert = profile == PlaybackProfile.ALERT)
            try {
                player.play(samples, profile, sampleRateHz)
            } catch (e: Exception) {
                Log.w(TAG, "playback failed", e)
                _state.update { it.copy(error = "Playback failed: ${e.message}") }
            } finally {
                _state.update { it.copy(playing = false) }
                // Capture, if running, still needs focus; don't pull it out from under it.
                if (!_state.value.capturing) focus.abandon()
            }
        }
    }

    /** Sound the alert tone — proves the alarm/DND path with no radio link involved. */
    fun playAlertTone() = play(AlertTone.generate(), PlaybackProfile.ALERT)

    fun stopPlayback() {
        player.stop()
        playbackJob?.cancel()
        _state.update { it.copy(playing = false) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    fun shutdown() {
        stopCapture()
        stopPlayback()
        router.release()
        focus.abandon()
    }

    private fun refreshArchiveStats() {
        val files = archive.list()
        _state.update { it.copy(archivedClips = files.size, archivedBytes = files.sumOf { f -> f.length() }) }
    }

    private companion object {
        const val TAG = "VoiceSession"
    }
}
