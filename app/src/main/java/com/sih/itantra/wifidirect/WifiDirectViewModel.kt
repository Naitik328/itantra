package com.sih.itantra.wifidirect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.sih.itantra.ITantraApp
import com.sih.itantra.ai.Language
import com.sih.itantra.ai.RelayMetrics
import com.sih.itantra.model.PeerDevice
import com.sih.itantra.model.WifiDirectUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin bridge between the Compose UI and the [WifiDirectManager] backend. Holds no Wi-Fi Direct
 * logic of its own — it just forwards user intents and re-exposes the manager's state flow.
 *
 * It is also where the transport meets the voice path: received NORMAL/ALERT frames are handed to
 * the app-scoped [com.sih.itantra.ai.SpeechRelay] so the far phone's message is spoken aloud.
 */
class WifiDirectViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = WifiDirectManager(application.applicationContext)

    private val speechRelay = (application as ITantraApp).speechRelay

    init {
        manager.onSpeechReceived = { text, langCode, alert, receivedAtNanos ->
            speechRelay.speak(text, Language.fromCode(langCode), alert, receivedAtNanos)
        }
    }

    val uiState: StateFlow<WifiDirectUiState> = manager.uiState

    /** Receiver-side latency + synth stats for the metrics HUD. */
    val relayMetrics: StateFlow<RelayMetrics> = speechRelay.metrics

    /** Language stamped on outgoing frames, so the peer speaks them in the matching voice. */
    var outgoingLanguage: Language
        get() = manager.outgoingLanguage
        set(value) { manager.outgoingLanguage = value }

    fun onScanClicked() = manager.startDiscovery()

    fun onDisconnectClicked() = manager.disconnect()

    fun onPeerClicked(peer: PeerDevice) = manager.connect(peer)

    /** Encode [text] to the binary packet format and send it to the connected peer. */
    fun onSendMessage(text: String) = manager.sendText(text, alert = false)

    /** Send [text] as a priority ALERT: the receiver speaks it through the alarm path. */
    fun onSendAlert(text: String) = manager.sendText(text, alert = true)

    fun onLanguageSelected(language: Language) { manager.outgoingLanguage = language }

    /** Screen became visible — begin continuous discovery so peers appear without a Scan tap. */
    fun onForeground() = manager.onForeground()

    /** Screen hidden — stop scanning to save power. */
    fun onBackground() = manager.onBackground()

    fun onPermissionsGranted() {
        manager.onPermissionsGranted()
        manager.onForeground()
    }

    fun hasRequiredPermissions(): Boolean = manager.hasRequiredPermissions()

    fun createReceiver(): WifiDirectBroadcastReceiver = manager.createReceiver()
}
