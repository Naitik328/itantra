package com.itantra.relay.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.relay.ui.Member
import com.itantra.relay.ui.theme.CardWhite
import com.itantra.relay.ui.theme.Hairline
import com.itantra.relay.ui.theme.Ink
import com.itantra.relay.ui.theme.InkSoft

/** A bordered white circle holding an icon — the back / settings / send / menu buttons. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    background: Color = CardWhite,
    tint: Color = Ink,
    border: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .then(if (border) Modifier.border(1.dp, Hairline, CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(size * 0.42f))
    }
}

/** A round member avatar — tinted circle with initials (photo stand-in). */
@Composable
fun Avatar(
    member: Member,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
    ring: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(member.tint)
            .then(if (ring) Modifier.border(2.dp, CardWhite, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            member.initials,
            color = CardWhite,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.34f).sp,
        )
    }
}

/** Overlapping avatar stack ending in a "+N" chip and a chevron. */
@Composable
fun StackedAvatars(
    members: List<Member>,
    extra: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        members.take(3).forEachIndexed { i, m ->
            Avatar(m, size = 30.dp, ring = true, modifier = Modifier.offset(x = (-8 * i).dp))
        }
        Box(
            Modifier
                .offset(x = (-8 * 3).dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(Ink),
            contentAlignment = Alignment.Center,
        ) {
            Text("+$extra", color = CardWhite, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = "See members",
            tint = InkSoft,
            modifier = Modifier.offset(x = (-16).dp),
        )
    }
}

/** Animated three-bar "speaking" equalizer. */
@Composable
fun SpeakingBars(
    color: Color,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "eq")
    val heights = (0..2).map { i ->
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(420 + i * 90),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "bar$i",
        )
    }
    Row(
        modifier = modifier.height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        heights.forEach { h ->
            val frac = if (active) h.value else 0.35f
            Box(
                Modifier
                    .width(4.dp)
                    .height((16 * frac).dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
