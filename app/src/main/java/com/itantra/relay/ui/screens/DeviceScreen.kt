package com.itantra.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.relay.ui.components.ChannelDisplay
import com.itantra.relay.ui.components.ControlButton
import com.itantra.relay.ui.components.PttButton
import com.itantra.relay.ui.components.SosButton
import com.itantra.relay.ui.components.SpeakingDisplay
import com.itantra.relay.ui.components.WtHeader
import com.itantra.relay.ui.theme.LedRed
import com.itantra.relay.ui.theme.bodyBrush

@Composable
fun DeviceScreen(
    channelName: String,
    channelSubtitle: String,
    channelNumber: String,
    online: Boolean,
    connected: Boolean,
    speaking: Boolean,
    activeSpeaker: String,
    timer: String,
    levels: List<Float>,
    speakerOn: Boolean,
    muted: Boolean,
    locked: Boolean,
    onToggleSpeaker: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleLock: () -> Unit,
    onOpenChannels: () -> Unit,
    onSos: () -> Unit,
    onDisconnect: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(bodyBrush())
            .systemBarsPadding()
            .padding(horizontal = 26.dp),
    ) {
        WtHeader(recording = speaking)

        Spacer(Modifier.height(18.dp))

        Box(
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenChannels,
            ),
        ) {
            if (speaking) {
                SpeakingDisplay(speaker = activeSpeaker, timer = timer, levels = levels)
            } else {
                ChannelDisplay(
                    name = channelName,
                    subtitle = channelSubtitle,
                    number = channelNumber,
                    online = online,
                )
            }
        }

        if (connected) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                DisconnectPill(onDisconnect)
            }
        }

        Spacer(Modifier.weight(1f))

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            SosButton(onClick = onSos)
        }

        Spacer(Modifier.weight(1f))

        PttButton(speaking = speaking, onHoldStart = onHoldStart, onHoldEnd = onHoldEnd)

        Spacer(Modifier.height(22.dp))

        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ControlButton(
                icon = if (speakerOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                label = "Speaker",
                active = speakerOn,
                onClick = onToggleSpeaker,
            )
            ControlButton(
                icon = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                label = "Lock",
                active = locked,
                onClick = onToggleLock,
            )
            ControlButton(
                icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                label = "Mute",
                active = muted,
                onClick = onToggleMute,
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DisconnectPill(onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, LedRed.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.LinkOff, null, tint = LedRed, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(6.dp))
        Text("Disconnect", color = LedRed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}
