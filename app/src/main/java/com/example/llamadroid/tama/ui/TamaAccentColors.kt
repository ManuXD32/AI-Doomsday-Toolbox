package com.example.llamadroid.tama.ui

import androidx.compose.ui.graphics.Color

// Darker accent shades (same hues as the old 4CAF50 / 42A5F5 / 8D6E63 accents) paired with a content
// colour that reaches >= 4.5:1. Pairs are verified in TamaLcdThemeContrastTest.

/** Store sell button / sell price. */
internal val StoreSellContainer = Color(0xFF2E7D32)
internal val StoreSellContent = Color.White

/** Store sell / buy price text on light and dark theme surfaces. */
internal val StorePriceSellLight = Color(0xFF2E7D32)
internal val StorePriceBuyLight = Color(0xFFBF360C)
internal val StorePriceSellDark = Color(0xFFA5D6A7)
internal val StorePriceBuyDark = Color(0xFFFFAB91)

/** Empty-state label drawn over the farm art. */
internal val StoreEmptyLabelBackground = Color(0xFF1C1B1F)
internal val StoreEmptyLabelText = Color.White

/** Livestock feed-mode toggle (on state). */
internal val LivestockFeedOnContainer = Color(0xFF1565C0)
internal val LivestockFeedOnContent = Color.White

/** Livestock collect button. */
internal val LivestockCollectContainer = Color(0xFF6D4C41)
internal val LivestockCollectContent = Color.White
internal val LivestockCollectDisabledContainer = Color(0xFFD7CCC8)
internal val LivestockCollectDisabledContent = Color(0xFF4E342E)
