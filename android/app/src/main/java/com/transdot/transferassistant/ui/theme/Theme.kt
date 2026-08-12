package com.transdot.transferassistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemeMode {
    System,
    Light,
    Dark,
}

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF0FF),
    onPrimaryContainer = Color(0xFF123278),
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceContainerLow = Color.White,
    outline = Color(0xFF747A84),
    outlineVariant = Color(0xFFDFE3E8),
    tertiary = Color(0xFF5C5F78),
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF082A69),
    primaryContainer = Color(0xFF203B76),
    onPrimaryContainer = Color(0xFFD9E3FF),
    background = Night,
    onBackground = Color(0xFFF1F3F5),
    surface = NightSurface,
    onSurface = Color(0xFFF1F3F5),
    surfaceContainerLow = NightSurface,
    outline = Color(0xFF8E949E),
    outlineVariant = Color(0xFF2C3138),
    tertiary = Color(0xFFC2C5DF),
)

@Composable
fun TransferAssistantTheme(
    mode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val useDarkColors = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }

    MaterialTheme(
        colorScheme = if (useDarkColors) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
