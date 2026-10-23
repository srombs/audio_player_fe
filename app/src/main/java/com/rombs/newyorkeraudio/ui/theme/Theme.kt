package com.rombs.newyorkeraudio.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

private val LightColors = lightColorScheme(
    primary = Color(0xFF253B35),
    background = Color(0xFFFAF8F3),
    surface = Color(0xFFFAF8F3),
    surfaceContainerHighest = Color(0xFFE8E2D5),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFBCD1C4),
    background = Color(0xFF181C19),
    surface = Color(0xFF181C19),
    surfaceContainerHighest = Color(0xFF303731),
)
private val DefaultTypography = Typography()
private val AppTypography = DefaultTypography.copy(
    headlineLarge = DefaultTypography.headlineLarge.copy(fontFamily = FontFamily.Serif),
    headlineMedium = DefaultTypography.headlineMedium.copy(fontFamily = FontFamily.Serif),
    titleLarge = DefaultTypography.titleLarge.copy(fontFamily = FontFamily.Serif),
    titleMedium = DefaultTypography.titleMedium.copy(fontFamily = FontFamily.Serif),
)

@Composable
fun NewYorkerAudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
