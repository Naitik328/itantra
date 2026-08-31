package com.sih.itantra.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sih.itantra.model.ChatMessage
import com.sih.itantra.model.ConnectionState
import com.sih.itantra.model.PeerDevice
import com.sih.itantra.model.WifiDirectUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    state: WifiDirectUiState,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    onPeerClick: (PeerDevice) -> Unit,
    onSendMessage: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wi-Fi Direct Connect") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(16.dp),
        ) {
            StatusCard(state)

            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onScan,
                    enabled = state.canScan && !state.isConnected,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(if (state.connectionState == ConnectionState.DISCOVERING) "Scanning…" else "Scan")
                }
                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = state.canDisconnect,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Filled.LinkOff, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Disconnect")
                }
            }

            Spacer(Modifier.height(20.dp))

            if (state.isConnected) {
                ConnectedCard(state)
                Spacer(Modifier.height(12.dp))
                ChatSection(
                    state = state,
                    onSendMessage = onSendMessage,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                PeerList(state, onPeerClick)
            }
        }
    }
}

@Composable
private fun ChatSection(
    state: WifiDirectUiState,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        // Headline metric: the most recent measured round-trip.
        val lastRtt = state.messages.lastOrNull { it.outgoing && it.roundTripMillis != null }?.roundTripMillis
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Messages",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (lastRtt != null) "Last round-trip: $lastRtt ms" else "Round-trip: —",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(8.dp))

        val listState = rememberLazyListState()
        LaunchedEffect(state.messages.size) {
            if (state.messages.isNotEmpty()) {
                listState.animateScrollToItem(state.messages.lastIndex)
            }
        }

        if (state.messages.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = if (state.linkReady) {
                        "Say hello — messages are encoded to binary before sending."
                    } else {
                        "Setting up secure channel…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(message)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        MessageInput(enabled = state.linkReady, onSend = onSendMessage)
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val alignment = if (message.outgoing) Alignment.End else Alignment.Start
    val bubbleColor = if (message.outgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val onBubble = if (message.outgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .align(alignment)
                .widthIn(max = 300.dp)
                .background(bubbleColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(message.text, color = onBubble, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                text = timingLine(message),
                color = onBubble.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** The per-message timing detail: the whole point of the exercise. */
private fun timingLine(message: ChatMessage): String = if (message.outgoing) {
    val rtt = message.roundTripMillis
    val rttText = if (rtt != null) "· ${rtt} ms round-trip" else "· sending…"
    "encoded ${message.codecMicros} µs · ${message.frameBytes} B $rttText"
} else {
    "decoded ${message.codecMicros} µs · ${message.frameBytes} B"
}

@Composable
private fun MessageInput(enabled: Boolean, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onSend(text)
            text = ""
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            enabled = enabled,
            placeholder = { Text(if (enabled) "Type a message" else "Connecting…") },
            maxLines = 3,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )
        FilledIconButton(
            onClick = submit,
            enabled = enabled && text.isNotBlank(),
        ) {
            Icon(Icons.Filled.Send, contentDescription = "Send")
        }
    }
}

@Composable
private fun StatusCard(state: WifiDirectUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIndicator(state)
                Spacer(Modifier.size(10.dp))
                Column {
                    Text(
                        text = statusHeadline(state.connectionState),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.thisDeviceName.isNotBlank()) {
                        Text(
                            text = "This device: ${state.thisDeviceName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (state.statusMessage.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusIndicator(state: WifiDirectUiState) {
    if (state.isBusy) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
    } else {
        Icon(
            imageVector = if (state.isConnected) Icons.Filled.WifiTethering else Icons.Filled.Devices,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun ConnectedCard(state: WifiDirectUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Connected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            InfoRow("Peer", state.connectedPeerName ?: "Unknown")
            InfoRow("Role", if (state.isGroupOwner) "Group Owner" else "Client")
            InfoRow("Group owner IP", state.groupOwnerAddress ?: "—")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun PeerList(state: WifiDirectUiState, onPeerClick: (PeerDevice) -> Unit) {
    if (state.peers.isEmpty()) {
        EmptyState(state)
        return
    }
    Text(
        "Nearby devices",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    val clickable = state.connectionState != ConnectionState.CONNECTING &&
        state.connectionState != ConnectionState.DISCONNECTING
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.peers, key = { it.deviceAddress.ifBlank { it.deviceName } }) { peer ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = clickable) { onPeerClick(peer) },
            ) {
                ListItem(
                    headlineContent = { Text(peer.deviceName) },
                    supportingContent = { Text(peer.statusLabel) },
                    leadingContent = {
                        Icon(Icons.Filled.Devices, contentDescription = null)
                    },
                    trailingContent = { Text("Connect") },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(state: WifiDirectUiState) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Icon(
            Icons.Filled.WifiTethering,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = when (state.connectionState) {
                ConnectionState.DISCOVERING -> "Searching for nearby devices…"
                ConnectionState.WIFI_OFF -> "Turn on Wi-Fi to get started."
                ConnectionState.P2P_UNSUPPORTED -> "Wi-Fi Direct isn't supported on this device."
                else -> "Tap Scan to find nearby devices running this app."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun statusHeadline(state: ConnectionState): String = when (state) {
    ConnectionState.P2P_UNSUPPORTED -> "Unsupported"
    ConnectionState.WIFI_OFF -> "Wi-Fi off"
    ConnectionState.IDLE -> "Ready"
    ConnectionState.DISCOVERING -> "Scanning"
    ConnectionState.PEERS_FOUND -> "Devices found"
    ConnectionState.CONNECTING -> "Connecting"
    ConnectionState.CONNECTED -> "Connected"
    ConnectionState.DISCONNECTING -> "Disconnecting"
}
