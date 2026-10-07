package dev.netmtr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Spectr {
    val indigo = Color(0xFF4F46E5)
    val indigo500 = Color(0xFF6366F1)
    val indigo100 = Color(0xFFE0E7FF)
    val indigo50 = Color(0xFFEEF2FF)
    val purple = Color(0xFF9333EA)
    val violet = Color(0xFF7C3AED)
    val emerald = Color(0xFF10B981)
    val rose = Color(0xFFE11D48)
    val amber = Color(0xFFF59E0B)
    val blue = Color(0xFF2563EB)
    val slate900 = Color(0xFF0F172A)
    val slate700 = Color(0xFF334155)
    val slate500 = Color(0xFF64748B)
    val slate200 = Color(0xFFE2E8F0)
    val slate100 = Color(0xFFF1F5F9)
    val slate50 = Color(0xFFF8FAFC)
    val white = Color(0xFFFFFFFF)
    val ink = Color(0xFF0F172A)
}

private val LightColors = lightColorScheme(
    primary = Spectr.indigo,
    onPrimary = Spectr.white,
    primaryContainer = Spectr.indigo100,
    onPrimaryContainer = Color(0xFF312E81),
    secondary = Spectr.violet,
    onSecondary = Spectr.white,
    secondaryContainer = Color(0xFFEDE9FE),
    onSecondaryContainer = Color(0xFF4C1D95),
    background = Spectr.slate50,
    onBackground = Spectr.ink,
    surface = Spectr.white,
    onSurface = Spectr.ink,
    surfaceVariant = Spectr.slate100,
    onSurfaceVariant = Spectr.slate500,
    outline = Spectr.slate200,
    error = Spectr.rose,
    errorContainer = Color(0xFFFFE4E6),
    onErrorContainer = Color(0xFF9F1239),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF3730A3),
    onPrimaryContainer = Spectr.indigo100,
    secondary = Color(0xFFC4B5FD),
    onSecondary = Color(0xFF2E1065),
    secondaryContainer = Color(0xFF5B21B6),
    onSecondaryContainer = Color(0xFFEDE9FE),
    background = Color(0xFF111827),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF1F2937),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF374151),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF4B5563),
    error = Color(0xFFFB7185),
    errorContainer = Color(0xFF4C0519),
    onErrorContainer = Color(0xFFFFE4E6),
)

private val SpectrShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val SpectrType = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun NetMtrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = SpectrType,
        shapes = SpectrShapes,
        content = content,
    )
}
