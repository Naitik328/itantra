package com.itantra.relay.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 16 kHz mono PCM-16 microphone capture — the format the STT models expect.
 *
 * Emits raw [ShortArray] chunks as a cold [Flow]; collecting starts the mic,
 * cancelling the collector stops and releases it. The caller must already hold
 * the RECORD_AUDIO permission.
 *
 * Next step: feed these chunks into Silero VAD, then into the streaming STT model.
 */
class AudioCapture(
    private val sampleRate: Int = 16_000,
) {
    private val minBuf = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    private val bufferSize = if (minBuf > 0) minBuf * 2 else sampleRate

    @SuppressLint("MissingPermission") // permission is checked in the UI before collecting
    fun stream(): Flow<ShortArray> = callbackFlow {
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        val buf = ShortArray(bufferSize / 2)

        val running = AtomicBoolean(true)
        record.startRecording()
        val worker = thread(name = "itantra-mic") {
            while (running.get()) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) trySend(buf.copyOf(n))
            }
        }

        awaitClose {
            running.set(false)
            worker.join(500)
            runCatching {
                record.stop()
                record.release()
            }
        }
    }
}
