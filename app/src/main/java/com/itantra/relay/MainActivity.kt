package com.itantra.relay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.itantra.relay.audio.AudioCapture
import com.itantra.relay.transport.RelayHub
import com.itantra.relay.transport.RelayService
import com.itantra.relay.transport.WifiDirectTransport
import com.itantra.relay.ui.ConnStatus
import com.itantra.relay.ui.ProfileStore
import com.itantra.relay.ui.Squad
import com.itantra.relay.ui.UserProfile
import com.itantra.relay.ui.screens.ChannelsScreen
import com.itantra.relay.ui.screens.CreateSheet
import com.itantra.relay.ui.screens.DeviceScreen
import com.itantra.relay.ui.screens.IncomingAlertOverlay
import com.itantra.relay.ui.screens.NearbySheet
import com.itantra.relay.ui.screens.OnboardingScreen
import com.itantra.relay.ui.screens.ProfileScreen
import com.itantra.relay.ui.screens.SendingAlertSheet
import com.itantra.relay.ui.theme.ChannelTints
import com.itantra.relay.ui.theme.ItantraTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark status-bar icons read cleanly on the bone-plastic body.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            ItantraTheme {
                AppRoot()
            }
        }
    }
}

private enum class Screen { DEVICE, CHANNELS, SETTINGS }

/** Stable id for the one live 1:1 peer channel. */
private const val PEER_ID = "peer-link"

/** Whether the runtime permission Wi-Fi Direct discovery needs is granted. */
private fun hasWifiPermission(c: android.content.Context): Boolean {
    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }
    return ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    var profile by remember { mutableStateOf(ProfileStore.load(context)) }

    val current = profile
    if (current == null) {
        OnboardingScreen(onDone = { p ->
            ProfileStore.save(context, p)
            profile = p
        })
    } else {
        MainApp(current)
    }
}

