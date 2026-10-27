package com.example.llamadroid.tama.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/** Frames in the `fx_*` strips (horizontal, square frames: frame width = strip width / 4). */
internal const val FARM_FX_STRIP_FRAMES = 4

/** Decodes `assets/farm/Others/<name>.png`; null when the asset is missing so callers can fall back. */
internal fun loadFarmFxBitmap(context: Context, name: String): ImageBitmap? = runCatching {
    context.assets.open("farm/Others/$name.png").use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
}.getOrNull()

/** Draws frame [frame] (0 until [FARM_FX_STRIP_FRAMES]) of a horizontal [strip], stretched over the whole scope, unfiltered. */
internal fun DrawScope.drawFarmFxFrame(strip: ImageBitmap, frame: Int) {
    val frameWidth = strip.width / FARM_FX_STRIP_FRAMES
    val index = frame.coerceIn(0, FARM_FX_STRIP_FRAMES - 1)
    drawImage(
        image = strip,
        srcOffset = IntOffset(index * frameWidth, 0),
        srcSize = IntSize(frameWidth, strip.height),
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        filterQuality = FilterQuality.None
    )
}

/** Fills the parent with one frame of [strip]. Decorative, so hidden from accessibility. */
@Composable
internal fun BoxScope.FarmFxFrame(strip: ImageBitmap, frame: Int) {
    Canvas(modifier = Modifier.matchParentSize().clearAndSetSemantics {}) { drawFarmFxFrame(strip, frame) }
}
