package com.example.llamadroid.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.AppVisualStyle
import com.example.llamadroid.data.PixelThemeVariant
import com.example.llamadroid.ui.theme.PixelCornerShape
import com.example.llamadroid.ui.theme.pixelColorScheme
import com.example.llamadroid.ui.theme.pixelSpritePalette

/**
 * Visual style picker shown inside the Appearance card: Soft Studio / Pixel retro, plus four
 * pixel palette swatch cards when Pixel is selected. [variantPreview] is a slot for the mini
 * sprite preview (filled in by the sprite work); it is drawn above the colour squares.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceStyleSection(
    visualStyle: AppVisualStyle,
    pixelVariant: PixelThemeVariant,
    onVisualStyleChange: (AppVisualStyle) -> Unit,
    onPixelVariantChange: (PixelThemeVariant) -> Unit,
    modifier: Modifier = Modifier,
    pixelFontEnabled: Boolean = false,
    onPixelFontChange: (Boolean) -> Unit = {},
    onInteraction: () -> Unit = {},
    variantPreview: @Composable (variant: PixelThemeVariant, modifier: Modifier) -> Unit = { _, _ -> }
) {
    val styles = listOf(
        AppVisualStyle.SOFT_STUDIO to stringResource(R.string.pixel_theme_style_soft_studio),
        AppVisualStyle.PIXEL to stringResource(R.string.pixel_theme_style_pixel)
    )
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.pixel_theme_style_title),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = stringResource(R.string.pixel_theme_style_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            styles.forEachIndexed { index, (style, label) ->
                SegmentedButton(
                    selected = visualStyle == style,
                    onClick = {
                        onInteraction()
                        onVisualStyleChange(style)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = styles.size)
                ) {
                    Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (visualStyle == AppVisualStyle.PIXEL) {
            Text(
                text = stringResource(R.string.pixel_theme_variant_title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2
            ) {
                PixelThemeVariant.entries.forEach { variant ->
                    PixelVariantCard(
                        variant = variant,
                        selected = variant == pixelVariant,
                        onClick = {
                            onInteraction()
                            onPixelVariantChange(variant)
                        },
                        preview = variantPreview,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.pixel_theme_font_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.pixel_theme_font_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = pixelFontEnabled,
                    onCheckedChange = {
                        onInteraction()
                        onPixelFontChange(it)
                    }
                )
            }
        }
    }
}

@Composable
private fun PixelVariantCard(
    variant: PixelThemeVariant,
    selected: Boolean,
    onClick: () -> Unit,
    preview: @Composable (PixelThemeVariant, Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    // The swatch always shows the variant's own light scheme so it reads the same in both modes.
    val scheme = pixelColorScheme(variant, dark = false)
    val sprite = pixelSpritePalette(variant, dark = false)
    val name = variantName(variant)
    val selectedLabel = stringResource(R.string.pixel_theme_variant_selected)
    val shape = PixelCornerShape(3.dp, 2)
    Surface(
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = if (selected) "$name, $selectedLabel" else name
            },
        shape = shape,
        color = scheme.surface,
        border = BorderStroke(
            width = if (selected) 3.dp else 2.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else scheme.outline
        ),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            preview(variant, Modifier.fillMaxWidth())
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = variantDescription(variant),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(scheme.primary, scheme.secondary, scheme.tertiary, sprite.base).forEach { color ->
                    ColorSquare(color = color, outline = scheme.outline)
                }
            }
        }
    }
}

@Composable
private fun ColorSquare(color: Color, outline: Color) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(color)
            .border(1.dp, outline)
    )
}

@Composable
private fun variantName(variant: PixelThemeVariant): String = stringResource(
    when (variant) {
        PixelThemeVariant.CANDY_POP -> R.string.pixel_theme_variant_candy_pop
        PixelThemeVariant.POCKET_LCD -> R.string.pixel_theme_variant_pocket_lcd
        PixelThemeVariant.ARCADE_NIGHT -> R.string.pixel_theme_variant_arcade_night
        PixelThemeVariant.SUNSET_CARTRIDGE -> R.string.pixel_theme_variant_sunset_cartridge
    }
)

@Composable
private fun variantDescription(variant: PixelThemeVariant): String = stringResource(
    when (variant) {
        PixelThemeVariant.CANDY_POP -> R.string.pixel_theme_variant_candy_pop_desc
        PixelThemeVariant.POCKET_LCD -> R.string.pixel_theme_variant_pocket_lcd_desc
        PixelThemeVariant.ARCADE_NIGHT -> R.string.pixel_theme_variant_arcade_night_desc
        PixelThemeVariant.SUNSET_CARTRIDGE -> R.string.pixel_theme_variant_sunset_cartridge_desc
    }
)
