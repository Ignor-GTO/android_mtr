package dev.netmtr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF0F6E62)
private val TealBright = Color(0xFF8EE0D0)
private val Ink = Color(0xFF14211E)
private val Paper = Color(0xFFF3F7F5)
private val Mist = Color(0xFFE3EEEA)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBE4),
    onPrimaryContainer = Color(0xFF06332C),
    secondary = Color(0xFF3E5C55),
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Mist,
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = TealBright,
    onPrimary = Color(0xFF06332C),
    primaryContainer = Color(0xFF154F46),
    onPrimaryContainer = Color(0xFFD7FFF4),
    secondary = Color(0xFFB7CDC6),
    background = Color(0xFF101614),
    onBackground = Color(0xFFE6F2EE),
    surface = Color(0xFF18211E),
    onSurface = Color(0xFFE6F2EE),
    surfaceVariant = Color(0xFF24312D),
    error = Color(0xFFFFB4AB),
)

@Composable
fun NetMtrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
