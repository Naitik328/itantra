package com.sih.itantra.audio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.sih.itantra.ITantraApp
import com.sih.itantra.service.VoiceSessionService
import kotlinx.coroutines.flow.StateFlow

/**
 * Screen-facing wrapper over the process-scoped [VoiceSession].
 *
 * It holds no audio state itself — the session outlives this ViewModel deliberately — and adds
 * only the one thing the session should not know about: starting and stopping the foreground
 * service that keeps the microphone legal while the app is off screen.
 */
class VoiceViewModel(application: Application) : AndroidViewModel(application) {

    private val session: VoiceSession = (application as ITantraApp).voiceSession

    val state: StateFlow<VoiceSessionState> = session.state
    val level: StateFlow<Float> = session.level

    fun hasMicPermission(): Boolean = session.hasMicPermission()

    // -- push to talk -------------------------------------------------------------------------

    fun onTalkPressed() {
        VoiceSessionService.start(getApplication())
        session.startCapture()
    }

    fun onTalkReleased() {
        session.stopCapture()
        VoiceSessionService.stop(getApplication())
    }

    // -- hands free ---------------------------------------------------------------------------

    fun onHandsFreeToggled() {
        if (state.value.capturing) onTalkReleased() else onTalkPressed()
    }

    // -- configuration ------------------------------------------------------------------------

    fun onModeSelected(mode: CaptureMode) {
        if (state.value.capturing && mode != state.value.mode) {
            VoiceSessionService.stop(getApplication())
        }
        session.setMode(mode)
    }

    fun onRouteSelected(route: AudioRoute) = session.setRoute(route)

    fun onArchiveToggled(enabled: Boolean) = session.setArchiveEnabled(enabled)

    fun onEchoToggled(enabled: Boolean) = session.setEchoEnabled(enabled)

    fun onClearArchive() = session.clearArchive()

    fun onTestAlert() = session.playAlertTone()

    fun onStopPlayback() = session.stopPlayback()

    fun onErrorShown() = session.clearError()

    /** Path shown in the UI so the AI team knows where to point `adb pull`. */
    fun archivePath(): String? = session.archiveDirectory()?.absolutePath
}
