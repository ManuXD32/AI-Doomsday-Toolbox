package com.example.llamadroid.tama.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaLcdThemeContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private val schemes: List<Pair<String, ColorScheme>> =
        listOf("light" to tamaLcdLightScheme, "dark" to tamaLcdDarkScheme)

    private fun textPairs(s: ColorScheme) = listOf(
        "primary" to (s.primary to s.onPrimary),
        "primaryContainer" to (s.primaryContainer to s.onPrimaryContainer),
        "secondary" to (s.secondary to s.onSecondary),
        "secondaryContainer" to (s.secondaryContainer to s.onSecondaryContainer),
        "tertiary" to (s.tertiary to s.onTertiary),
        "tertiaryContainer" to (s.tertiaryContainer to s.onTertiaryContainer),
        "error" to (s.error to s.onError),
        "errorContainer" to (s.errorContainer to s.onErrorContainer),
        "background" to (s.background to s.onBackground),
        "surface" to (s.surface to s.onSurface),
        "surfaceVariant" to (s.surfaceVariant to s.onSurfaceVariant),
        "inverseSurface" to (s.inverseSurface to s.inverseOnSurface),
        "surfaceBright" to (s.surfaceBright to s.onSurface),
        "surfaceDim" to (s.surfaceDim to s.onSurface),
        "surfaceContainerLowest" to (s.surfaceContainerLowest to s.onSurface),
        "surfaceContainerLow" to (s.surfaceContainerLow to s.onSurface),
        "surfaceContainer" to (s.surfaceContainer to s.onSurface),
        "surfaceContainerHigh" to (s.surfaceContainerHigh to s.onSurface),
        "surfaceContainerHighest" to (s.surfaceContainerHighest to s.onSurface),
        "onSurfaceVariantOnSurface" to (s.surface to s.onSurfaceVariant),
        "errorOnSurface" to (s.surface to s.error),
        "secondaryOnSurface" to (s.surface to s.secondary),
        "tertiaryOnSurface" to (s.surface to s.tertiary),
        "inversePrimaryOnInverseSurface" to (s.inverseSurface to s.inversePrimary)
    )

    @Test
    fun `every text role pair meets AA in both LCD schemes`() {
        for ((scheme, colors) in schemes) {
            for ((name, pair) in textPairs(colors)) {
                val ratio = contrast(pair.first, pair.second)
                assertTrue("$scheme $name contrast $ratio < 4.5", ratio >= 4.5)
            }
        }
    }

    @Test
    fun `outlines and control colours are visible against surfaces`() {
        for ((scheme, colors) in schemes) {
            val checks = listOf(
                "outlineOnSurface" to contrast(colors.outline, colors.surface),
                "outlineOnSurfaceVariant" to contrast(colors.outline, colors.surfaceVariant),
                "outlineVariantOnSurface" to contrast(colors.outlineVariant, colors.surface),
                "primaryOnSurface" to contrast(colors.primary, colors.surface)
            )
            for ((name, ratio) in checks) {
                assertTrue("$scheme $name contrast $ratio < 3.0", ratio >= 3.0)
            }
        }
    }

    @Test
    fun `helper text and error text are readable on the cream popup body`() {
        val body = tamaLcdLightScheme.surface
        assertTrue(contrast(TamaHelperText, body) >= 4.5)
        assertTrue(contrast(TamaErrorText, body) >= 4.5)
        assertTrue(contrast(TamaErrorText, TamaBackground) >= 4.5)
        assertTrue(contrast(TamaMutedText, TamaBackground) >= 4.5)
    }

    @Test
    fun `switch colours keep thumb distinct from track`() {
        assertTrue(contrast(TamaLight, TamaDark) >= 3.0)
        assertTrue(contrast(TamaAccent, TamaLight) >= 3.0)
    }

    private fun over(fg: Color, bg: Color): Color = Color(
        red = fg.red * fg.alpha + bg.red * (1f - fg.alpha),
        green = fg.green * fg.alpha + bg.green * (1f - fg.alpha),
        blue = fg.blue * fg.alpha + bg.blue * (1f - fg.alpha)
    )

    @Test
    fun `round 13 accent pairs meet AA`() {
        val white = Color.White
        val darkSurface = Color(0xFF1C1B1F)
        val pairs = listOf(
            "sell button" to contrast(StoreSellContainer, StoreSellContent),
            "sell price light" to contrast(StorePriceSellLight, white),
            "buy price light" to contrast(StorePriceBuyLight, white),
            "sell price dark" to contrast(StorePriceSellDark, darkSurface),
            "buy price dark" to contrast(StorePriceBuyDark, darkSurface),
            "store empty label" to contrast(StoreEmptyLabelBackground.copy(alpha = 1f), StoreEmptyLabelText),
            "feed toggle on" to contrast(LivestockFeedOnContainer, LivestockFeedOnContent),
            "collect enabled" to contrast(LivestockCollectContainer, LivestockCollectContent),
            "collect disabled" to contrast(LivestockCollectDisabledContainer, LivestockCollectDisabledContent),
            "farm popup enabled" to contrast(FarmPopupButtonEnabled, FarmPopupButtonOnEnabled),
            "farm popup disabled" to contrast(FarmPopupButtonDisabled, FarmPopupButtonOnDisabled),
            "gate text button" to contrast(GateColorAccent, GateColorPanel),
            "gate locked text" to contrast(over(GateColorText.copy(alpha = 0.62f), GateColorPanel), GateColorPanel),
            "gate unchecked box" to contrast(over(GateColorText.copy(alpha = 0.78f), GateColorPanel), GateColorPanel),
            "gate disabled button" to contrast(over(GateColorText.copy(alpha = 0.6f), GateColorPanel), GateColorPanel)
        )
        for ((name, ratio) in pairs) {
            assertTrue("$name contrast $ratio < 4.5", ratio >= 4.5)
        }
    }

    @Test
    fun `farm popup button stands out from the cream section and is more prominent than disabled`() {
        val section = Color(0xFFF9F8F3)
        val overlay = Color(0xFF101010)
        assertTrue(contrast(FarmPopupButtonEnabled, section) >= 2.9)
        assertTrue(contrast(FarmPopupButtonEnabled, overlay) >= 3.0)
        assertTrue(
            contrast(FarmPopupButtonOnEnabled, FarmPopupButtonEnabled) >
                contrast(FarmPopupButtonOnDisabled, FarmPopupButtonDisabled)
        )
    }
}
