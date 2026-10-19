package com.offlinewallet.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF101311)
val Raised = Color(0xFF1B201C)
val Line = Color(0xFF313832)
val Ivory = Color(0xFFF3F0E8)
val Dim = Color(0xFFC4BEB4)
val Muted = Color(0xFF8E8A81)
val Copper = Color(0xFFE0B08A)
val Steel = Color(0xFFA9C0DC)
val Danger = Color(0xFFE36A5D)
val Ok = Color(0xFF8FCBB0)

private val Scheme = darkColorScheme(
    primary = Ivory,
    onPrimary = Ink,
    secondary = Copper,
    background = Ink,
    onBackground = Ivory,
    surface = Raised,
    onSurface = Ivory,
    error = Danger,
    onError = Ink,
    outline = Line,
)

private val Type = Typography(
    headlineLarge = TextStyle(fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Light),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.8.sp),
)

val Mono = FontFamily.Monospace

@Composable
fun WalletTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
