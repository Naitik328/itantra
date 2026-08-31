package com.sih.itantra.wifidirect

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.MacAddress
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pManager.Channel
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.sih.itantra.model.ChatMessage
import com.sih.itantra.model.ConnectionState
import com.sih.itantra.model.PeerDevice
import com.sih.itantra.model.WifiDirectUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Hardened wrapper around [WifiP2pManager]. This is the whole Wi-Fi Direct backend:
 * channel lifecycle, discovery with retry/backoff, connect, teardown and system-broadcast
 * handling all live here so the UI never touches framework APIs directly.
 *
 * All public methods are safe to call from the main thread and never throw — framework
 * calls are guarded so transient P2P states can't crash the app.
 */
class WifiDirectManager(private val appContext: Context) {

    private val _uiState = MutableStateFlow(WifiDirectUiState())
    val uiState: StateFlow<WifiDirectUiState> = _uiState.asStateFlow()

    private val manager: WifiP2pManager? = appContext.getSystemService()
    private var channel: Channel? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Guards how many times we re-init a dead channel to avoid infinite loops. */
    private var channelReinitAttempts = 0

    /** Discovery retry bookkeeping. */
    private var discoveryRetries = 0
    private var pendingDiscoveryRetry: Runnable? = null

    /** Tracks a stuck framework-BUSY state so we can force a P2P reset instead of retrying forever. */
    private var consecutiveBusy = 0
    private var busyRecoveries = 0
    private var totalBusyResets = 0
    private var recovering = false

    /** Fires if a connection negotiation never completes, so the UI never looks frozen. */
    private var pendingConnectTimeout: Runnable? = null

    /** Periodic re-issue of discovery so the device stays discoverable the whole time we're open. */
    private var discoveryHeartbeat: Runnable? = null

    // --- Messaging over the connected link ---------------------------------------------------

    /** Active socket transport, non-null only while connected. */
    private var transport: MessageTransport? = null

    /** Our node id / the peer's, derived from role (GO = 1, client = 2). */
    private var localNodeId = 0
    private var peerNodeId = 0

    /** Next outbound sequence number, wraps at 256. */
    private var sendSeq = 0

    /** seq -> elapsedRealtime when sent, so an incoming ACK yields the round-trip time. */
    private val pendingAcks = HashMap<Int, Long>()

    /** Monotonic id source for chat messages. */
    private var nextMessageId = 0L

    /** True once Wi-Fi P2P has reported itself enabled at least once. */
    private var p2pEnabled = false

    /**
     * True while the app is in the foreground and wants continuous discovery. When set, the
     * manager keeps peer discovery running and re-issues it whenever the framework's ~2-minute
     * cycle ends, so a peer shows up as soon as both apps are open — no manual Scan tap needed.
     */
    private var autoDiscover = false

    /** Mirrors the framework's discovery state so we can skip redundant start/stop calls. */
    private var discoveryActive = false

    /** Old persistent groups are wiped once per process; this guards that one-time cleanup. */
    private var persistentGroupsCleared = false

    /**
     * True once the peer is actually present in the current session. Used so the group owner only
     * tears down on an *empty* client list if a client had genuinely joined — otherwise the brief
     * "group formed, client not yet associated" window at setup would look like a departure.
     */
    private var peerWasPresent = false

    private val channelListener = WifiP2pManager.ChannelListener {
        // The framework channel died (Wi-Fi toggled, driver reset, etc.). Rebuild it once so
        // discovery/connect keep working without the user restarting the app.
        Log.w(TAG, "P2P channel disconnected")
        if (channelReinitAttempts < MAX_CHANNEL_REINIT) {
            channelReinitAttempts++
            mainHandler.postDelayed({ initializeChannel() }, 500)
        } else {
            updateState { it.copy(statusMessage = "Wi-Fi Direct channel lost. Toggle Wi-Fi and retry.") }
        }
    }

    init {
        if (manager == null) {
            updateState {
                it.copy(
                    connectionState = ConnectionState.P2P_UNSUPPORTED,
                    statusMessage = "This device does not support Wi-Fi Direct.",
                )
            }
        } else {
            initializeChannel()
        }
    }

    private fun initializeChannel() {
        val mgr = manager ?: return
        channel = mgr.initialize(appContext, Looper.getMainLooper(), channelListener)
        Log.d(TAG, "P2P channel initialized")
    }

    // ---------------------------------------------------------------------------------------
    // Public actions
    // ---------------------------------------------------------------------------------------

