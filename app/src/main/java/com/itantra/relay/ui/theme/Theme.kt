package com.itantra.relay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

private val AppColors = darkColorScheme(
    primary = Accent,
    background = BodyMid,
    surface = BodyMid,
    onPrimary = DisplayText,
    onBackground = Ink,
    onSurface = Ink,
)

@Composable
fun ItantraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = AppTypography,
        content = content,
    )
}

/** The plastic-shell wash — a soft top-lit gradient down the device body. */
fun bodyBrush() = Brush.linearGradient(
    colorStops = arrayOf(
        0.0f to BodyTop,
        0.5f to BodyMid,
        1.0f to BodyBottom,
    ),
    start = Offset(0f, 0f),
    end = Offset(0f, Float.POSITIVE_INFINITY),
)

/** The dark inset-display gradient. */
fun displayBrush() = Brush.verticalGradient(listOf(DisplayTop, DisplayBottom))
