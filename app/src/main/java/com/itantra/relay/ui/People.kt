package com.itantra.relay.ui

import androidx.compose.ui.graphics.Color
import com.itantra.relay.ui.theme.AvatarTints

/** A single 1-to-1 contact — a person, not a group. */
data class Person(
    val id: String,
    val name: String,
    val tint: Color,
    val lastActive: String,
    val status: ConnStatus,
    val unread: Int = 0,
) {
    val initials: String
        get() = name.trim().split(" ").take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
}
