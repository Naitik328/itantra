package com.sih.itantra.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import androidx.core.content.getSystemService

/**
 * Audio focus, which is how this app finds out that an incoming call, an alarm or a navigation
 * prompt has taken the audio hardware away from it.
 *
 * Focus is the mechanism that makes "behave when a call comes in" work without reading call
 * state (which would mean asking for READ_PHONE_STATE — a permission a hackathon reviewer would
 * rightly ask about, for information the focus system already gives us for free).
 *
 * A transient loss pauses capture and resumes it afterwards; a permanent loss stops the session
 * outright, because something else now owns the microphone.
 */
class AudioFocusController(
    context: Context,
    private val onTransientLoss: () -> Unit,
    private val onLoss: () -> Unit,
    private val onRegain: () -> Unit,
) {

    private val audioManager = context.getSystemService<AudioManager>()

    private var request: AudioFocusRequest? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "focus lost permanently")
                request = null
                onLoss()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> {
                // Ducking is meaningless for a microphone, so a duck request is treated as a
                // pause: half-volume speech would be recognised as badly as no speech.
                Log.d(TAG, "focus lost transiently ($change)")
                onTransientLoss()
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "focus regained")
                onRegain()
            }
        }
    }

    /**
     * Take exclusive focus for capture. `GAIN_TRANSIENT_EXCLUSIVE` asks the system to stop other
     * apps from playing over us rather than merely ducking them — audible playback would be
     * picked straight back up by the open microphone.
     */
    fun requestForCapture(): Boolean = acquire(
        durationHint = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
        usage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
        contentType = AudioAttributes.CONTENT_TYPE_SPEECH,
    )

    /** Take focus for playing a received message back. */
    fun requestForPlayback(alert: Boolean): Boolean = acquire(
        durationHint = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        usage = if (alert) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA,
        contentType = if (alert) {
            AudioAttributes.CONTENT_TYPE_SONIFICATION
        } else {
            AudioAttributes.CONTENT_TYPE_SPEECH
        },
    )

    fun abandon() {
        val manager = audioManager ?: return
        request?.let { manager.abandonAudioFocusRequest(it) }
        request = null
    }

    private fun acquire(durationHint: Int, usage: Int, contentType: Int): Boolean {
        val manager = audioManager ?: return false
        abandon()

        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()

        val req = AudioFocusRequest.Builder(durationHint)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(listener)
            .setWillPauseWhenDucked(true)
            .build()

        request = req
        val result = manager.requestAudioFocus(req)
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) {
            Log.w(TAG, "audio focus denied (result=$result)")
            request = null
        }
        return granted
    }

    private companion object {
        const val TAG = "AudioFocus"
    }
}