    /** Start (or restart) peer discovery. Assumes required permissions are already granted. */
    fun startDiscovery() {
        val mgr = manager ?: return
        val ch = channel ?: run { initializeChannel(); channel } ?: return

        if (!hasRequiredPermissions()) {
            updateState {
                it.copy(permissionRequired = true, statusMessage = "Permission needed to scan for devices.")
            }
            return
        }
        if (_uiState.value.connectionState == ConnectionState.WIFI_OFF ||
            _uiState.value.connectionState == ConnectionState.P2P_UNSUPPORTED
        ) {
            updateState { it.copy(statusMessage = "Turn Wi-Fi on to scan.") }
            return
        }

        autoDiscover = true
        discoveryRetries = 0
        // A manual scan is a fresh start — clear the BUSY-recovery budget (e.g. after the user
        // toggled Wi-Fi as prompted).
        consecutiveBusy = 0
        busyRecoveries = 0
        totalBusyResets = 0
        recovering = false
        cancelPendingDiscoveryRetry()
        updateState {
            it.copy(
                connectionState = ConnectionState.DISCOVERING,
                permissionRequired = false,
                statusMessage = "Scanning for nearby devices…",
            )
        }
        // Only bounce discovery if it's already running (a manual refresh). On a cold start
        // there's nothing to stop, so skip the extra round-trip and scan immediately.
        if (discoveryActive) {
            safeStopDiscovery { attemptDiscovery() }
        } else {
            attemptDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    private fun attemptDiscovery() {
        val mgr = manager ?: return
        val ch = channel ?: return
        // A recovery is in flight; it will re-issue discovery itself. Don't pile on more BUSY calls.
        if (recovering) return
        try {
            mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "discoverPeers success")
                    discoveryRetries = 0
                    consecutiveBusy = 0
                    busyRecoveries = 0
                    totalBusyResets = 0
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "discoverPeers failed: ${reasonName(reason)}")
                    // A persistent BUSY means the P2P framework is wedged (leftover group or a
                    // half-open negotiation). Retrying the same call never clears it — after a few
                    // in a row, run a full reset instead.
                    if (reason == WifiP2pManager.BUSY) {
                        consecutiveBusy++
                        if (consecutiveBusy >= BUSY_RECOVERY_THRESHOLD) {
                            recoverFromBusy()
                            return
                        }
                    }
                    // BUSY/ERROR are transient — retry with capped backoff instead of giving up.
                    if (reason != WifiP2pManager.P2P_UNSUPPORTED &&
                        discoveryRetries < MAX_DISCOVERY_RETRIES
                    ) {
                        discoveryRetries++
                        val delay = RETRY_BASE_MS * (1L shl (discoveryRetries - 1))
                        updateState { it.copy(statusMessage = "Retrying scan (${discoveryRetries})…") }
                        pendingDiscoveryRetry = Runnable { attemptDiscovery() }
                        mainHandler.postDelayed(pendingDiscoveryRetry!!, delay)
                    } else {
                        updateState {
                            it.copy(
                                connectionState = ConnectionState.IDLE,
                                statusMessage = "Couldn't start scan (${reasonName(reason)}). Try again.",
                            )
                        }
                    }
                }
            })
        } catch (se: SecurityException) {
            Log.e(TAG, "discoverPeers SecurityException", se)
            updateState {
                it.copy(
                    connectionState = ConnectionState.IDLE,
                    permissionRequired = true,
                    statusMessage = "Permission needed to scan for devices.",
                )
            }
        }
    }

    /** Connect to the given peer using push-button (PBC) config. */
    @SuppressLint("MissingPermission")
    fun connect(peer: PeerDevice) {
        Log.d(TAG, "connect() requested for ${peer.deviceName} [${peer.deviceAddress}]")
        val mgr = manager ?: run {
            Log.w(TAG, "connect: no WifiP2pManager (unsupported device)")
            return
        }
        // A dead channel would otherwise make connect() a silent no-op — rebuild it first.
        if (channel == null) {
            Log.w(TAG, "connect: channel was null, re-initializing")
            initializeChannel()
        }
        val ch = channel ?: run {
            Log.w(TAG, "connect: still no channel; aborting")
            updateState { it.copy(statusMessage = "Wi-Fi Direct not ready. Toggle Wi-Fi and retry.") }
            return
        }
        if (peer.deviceAddress.isBlank()) {
            Log.w(TAG, "connect: peer has no device address")
            updateState { it.copy(statusMessage = "That device can't be connected to. Scan again.") }
            return
        }
        if (!hasRequiredPermissions()) {
            Log.w(TAG, "connect: missing runtime permissions")
            updateState {
                it.copy(permissionRequired = true, statusMessage = "Permission needed to connect.")
            }
            return
        }

        cancelPendingDiscoveryRetry()
        updateState {
            it.copy(
                connectionState = ConnectionState.CONNECTING,
                statusMessage = "Inviting ${peer.deviceName}… accept the prompt on the other phone.",
            )
        }
        scheduleConnectTimeout(peer.deviceName)

        // No persistent-group wipe here on purpose: buildConnectConfig() requests a *temporary*
        // group (enablePersistentMode=false), so the framework never re-invokes a stored group.
        // The one-time cleanup on P2P-enabled plus the disconnect cleanup handle legacy leftovers,
        // keeping the tap-to-connect path free of ~32 blocking framework calls.
        val config = buildConnectConfig(peer.deviceAddress)

        // IMPORTANT: do NOT stop discovery first. On many devices (e.g. Samsung) stopping
        // discovery instantly drops the discovered-peer list, so connect() then targets a
        // peer the framework has already forgotten and the request is dropped. The framework
        // stops discovery itself as part of negotiating, so we call connect() directly.
        try {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "connect() invitation sent successfully")
                    // Final CONNECTED state arrives via the connection-changed broadcast.
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "connect() failed: ${reasonName(reason)}")
                    cancelConnectTimeout()
                    // A transient ERROR/BUSY right after tapping is common; offer a clean retry.
                    updateState {
                        it.copy(
                            connectionState = ConnectionState.PEERS_FOUND,
                            statusMessage = "Connection failed (${reasonName(reason)}). Tap to retry.",
                        )
                    }
                    // A BUSY connect points at the same wedged P2P state; reset before resuming.
                    if (reason == WifiP2pManager.BUSY) {
                        recoverFromBusy()
                    } else {
                        // Resume scanning so the peer list stays fresh for an immediate retry.
                        maybeAutoDiscover()
                    }
                }
            })
        } catch (se: SecurityException) {
            Log.e(TAG, "connect SecurityException", se)
            cancelConnectTimeout()
            updateState {
                it.copy(
                    connectionState = ConnectionState.PEERS_FOUND,
                    permissionRequired = true,
                    statusMessage = "Permission needed to connect.",
                )
            }
        }
    }

    /**
     * Build the connect config. On API 29+ we request a **temporary** group
     * ([WifiP2pConfig.Builder.enablePersistentMode] = false) so every connect is a fresh GO
     * negotiation. A persistent group would instead be stored on first pairing and silently
     * re-invoked afterwards with fixed roles, which is why reconnecting in the opposite
     * direction failed. Falls back to the legacy config on older devices.
     */
    private fun buildConnectConfig(address: String): WifiP2pConfig {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // The Builder has no setGroupOwnerIntent(); groupOwnerIntent is a public field
                // on the built config, so set it after build().
                return WifiP2pConfig.Builder()
                    .setDeviceAddress(MacAddress.fromString(address))
                    .enablePersistentMode(false)
                    .build()
                    .apply { groupOwnerIntent = GROUP_OWNER_INTENT }
            } catch (t: Throwable) {
                Log.w(TAG, "Builder config failed, using legacy config: ${t.message}")
            }
        }
        return WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = WpsInfo.PBC
            // Bias towards a stable group owner; 0..15, higher = more likely to be GO.
            groupOwnerIntent = GROUP_OWNER_INTENT
        }
    }

    /**
     * Delete every persistent group Wi-Fi Direct has stored for this device.
     *
     * Android saves each formed group as a *persistent* group (the `[PERSISTENT]` flag in
     * wpa_supplicant). On the next connect it prefers to silently *re-invoke* that stored
     * group — reusing the original GO/client role assignment — over a fresh negotiation. That
     * reinvite only succeeds when driven from the side that originally became client, so
     * reconnecting the other way is dropped or fails with FORMATION_FAILED. Clearing the store
     * forces a clean push-button negotiation regardless of who initiates.
     *
     * [WifiP2pManager.deletePersistentGroup] is a hidden API, so we reach it by reflection and
     * best-effort clear netIds 0..31 (deleting a non-existent id just fails harmlessly).
     */
    @SuppressLint("MissingPermission")
    private fun deletePersistentGroups() {
        val mgr = manager ?: return
        val ch = channel ?: return
        try {
            val method = WifiP2pManager::class.java.getMethod(
                "deletePersistentGroup",
                Channel::class.java,
                Int::class.javaPrimitiveType,
                WifiP2pManager.ActionListener::class.java,
            )
            for (netId in 0..31) {
                method.invoke(mgr, ch, netId, null)
            }
            Log.d(TAG, "deletePersistentGroups: cleared stored groups")
        } catch (t: Throwable) {
            Log.d(TAG, "deletePersistentGroups unavailable: ${t.message}")
        }
    }

    private fun scheduleConnectTimeout(peerName: String) {
        cancelConnectTimeout()
        pendingConnectTimeout = Runnable {
            if (_uiState.value.connectionState == ConnectionState.CONNECTING) {
                Log.w(TAG, "connect timed out for $peerName")
                updateState {
                    it.copy(
                        connectionState = ConnectionState.PEERS_FOUND,
                        statusMessage = "No response from $peerName. Make sure they accept the invite, then try again.",
                    )
                }
                // Resume scanning so the list is ready for another attempt.
                maybeAutoDiscover()
            }
        }
        mainHandler.postDelayed(pendingConnectTimeout!!, CONNECT_TIMEOUT_MS)
    }

    private fun cancelConnectTimeout() {
        pendingConnectTimeout?.let { mainHandler.removeCallbacks(it) }
        pendingConnectTimeout = null
    }

    /** Tear down the current connection / cancel an in-progress negotiation. */
    fun disconnect() {
        val mgr = manager ?: return
        val ch = channel ?: return
        cancelPendingDiscoveryRetry()

        updateState {
            it.copy(
                connectionState = ConnectionState.DISCONNECTING,
                statusMessage = "Disconnecting…",
            )
        }

        // If we were mid-negotiation, cancel the pending invitation first.
        mgr.cancelConnect(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "cancelConnect success") }
            override fun onFailure(reason: Int) { Log.d(TAG, "cancelConnect: ${reasonName(reason)}") }
        })

        // Then remove any formed group. Either listener path lands us back at IDLE.
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "removeGroup success")
                // Also drop the saved persistent group so the next connect negotiates fresh
                // in either direction instead of re-inviting this stale group.
                deletePersistentGroups()
                resetToIdle("Disconnected.")
            }

            override fun onFailure(reason: Int) {
                Log.d(TAG, "removeGroup: ${reasonName(reason)}")
                // No group existed — that's fine, we're already disconnected.
                deletePersistentGroups()
                resetToIdle("Disconnected.")
            }
        })
    }

    /**
     * Called when the screen becomes visible. Turns on continuous discovery so peers appear
     * automatically. Note: Wi-Fi Direct only advertises a device while that device is itself
     * discovering, so both phones still need the app in the foreground — this just removes the
     * requirement to tap Scan on each one.
     */
    fun onForeground() {
        autoDiscover = true
        maybeAutoDiscover()
        startDiscoveryHeartbeat()
    }

    /** Called when the screen is hidden. Stops scanning to save power; leaves any live connection. */
    fun onBackground() {
        autoDiscover = false
        cancelPendingDiscoveryRetry()
        stopDiscoveryHeartbeat()
        val s = _uiState.value.connectionState
        if (s == ConnectionState.DISCOVERING || s == ConnectionState.PEERS_FOUND || s == ConnectionState.IDLE) {
            safeStopDiscovery { }
        }
    }

    /**
     * Keep the device discoverable for as long as the app is open. Some phones silently stop
     * advertising after the framework's ~2-minute discovery window without ever reporting it, so
     * we proactively re-issue discovery on a timer. Skipped while connecting/connected so it never
     * disturbs a live session.
     */
    private fun startDiscoveryHeartbeat() {
        stopDiscoveryHeartbeat()
        discoveryHeartbeat = object : Runnable {
            override fun run() {
                if (autoDiscover && canDiscoverNow()) {
                    attemptDiscovery()
                }
                mainHandler.postDelayed(this, DISCOVERY_HEARTBEAT_MS)
            }
        }
        mainHandler.postDelayed(discoveryHeartbeat!!, DISCOVERY_HEARTBEAT_MS)
    }

    private fun stopDiscoveryHeartbeat() {
        discoveryHeartbeat?.let { mainHandler.removeCallbacks(it) }
        discoveryHeartbeat = null
    }

    /**
     * The framework reports discovery starting/stopping. Android ends each discovery cycle after
     * about two minutes; when it stops and we still want to be found, re-issue discovery so the
     * peer list keeps refreshing on its own.
     */
    fun onDiscoveryChanged(active: Boolean) {
        discoveryActive = active
        if (!active) {
            mainHandler.postDelayed({ maybeAutoDiscover() }, DISCOVERY_RESTART_MS)
        }
    }

    /** Re-issue discovery if we're foregrounded and in a state where scanning makes sense. */
    private fun maybeAutoDiscover() {
        if (!autoDiscover || !canDiscoverNow()) return
        // A BUSY recovery is in flight; let it drive discovery when it finishes.
        if (recovering) return
        // Already scanning — don't reset the driver's search cycle with a redundant call.
        if (discoveryActive) return
        // Show the scanning state on a cold start; on periodic restarts keep the current peers/state.
        if (_uiState.value.connectionState == ConnectionState.IDLE) {
            updateState {
                it.copy(
                    connectionState = ConnectionState.DISCOVERING,
                    statusMessage = "Scanning for nearby devices…",
                )
            }
        }
        attemptDiscovery()
    }

    private fun canDiscoverNow(): Boolean {
        val s = _uiState.value.connectionState
        return p2pEnabled && hasRequiredPermissions() &&
            s != ConnectionState.CONNECTING &&
            s != ConnectionState.CONNECTED &&
            s != ConnectionState.DISCONNECTING &&
            s != ConnectionState.P2P_UNSUPPORTED &&
            s != ConnectionState.WIFI_OFF
    }

    // ---------------------------------------------------------------------------------------
    // Broadcast-driven updates (called by WifiDirectBroadcastReceiver)
    // ---------------------------------------------------------------------------------------

    fun onP2pStateChanged(enabled: Boolean) {
        p2pEnabled = enabled
        if (enabled) {
            channelReinitAttempts = 0
            if (_uiState.value.connectionState == ConnectionState.WIFI_OFF) {
                updateState { it.copy(connectionState = ConnectionState.IDLE, statusMessage = "Wi-Fi Direct ready.") }
            }
            // One-time wipe of any persistent groups left over from before enablePersistentMode
            // was in use. Done here (channel ready, P2P on) and off the connect path.
            if (!persistentGroupsCleared) {
                persistentGroupsCleared = true
                deletePersistentGroups()
            }
            // P2P may have come up after the screen was already showing — begin discovery now.
            maybeAutoDiscover()
        } else {
            cancelPendingDiscoveryRetry()
            updateState {
                it.copy(
                    connectionState = ConnectionState.WIFI_OFF,
                    peers = emptyList(),
                    connectedPeerName = null,
                    groupOwnerAddress = null,
                    statusMessage = "Wi-Fi is off. Turn it on to use Wi-Fi Direct.",
                )
            }
        }
    }

    fun onPeersAvailable(devices: Collection<WifiP2pDevice>) {
        val peers = devices.map {
            PeerDevice(
                deviceAddress = it.deviceAddress ?: "",
                deviceName = it.deviceName?.ifBlank { "Unknown device" } ?: "Unknown device",
                status = it.status,
            )
        }
        Log.d(TAG, "peers available: ${peers.size}")
        updateState { current ->
            // Don't downgrade a CONNECTING/CONNECTED state just because peers refreshed.
            val newState = when (current.connectionState) {
                ConnectionState.DISCOVERING, ConnectionState.PEERS_FOUND, ConnectionState.IDLE ->
                    if (peers.isNotEmpty()) ConnectionState.PEERS_FOUND else current.connectionState
                else -> current.connectionState
            }
            current.copy(
                peers = peers,
                connectionState = newState,
                statusMessage = when (current.connectionState) {
                    // Don't clobber the message while a connect/disconnect is mid-flight.
                    ConnectionState.CONNECTING,
                    ConnectionState.CONNECTED,
                    ConnectionState.DISCONNECTING -> current.statusMessage
                    else -> when {
                        peers.isEmpty() && newState == ConnectionState.DISCOVERING -> "Scanning for nearby devices…"
                        peers.isEmpty() -> "No devices found yet. Scan again."
                        else -> "Found ${peers.size} device(s). Tap one to connect."
                    }
                },
            )
        }
    }

    fun onConnectionChanged(isConnected: Boolean) {
        if (isConnected) {
            if (_uiState.value.connectionState == ConnectionState.CONNECTED) {
                // Already connected and something changed. On the group owner this fires when
                // the client leaves — but the GO keeps its (now empty) group up, so isConnected
                // stays true and we'd otherwise look connected forever. Re-check the client list.
                verifyPeerStillPresent()
            } else {
                requestConnectionInfo()
            }
        } else {
            // Covers the remote side pressing disconnect too — return cleanly to idle.
            if (_uiState.value.connectionState == ConnectionState.CONNECTED ||
                _uiState.value.connectionState == ConnectionState.DISCONNECTING
            ) {
                // The remote side may have torn down the group; clear our saved persistent
                // entry too so a future reconnect from either side negotiates fresh.
                deletePersistentGroups()
                resetToIdle("Disconnected.")
            }
        }
    }

    /**
     * We think we're connected and a connection-change just fired. If we're the group owner and
     * our peer has left (empty client list), or the group is gone entirely, tear our side down so
     * both phones agree they're disconnected — fixes the GO staying "Connected" after the client
     * leaves.
     */
    @SuppressLint("MissingPermission")
    private fun verifyPeerStillPresent() {
        val mgr = manager ?: return
        val ch = channel ?: return
        try {
            mgr.requestGroupInfo(ch) { group ->
                // A present peer: a client on the GO side, or the owner on the client side.
                val peerPresent = group != null &&
                    if (group.isGroupOwner) !group.clientList.isNullOrEmpty() else group.owner != null
                if (peerPresent) {
                    peerWasPresent = true
                    return@requestGroupInfo
                }
                // Empty now — only a real departure if a peer had actually joined this session.
                if (peerWasPresent && _uiState.value.connectionState == ConnectionState.CONNECTED) {
                    Log.d(TAG, "peer left the group; tearing down our side")
                    tearDownAfterPeerLeft()
                }
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "verifyPeerStillPresent SecurityException", se)
        }
    }

    /** Remove our lingering (empty) group after the peer left, then return to scanning. */
    private fun tearDownAfterPeerLeft() {
        val mgr = manager ?: return resetToIdle("Disconnected.")
        val ch = channel ?: return resetToIdle("Disconnected.")
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                deletePersistentGroups()
                resetToIdle("Disconnected.")
            }

            override fun onFailure(reason: Int) {
                deletePersistentGroups()
                resetToIdle("Disconnected.")
            }
        })
    }

    fun onThisDeviceChanged(device: WifiP2pDevice) {
        updateState { it.copy(thisDeviceName = device.deviceName ?: "This device") }
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        val mgr = manager ?: return
        val ch = channel ?: return
        try {
            mgr.requestConnectionInfo(ch) { info ->
                if (info != null && info.groupFormed) {
                    cancelConnectTimeout()
                    val goAddress = info.groupOwnerAddress?.hostAddress
                    // Flip to CONNECTED right away — don't make the user wait on the extra
                    // requestGroupInfo round-trip that only supplies the peer's friendly name.
                    updateState {
                        it.copy(
                            connectionState = ConnectionState.CONNECTED,
                            isGroupOwner = info.isGroupOwner,
                            groupOwnerAddress = goAddress,
                            statusMessage = if (info.isGroupOwner) {
                                "Connected as group owner."
                            } else {
                                "Connected to group owner."
                            },
                        )
                    }
                    // Open the message socket for this session.
                    startTransport(info.isGroupOwner, goAddress)
                    // Fill in the peer name once group info arrives; the UI is already connected.
                    // A non-null name means the peer is actually in the group — remember that so
                    // the GO knows a later empty client list is a real departure, not setup.
                    fetchGroupPeerName { peerName ->
                        if (peerName != null) {
                            peerWasPresent = true
                            updateState { it.copy(connectedPeerName = peerName) }
                        }
                    }
                }
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "requestConnectionInfo SecurityException", se)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchGroupPeerName(onResult: (String?) -> Unit) {
        val mgr = manager ?: return onResult(null)
        val ch = channel ?: return onResult(null)
        try {
            mgr.requestGroupInfo(ch) { group ->
                if (group == null) {
                    onResult(null)
                    return@requestGroupInfo
                }
                val name = if (group.isGroupOwner) {
                    group.clientList?.firstOrNull()?.deviceName
                } else {
                    group.owner?.deviceName
                }
                onResult(name)
            }
        } catch (se: SecurityException) {
            onResult(null)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Messaging
    // ---------------------------------------------------------------------------------------

    /** Open the socket for the current session. Roles: group owner listens, client dials in. */
    private fun startTransport(isGroupOwner: Boolean, goAddress: String?) {
        if (transport != null) return
        localNodeId = if (isGroupOwner) NODE_GO else NODE_CLIENT
        peerNodeId = if (isGroupOwner) NODE_CLIENT else NODE_GO
        sendSeq = 0
        pendingAcks.clear()
        updateState { it.copy(linkReady = false, messages = emptyList()) }

        val t = MessageTransport(
            onLinkReady = {
                mainHandler.post {
                    updateState { it.copy(linkReady = true, statusMessage = "Ready to message.") }
                }
            },
            onFrame = { frame -> mainHandler.post { handleIncomingFrame(frame) } },
            onClosed = { reason ->
                mainHandler.post {
                    if (_uiState.value.connectionState == ConnectionState.CONNECTED) {
                        updateState { it.copy(linkReady = false) }
                        Log.d(TAG, "message link closed: $reason")
                    }
                }
            },
        )
        transport = t
        t.start(isGroupOwner, goAddress)
    }

    private fun stopTransport() {
        transport?.stop()
        transport = null
        pendingAcks.clear()
    }

    /**
     * Encode [text] into the binary [Packet] format and send it, timing the encode step. The
     * sent message shows a round-trip time once the peer's ACK returns.
     */
    fun sendText(text: String) {
        val t = transport ?: return
        if (!_uiState.value.linkReady) {
            updateState { it.copy(statusMessage = "Link not ready yet — one moment.") }
            return
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val seq = sendSeq and 0xFF
        val t0 = System.nanoTime()
        val frame = try {
            Packet.encode(Packet.TYPE_NORMAL, localNodeId, peerNodeId, LANG_DEFAULT, seq, trimmed)
        } catch (e: IllegalArgumentException) {
            updateState { it.copy(statusMessage = "Message too long (max ${Packet.MAX_PAYLOAD} bytes).") }
            return
        }
        val encodeMicros = (System.nanoTime() - t0) / 1000
        sendSeq = (sendSeq + 1) and 0xFF

        val message = ChatMessage(
            id = nextMessageId++,
            text = trimmed,
            outgoing = true,
            type = Packet.TYPE_NORMAL,
            seq = seq,
            frameBytes = frame.size,
            codecMicros = encodeMicros,
        )
        pendingAcks[seq] = SystemClock.elapsedRealtime()
        updateState { it.copy(messages = it.messages + message) }
        t.send(frame)
    }

    /** Decode a received frame (timing the decode), display it, and ACK NORMAL/ALERT messages. */
    private fun handleIncomingFrame(frame: ByteArray) {
        val t0 = System.nanoTime()
        val decoded = Packet.decode(frame)
        val decodeMicros = (System.nanoTime() - t0) / 1000
        if (decoded == null) {
            Log.w(TAG, "dropped a corrupt/failed-CRC frame")
            return
        }

        when (decoded.type) {
            Packet.TYPE_ACK -> {
                val sentAt = pendingAcks.remove(decoded.seq) ?: return
                val rtt = SystemClock.elapsedRealtime() - sentAt
                updateState { state ->
                    state.copy(
                        messages = state.messages.map { m ->
                            if (m.outgoing && m.seq == decoded.seq && m.roundTripMillis == null) {
                                m.copy(roundTripMillis = rtt)
                            } else {
                                m
                            }
                        },
                    )
                }
            }

            Packet.TYPE_NORMAL, Packet.TYPE_ALERT -> {
                val message = ChatMessage(
                    id = nextMessageId++,
                    text = decoded.text,
                    outgoing = false,
                    type = decoded.type,
                    seq = decoded.seq,
                    frameBytes = frame.size,
                    codecMicros = decodeMicros,
                )
                updateState { it.copy(messages = it.messages + message) }
                // ACK it back so the sender can measure the round-trip. Empty payload.
                val ack = Packet.encode(
                    Packet.TYPE_ACK, localNodeId, decoded.src, LANG_DEFAULT, decoded.seq, "",
                )
                transport?.send(ack)
            }

            else -> Log.d(TAG, "ignoring frame type ${decoded.type}") // 3-15 reserved/ignored
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun safeStopDiscovery(then: () -> Unit) {
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) {
            then()
            return
        }
        try {
            mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { then() }
                override fun onFailure(reason: Int) { then() }
            })
        } catch (se: SecurityException) {
            then()
        }
    }

    /** cancelConnect that always calls [then], even when nothing is pending. */
    @SuppressLint("MissingPermission")
    private fun safeCancelConnect(then: () -> Unit) {
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) return then()
        try {
            mgr.cancelConnect(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { then() }
                override fun onFailure(reason: Int) { then() }
            })
        } catch (e: Exception) {
            then()
        }
    }

    /** removeGroup that always calls [then], even when no group exists. */
    @SuppressLint("MissingPermission")
    private fun safeRemoveGroup(then: () -> Unit) {
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) return then()
        try {
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { then() }
                override fun onFailure(reason: Int) { then() }
            })
        } catch (e: Exception) {
            then()
        }
    }

    /**
     * The P2P framework is stuck returning BUSY — usually a leftover group or a half-open
     * negotiation from a failed connect that survives even an app restart. Clear all pending P2P
     * state (cancel any connect, remove any group, stop discovery), and if repeated resets don't
     * take, rebuild the channel. Then resume discovery.
     */
    @SuppressLint("MissingPermission")
    private fun recoverFromBusy() {
        if (recovering) return
        // If even channel rebuilds haven't cleared it, only a Wi-Fi driver reset will. Stop
        // hammering and tell the user; a manual Scan (or Wi-Fi toggle) resets these counters.
        if (totalBusyResets >= MAX_TOTAL_BUSY_RESETS) {
            Log.w(TAG, "P2P still BUSY after $totalBusyResets resets; asking user to toggle Wi-Fi")
            updateState {
                it.copy(
                    connectionState = ConnectionState.IDLE,
                    statusMessage = "Wi-Fi Direct is stuck. Turn Wi-Fi off and on, then Scan.",
                )
            }
            return
        }
        recovering = true
        consecutiveBusy = 0
        totalBusyResets++
        cancelPendingDiscoveryRetry()
        Log.w(TAG, "P2P wedged (BUSY); running recovery #${busyRecoveries + 1}")
        updateState { it.copy(statusMessage = "Resetting Wi-Fi Direct…") }

        safeCancelConnect {
            safeRemoveGroup {
                safeStopDiscovery {
                    busyRecoveries++
                    // If clearing state hasn't helped after a couple of tries, rebuild the channel.
                    if (busyRecoveries >= MAX_BUSY_RECOVERIES) {
                        busyRecoveries = 0
                        Log.w(TAG, "recovery: rebuilding P2P channel")
                        initializeChannel()
                    }
                    mainHandler.postDelayed({
                        recovering = false
                        maybeAutoDiscover()
                    }, BUSY_RECOVERY_DELAY_MS)
                }
            }
        }
    }

    private fun resetToIdle(message: String) {
        cancelPendingDiscoveryRetry()
        cancelConnectTimeout()
        peerWasPresent = false
        consecutiveBusy = 0
        stopTransport()
        updateState {
            it.copy(
                connectionState = if (p2pEnabled) ConnectionState.IDLE else ConnectionState.WIFI_OFF,
                peers = emptyList(),
                connectedPeerName = null,
                isGroupOwner = false,
                groupOwnerAddress = null,
                statusMessage = message,
                linkReady = false,
                messages = emptyList(),
            )
        }
        // After a disconnect/teardown, resume scanning automatically if we're still foregrounded.
        maybeAutoDiscover()
    }

    private fun cancelPendingDiscoveryRetry() {
        pendingDiscoveryRetry?.let { mainHandler.removeCallbacks(it) }
        pendingDiscoveryRetry = null
    }

    fun onPermissionsGranted() {
        updateState { it.copy(permissionRequired = false) }
    }

    /** Build a broadcast receiver wired to this manager's channel, without exposing internals. */
    fun createReceiver(): WifiDirectBroadcastReceiver =
        WifiDirectBroadcastReceiver(this, manager) { channel }

    fun hasRequiredPermissions(): Boolean {
        val perms = requiredPermissions()
        return perms.all {
            ContextCompat.checkSelfPermission(appContext, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun updateState(transform: (WifiDirectUiState) -> WifiDirectUiState) {
        _uiState.update(transform)
    }

    private fun reasonName(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
        WifiP2pManager.BUSY -> "busy"
        WifiP2pManager.ERROR -> "error"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
        else -> "reason $reason"
    }

    companion object {
        private const val TAG = "WifiDirectManager"
        private const val MAX_DISCOVERY_RETRIES = 3
        private const val MAX_CHANNEL_REINIT = 3
        private const val RETRY_BASE_MS = 1000L
        private const val CONNECT_TIMEOUT_MS = 30_000L

        /** Delay before re-issuing discovery after the framework's cycle ends. */
        private const val DISCOVERY_RESTART_MS = 1500L

        /**
         * Interval for the discovery heartbeat. Kept under the framework's ~2-minute discovery
         * timeout so we re-issue before it silently stops advertising and we drop off other
         * phones' radar. 90s leaves comfortable margin.
         */
        private const val DISCOVERY_HEARTBEAT_MS = 90_000L

        /** Consecutive BUSY failures before we stop retrying and force a P2P reset. */
        private const val BUSY_RECOVERY_THRESHOLD = 3

        /** Reset attempts (clear state) before we escalate to rebuilding the channel. */
        private const val MAX_BUSY_RECOVERIES = 2

        /** Total resets before we give up and ask the user to toggle Wi-Fi. */
        private const val MAX_TOTAL_BUSY_RESETS = 4

        /** Node ids used in the packet src/dst fields, derived from the group role. */
        private const val NODE_GO = 1
        private const val NODE_CLIENT = 2

        /** Default language field (0). */
        private const val LANG_DEFAULT = 0

        /** Settle time after a reset before we re-issue discovery. */
        private const val BUSY_RECOVERY_DELAY_MS = 2500L

        /** GO negotiation bias; 0..15, higher = more likely to become group owner. */
        private const val GROUP_OWNER_INTENT = 7

        /** Runtime permissions this app needs, keyed by SDK level. */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
    }
}
