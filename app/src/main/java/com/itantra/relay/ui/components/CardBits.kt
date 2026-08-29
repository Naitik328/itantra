package com.itantra.relay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.relay.ui.ConnStatus
import com.itantra.relay.ui.theme.AccentBlue
import com.itantra.relay.ui.theme.CardWhite

/** Tinted-initial avatar with a small connection-status dot in the corner. */
@Composable
fun AvatarWithStatus(initials: String, tint: Color, statusColor: Color, size: Dp) {
    Box(Modifier.size(size)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(tint),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials, color = CardWhite, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.34f).sp)
        }
        Box(
            Modifier.align(Alignment.BottomEnd).size(15.dp).clip(CircleShape).background(CardWhite),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(statusColor))
        }
    }
}

@Composable
fun StatusPill(status: ConnStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(status.color))
        Spacer(Modifier.size(5.dp))
        Text(status.label, color = status.color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun UnreadBadge(count: Int) {
    Box(
        Modifier.size(20.dp).clip(CircleShape).background(AccentBlue),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 9) "9+" else "$count", color = CardWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
