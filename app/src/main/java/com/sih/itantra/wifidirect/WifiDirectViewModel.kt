package com.sih.itantra.wifidirect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.sih.itantra.model.PeerDevice
import com.sih.itantra.model.WifiDirectUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin bridge between the Compose UI and the [WifiDirectManager] backend. Holds no Wi-Fi Direct
 * logic of its own — it just forwards user intents and re-exposes the manager's state flow.
 */
class WifiDirectViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = WifiDirectManager(application.applicationContext)

    val uiState: StateFlow<WifiDirectUiState> = manager.uiState

    fun onScanClicked() = manager.startDiscovery()

    fun onDisconnectClicked() = manager.disconnect()

    fun onPeerClicked(peer: PeerDevice) = manager.connect(peer)

    /** Encode [text] to the binary packet format and send it to the connected peer. */
    fun onSendMessage(text: String) = manager.sendText(text)

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
