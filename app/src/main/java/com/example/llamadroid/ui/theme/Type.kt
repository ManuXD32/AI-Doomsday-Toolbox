package com.example.llamadroid.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R

val Typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.3.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

/** Pixelify Sans (SIL OFL 1.1, see assets/licenses/pixelify-sans-OFL.txt). Covers ASCII and Spanish accents. */
val PixelFontFamily = FontFamily(
    Font(R.font.pixelify_sans_regular, FontWeight.Normal),
    Font(R.font.pixelify_sans_bold, FontWeight.Bold)
)

/**
 * Pixel theme typography: the pixel font is used for display, headline, title and label
 * styles only. Body styles keep the default font so long text stays readable.
 */
val PixelTypography: Typography = Typography.let { base ->
    fun TextStyle.pixel() = copy(fontFamily = PixelFontFamily)
    base.copy(
        displayLarge = base.displayLarge.pixel(),
        displayMedium = base.displayMedium.pixel(),
        displaySmall = base.displaySmall.pixel(),
        headlineLarge = base.headlineLarge.pixel(),
        headlineMedium = base.headlineMedium.pixel(),
        headlineSmall = base.headlineSmall.pixel(),
        titleLarge = base.titleLarge.pixel(),
        titleMedium = base.titleMedium.pixel(),
        titleSmall = base.titleSmall.pixel(),
        labelLarge = base.labelLarge.pixel(),
        labelMedium = base.labelMedium.pixel(),
        labelSmall = base.labelSmall.pixel()
    )
}
