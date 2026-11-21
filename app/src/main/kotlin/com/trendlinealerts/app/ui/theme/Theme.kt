package com.trendlinealerts.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val BgDark = Color(0xFF0B0F14)
val SurfaceDark = Color(0xFF131922)
val SurfaceVariantDark = Color(0xFF1B2430)
val OutlineDark = Color(0xFF2B3748)

val TextPrimary = Color(0xFFEAF0F6)
val TextSecondary = Color(0xFF93A1B5)

val AccentBlue = Color(0xFF4DA3FF)
val SignalGreen = Color(0xFF26C281)
val SignalRed = Color(0xFFFF5252)
val SignalYellow = Color(0xFFFFC107)
val SignalGrey = Color(0xFF6B7785)

val AppTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

private val DarkColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = BgDark,
    secondary = SignalGreen,
    onSecondary = BgDark,
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    outline = OutlineDark,
    error = SignalRed,
    onError = TextPrimary
)

@Composable
fun TrendlineAlertsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content
    )
}