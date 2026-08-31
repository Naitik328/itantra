package com.sih.itantra.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService

/** Where received speech comes out. */
enum class AudioRoute {
    /** Let the platform decide: wired headset if one is plugged in, otherwise the speaker. */
    AUTO,

    /** Loudspeaker — the field default, so a group can hear an incoming message. */
    SPEAKER,

    /** Earpiece — hold the phone to your ear when the message shouldn't be overheard. */
    EARPIECE,
}

/**
 * Output routing for playback.
 *
 * Bluetooth audio devices are deliberately not handled here. Routing to them needs
 * BLUETOOTH_CONNECT, and this app has no Bluetooth permissions at all — the team dropped the
 * Bluetooth transport in favour of Wi-Fi Direct plus LoRa over USB. A connected BT headset will
 * still receive audio when the platform picks it under [AudioRoute.AUTO]; we just never force
 * it either way.
 */
class AudioRouter(context: Context) {

    private val audioManager = context.getSystemService<AudioManager>()

    fun apply(route: AudioRoute) {
        val manager = audioManager ?: return
        try {
            when (route) {
                AudioRoute.AUTO -> {
                    clearForcedDevice(manager)
                    manager.mode = AudioManager.MODE_NORMAL
                }

                AudioRoute.SPEAKER -> {
                    manager.mode = AudioManager.MODE_NORMAL
                    force(manager, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                        @Suppress("DEPRECATION")
                        manager.isSpeakerphoneOn = true
                    }
                }

                AudioRoute.EARPIECE -> {
                    // The earpiece is only reachable in communication mode; in MODE_NORMAL the
                    // platform will route media to the speaker no matter what we ask for.
                    manager.mode = AudioManager.MODE_IN_COMMUNICATION
                    force(manager, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                        @Suppress("DEPRECATION")
                        manager.isSpeakerphoneOn = false
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "route change refused: ${e.message}")
        }
    }

    /** Release any forced routing and put the audio mode back to normal. */
    fun release() {
        val manager = audioManager ?: return
        runCatching {
            clearForcedDevice(manager)
            manager.mode = AudioManager.MODE_NORMAL
        }
    }

    fun isWiredHeadsetConnected(): Boolean {
        val manager = audioManager ?: return false
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
    }

    /** What the user should be told is happening right now. */
    fun describe(route: AudioRoute): String = when (route) {
        AudioRoute.SPEAKER -> "Speaker"
        AudioRoute.EARPIECE -> "Earpiece"
        AudioRoute.AUTO -> if (isWiredHeadsetConnected()) "Headset" else "Speaker"
    }

    private fun force(manager: AudioManager, deviceType: Int, legacy: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = manager.availableCommunicationDevices.firstOrNull { it.type == deviceType }
            if (device != null && manager.setCommunicationDevice(device)) return
            Log.d(TAG, "no communication device of type $deviceType; falling back")
        }
        legacy()
    }

    private fun clearForcedDevice(manager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = false
        }
    }

    private companion object {
        const val TAG = "AudioRouter"
    }
}
