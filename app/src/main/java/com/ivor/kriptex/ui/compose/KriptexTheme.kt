package com.ivor.kriptex.ui.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TerminalColors = darkColorScheme(
    // Creepy dark red terminal vibe
    primary = Color(0xFF6B0F1A),
    onPrimary = Color(0xFFFFEDEE),
    secondary = Color(0xFF6B0F1A),
    onSecondary = Color(0xFFFFEDEE),
    background = Color(0xFF050507),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF0A0507),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF14070A),
    onSurfaceVariant = Color(0xFFBEBEC4),
    // Dark-ish purple for secondary status values (e.g., TBA, not-ready).
    tertiary = Color(0xFF7E57C2),
    onTertiary = Color(0xFFFFEDEE),
    // Keep all "red" accents consistent with the app's primary red.
    error = Color(0xFF6B0F1A),
    onError = Color(0xFFFFEDEE),
    // Make text-field frames clearly visible.
    outline = Color(0xFF5A1A22),
)

private val TerminalTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 16.sp,
        letterSpacing = 0.2.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        letterSpacing = 0.2.sp,
        lineHeight = 20.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 18.sp,
        letterSpacing = 0.2.sp,
        lineHeight = 22.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        letterSpacing = 0.6.sp,
    ),
)

private val TerminalShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(0.dp),
)

@Composable
fun KriptexTheme(
    content: @Composable () -> Unit,
) {
    // Explicitly dark, no DayNight switching.
    // (We still keep this guard to avoid surprises if the system forces light.)
    val systemIsDark = isSystemInDarkTheme()
    @Suppress("UNUSED_VARIABLE")
    val ignore = systemIsDark

    MaterialTheme(
        colorScheme = TerminalColors,
        typography = TerminalTypography,
        shapes = TerminalShapes,
        content = content,
    )
}
