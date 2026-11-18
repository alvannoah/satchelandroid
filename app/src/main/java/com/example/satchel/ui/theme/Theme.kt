package com.example.satchel.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

private val LightColorScheme = lightColorScheme(
    primary = Light.accent,
    onPrimary = Light.onAccent,
    primaryContainer = Light.accentSoft,
    onPrimaryContainer = Light.onNavActive,
    secondaryContainer = Light.navActive,
    onSecondaryContainer = Light.onNavActive,
    background = Light.background,
    onBackground = Light.text,
    surface = Light.surface,
    onSurface = Light.text,
    surfaceVariant = Light.surface2,
    onSurfaceVariant = Light.muted,
    surfaceContainerLowest = Light.surface,
    surfaceContainerLow = Light.surface2,
    surfaceContainer = Light.surface2,
    surfaceContainerHigh = Light.surface3,
    surfaceContainerHighest = Light.surface3,
    outline = Light.muted,
    outlineVariant = Light.border,
    error = Light.danger,
    errorContainer = Light.dangerBg,
)

private val DarkColorScheme = darkColorScheme(
    primary = Dark.accent,
    onPrimary = Dark.onAccent,
    primaryContainer = Dark.accentSoft,
    onPrimaryContainer = Dark.onNavActive,
    secondaryContainer = Dark.navActive,
    onSecondaryContainer = Dark.onNavActive,
    background = Dark.background,
    onBackground = Dark.text,
    surface = Dark.background,
    onSurface = Dark.text,
    surfaceVariant = Dark.surface2,
    onSurfaceVariant = Dark.muted,
    surfaceContainerLowest = Dark.background,
    surfaceContainerLow = Dark.surface,
    surfaceContainer = Dark.surface,
    surfaceContainerHigh = Dark.surface2,
    surfaceContainerHighest = Dark.surface3,
    outline = Dark.muted,
    outlineVariant = Dark.border,
    error = Dark.danger,
    errorContainer = Dark.dangerBg,
)

val LocalSatchelColors = staticCompositionLocalOf { LightExtra }

/** The extra colours: `Satchel.colors.official`, and so on. */
object Satchel {
    val colors: SatchelColors
        @Composable get() = LocalSatchelColors.current
}

@Composable
fun SatchelTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSatchelColors provides if (darkTheme) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
            content = content,
        )
    }
}
