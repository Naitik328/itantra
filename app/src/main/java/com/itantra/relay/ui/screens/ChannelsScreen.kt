package com.itantra.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.relay.ui.Squad
import com.itantra.relay.ui.components.ChannelRow
import com.itantra.relay.ui.components.RaisedButton
import com.itantra.relay.ui.components.WtHeader
import com.itantra.relay.ui.theme.BodyBottom
import com.itantra.relay.ui.theme.BodyTop
import com.itantra.relay.ui.theme.ChannelTints
import com.itantra.relay.ui.theme.Ink
import com.itantra.relay.ui.theme.InkFaint
import com.itantra.relay.ui.theme.InkSoft
import com.itantra.relay.ui.theme.PanelLo
import com.itantra.relay.ui.theme.bodyBrush

@Composable
fun ChannelsScreen(
    channels: List<Squad>,
    selectedId: String?,
    onSelect: (Squad) -> Unit,
    onCreate: () -> Unit,
    onSettings: () -> Unit,
    onScanNearby: () -> Unit,
    onMenu: (Squad) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(bodyBrush())
            .systemBarsPadding()
            .padding(horizontal = 26.dp),
    ) {
        WtHeader(recording = false, modifier = Modifier.padding(horizontal = 0.dp))

        Spacer(Modifier.height(14.dp))

        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "CHANNELS",
                color = InkSoft,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.weight(1f))
            RoundChip(Icons.Filled.Add, "Create channel", onCreate)
            Spacer(Modifier.size(12.dp))
            RoundChip(Icons.Filled.Settings, "Settings", onSettings)
        }

        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            if (channels.isEmpty()) {
                item {
                    Text(
                        "No channels yet — create one, or scan for phones nearby.",
                        color = InkFaint,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            itemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
                ChannelRow(
                    name = channel.name,
                    members = channel.members,
                    number = "%02d".format(index + 1),
                    tint = ChannelTints[index % ChannelTints.size],
                    selected = channel.id == selectedId,
                    onClick = { onSelect(channel) },
                    onMenu = { onMenu(channel) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        RaisedButton(label = "SCAN NEARBY", onClick = onScanNearby)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RoundChip(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .shadow(4.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(BodyTop, BodyBottom)))
            .border(1.dp, PanelLo.copy(alpha = 0.7f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = Ink, modifier = Modifier.size(22.dp))
    }
}
