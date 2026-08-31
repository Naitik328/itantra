package com.sih.itantra.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import androidx.core.content.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** How a piece of audio should reach the user. */
enum class PlaybackProfile {
    /** An ordinary received message, read out as a voice note. */
    VOICE,

    /**
     * An ALERT frame. Routed as an alarm at full volume, because the entire point of the
     * priority path is that it reaches someone who is not looking at their phone.
     */
    ALERT,
}

/**
 * PCM playback through [AudioTrack].
 *
 * The two profiles differ in more than volume. [PlaybackProfile.ALERT] declares
 * `USAGE_ALARM`, which is what actually gets the audio past Do Not Disturb: Android's default
 * "Priority only" and "Alarms only" DND modes both let alarm-usage streams through, while
 * media-usage streams are silenced. The alarm stream is also pushed to maximum for the
 * duration of the alert and restored afterwards, so a phone left on a quiet volume still rings.
 *
 * Full DND bypass beyond alarms needs Notification Policy access, which only the user can grant
 * — see [DoNotDisturbAccess].
 */
class AudioPlayer(private val context: Context) {

    private val audioManager = context.getSystemService<AudioManager>()

    @Volatile
    private var track: AudioTrack? = null

    /**
     * Play [samples] to completion. Suspends until the last sample has been written, and returns
     * early if the caller's coroutine is cancelled or [stop] is called.
     *
     * [sampleRateHz] is the rate of *this* buffer, which is not always the capture rate: a TTS
     * voice speaks at its own rate (Piper hi_IN is 22050 Hz) while the microphone runs at 16 kHz.
     * Passing the wrong rate here is what makes synthesised speech play chipmunk-fast or slow.
     */
    suspend fun play(
        samples: ShortArray,
        profile: PlaybackProfile,
        sampleRateHz: Int = AudioSpec.SAMPLE_RATE_HZ,
    ) {
        if (samples.isEmpty()) return
        withContext(Dispatchers.IO) {
            val restoreVolume = if (profile == PlaybackProfile.ALERT) raiseAlarmVolume() else null
            val t = build(profile, sampleRateHz)
            track = t
            try {
                t.play()
                var offset = 0
                while (offset < samples.size && currentCoroutineContext().isActive) {
                    val written = t.write(samples, offset, samples.size - offset)
                    if (written < 0) {
                        Log.w(TAG, "write failed: $written")
                        break
                    }
                    if (written == 0) break // stopped underneath us
                    offset += written
                }
                // Let the hardware drain what has been queued instead of cutting the tail off.
                if (currentCoroutineContext().isActive) {
                    runCatching { t.stop() }
                }
            } finally {
                track = null
                runCatching { t.release() }
                restoreVolume?.invoke()
            }
        }
    }

    /** Cut playback short — a new ALERT arriving should not queue behind an old voice note. */
    fun stop() {
        val t = track ?: return
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.stop() }
    }

    private fun build(profile: PlaybackProfile, sampleRateHz: Int): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(
                when (profile) {
                    PlaybackProfile.VOICE -> AudioAttributes.USAGE_MEDIA
                    PlaybackProfile.ALERT -> AudioAttributes.USAGE_ALARM
                },
            )
            .setContentType(
                when (profile) {
                    PlaybackProfile.VOICE -> AudioAttributes.CONTENT_TYPE_SPEECH
                    PlaybackProfile.ALERT -> AudioAttributes.CONTENT_TYPE_SONIFICATION
                },
            )
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val minBytes = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(AudioSpec.FRAME_BYTES * 4)

        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBytes * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /**
     * Push the alarm stream to maximum, returning a function that puts it back. Returns null if
     * the volume could not be changed — under some DND policies this throws, and an alert that
     * plays at the user's own volume is far better than one that crashes.
     */
    private fun raiseAlarmVolume(): (() -> Unit)? {
        val manager = audioManager ?: return null
        return try {
            val stream = AudioManager.STREAM_ALARM
            val previous = manager.getStreamVolume(stream)
            val max = manager.getStreamMaxVolume(stream)
            if (previous >= max) return null
            manager.setStreamVolume(stream, max, 0)
            // Explicit `return` rather than a trailing expression: a bare lambda on the next
            // line would be parsed as another argument to setStreamVolume.
            return fun() {
                runCatching { manager.setStreamVolume(stream, previous, 0) }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot raise alarm volume without DND policy access: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "AudioPlayer"
    }
}
