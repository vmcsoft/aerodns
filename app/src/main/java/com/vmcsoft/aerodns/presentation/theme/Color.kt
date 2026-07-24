package com.vmcsoft.aerodns.presentation.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// 1. NEON BRANDING
val AeroCyan = Color(0xFF00E5FF)       // Primary Action / Glow effects
val ElectricBlue = Color(0xFF2979FF)  // Secondary / Gradients
val AeroGradient = Brush.horizontalGradient(
    colors = listOf(AeroCyan, ElectricBlue)
)

// 2. DARK BACKGROUNDS
val DeepSkyBlack = Color(0xFF0A0E14)   // Main Background
val MidnightSurface = Color(0xFF161B22) // Cards & Containers
val StrokeGray = Color(0xFF2D333B)     // Borders

// 3. TEXT HIERARCHY
val TextWhite = Color(0xFFFFFFFF)
val TextGray = Color(0xFFB0BEC5)

// 4. STATUS INDICATORS
val StatusConnected = Color(0xFF00C853)    // Green (status text)
val StatusDisconnected = Color(0xFFFF1744) // Red
// Active/Connected button: cyan-teal "lit up" (fits neon branding better than green)
val ActiveButtonCyan = Color(0xFF00E5CC)

// Aliases for compatibility (theme uses these names)
val TrueBlack = DeepSkyBlack
val DarkBackground = DeepSkyBlack
val DarkSurface = MidnightSurface
val DarkSurfaceVariant = MidnightSurface
val TextPrimary = TextWhite
val TextSecondary = TextGray
val AeroGreen = StatusConnected
val AeroRed = StatusDisconnected
val AeroCyanDark = ElectricBlue
val AeroOrange = Color(0xFFFF9100)
val TextTertiary = TextGray.copy(alpha = 0.7f)
