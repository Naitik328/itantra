package com.itantra.relay.ui

import androidx.compose.ui.graphics.Color
import com.itantra.relay.ui.theme.AvatarTints
import com.itantra.relay.ui.theme.StatusAmber
import com.itantra.relay.ui.theme.StatusGray
import com.itantra.relay.ui.theme.StatusGreen

enum class ConnStatus(val label: String, val color: Color) {
    CONNECTED("Connected", StatusGreen),
    NEARBY("Nearby", StatusAmber),
    OFFLINE("Offline", StatusGray),
}

data class Squad(
    val id: String,
    val name: String,
    val tint: Color,
    val lastActive: String,
    val status: ConnStatus,
    val unread: Int = 0,
    val members: Int = 0,
    val pinned: Boolean = false,
) {
    val initials: String
        get() = name.trim().split(" ").take(2)
            .mapNotNull { it.firstOrNull()?.uppercaseChar() }
            .joinToString("")
}
