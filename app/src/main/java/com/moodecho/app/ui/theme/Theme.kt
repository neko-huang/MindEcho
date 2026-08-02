package com.moodecho.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dark color scheme for MindEcho.
 * Uses deep violet tones for a calm, focused feel.
 */
private val DarkColorScheme = darkColorScheme(
    primary = MindEchoPrimary,
    onPrimary = MindEchoOnPrimary,
    primaryContainer = MindEchoTertiary,
    onPrimaryContainer = MindEchoOnPrimaryContainer,
    secondary = MindEchoSecondary,
    onSecondary = MindEchoOnSecondary,
    secondaryContainer = MindEchoSecondaryContainer,
    onSecondaryContainer = MindEchoOnSecondaryContainer,
    tertiary = MindEchoTertiary,
    onTertiary = MindEchoOnTertiary,
    tertiaryContainer = MindEchoTertiaryContainer,
    onTertiaryContainer = MindEchoOnTertiaryContainer,
    background = MindEchoBackground,
    onBackground = MindEchoOnBackground,
    surface = MindEchoSurface,
    onSurface = MindEchoOnSurface,
    surfaceVariant = MindEchoSurfaceVariant,
    onSurfaceVariant = MindEchoOnSurfaceVariant
)

/**
 * Light color scheme for MindEcho.
 */
private val LightColorScheme = lightColorScheme(
    primary = MindEchoPrimary,
    onPrimary = MindEchoOnPrimary,
    primaryContainer = MindEchoPrimaryContainer,
    onPrimaryContainer = MindEchoOnPrimaryContainer,
    secondary = MindEchoSecondary,
    onSecondary = MindEchoOnSecondary,
    secondaryContainer = MindEchoSecondaryContainer,
    onSecondaryContainer = MindEchoOnSecondaryContainer,
    tertiary = PurpleGrey40,
    onTertiary = Color.White,
    tertiaryContainer = PurpleGrey80,
    onTertiaryContainer = PurpleGrey40,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant
)

/**
 * MindEcho theme composable.
 * Wraps the app UI with the appropriate color scheme and typography.
 */
@Composable
fun MindEchoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MindEchoTypography,
        content = content
    )
}
