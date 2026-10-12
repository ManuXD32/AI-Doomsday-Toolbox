package com.example.llamadroid.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.example.llamadroid.data.PixelThemeVariant

/**
 * Colour roles for the optional pixel retro theme. Each variant has a light and a dark
 * scheme so System/Light/Dark keeps working. Text roles are kept at WCAG AA (>= 4.5:1);
 * `PixelPalettesTest` guards this.
 */
private class PixelRoles(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val background: Color,
    val onBackground: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val inversePrimary: Color
)

private fun PixelRoles.toColorScheme(dark: Boolean): ColorScheme {
    // Surface containers step from the surface towards surfaceVariant so every level keeps
    // onSurface readable (both ends are checked by the contrast test).
    fun step(amount: Float) = lerp(surface, surfaceVariant, amount)
    return if (dark) {
        darkColorScheme(
            primary = primary, onPrimary = onPrimary,
            primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary, onSecondary = onSecondary,
            secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary, onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
            background = background, onBackground = onBackground,
            surface = surface, onSurface = onSurface,
            surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
            inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
            surfaceDim = background, surfaceBright = step(0.9f),
            surfaceContainerLowest = background, surfaceContainerLow = step(0.2f),
            surfaceContainer = step(0.45f), surfaceContainerHigh = step(0.7f),
            surfaceContainerHighest = surfaceVariant,
            surfaceTint = primary, outline = outline, outlineVariant = outlineVariant
        )
    } else {
        lightColorScheme(
            primary = primary, onPrimary = onPrimary,
            primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary, onSecondary = onSecondary,
            secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary, onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
            background = background, onBackground = onBackground,
            surface = surface, onSurface = onSurface,
            surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
            inverseSurface = inverseSurface, inverseOnSurface = inverseOnSurface,
            surfaceDim = step(0.6f), surfaceBright = surface,
            surfaceContainerLowest = surface, surfaceContainerLow = step(0.2f),
            surfaceContainer = step(0.45f), surfaceContainerHigh = step(0.7f),
            surfaceContainerHighest = surfaceVariant,
            surfaceTint = primary, outline = outline, outlineVariant = outlineVariant
        )
    }
}

private val CandyPopLight = PixelRoles(
    primary = Color(0xFFB8265F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD3E4),
    onPrimaryContainer = Color(0xFF4A0D26),
    secondary = Color(0xFF2E7D65),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDEFE0),
    onSecondaryContainer = Color(0xFF0B3A2C),
    tertiary = Color(0xFF6B52B8),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE2D8FF),
    onTertiaryContainer = Color(0xFF2B1A5E),
    background = Color(0xFFFFF3F8),
    onBackground = Color(0xFF3A2036),
    surface = Color(0xFFFFFBFC),
    onSurface = Color(0xFF3A2036),
    surfaceVariant = Color(0xFFF6E1EB),
    onSurfaceVariant = Color(0xFF5C3F52),
    outline = Color(0xFF8A6A7D),
    outlineVariant = Color(0xFFE8C9D8),
    inverseSurface = Color(0xFF3F2A3A),
    inverseOnSurface = Color(0xFFFFEAF3),
    inversePrimary = Color(0xFFFFB0CC)
).toColorScheme(dark = false)

private val CandyPopDark = PixelRoles(
    primary = Color(0xFFFFA8CB),
    onPrimary = Color(0xFF4A0D26),
    primaryContainer = Color(0xFF7A1C47),
    onPrimaryContainer = Color(0xFFFFD3E4),
    secondary = Color(0xFF8FDCC0),
    onSecondary = Color(0xFF0B3A2C),
    secondaryContainer = Color(0xFF1F5A47),
    onSecondaryContainer = Color(0xFFCDEFE0),
    tertiary = Color(0xFFCBB8FF),
    onTertiary = Color(0xFF2B1A5E),
    tertiaryContainer = Color(0xFF4B3A8C),
    onTertiaryContainer = Color(0xFFE2D8FF),
    background = Color(0xFF24151F),
    onBackground = Color(0xFFF8E5EE),
    surface = Color(0xFF2F1D28),
    onSurface = Color(0xFFF8E5EE),
    surfaceVariant = Color(0xFF45303C),
    onSurfaceVariant = Color(0xFFE3C7D6),
    outline = Color(0xFFA98597),
    outlineVariant = Color(0xFF5C4452),
    inverseSurface = Color(0xFFF8E5EE),
    inverseOnSurface = Color(0xFF3A2036),
    inversePrimary = Color(0xFFB8265F)
).toColorScheme(dark = true)

