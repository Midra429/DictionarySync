package me.midra.dictionarysync.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Fallback for devices without dynamic color (API < 31). M3 Theme Builder-style palette from the launcher icon:
// blue primary keeping the icon's chroma (seed #005ECE), yellow tertiary (sync badge, hue of #FFCF28), M3 chromas elsewhere.
internal val LightColors = lightColorScheme(
    primary = Color(0xFF005AC5), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD8E2FF), onPrimaryContainer = Color(0xFF004397),
    secondary = Color(0xFF575E71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDBE2F9), onSecondaryContainer = Color(0xFF3F4759),
    tertiary = Color(0xFF6D5D2E), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF7E1A6), onTertiaryContainer = Color(0xFF544519),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFFAF8FD), onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFAF8FD), onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE1E2EC), onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF757780), outlineVariant = Color(0xFFC5C6D0),
    inverseSurface = Color(0xFF303034), inverseOnSurface = Color(0xFFF2F0F4), inversePrimary = Color(0xFFAEC6FF),
    surfaceDim = Color(0xFFDBD9DD), surfaceBright = Color(0xFFFAF8FD),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF5F3F7),
    surfaceContainer = Color(0xFFEFEDF1), surfaceContainerHigh = Color(0xFFE9E7EC),
    surfaceContainerHighest = Color(0xFFE3E2E6),
)

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFAEC6FF), onPrimary = Color(0xFF002E6B),
    primaryContainer = Color(0xFF004397), onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFBFC6DC), onSecondary = Color(0xFF293041),
    secondaryContainer = Color(0xFF3F4759), onSecondaryContainer = Color(0xFFDBE2F9),
    tertiary = Color(0xFFDAC58C), onTertiary = Color(0xFF3C2F04),
    tertiaryContainer = Color(0xFF544519), onTertiaryContainer = Color(0xFFF7E1A6),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121316), onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF121316), onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF44474F), onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8E9099), outlineVariant = Color(0xFF44474F),
    inverseSurface = Color(0xFFE3E2E6), inverseOnSurface = Color(0xFF303034), inversePrimary = Color(0xFF005AC5),
    surfaceDim = Color(0xFF121316), surfaceBright = Color(0xFF38393C),
    surfaceContainerLowest = Color(0xFF0D0E11), surfaceContainerLow = Color(0xFF1B1B1F),
    surfaceContainer = Color(0xFF1F1F23), surfaceContainerHigh = Color(0xFF292A2D),
    surfaceContainerHighest = Color(0xFF343538),
)
