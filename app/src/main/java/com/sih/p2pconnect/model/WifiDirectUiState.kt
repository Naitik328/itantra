package com.sih.p2pconnect.model

/**
 * High-level connection lifecycle for the Wi-Fi Direct backend. The UI reacts only to this
 * enum plus the accompanying data in [WifiDirectUiState], keeping the framework details in
 * the manager layer.
 */
enum class ConnectionState {
    /** Device hardware does not support Wi-Fi P2P at all. */
    P2P_UNSUPPORTED,

    /** Wi-Fi (and therefore P2P) is turned off. Scanning is disabled. */
    WIFI_OFF,

    /** Wi-Fi is on, nothing in progress. Ready to scan. */
    IDLE,

    /** Actively discovering peers. */
    DISCOVERING,

    /** Discovery finished / peers are available to pick from. */
    PEERS_FOUND,

    /** A connection / group negotiation is in progress. */
    CONNECTING,

    /** Connected to a peer (group formed). */
    CONNECTED,

    /** Tearing down an active connection. */
    DISCONNECTING,
}

/** A single discovered peer device, in a UI-friendly shape. */
data class PeerDevice(
    val deviceAddress: String,
    val deviceName: String,
    /** Raw WifiP2pDevice.status: 0=CONNECTED, 1=INVITED, 2=FAILED, 3=AVAILABLE, 4=UNAVAILABLE. */
    val status: Int,
) {
    val statusLabel: String
        get() = when (status) {
            0 -> "Connected"
            1 -> "Invited"
            2 -> "Failed"
            3 -> "Available"
            else -> "Unavailable"
        }
}

/** Immutable snapshot of everything the single screen needs to render. */
data class WifiDirectUiState(
    val connectionState: ConnectionState = ConnectionState.IDLE,
    val thisDeviceName: String = "",
    val peers: List<PeerDevice> = emptyList(),
    /** Non-null only while [connectionState] == CONNECTED. */
    val connectedPeerName: String? = null,
    val isGroupOwner: Boolean = false,
    val groupOwnerAddress: String? = null,
    /** Transient, human-readable status / error line shown under the buttons. */
    val statusMessage: String = "",
    /** True while a permission grant is still required before scanning can start. */
    val permissionRequired: Boolean = false,
    /** True once the message socket to the peer is established (only meaningful when CONNECTED). */
    val linkReady: Boolean = false,
    /** Chat history for the current connected session, oldest first. */
    val messages: List<ChatMessage> = emptyList(),
) {
    val isConnected: Boolean get() = connectionState == ConnectionState.CONNECTED

    val isBusy: Boolean
        get() = connectionState == ConnectionState.DISCOVERING ||
            connectionState == ConnectionState.CONNECTING ||
            connectionState == ConnectionState.DISCONNECTING

    val canScan: Boolean
        get() = connectionState == ConnectionState.IDLE ||
            connectionState == ConnectionState.PEERS_FOUND ||
            connectionState == ConnectionState.DISCOVERING

    val canDisconnect: Boolean
        get() = connectionState == ConnectionState.CONNECTED ||
            connectionState == ConnectionState.CONNECTING
}
