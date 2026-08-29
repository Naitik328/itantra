package com.itantra.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Sensors
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.relay.ui.UserProfile
import com.itantra.relay.ui.components.ProfileAvatar
import com.itantra.relay.ui.components.WtHeader
import com.itantra.relay.ui.theme.BodyBottom
import com.itantra.relay.ui.theme.BodyTop
import com.itantra.relay.ui.theme.BrandMono
import com.itantra.relay.ui.theme.ChannelTints
import com.itantra.relay.ui.theme.Ink
import com.itantra.relay.ui.theme.InkSoft
import com.itantra.relay.ui.theme.PanelLo
import com.itantra.relay.ui.theme.bodyBrush

@Composable
fun ProfileScreen(
    user: UserProfile,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(bodyBrush())
            .systemBarsPadding()
            .padding(horizontal = 26.dp),
    ) {
        WtHeader(recording = false, modifier = Modifier.padding(horizontal = 0.dp))

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .shadow(4.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(BodyTop, BodyBottom)))
                    .border(1.dp, PanelLo.copy(alpha = 0.7f), CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ChevronLeft, "Back", tint = Ink, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("PROFILE", color = InkSoft, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(42.dp))
        }

        Spacer(Modifier.weight(1f))

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            ProfileAvatar(
                name = user.name,
                tint = ChannelTints[user.avatarColorIndex % ChannelTints.size],
                photoPath = user.avatarPhotoPath,
                size = 118.dp,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                user.name,
                fontFamily = BrandMono,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                color = Ink,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(user.gender.label, color = InkSoft, fontSize = 14.sp)
        }

        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ActionTile("Create", Icons.Filled.Add, Modifier.weight(1f), onCreate)
            ActionTile("Scan", Icons.Filled.Sensors, Modifier.weight(1f), onJoin)
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ActionTile(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(58.dp)
            .shadow(5.dp, RoundedCornerShape(16.dp), clip = false)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(BodyTop, BodyBottom)))
            .border(1.dp, PanelLo.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}
