package com.wristtube.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/** A warm coral on black: easy to read on an OLED in the dark, and kind to the battery. */
object Palette {
    val accent = Color(0xFFFF6B57)
    val accentDim = Color(0xFF4A1D17)
    val onAccent = Color(0xFF1B0A07)
    val text = Color(0xFFF2EEEC)
    val muted = Color(0xFFB4ACA8)
    val faint = Color(0xFF7C7471)
    val card = Color(0xFF221D1C)
    val live = Color(0xFFE5322D)
}

private val scheme = ColorScheme(
    primary = Palette.accent,
    onPrimary = Palette.onAccent,
    primaryContainer = Palette.accentDim,
    onPrimaryContainer = Color(0xFFFFDAD3),
    secondary = Color(0xFFE7BDB5),
    onSecondary = Color(0xFF442A25),
    secondaryContainer = Color(0xFF3A2C29),
    onSecondaryContainer = Color(0xFFFFDAD3),
    surfaceContainerLow = Color(0xFF181413),
    surfaceContainer = Palette.card,
    surfaceContainerHigh = Color(0xFF2E2826),
    onSurface = Palette.text,
    onSurfaceVariant = Palette.muted,
    outline = Color(0xFF8D7F7B),
    outlineVariant = Color(0xFF524440),
    background = Color.Black,
    onBackground = Palette.text,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun WristTubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