private val PocketLcdLight = PixelRoles(
    primary = Color(0xFF306230),
    onPrimary = Color(0xFFF4F8E0),
    primaryContainer = Color(0xFFC9DE8A),
    onPrimaryContainer = Color(0xFF0F380F),
    secondary = Color(0xFF0F380F),
    onSecondary = Color(0xFF9BBC0F),
    secondaryContainer = Color(0xFFA9C25A),
    onSecondaryContainer = Color(0xFF0F380F),
    tertiary = Color(0xFF4A6B1C),
    onTertiary = Color(0xFFF4F8E0),
    tertiaryContainer = Color(0xFFB5CE6A),
    onTertiaryContainer = Color(0xFF0F380F),
    background = Color(0xFFD4D4AA),
    onBackground = Color(0xFF1B2A10),
    surface = Color(0xFFE1E6B8),
    onSurface = Color(0xFF0F380F),
    surfaceVariant = Color(0xFFD3DB9C),
    onSurfaceVariant = Color(0xFF2C3E1A),
    outline = Color(0xFF4E6A2E),
    outlineVariant = Color(0xFFA9B878),
    inverseSurface = Color(0xFF0F380F),
    inverseOnSurface = Color(0xFFE1E6B8),
    inversePrimary = Color(0xFF8BAC0F)
).toColorScheme(dark = false)

private val PocketLcdDark = PixelRoles(
    primary = Color(0xFF9BBC0F),
    onPrimary = Color(0xFF0F380F),
    primaryContainer = Color(0xFF306230),
    onPrimaryContainer = Color(0xFFE1F0A8),
    secondary = Color(0xFF8BAC0F),
    onSecondary = Color(0xFF0F380F),
    secondaryContainer = Color(0xFF24491E),
    onSecondaryContainer = Color(0xFFD3E8A0),
    tertiary = Color(0xFFB7D44A),
    onTertiary = Color(0xFF12300F),
    tertiaryContainer = Color(0xFF3B5C1E),
    onTertiaryContainer = Color(0xFFE1F0A8),
    background = Color(0xFF0B1F0B),
    onBackground = Color(0xFFE1F0A8),
    surface = Color(0xFF102A10),
    onSurface = Color(0xFFE1F0A8),
    surfaceVariant = Color(0xFF21401C),
    onSurfaceVariant = Color(0xFFC4DA8C),
    outline = Color(0xFF6E8F3C),
    outlineVariant = Color(0xFF2F4D26),
    inverseSurface = Color(0xFFE1F0A8),
    inverseOnSurface = Color(0xFF0F380F),
    inversePrimary = Color(0xFF306230)
).toColorScheme(dark = true)

private val ArcadeNightLight = PixelRoles(
    primary = Color(0xFF00697A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB8F1FA),
    onPrimaryContainer = Color(0xFF00363F),
    secondary = Color(0xFFA01A82),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD6F3),
    onSecondaryContainer = Color(0xFF4A0039),
    tertiary = Color(0xFF7A5F00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFEE9A),
    onTertiaryContainer = Color(0xFF241A00),
    background = Color(0xFFEEF1FF),
    onBackground = Color(0xFF14183A),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF14183A),
    surfaceVariant = Color(0xFFDDE2F7),
    onSurfaceVariant = Color(0xFF3A4170),
    outline = Color(0xFF5E679C),
    outlineVariant = Color(0xFFB9C0E6),
    inverseSurface = Color(0xFF14183A),
    inverseOnSurface = Color(0xFFF0F2FF),
    inversePrimary = Color(0xFF6BE3F5)
).toColorScheme(dark = false)

private val ArcadeNightDark = PixelRoles(
    primary = Color(0xFF3DEBFF),
    onPrimary = Color(0xFF00363F),
    primaryContainer = Color(0xFF0A4F5C),
    onPrimaryContainer = Color(0xFFB8F8FF),
    secondary = Color(0xFFFF5EDC),
    onSecondary = Color(0xFF4A0039),
    secondaryContainer = Color(0xFF7A1D66),
    onSecondaryContainer = Color(0xFFFFD6F3),
    tertiary = Color(0xFFFFE84A),
    onTertiary = Color(0xFF241A00),
    tertiaryContainer = Color(0xFF5C4B00),
    onTertiaryContainer = Color(0xFFFFF3A8),
    background = Color(0xFF0B0E24),
    onBackground = Color(0xFFE4E8FF),
    surface = Color(0xFF12173A),
    onSurface = Color(0xFFE4E8FF),
    surfaceVariant = Color(0xFF242B5C),
    onSurfaceVariant = Color(0xFFB6BEEA),
    outline = Color(0xFF7C86C2),
    outlineVariant = Color(0xFF363E73),
    inverseSurface = Color(0xFFE4E8FF),
    inverseOnSurface = Color(0xFF14183A),
    inversePrimary = Color(0xFF00697A)
).toColorScheme(dark = true)

private val SunsetCartridgeLight = PixelRoles(
    primary = Color(0xFFB34100),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFD9BF),
    onPrimaryContainer = Color(0xFF4A1C00),
    secondary = Color(0xFF6A3FA0),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7D7FF),
    onSecondaryContainer = Color(0xFF2A0F55),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE3A8),
    onTertiaryContainer = Color(0xFF2E1D00),
    background = Color(0xFFFFF4E0),
    onBackground = Color(0xFF3A2418),
    surface = Color(0xFFFFFAF0),
    onSurface = Color(0xFF3A2418),
    surfaceVariant = Color(0xFFF6E3C8),
    onSurfaceVariant = Color(0xFF5C4128),
    outline = Color(0xFF8C6E50),
    outlineVariant = Color(0xFFE9D2B0),
    inverseSurface = Color(0xFF3A2418),
    inverseOnSurface = Color(0xFFFFF1DC),
    inversePrimary = Color(0xFFFFB68A)
).toColorScheme(dark = false)

