package com.itantra.relay.ui

import androidx.compose.ui.graphics.Color
import com.itantra.relay.ui.theme.AvatarTints

/** A placeholder member for the walkie group. Stands in for a paired peer. */
data class Member(
    val name: String,
    val tint: Color,
) {
    val initials: String
        get() = name.trim().split(" ").take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
}
