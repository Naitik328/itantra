package com.sih.itantra.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/** Raised when the microphone cannot be opened or dies mid-capture. */
class AudioCaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The microphone, as a cold [Flow] of fixed-size PCM frames.
 *
 * Collecting starts the recorder; cancelling the collector stops and releases it, so there is
 * no separate lifecycle to get wrong and no way to leak the mic by forgetting to call stop.
 * The blocking read loop runs on [Dispatchers.IO], never the main thread.
 */
class AudioCapture {

    /**
     * @param source the [MediaRecorder.AudioSource] to open. The default,
     *   `VOICE_RECOGNITION`, is the one tuned for speech recognition: unlike `MIC` it skips the
     *   automatic gain control and aggressive noise suppression that phones apply for human
     *   listeners, which are exactly the processing steps that smear the spectral detail an
     *   acoustic model is trying to read.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun frames(source: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION): Flow<ShortArray> = flow {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            AudioSpec.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) {
            throw AudioCaptureException(
                "device rejected 16 kHz mono PCM (getMinBufferSize returned $minBufferBytes)",
            )
        }

        // Ask for well over the minimum: an under-sized buffer overruns the moment the reader
        // is descheduled, and an overrun is a hole in the middle of a word.
        val bufferBytes = maxOf(minBufferBytes * 2, AudioSpec.FRAME_BYTES * 8)

        val record = try {
            AudioRecord(
                source,
                AudioSpec.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (e: IllegalArgumentException) {
            throw AudioCaptureException("could not construct AudioRecord: ${e.message}", e)
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw AudioCaptureException("microphone unavailable — another app may be holding it")
        }

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw AudioCaptureException("microphone did not start (policy or another app)")
            }
            Log.d(TAG, "capture started: ${AudioSpec.SAMPLE_RATE_HZ} Hz, buffer $bufferBytes B")

            val frame = ShortArray(AudioSpec.FRAME_SAMPLES)
            while (currentCoroutineContext().isActive) {
                var filled = 0
                while (filled < AudioSpec.FRAME_SAMPLES) {
                    val n = record.read(frame, filled, AudioSpec.FRAME_SAMPLES - filled)
                    if (n < 0) throw AudioCaptureException("read failed: ${readErrorName(n)}")
                    if (n == 0) break // stopped underneath us
                    filled += n
                }
                if (filled < AudioSpec.FRAME_SAMPLES) break

                // A defensive copy: the frame travels to the segmenter and possibly into an
                // utterance that outlives this iteration. ~31 KB/s of garbage, which is
                // nothing next to the model inference downstream.
                emit(frame.copyOf())
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            Log.d(TAG, "capture stopped")
        }
    }.flowOn(Dispatchers.IO)

    private fun readErrorName(code: Int): String = when (code) {
        AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
        AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
        AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
        else -> "ERROR($code)"
    }

    private companion object {
        const val TAG = "AudioCapture"
    }
}
