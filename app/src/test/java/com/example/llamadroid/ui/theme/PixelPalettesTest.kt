package com.example.llamadroid.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.llamadroid.data.PixelThemeVariant
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelPalettesTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun textPairs(s: ColorScheme) = listOf(
        "primary" to (s.primary to s.onPrimary),
        "primaryContainer" to (s.primaryContainer to s.onPrimaryContainer),
        "secondary" to (s.secondary to s.onSecondary),
        "secondaryContainer" to (s.secondaryContainer to s.onSecondaryContainer),
        "tertiary" to (s.tertiary to s.onTertiary),
        "tertiaryContainer" to (s.tertiaryContainer to s.onTertiaryContainer),
        "background" to (s.background to s.onBackground),
        "surface" to (s.surface to s.onSurface),
        "surfaceVariant" to (s.surfaceVariant to s.onSurfaceVariant),
        "inverseSurface" to (s.inverseSurface to s.inverseOnSurface),
        "surfaceContainerLow" to (s.surfaceContainerLow to s.onSurface),
        "surfaceContainer" to (s.surfaceContainer to s.onSurface),
        "surfaceContainerHigh" to (s.surfaceContainerHigh to s.onSurface),
        "surfaceContainerHighest" to (s.surfaceContainerHighest to s.onSurface),
        "surfaceDim" to (s.surfaceDim to s.onSurface),
        "surfaceVariantOnBackground" to (s.background to s.onSurfaceVariant)
    )

    @Test
    fun `every variant meets AA contrast for text roles in light and dark`() {
        for (variant in PixelThemeVariant.entries) {
            for (dark in listOf(false, true)) {
                for ((name, pair) in textPairs(pixelColorScheme(variant, dark))) {
                    val ratio = contrast(pair.first, pair.second)
                    assertTrue("$variant dark=$dark $name contrast $ratio < 4.5", ratio >= 4.5)
                }
            }
        }
    }

    @Test
    fun `outlines are visible against surfaces`() {
        for (variant in PixelThemeVariant.entries) {
            for (dark in listOf(false, true)) {
                val s = pixelColorScheme(variant, dark)
                val ratio = contrast(s.outline, s.surface)
                assertTrue("$variant dark=$dark outline contrast $ratio < 3", ratio >= 3.0)
            }
        }
    }

    @Test
    fun `sprite outline reads against the scheme background`() {
        for (variant in PixelThemeVariant.entries) {
            for (dark in listOf(false, true)) {
                val s = pixelColorScheme(variant, dark)
                val sprite = pixelSpritePalette(variant, dark)
                val ratio = contrast(sprite.base, s.surface)
                assertTrue("$variant dark=$dark sprite base vs surface $ratio < 1.5", ratio >= 1.5 || contrast(sprite.outline, s.surface) >= 3.0)
            }
        }
    }
}
