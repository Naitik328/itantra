package com.itantra.relay.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * WT-01 — a skeuomorphic "hardware walkie-talkie" palette.
 *
 * The device is a warm bone-plastic body with dark inset displays, a metallic
 * rotary knob, and a single molten-orange accent for anything live.
 */

// ---- Body (the plastic shell) ----
val BodyTop = Color(0xFFE7E3DB)
val BodyMid = Color(0xFFD9D4CB)
val BodyBottom = Color(0xFFC6C0B5)

// Emboss highlight / shadow used to fake raised & recessed plastic.
val PanelHi = Color(0xFFF4F1EB)
val PanelLo = Color(0xFFAFA99D)
val PanelEdge = Color(0xFF8F897D)

// ---- Displays (the dark inset screens) ----
val DisplayTop = Color(0xFF262320)
val DisplayBottom = Color(0xFF141210)
val DisplayText = Color(0xFFEDE7DB)
val DisplayDim = Color(0xFF938D82)
val WaveGreen = Color(0xFF97A64A)

// ---- The one accent ----
val Accent = Color(0xFFE0611C)
val AccentBright = Color(0xFFF4762A)
val AccentGlow = Color(0x66F4762A)

// ---- Metallic knob ----
val KnobHi = Color(0xFFF1EDE5)
val KnobMid = Color(0xFFD3CEC4)
val KnobLo = Color(0xFF9C968A)
val KnobEdge = Color(0xFF837D71)

// ---- Status LEDs ----
val LedGreen = Color(0xFF74D15C)
val LedGreenGlow = Color(0x8874D15C)
val LedRed = Color(0xFFF0483A)
val LedRedGlow = Color(0x88F0483A)

// ---- Ink on the plastic body ----
val Ink = Color(0xFF3A362F)
val InkSoft = Color(0xFF837D71)
val InkFaint = Color(0xFFA39D91)

// Channel rows (dark chips on the body).
val ChannelBg = Color(0xFF201E1B)
val ChannelBgActive = Color(0xFF2A2622)
val ChannelText = Color(0xFFECE7DD)
val ChannelDim = Color(0xFF8C877D)

// A spread of channel-icon tints matching the mockup.
val ChannelTints = listOf(
    Color(0xFFE0611C), // orange
    Color(0xFFA07BE0), // purple
    Color(0xFF4C86E0), // blue
    Color(0xFFE0B44C), // yellow
    Color(0xFF3FB6A8), // teal
    Color(0xFFE0567C), // rose
)

// ---- Back-compat aliases (kept so unrelated code keeps compiling) ----
val CardWhite = Color(0xFFF4F1EB)
val ChipGray = Color(0xFFCFC9BF)
val Hairline = Color(0xFFBBB5A9)
val PillBlack = Color(0xFF201E1B)
val AccentBlue = Accent
val RecordingRed = LedRed
val StatusGreen = LedGreen
val StatusAmber = Color(0xFFE0B44C)
val StatusGray = InkFaint
val AvatarOrange = Accent
val AvatarTints = ChannelTints
