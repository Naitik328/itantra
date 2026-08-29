package com.itantra.relay.transport

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.itantra.relay.protocol.WireFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** A discovered iTantra peer (from Wi-Fi P2P service discovery). */
data class WifiPeer(val name: String, val address: String, val device: WifiP2pDevice)

/**
 * Backend B — phone ↔ phone over **Wi-Fi Direct (Wi-Fi P2P)**.
 *
 * Advertises an iTantra DNS-SD service continuously (so this phone is discoverable
 * for as long as the app runs — no Bluetooth-style 5-minute cap) and discovers
 * peers advertising the same service (so only phones running iTantra appear).
 *
 * Two link roles:
 *  - **1:1 walkie link** via [connect] — this phone joins a peer's group as a client.
 *  - **Alert host** via [startAlertHost] — this phone becomes an autonomous Group
 *    Owner, accepts *many* clients at once, flags `alert=1` in its DNS-SD TXT record
 *    so nearby phones auto-join, and [broadcast]s a wire frame to everyone. [send]
 *    fans out over whichever links exist, so the same call works in both roles.
 */
class WifiDirectTransport(
    context: Context,
    private val scope: CoroutineScope,
) : Transport {

    enum class Status { IDLE, DISCOVERING, CONNECTING, CONNECTED, ERROR }

    /** One accepted client while this phone is the alert host. */
    private class ClientConn(val socket: Socket, val output: OutputStream) {
        val mutex = Mutex()
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incoming = _incoming.asSharedFlow()

    private val _status = MutableStateFlow(Status.IDLE)
    val status = _status.asStateFlow()

    private val _peer = MutableStateFlow<String?>(null)
    val peer = _peer.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _peers = MutableStateFlow<List<WifiPeer>>(emptyList())
    val peers = _peers.asStateFlow()

    /** How many phones are currently joined to us while we host an alert. */
    private val _connectedCount = MutableStateFlow(0)
    val connectedCount = _connectedCount.asStateFlow()

    private val discovered = LinkedHashMap<String, WifiPeer>()
    private var myName = "iTantra"
    private var connectingName: String? = null

    private var discoveryJob: Job? = null
    private var connJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var serverSocket: ServerSocket? = null
    @Volatile private var initialized = false
    private var serviceRequest: WifiP2pDnsSdServiceRequest? = null

    // Client role (we joined someone else's group).
    private var socket: Socket? = null
    private var output: OutputStream? = null
    private val writeMutex = Mutex()

    // Host role (an alert we're broadcasting) — many clients at once.
    private val clients = CopyOnWriteArrayList<ClientConn>()
    @Volatile private var hosting = false

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context?, intent: Intent) {
            val ch = channel ?: return
            val mgr = manager ?: return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    mgr.requestConnectionInfo(ch) { info ->
                        if (info != null && info.groupFormed) {
                            connectTimeoutJob?.cancel(); connectTimeoutJob = null
                            if (info.isGroupOwner) {
                                if (!hosting) {
                                    // A 1:1 link where we're the group owner — surface the
                                    // peer immediately, then refine its name from group info.
                                    if (_peer.value == null) _peer.value = connectingName ?: "Peer"
                                    runCatching {
                                        mgr.requestGroupInfo(ch) { group ->
                                            val n = group?.clientList?.firstOrNull()?.deviceName
                                            if (!n.isNullOrBlank()) _peer.value = n
                                        }
                                    }
                                }
                                startHostAccept()
                            } else {
                                startClient(info.groupOwnerAddress)
                            }
                        } else {
                            // Group torn down — only react to an *established* link
                            // ending (peer left / app killed). Ignoring it mid-connect
                            // avoids aborting the handshake; the timeout catches stalls.
                            if (!hosting && _status.value == Status.CONNECTED) handlePeerLost()
                        }
                    }
                }
            }
        }
    }

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    /**
     * Start (or restart) advertising + discovery. Safe to call more than once —
     * the channel and receiver are set up only on the first call, but advertising
     * and the discovery loop are (re)started every time. That matters because the
     * first call often happens before the NEARBY_WIFI_DEVICES / location runtime
     * permission is granted (so addLocalService / discoverServices silently fail);
     * calling it again after the grant is what actually gets the phone on the air.
     */
    @SuppressLint("MissingPermission")
    fun register(userName: String) {
        val mgr = manager
        if (mgr == null) {
            _status.value = Status.ERROR
            _error.value = "This device has no Wi-Fi Direct."
            return
        }
        myName = userName.ifBlank { "iTantra" }
        if (!initialized) {
            channel = mgr.initialize(appContext, Looper.getMainLooper(), null)
            ContextCompat.registerReceiver(appContext, receiver, intentFilter, ContextCompat.RECEIVER_EXPORTED)
            setupServiceDiscovery()
            // Clear any P2P group left over from a previous run (a killed app leaves
            // the group at the OS level, which otherwise blocks the next connect).
            runCatching { mgr.removeGroup(channel, null) }
            initialized = true
        }
        // Don't kick discovery back on if we're mid-connect or already linked.
        if (_status.value != Status.CONNECTED && _status.value != Status.CONNECTING) {
            resumeDiscovery()
        }
    }

    /**
     * (Re)advertise and start the discovery loop. Discovery is PAUSED while
     * connecting or connected — calling connect()/createGroup() while a peer or
     * service scan is running makes the framework return BUSY, which is exactly
     * why a reconnect after a disconnect fails.
     */
    @SuppressLint("MissingPermission")
    private fun resumeDiscovery() {
        advertise(alert = false)
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            while (isActive) {
                // Peer discovery first, let the radio settle, THEN service discovery —
                // running them together makes discoverServices return BUSY on many
                // phones. Peer discovery keeps Samsung actively scanning so it picks
                // up the other phone's DNS-SD advert.
                discoverPeersOnce()
                delay(2_000)
                discoverServicesOnce()
                if (_status.value != Status.CONNECTED && _status.value != Status.CONNECTING) {
                    _status.value = Status.DISCOVERING
                }
                delay(10_000)
            }
        }
    }

    /** Stop the discovery loop and the framework's active scan before connecting. */
    @SuppressLint("MissingPermission")
    private fun pauseDiscovery() {
        discoveryJob?.cancel(); discoveryJob = null
        val mgr = manager; val ch = channel
        if (mgr != null && ch != null) runCatching { mgr.stopPeerDiscovery(ch, null) }
    }

    fun unregister() {
        discoveryJob?.cancel(); discoveryJob = null
        stopAlertHost()
        stop()
        val mgr = manager; val ch = channel
        if (mgr != null && ch != null) {
            runCatching { mgr.clearLocalServices(ch, null) }
            runCatching { mgr.clearServiceRequests(ch, null) }
        }
        runCatching { appContext.unregisterReceiver(receiver) }
        channel = null
        initialized = false
    }

    /**
     * Advertise this phone's DNS-SD service. When [alert] is true the TXT record
     * carries `alert=1`, which nearby phones watch for to auto-join our group.
     */
    @SuppressLint("MissingPermission")
    private fun advertise(alert: Boolean) {
        val mgr = manager ?: return; val ch = channel ?: return
        val record = if (alert) mapOf("name" to myName, "alert" to "1") else mapOf("name" to myName)
        val info = WifiP2pDnsSdServiceInfo.newInstance(myName, SERVICE_TYPE, record)
        mgr.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { mgr.addLocalService(ch, info, logListener("addLocalService")) }
            override fun onFailure(reason: Int) { mgr.addLocalService(ch, info, logListener("addLocalService")) }
        })
    }

    @SuppressLint("MissingPermission")
    private fun setupServiceDiscovery() {
        val mgr = manager ?: return; val ch = channel ?: return
        mgr.setDnsSdResponseListeners(
            ch,
            { instanceName, registrationType, srcDevice ->
                if (registrationType.startsWith(SERVICE_TYPE, ignoreCase = true) && srcDevice != null) {
                    Log.d(TAG, "found service: $instanceName @ ${srcDevice.deviceAddress}")
                    discovered[srcDevice.deviceAddress] = WifiPeer(instanceName, srcDevice.deviceAddress, srcDevice)
                    _peers.value = discovered.values.toList()
                }
            },
            { _, txtRecord, srcDevice ->
                // A peer advertising alert=1 is broadcasting an SOS — auto-join it.
                if (srcDevice != null && txtRecord["alert"] == "1") {
                    maybeAutoJoinAlert(srcDevice, txtRecord["name"])
                }
            },
        )
        addServiceRequest()
    }

    @SuppressLint("MissingPermission")
    private fun addServiceRequest() {
        val mgr = manager ?: return; val ch = channel ?: return
        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance().also { req ->
            mgr.addServiceRequest(ch, req, logListener("addServiceRequest"))
        }
    }

    /** Join a peer that is broadcasting an alert, unless we're already busy/hosting. */
    private fun maybeAutoJoinAlert(srcDevice: WifiP2pDevice, name: String?) {
        if (hosting) return // we're the one alerting
        if (_status.value == Status.CONNECTING || _status.value == Status.CONNECTED) return
        val peer = discovered[srcDevice.deviceAddress]
            ?: WifiPeer(name ?: srcDevice.deviceName ?: "Peer", srcDevice.deviceAddress, srcDevice)
        connect(peer)
    }

    /**
     * Start peer discovery. Many phones (Samsung especially) only return DNS-SD
     * service responses while peer discovery is active — this keeps the radio
     * scanning so the other phone's advert actually comes through.
     */
    @SuppressLint("MissingPermission")
    private fun discoverPeersOnce() {
        val mgr = manager ?: return; val ch = channel ?: return
        mgr.discoverPeers(ch, logListener("discoverPeers"))
    }

    /** Run DNS-SD service discovery; repair the request list if the OS dropped it. */
    @SuppressLint("MissingPermission")
    private fun discoverServicesOnce() {
        val mgr = manager ?: return; val ch = channel ?: return
        mgr.discoverServices(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.d(TAG, "discoverServices ok") }
            override fun onFailure(reason: Int) {
                Log.w(TAG, "discoverServices failed: ${reasonName(reason)}")
                if (reason == WifiP2pManager.NO_SERVICE_REQUESTS) addServiceRequest()
            }
        })
    }

    /** Re-advertise and restart the scan — wired to the Nearby sheet's Refresh. */
    fun retry() {
        if (_status.value == Status.CONNECTED || _status.value == Status.CONNECTING) return
        resumeDiscovery()
    }

    private fun logListener(what: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { Log.d(TAG, "$what ok") }
        override fun onFailure(reason: Int) { Log.w(TAG, "$what failed: ${reasonName(reason)}") }
    }

    private fun reasonName(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
        WifiP2pManager.ERROR -> "ERROR"
        else -> "reason $reason"
    }

    /** Connect to a discovered [peer] — forms a P2P group, then a socket (client role). */
    @SuppressLint("MissingPermission")
    fun connect(peer: WifiPeer) {
        val mgr = manager ?: return; val ch = channel ?: return
        _error.value = null
        connectingName = peer.name
        _peer.value = null
        _status.value = Status.CONNECTING
        val config = WifiP2pConfig().apply {
            deviceAddress = peer.address
            wps.setup = WpsInfo.PBC
        }

        // Plain connect() — the path that actually works. BUSY just means a scan is
        // still winding down (or a stale group is up), so retry a few times; after a
        // couple of tries, clear any leftover group in case that's what's blocking.
        fun attempt(tries: Int) {
            mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { Log.d(TAG, "connect initiated (try $tries)") }
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "connect failed: ${reasonName(reason)} (try $tries)")
                    if (reason == WifiP2pManager.BUSY && tries < 5) {
                        if (tries == 2) runCatching { mgr.removeGroup(ch, null) }
                        scope.launch(Dispatchers.Main) { delay(1200); attempt(tries + 1) }
                    } else {
                        _error.value = "Couldn't start the connection (${reasonName(reason)})."
                        handlePeerLost()
                    }
                }
            })
        }

        // Stop launching new scans so we stop feeding the BUSY, but DON'T call
        // stopPeerDiscovery/removeGroup up front — that was breaking the connect.
        discoveryJob?.cancel(); discoveryJob = null
        attempt(0)

        // If the group never forms, give up instead of hanging on "Connecting…".
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(30_000)
            if (_status.value == Status.CONNECTING) {
                _error.value = "Couldn't reach ${peer.name}. Try again."
                handlePeerLost()
            }
        }
    }

    /** Tear down the current link on demand (the Disconnect button). */
    fun disconnect() = stop()

    /** Local cleanup when a link drops (peer left / app killed) — no removeGroup. */
    private fun handlePeerLost() {
        connectTimeoutJob?.cancel(); connectTimeoutJob = null
        connJob?.cancel(); connJob = null
        runCatching { socket?.close() }; socket = null; output = null
        runCatching { serverSocket?.close() }; serverSocket = null
        _peer.value = null
        if (_status.value == Status.CONNECTED || _status.value == Status.CONNECTING) {
            _status.value = if (channel != null) Status.DISCOVERING else Status.IDLE
        }
        // Back to scanning so the phones can find and reconnect to each other.
        if (channel != null && !hosting) resumeDiscovery()
    }

    override fun start() {}

    /** Tear down the current 1:1 group/socket and return to discovering. */
    @SuppressLint("MissingPermission")
    override fun stop() {
        if (hosting) return // handled by stopAlertHost()
        connectTimeoutJob?.cancel(); connectTimeoutJob = null
        connectingName = null
        connJob?.cancel(); connJob = null
        runCatching { socket?.close() }
        runCatching { serverSocket?.close() }
        socket = null; serverSocket = null; output = null
        val mgr = manager; val ch = channel
        if (mgr != null && ch != null) runCatching { mgr.removeGroup(ch, null) }
        if (_status.value != Status.ERROR) {
            _status.value = if (channel != null) Status.DISCOVERING else Status.IDLE
        }
        _peer.value = null
        // Resume scanning after an explicit disconnect so a fresh connect works.
        if (channel != null) resumeDiscovery()
    }

    // ---- Alert host (one-to-many) -----------------------------------------

    /**
     * Become an autonomous Group Owner and start accepting many clients. Flags
     * `alert=1` in the DNS-SD record so nearby phones auto-join. Follow with
     * [broadcast] to push the alert frame, then [stopAlertHost] to tear down.
     */
    @SuppressLint("MissingPermission")
    fun startAlertHost() {
        val mgr = manager ?: return; val ch = channel ?: return
        // A phone can be in only one P2P group — drop any 1:1 link first.
        connJob?.cancel(); connJob = null
        runCatching { socket?.close() }; socket = null; output = null
        clients.clear(); _connectedCount.value = 0
        hosting = true
        _error.value = null
        _status.value = Status.CONNECTING
        // createGroup also returns BUSY if a scan is running — stop it first.
        pauseDiscovery()
        advertise(alert = true)
        val createGroup = {
            mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { /* CONNECTION_CHANGED → isGroupOwner → startHostAccept */ }
                override fun onFailure(reason: Int) { fail("Couldn't start the alert group (code $reason).") }
            })
        }
        // If a stale group exists, createGroup fails with BUSY — clear it, then retry.
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(reason: Int) {
                mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { createGroup() }
                    override fun onFailure(r: Int) { createGroup() }
                })
            }
        })
    }

    /** Stop hosting the alert: revert the TXT record, drop clients, remove the group. */
    @SuppressLint("MissingPermission")
    fun stopAlertHost() {
        if (!hosting) return
        hosting = false
        connJob?.cancel(); connJob = null
        clients.forEach { runCatching { it.socket.close() } }
        clients.clear(); _connectedCount.value = 0
        runCatching { serverSocket?.close() }; serverSocket = null
        advertise(alert = false)
        val mgr = manager; val ch = channel
        if (mgr != null && ch != null) runCatching { mgr.removeGroup(ch, null) }
        if (_status.value != Status.ERROR) {
            _status.value = if (channel != null) Status.DISCOVERING else Status.IDLE
        }
        _peer.value = null
        if (channel != null) resumeDiscovery()
    }

    /** Push one wire frame to everyone we're linked to (host fan-out + client link). */
    suspend fun broadcast(frame: ByteArray) = send(frame)

    override suspend fun send(frame: ByteArray) {
        // Client link (we joined a host).
        output?.let { out ->
            writeMutex.withLock {
                withContext(Dispatchers.IO) { runCatching { out.write(frame); out.flush() } }
            }
        }
        // Host fan-out (clients joined us).
        for (c in clients) {
            val ok = c.mutex.withLock {
                withContext(Dispatchers.IO) {
                    runCatching { c.output.write(frame); c.output.flush() }.isSuccess
                }
            }
            if (!ok) dropClient(c)
        }
    }

    // ---- Sockets ----------------------------------------------------------

    /** Group-owner accept loop — keeps taking clients for as long as we host. */
    private fun startHostAccept() {
        connJob?.cancel()
        connJob = scope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket(PORT)
                serverSocket = ss
                _status.value = Status.CONNECTED
                while (isActive) {
                    val s = ss.accept()
                    val conn = ClientConn(s, s.getOutputStream())
                    clients.add(conn)
                    _connectedCount.value = clients.size
                    launch { serveClient(conn) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: IOException) {
                // serverSocket closed on teardown — expected.
            } catch (e: Exception) {
                if (hosting) fail("Host socket failed: ${e.message}")
            }
        }
    }

    private suspend fun serveClient(conn: ClientConn) {
        try {
            readLoop(conn.socket.getInputStream())
        } finally {
            dropClient(conn)
        }
    }

    private fun dropClient(conn: ClientConn) {
        if (clients.remove(conn)) _connectedCount.value = clients.size
        runCatching { conn.socket.close() }
    }

    private fun startClient(host: InetAddress) {
        connJob?.cancel()
        connJob = scope.launch(Dispatchers.IO) {
            var s: Socket? = null
            for (attempt in 0 until 12) { // server may need a moment after the group forms
                s = runCatching {
                    Socket().apply { connect(InetSocketAddress(host, PORT), 3000) }
                }.getOrNull()
                if (s != null) break
                delay(500)
            }
            if (s == null) {
                fail("Couldn't reach the other phone after connecting.")
                return@launch
            }
            serveAsClient(s)
        }
    }

    private suspend fun serveAsClient(s: Socket) {
        socket = s
        output = s.getOutputStream()
        _peer.value = connectingName ?: "Peer"
        _error.value = null
        _status.value = Status.CONNECTED
        readLoop(s.getInputStream())
        output = null
        _peer.value = null
        runCatching { s.close() }
        if (socket === s) socket = null
        // Link lost → back to discovering.
        if (_status.value == Status.CONNECTED) _status.value = Status.DISCOVERING
    }

    /** Reassemble length-delimited wire frames from the byte stream (same as Backend A). */
    private suspend fun readLoop(input: InputStream) {
        val data = DataInputStream(input)
        val header = ByteArray(WireFrame.HEADER_SIZE)
        try {
            while (true) {
                data.readFully(header)
                val len = header[5].toInt() and 0xFF
                val frame = ByteArray(WireFrame.HEADER_SIZE + len + WireFrame.CRC_SIZE)
                System.arraycopy(header, 0, frame, 0, WireFrame.HEADER_SIZE)
                data.readFully(frame, WireFrame.HEADER_SIZE, len + WireFrame.CRC_SIZE)
                _incoming.emit(frame)
            }
        } catch (_: IOException) {
            // peer closed / link lost
        }
    }

    private fun fail(message: String) {
        _error.value = message
        _status.value = Status.ERROR
    }

    companion object {
        private const val SERVICE_TYPE = "_itantra._tcp"
        private const val PORT = 8988
        private const val TAG = "ItantraP2P"
    }
}