@Composable
private fun MainApp(profile: UserProfile) {
    val context = LocalContext.current

    // Navigation back-stack.
    val backStack = remember { mutableStateListOf(Screen.DEVICE) }
    fun go(s: Screen) { if (backStack.last() != s) backStack.add(s) }
    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        else (context as? Activity)?.finish()
    }

    // Channels are real: they appear when a peer connects over Wi-Fi Direct (plus
    // any the user creates). There is one live 1:1 peer link at a time, kept under
    // a stable id so its name can be refreshed as the transport learns it.
    val channels = remember { mutableStateListOf<Squad>() }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selectedIndex = channels.indexOfFirst { it.id == selectedId }
    val selected = channels.getOrNull(selectedIndex)

    // Device controls.
    var speakerOn by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }

    // Shared Wi-Fi Direct transport (lives in the foreground RelayService).
    val scope = rememberCoroutineScope()
    val wifi = remember { RelayHub.getOrCreate(context) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java))
        // If the Wi-Fi Direct permission is already granted, get on the air now —
        // the service may have registered before the grant, so refresh it here.
        if (hasWifiPermission(context)) wifi.register(profile.name)
    }

    val wifiStatus by wifi.status.collectAsState()
    val wifiPeer by wifi.peer.collectAsState()
    val wifiError by wifi.error.collectAsState()
    val wifiPeers by wifi.peers.collectAsState()
    val reachedCount by wifi.connectedCount.collectAsState()
    val incomingAlert by RelayHub.latestAlert.collectAsState()

    // Sheets / overlays.
    var showCreate by remember { mutableStateOf(false) }
    var showNearby by remember { mutableStateOf(false) }
    var showSendingAlert by remember { mutableStateOf(false) }
    var alertSending by remember { mutableStateOf(false) }

    fun endAlert() {
        showSendingAlert = false
        alertSending = false
        scope.launch { wifi.stopAlertHost() }
    }

    fun sendAlert() {
        if (showSendingAlert) return
        showSendingAlert = true
        alertSending = true
        scope.launch {
            val bytes = RelayHub.sosBytes(profile.name)
            wifi.startAlertHost()
            val end = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < end && showSendingAlert) {
                wifi.broadcast(bytes)
                delay(1500)
            }
            alertSending = false
        }
    }

    // Talk state.
    var activeSpeaker by remember { mutableStateOf(profile.name.ifBlank { "You" }) }
    var speaking by remember { mutableStateOf(false) }
    var seconds by remember { mutableIntStateOf(0) }
    // Rolling mic amplitudes (0..1, newest last) that drive the live waveform.
    val levels = remember { mutableStateListOf<Float>() }

    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        micGranted = granted
        if (granted) {
            activeSpeaker = profile.name.ifBlank { "You" }
            speaking = true
        }
    }

    LaunchedEffect(speaking, micGranted) {
        if (speaking && micGranted) {
            seconds = 0
            levels.clear()
            val ticker = launch {
                while (true) {
                    delay(1000)
                    seconds++
                }
            }
            try {
                AudioCapture().stream().collect { frame ->
                    // Split each chunk into short windows and push an RMS level per
                    // window, so the waveform tracks the real voice envelope.
                    val window = 512
                    var i = 0
                    while (i < frame.size) {
                        val end = minOf(i + window, frame.size)
                        var sumSq = 0.0
                        for (j in i until end) {
                            val s = frame[j].toDouble()
                            sumSq += s * s
                        }
                        val n = end - i
                        val rms = if (n > 0) sqrt(sumSq / n) else 0.0
                        // ~6000 RMS reads as a full bar; a gentle curve lifts quiet speech.
                        val level = (rms / 6000.0).coerceIn(0.0, 1.0).pow(0.7).toFloat()
                        levels.add(level)
                        i = end
                    }
                    while (levels.size > 46) levels.removeAt(0)
                }
            } finally {
                ticker.cancel()
                levels.clear()
            }
        }
    }

    val timerText = "%02d:%02d".format(seconds / 60, seconds % 60)

    fun startSpeaking() {
        if (micGranted) {
            activeSpeaker = profile.name.ifBlank { "You" }
            speaking = true
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun stopSpeaking() { speaking = false }

    // Keep the single live peer channel in sync on BOTH phones — whoever initiated.
    // A connection upserts + selects it and opens the device; a drop removes it.
    LaunchedEffect(wifiStatus, wifiPeer) {
        when (wifiStatus) {
            WifiDirectTransport.Status.CONNECTED -> {
                val name = wifiPeer ?: "Peer"
                val tint = ChannelTints[(name.hashCode() and 0x7fffffff) % ChannelTints.size]
                val channel = Squad(PEER_ID, name, tint, "now", ConnStatus.CONNECTED, members = 2)
                val idx = channels.indexOfFirst { it.id == PEER_ID }
                if (idx >= 0) channels[idx] = channel else channels.add(0, channel)
                selectedId = PEER_ID
                showNearby = false
                while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
            }
            WifiDirectTransport.Status.CONNECTING -> { /* leave the UI as-is */ }
            else -> {
                // Link ended (disconnected / peer left / failed) — drop the channel.
                channels.removeAll { it.id == PEER_ID }
                if (selectedId == PEER_ID) selectedId = channels.firstOrNull()?.id
            }
        }
    }

    BackHandler { back() }

    val connected = wifiStatus == WifiDirectTransport.Status.CONNECTED
    val connecting = wifiStatus == WifiDirectTransport.Status.CONNECTING
    val channelName = when {
        connecting -> "Connecting…"
        selected != null -> selected.name
        else -> "No channel"
    }
    val channelSubtitle = when {
        connecting -> "Reaching the other phone…"
        connected && selected != null -> "Connected"
        selected != null -> "${selected.members} members online"
        else -> "Scan nearby to connect"
    }
    val channelNumber = if (selected != null && selectedIndex >= 0) "%02d".format(selectedIndex + 1) else "--"

    when (backStack.last()) {
        Screen.DEVICE -> DeviceScreen(
            channelName = channelName,
            channelSubtitle = channelSubtitle,
            channelNumber = channelNumber,
            online = connected,
            connected = connected,
            speaking = speaking,
            activeSpeaker = activeSpeaker,
            timer = timerText,
            levels = levels,
            speakerOn = speakerOn,
            muted = muted,
            locked = locked,
            onToggleSpeaker = { speakerOn = !speakerOn },
            onToggleMute = { muted = !muted },
            onToggleLock = { locked = !locked },
            onOpenChannels = { go(Screen.CHANNELS) },
            onSos = { sendAlert() },
            onDisconnect = { wifi.disconnect() },
            onHoldStart = { startSpeaking() },
            onHoldEnd = { stopSpeaking() },
        )

        Screen.CHANNELS -> ChannelsScreen(
            channels = channels,
            selectedId = selectedId,
            onSelect = { selectedId = it.id; back() },
            onCreate = { showCreate = true },
            onSettings = { go(Screen.SETTINGS) },
            onScanNearby = { showNearby = true },
            onMenu = { selectedId = it.id },
        )

        Screen.SETTINGS -> ProfileScreen(
            user = profile,
            onBack = { back() },
            onCreate = { showCreate = true },
            onJoin = { showNearby = true },
        )
    }

    if (showNearby) {
        NearbySheet(
            status = wifiStatus,
            peer = wifiPeer,
            error = wifiError,
            peers = wifiPeers,
            onConnect = { p -> wifi.connect(p) },
            onReady = { wifi.register(profile.name) },
            onRetry = { wifi.retry() },
            onDismiss = { showNearby = false },
        )
    }
    if (showSendingAlert) {
        SendingAlertSheet(
            count = reachedCount,
            sending = alertSending,
            onDone = { endAlert() },
            onDismiss = { endAlert() },
        )
    }
    incomingAlert?.let { a ->
        IncomingAlertOverlay(a) { RelayHub.setAlert(null) }
    }
    if (showCreate) {
        CreateSheet(
            onDismiss = { showCreate = false },
            onCreate = { name, tint ->
                val id = "c${System.currentTimeMillis()}"
                channels.add(Squad(id, name, tint, "now", ConnStatus.CONNECTED, members = 1, pinned = true))
                selectedId = id
                showCreate = false
                while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
            },
        )
    }
}
