package dev.autopilot.terminal.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val TerminalBlack = Color(0xFFF4F5F8)
val TerminalSurface = Color(0xFFFFFFFF)
val AccentGreen = Color(0xFF16A34A)
val AccentPurple = Color(0xFF7C3AED)
val AccentAmber = Color(0xFFB45309)

val WinBg = Color(0xFFF4F5F8)
val WinSurface = Color(0xFFFFFFFF)
val WinBorder = Color(0xFFE2E5EC)
val TextMain = Color(0xFF1A1D23)
val TextDim = Color(0xFF6B7280)
val DotR = Color(0xFFFF5F57)
val DotY = Color(0xFFFEBC2E)
val DotG = Color(0xFF28C840)
val Cyan = Color(0xFF0891B2)

private val LightScheme = lightColorScheme(
    primary = AccentGreen,
    onPrimary = Color.White,
    secondary = AccentPurple,
    background = WinBg,
    surface = WinSurface,
    surfaceVariant = Color(0xFFEAECF1),
    onSurfaceVariant = TextDim,
    onBackground = TextMain,
    onSurface = TextMain,
    outline = WinBorder
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF4ADE80),
    onPrimary = Color.Black,
    secondary = Color(0xFFA78BFA),
    background = Color(0xFF0C0C14),
    surface = Color(0xFF14141F),
    surfaceVariant = Color(0xFF1E1E2A),
    onSurfaceVariant = Color(0xFF9AA0B4),
    onBackground = Color(0xFFE5E5E5),
    onSurface = Color(0xFFE5E5E5),
    outline = Color(0xFF2A2A3A)
)

@Composable
fun AutopilotTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content
    )
}

fun ansiColor(index: Int?): Color = when (index) {
    null -> Color(0xFFE5E5E5)
    in 0..7 -> when (index) {
        0 -> Color(0xFF3F3F46); 1 -> Color(0xFFEF4444); 2 -> AccentGreen; 3 -> AccentAmber
        4 -> Color(0xFF60A5FA); 5 -> AccentPurple; 6 -> Color(0xFF22D3EE); else -> Color.White
    }
    in 8..15 -> ansiColor((index!! - 8))
    else -> {
        val v = index!!
        if (v >= 0x1000000) Color(v and 0xFFFFFF)
        else palette256(v)
    }
}

private fun palette256(v: Int): Color {
    return if (v < 16) ansiColor(v)
    else {
        val base = (v - 16).coerceIn(0, 215)
        val r = base / 36; val g = (base % 36) / 6; val b = base % 6
        val scale = { c: Int -> if (c == 0) 0 else 55 + c * 40 }
        Color(scale(r), scale(g), scale(b))
    }
}
