package com.vmcsoft.aerodns.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Theme: Dark Mode ONLY. DeepSkyBlack background, MidnightSurface for cards,
// AeroCyan for toggles/active/highlights. Main Connect button uses AeroGradient.
private val DarkColorScheme = darkColorScheme(
    primary = AeroCyan,
    onPrimary = DeepSkyBlack,
    primaryContainer = ElectricBlue,
    onPrimaryContainer = TextWhite,

    secondary = ElectricBlue,
    onSecondary = TextWhite,
    secondaryContainer = MidnightSurface,
    onSecondaryContainer = TextWhite,

    tertiary = AeroCyan,
    onTertiary = DeepSkyBlack,

    error = StatusDisconnected,
    onError = TextWhite,

    background = DeepSkyBlack,
    onBackground = TextWhite,

    surface = MidnightSurface,
    onSurface = TextWhite,
    surfaceVariant = MidnightSurface,
    onSurfaceVariant = TextGray,

    outline = StrokeGray,
    outlineVariant = StrokeGray
)

@Composable
fun AeroDNSTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
