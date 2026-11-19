package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val PocketBoyDarkScheme = darkColorScheme(
    primary = ElectricLime,
    onPrimary = ElectricLimeDark,
    primaryContainer = ElectricLimeContainer,
    onPrimaryContainer = ElectricLime,
    secondary = AtomicIndigo,
    onSecondary = TextPrimary,
    secondaryContainer = AtomicIndigoContainer,
    onSecondaryContainer = TextPrimary,
    tertiary = CoralButton,
    onTertiary = TextPrimary,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = ConsoleSurface,
    onSurface = TextPrimary,
    surfaceVariant = ConsoleSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainerHigh = ConsoleSurfaceHigh,
    outline = ConsoleOutline,
    error = CoralButton,
    onError = TextPrimary
)

private val PocketBoyLightScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightSurface,
    primaryContainer = ElectricLimeContainer,
    onPrimaryContainer = ElectricLime,
    secondary = LightSecondary,
    onSecondary = LightSurface,
    tertiary = CoralButton,
    background = LightBg,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary
)

val PocketBoyShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun PocketBoyTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) PocketBoyDarkScheme else PocketBoyLightScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = PocketBoyShapes,
        content = content
    )
}
