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
    primaryContainer = Color(0xFFE8EEFF),
    onPrimaryContainer = Color(0xFF14336F),
    secondary = Color(0xFF4F5F7A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7ECF5),
    onSecondaryContainer = Color(0xFF273349),
    tertiary = Color(0xFF2D6A5F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD7F2EA),
    onTertiaryContainer = Color(0xFF143C35),
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFCFD),
    surfaceContainer = Color(0xFFF2F4F7),
    surfaceContainerHigh = Color(0xFFEAEDF2),
    surfaceContainerHighest = Color(0xFFE2E6EC),
    onSurfaceVariant = Color(0xFF525963),
    outline = Color(0xFF707780),
    outlineVariant = Color(0xFFD6DAE1),
    inverseSurface = Color(0xFF2D3036),
    inverseOnSurface = Color(0xFFF4F5F7),
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueDark,
    onPrimary = Color(0xFF002D6D),
    primaryContainer = Color(0xFF163F86),
    onPrimaryContainer = Color(0xFFDAE2FF),
    secondary = Color(0xFFBCC7DB),
    onSecondary = Color(0xFF263143),
    secondaryContainer = Color(0xFF3C475A),
    onSecondaryContainer = Color(0xFFD8E3F8),
    tertiary = Color(0xFF9DD6C8),
    onTertiary = Color(0xFF06382F),
    tertiaryContainer = Color(0xFF215046),
    onTertiaryContainer = Color(0xFFB9F2E4),
    background = Night,
    onBackground = Color(0xFFE7E9EF),
    surface = NightSurface,
    onSurface = Color(0xFFE7E9EF),
    surfaceContainerLowest = Color(0xFF0B0D10),
    surfaceContainerLow = Color(0xFF15171B),
    surfaceContainer = Color(0xFF1B1D22),
    surfaceContainerHigh = Color(0xFF24272D),
    surfaceContainerHighest = Color(0xFF2E3138),
    onSurfaceVariant = Color(0xFFC2C6CF),
    outline = Color(0xFF8C919B),
    outlineVariant = Color(0xFF41454D),
    inverseSurface = Color(0xFFE7E9EF),
    inverseOnSurface = Color(0xFF2D3036),
    scrim = Color.Black,
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
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
