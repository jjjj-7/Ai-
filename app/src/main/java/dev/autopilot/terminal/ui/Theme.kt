package dev.autopilot.terminal.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val TerminalBlack = Color(0xFF0C0C14)
val TerminalSurface = Color(0xFF14141F)
val AccentGreen = Color(0xFF4ADE80)
val AccentPurple = Color(0xFFA78BFA)
val AccentAmber = Color(0xFFFBBF24)

val WinBg = Color(0xFF030604)
val WinSurface = Color(0xFF090F0B)
val WinBorder = Color(0xFF16241B)
val TextMain = Color(0xFFD6E4D6)
val TextDim = Color(0xFF71826F)
val DotR = Color(0xFFFF5F57)
val DotY = Color(0xFFFEBC2E)
val DotG = Color(0xFF28C840)
val Cyan = Color(0xFF22D3EE)

private val DarkScheme = darkColorScheme(
    primary = AccentGreen,
    onPrimary = Color.Black,
    secondary = AccentPurple,
    background = TerminalBlack,
    surface = TerminalSurface,
    onBackground = Color(0xFFE5E5E5),
    onSurface = Color(0xFFE5E5E5)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF166534),
    secondary = Color(0xFF6D28D9)
)

@Composable
fun AutopilotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
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
