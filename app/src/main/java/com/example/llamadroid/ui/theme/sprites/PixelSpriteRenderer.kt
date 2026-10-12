package com.example.llamadroid.ui.theme.sprites

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.example.llamadroid.ui.theme.LocalAppVisualStyle
import com.example.llamadroid.ui.theme.PixelSpritePalette

/**
 * Resolves a palette character to an ARGB colour (0 for transparent). When a variant's accent
 * equals its outline (Pocket LCD light) the shade is used so accent details stay visible.
 */
internal fun spriteArgb(char: Char, palette: PixelSpritePalette): Int = when (char) {
    'o' -> palette.outline.toArgb()
    'b' -> palette.base.toArgb()
    's' -> palette.shade.toArgb()
    'h' -> palette.highlight.toArgb()
    'a' -> (if (palette.accent == palette.outline) palette.shade else palette.accent).toArgb()
    'f' -> palette.face.toArgb()
    'w' -> lerp(palette.highlight, Color.White, 0.6f).toArgb()
    else -> 0
}

/** Pure pixel buffer for a sprite (row-major ARGB), exposed for tests. */
internal fun spritePixels(sprite: PixelSprite, palette: PixelSpritePalette): IntArray {
    val size = PixelSprite.SIZE
    val pixels = IntArray(size * size)
    sprite.rows.forEachIndexed { y, row ->
        row.forEachIndexed { x, ch -> pixels[y * size + x] = spriteArgb(ch, palette) }
    }
    return pixels
}

private object SpriteBitmapCache {
    private val cache = LruCache<Pair<AppSpriteKey, PixelSpritePalette>, ImageBitmap>(128)

    fun get(key: AppSpriteKey, palette: PixelSpritePalette): ImageBitmap {
        val cacheKey = key to palette
        cache.get(cacheKey)?.let { return it }
        val size = PixelSprite.SIZE
        val bitmap = Bitmap.createBitmap(
            spritePixels(PixelSprites.get(key), palette), size, size, Bitmap.Config.ARGB_8888
        ).asImageBitmap()
        cache.put(cacheKey, bitmap)
        return bitmap
    }
}

/** A 16x16 [ImageBitmap] for [key] in [palette], built once per (sprite, palette). */
@Composable
fun rememberSpriteBitmap(key: AppSpriteKey, palette: PixelSpritePalette): ImageBitmap =
    remember(key, palette) { SpriteBitmapCache.get(key, palette) }

/**
 * Draws [key] in the active pixel palette with nearest-neighbour scaling. Size it with the
 * modifier (multiples of 16dp stay perfectly crisp); it defaults to 24dp like a Material icon.
 */
@Composable
fun PixelSpriteImage(
    key: AppSpriteKey,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    palette: PixelSpritePalette = LocalAppVisualStyle.current.spritePalette
) {
    Image(
        bitmap = rememberSpriteBitmap(key, palette),
        contentDescription = contentDescription,
        modifier = modifier.size(24.dp),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None
    )
}
