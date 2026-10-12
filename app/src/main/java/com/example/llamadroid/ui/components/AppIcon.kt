package com.example.llamadroid.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.llamadroid.ui.theme.LocalAppVisualStyle
import com.example.llamadroid.ui.theme.sprites.AppSpriteKey
import com.example.llamadroid.ui.theme.sprites.PixelSpriteImage
import com.example.llamadroid.ui.theme.sprites.SpriteMappings

/**
 * Theme-aware icon. In Soft Studio it is a normal Material [Icon]. In Pixel it draws [sprite]
 * (or the sprite registered for [icon]'s vector name) in the variant palette; [tint] is ignored
 * because sprites keep their own colours. Icons without a sprite fall back to the Material icon.
 */
@Composable
fun AppIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    sprite: AppSpriteKey? = null
) {
    val style = LocalAppVisualStyle.current
    val key = if (style.isPixel) sprite ?: SpriteMappings.forVector(icon) else null
    if (key != null) {
        PixelSpriteImage(key = key, modifier = modifier, contentDescription = contentDescription)
    } else {
        Icon(imageVector = icon, contentDescription = contentDescription, modifier = modifier, tint = tint)
    }
}
