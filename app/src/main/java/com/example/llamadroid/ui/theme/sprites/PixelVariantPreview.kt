package com.example.llamadroid.ui.theme.sprites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.llamadroid.data.PixelThemeVariant
import com.example.llamadroid.ui.theme.pixelSpritePalette

/** Sprites shown on each variant card in Appearance settings. */
val PixelVariantPreviewSprites = listOf(AppSpriteKey.AGENT_ROBOT, AppSpriteKey.MODEL_LLM, AppSpriteKey.TAMA)

/** Three sprites drawn in [variant]'s own (light) palette so users can preview it before choosing. */
@Composable
fun PixelVariantSpritePreview(variant: PixelThemeVariant, modifier: Modifier = Modifier) {
    val palette = remember(variant) { pixelSpritePalette(variant, dark = false) }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PixelVariantPreviewSprites.forEach { key ->
            PixelSpriteImage(key = key, modifier = Modifier.size(32.dp), palette = palette)
        }
    }
}
