package com.itantra.relay.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.itantra.relay.transport.WifiDirectTransport
import com.itantra.relay.transport.WifiPeer
import com.itantra.relay.ui.components.AvatarWithStatus
import com.itantra.relay.ui.theme.AccentBlue
import com.itantra.relay.ui.theme.AvatarTints
import com.itantra.relay.ui.theme.CardWhite
import com.itantra.relay.ui.theme.ChipGray
import com.itantra.relay.ui.theme.Hairline
import com.itantra.relay.ui.theme.Ink
import com.itantra.relay.ui.theme.InkFaint
import com.itantra.relay.ui.theme.InkSoft
import com.itantra.relay.ui.theme.PillBlack
import com.itantra.relay.ui.theme.StatusAmber
import com.itantra.relay.ui.theme.StatusGreen

/**
 * Wi-Fi Direct "nearby". Every phone advertises the iTantra service continuously,
 * so peers just appear — no discoverable prompt, no time limit. Tap Connect to
 * form the P2P group (the other phone accepts a one-time system invite).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbySheet(
    status: WifiDirectTransport.Status,
    peer: String?,
    error: String?,
    peers: List<WifiPeer>,
    onConnect: (WifiPeer) -> Unit,
    onReady: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState()

    val api33 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    fun check(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    fun hasPerms() = if (api33) check(Manifest.permission.NEARBY_WIFI_DEVICES)
    else check(Manifest.permission.ACCESS_FINE_LOCATION)

    var granted by remember { mutableStateOf(hasPerms()) }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = hasPerms() }
    fun requestPerms() {
        val list = if (api33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permLauncher.launch(list)
    }

    val wifi = remember { context.applicationContext.getSystemService(WifiManager::class.java) }
    val wifiOn = wifi?.isWifiEnabled == true
    val wifiSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = CardWhite) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Nearby", color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.size(4.dp))
            Text("Phones running iTantra over Wi-Fi Direct", color = InkSoft, fontSize = 13.sp)
            Spacer(Modifier.size(16.dp))

            when {
                !granted -> {
                    Text("Wi-Fi permission is needed to find nearby phones.", color = Ink, fontSize = 14.sp)
                    Spacer(Modifier.size(12.dp))
                    SheetButton("Grant permission") { requestPerms() }
                }

                !wifiOn -> {
                    Text("Wi-Fi is off.", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.size(4.dp))
                    Text("Wi-Fi Direct needs Wi-Fi turned on (you don't need to join a network).", color = InkFaint, fontSize = 13.sp)
                    Spacer(Modifier.size(12.dp))
                    SheetButton("Open Wi-Fi settings") {
                        wifiSettings.launch(Intent(Settings.ACTION_WIFI_SETTINGS))
                    }
                }

                else -> {
                    // Permission + Wi-Fi are both ready — (re)start advertising and
                    // discovery in case registration first ran before the grant.
                    LaunchedEffect(Unit) { onReady() }
                    val statusColor = when (status) {
                        WifiDirectTransport.Status.CONNECTED -> StatusGreen
                        WifiDirectTransport.Status.ERROR -> StatusAmber
                        else -> InkFaint
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
                        Spacer(Modifier.size(8.dp))
                        Text(
                            statusText(status) + (peer?.let { "  ·  $it" } ?: ""),
                            color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                    if (status == WifiDirectTransport.Status.ERROR && error != null) {
                        Spacer(Modifier.size(6.dp))
                        Text(error, color = StatusAmber, fontSize = 12.sp)
                    }
                    Spacer(Modifier.size(16.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Phones nearby", color = InkSoft, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(Modifier.size(10.dp))
                        if (status == WifiDirectTransport.Status.DISCOVERING) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(15.dp), color = AccentBlue)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "Refresh",
                            color = AccentBlue,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            modifier = Modifier.clickable { onRetry() },
                        )
                    }
                    Spacer(Modifier.size(8.dp))

                    if (peers.isEmpty()) {
                        Text(
                            "No iTantra phones found yet. Make sure the other phone has the app open.",
                            color = InkFaint, fontSize = 13.sp,
                        )
                    } else {
                        peers.forEach { p -> PeerRow(p, onConnect) }
                    }
                }
            }
        }
    }
}

private fun statusText(s: WifiDirectTransport.Status) = when (s) {
    WifiDirectTransport.Status.IDLE -> "Starting…"
    WifiDirectTransport.Status.DISCOVERING -> "Ready · discoverable"
    WifiDirectTransport.Status.CONNECTING -> "Connecting…"
    WifiDirectTransport.Status.CONNECTED -> "Connected"
    WifiDirectTransport.Status.ERROR -> "Connection failed"
}

@Composable
private fun PeerRow(peer: WifiPeer, onConnect: (WifiPeer) -> Unit) {
    val tint = AvatarTints[(peer.address.hashCode() and 0x7fffffff) % AvatarTints.size]
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AvatarWithStatus(peer.name.firstOrNull()?.uppercase() ?: "?", tint, StatusAmber, 44.dp)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.name, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("iTantra phone", color = InkFaint, fontSize = 12.sp)
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(PillBlack)
                .clickable { onConnect(peer) }
                .padding(horizontal = 18.dp, vertical = 9.dp),
        ) {
            Text("Connect", color = CardWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SheetButton(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(PillBlack)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = CardWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

/** Create-a-Squad sheet — name + colour, you become the host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateSheet(onDismiss: () -> Unit, onCreate: (String, Color) -> Unit) {
    val sheet = rememberModalBottomSheetState()
    var name by remember { mutableStateOf("") }
    var tintIndex by remember { mutableIntStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = CardWhite) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Create a Channel", color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Spacer(Modifier.size(4.dp))
            Text("You'll be the host — others can join nearby.", color = InkSoft, fontSize = 13.sp)
            Spacer(Modifier.size(18.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(ChipGray)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (name.isEmpty()) Text("Channel name", color = InkFaint, fontSize = 15.sp)
                    BasicTextField(
                        value = name, onValueChange = { name = it }, singleLine = true,
                        textStyle = TextStyle(color = Ink, fontSize = 15.sp),
                        cursorBrush = SolidColor(AccentBlue), modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.size(16.dp))
            Text("Colour", color = InkSoft, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AvatarTints.take(6).forEachIndexed { i, c ->
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(c)
                            .then(if (i == tintIndex) Modifier.border(2.5.dp, Ink, CircleShape) else Modifier)
                            .clickable { tintIndex = i },
                    )
                }
            }

            Spacer(Modifier.size(22.dp))
            val enabled = name.isNotBlank()
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (enabled) PillBlack else ChipGray)
                    .clickable(enabled = enabled) { onCreate(name.trim(), AvatarTints[tintIndex]) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Create Channel",
                    color = if (enabled) CardWhite else InkFaint,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                )
            }
        }
    }
}