private val SunsetCartridgeDark = PixelRoles(
    primary = Color(0xFFFFA064),
    onPrimary = Color(0xFF4A1C00),
    primaryContainer = Color(0xFF8A3000),
    onPrimaryContainer = Color(0xFFFFD9BF),
    secondary = Color(0xFFD2B5FF),
    onSecondary = Color(0xFF2A0F55),
    secondaryContainer = Color(0xFF54307F),
    onSecondaryContainer = Color(0xFFE7D7FF),
    tertiary = Color(0xFFFFC95A),
    onTertiary = Color(0xFF2E1D00),
    tertiaryContainer = Color(0xFF6B4800),
    onTertiaryContainer = Color(0xFFFFE3A8),
    background = Color(0xFF1F1220),
    onBackground = Color(0xFFF9E8D2),
    surface = Color(0xFF2A1830),
    onSurface = Color(0xFFF9E8D2),
    surfaceVariant = Color(0xFF43294A),
    onSurfaceVariant = Color(0xFFE6CFE6),
    outline = Color(0xFFA98AA8),
    outlineVariant = Color(0xFF573B5C),
    inverseSurface = Color(0xFFF9E8D2),
    inverseOnSurface = Color(0xFF3A2418),
    inversePrimary = Color(0xFFB34100)
).toColorScheme(dark = true)

/** Material colour scheme for a pixel [variant] in light or dark mode. */
fun pixelColorScheme(variant: PixelThemeVariant, dark: Boolean): ColorScheme = when (variant) {
    PixelThemeVariant.CANDY_POP -> if (dark) CandyPopDark else CandyPopLight
    PixelThemeVariant.POCKET_LCD -> if (dark) PocketLcdDark else PocketLcdLight
    PixelThemeVariant.ARCADE_NIGHT -> if (dark) ArcadeNightDark else ArcadeNightLight
    PixelThemeVariant.SUNSET_CARTRIDGE -> if (dark) SunsetCartridgeDark else SunsetCartridgeLight
}

/**
 * Colours the future pixel sprites are painted with. Sprites reference these slots rather
 * than raw colours so each variant (and light/dark) recolours them automatically.
 */
data class PixelSpritePalette(
    val outline: Color,
    val base: Color,
    val shade: Color,
    val highlight: Color,
    val accent: Color,
    val face: Color
)

fun pixelSpritePalette(variant: PixelThemeVariant, dark: Boolean): PixelSpritePalette = when (variant) {
    PixelThemeVariant.CANDY_POP -> if (dark) {
        PixelSpritePalette(Color(0xFF2B1A3A), Color(0xFFFFA8CB), Color(0xFFD9769F), Color(0xFFFFE3EF), Color(0xFF8FDCC0), Color(0xFF2B1A3A))
    } else {
        PixelSpritePalette(Color(0xFF4A1D3F), Color(0xFFFF8FB8), Color(0xFFD15C8A), Color(0xFFFFD6E6), Color(0xFF63C7A5), Color(0xFF4A1D3F))
    }
    PixelThemeVariant.POCKET_LCD -> if (dark) {
        PixelSpritePalette(Color(0xFF0F380F), Color(0xFF8BAC0F), Color(0xFF306230), Color(0xFF9BBC0F), Color(0xFFC4DA8C), Color(0xFF0F380F))
    } else {
        PixelSpritePalette(Color(0xFF0F380F), Color(0xFF8BAC0F), Color(0xFF306230), Color(0xFF9BBC0F), Color(0xFF0F380F), Color(0xFF0F380F))
    }
    PixelThemeVariant.ARCADE_NIGHT -> if (dark) {
        PixelSpritePalette(Color(0xFF05071A), Color(0xFF3DEBFF), Color(0xFF0A8FA3), Color(0xFFB8F8FF), Color(0xFFFF5EDC), Color(0xFFFFE84A))
    } else {
        PixelSpritePalette(Color(0xFF14183A), Color(0xFF1FC8E0), Color(0xFF00697A), Color(0xFFB8F1FA), Color(0xFFD03AB0), Color(0xFFE0B800))
    }
    PixelThemeVariant.SUNSET_CARTRIDGE -> if (dark) {
        PixelSpritePalette(Color(0xFF1F1220), Color(0xFFFFA064), Color(0xFFC96A2E), Color(0xFFFFD9BF), Color(0xFFD2B5FF), Color(0xFF1F1220))
    } else {
        PixelSpritePalette(Color(0xFF3A2418), Color(0xFFF08A3E), Color(0xFFB34100), Color(0xFFFFD9BF), Color(0xFF8E5CCB), Color(0xFF3A2418))
    }
}
