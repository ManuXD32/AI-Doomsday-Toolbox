package com.example.llamadroid.tama.world.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/** Tool grip and actor hand are both authored coordinates, never guessed from alpha bounds. */
internal fun DrawScope.drawWorldTool(
    entry: WorldSpriteAtlasEntry?,
    hand: Offset,
    tilePx: Float,
    elapsedMs: Long,
    direction: WorldDirection = WorldDirection.SOUTH
) {
    if (entry == null) return
    val frame = overlayFrame(entry, elapsedMs)
    val grip = entry.definition.toolAnchors[frame.toString()] ?: return
    drawOverlayFrame(entry, frame, hand, grip, tilePx, entry.definition.directionTransform(direction))
}

internal fun DrawScope.drawWorldOverlay(entry: WorldSpriteAtlasEntry?, foot: Offset, tilePx: Float, elapsedMs: Long) {
    if (entry == null) return
    val definition = entry.definition
    val point = definition.footAnchor.let { anchor ->
        WorldAssetPoint(if (anchor.x in 0f..1f) anchor.x * definition.frameWidth else anchor.x,
            if (anchor.y in 0f..1f) anchor.y * definition.frameHeight else anchor.y)
    }
    drawOverlayFrame(
        entry,
        overlayFrame(entry, elapsedMs),
        foot,
        point,
        tilePx,
        WorldAssetDirectionTransform()
    )
}

private fun overlayFrame(entry: WorldSpriteAtlasEntry, elapsedMs: Long): Int {
    val clip = entry.definition.clips.first()
    return clip.frames[((elapsedMs.coerceAtLeast(0) / clip.frameMs.coerceAtLeast(1)) % clip.frames.size).toInt()]
}

private fun DrawScope.drawOverlayFrame(entry: WorldSpriteAtlasEntry, frame: Int, position: Offset,
                                       anchor: WorldAssetPoint, tilePx: Float,
                                       transform: WorldAssetDirectionTransform) {
    val definition = entry.definition
    val pixelScale = tilePx / 32f
    val srcOffset = IntOffset(
        frame % definition.columns * definition.frameWidth,
        frame / definition.columns * definition.frameHeight
    )
    val srcSize = IntSize(definition.frameWidth, definition.frameHeight)
    val dstOffset = IntOffset(
        (position.x - anchor.x * pixelScale).roundToInt(),
        (position.y - anchor.y * pixelScale).roundToInt()
    )
    val dstSize = IntSize(
        (definition.frameWidth * pixelScale).roundToInt().coerceAtLeast(1),
        (definition.frameHeight * pixelScale).roundToInt().coerceAtLeast(1)
    )
    fun DrawScope.drawFrame() {
        drawImage(
            entry.image,
            srcOffset = srcOffset,
            srcSize = srcSize,
            dstOffset = dstOffset,
            dstSize = dstSize,
            filterQuality = FilterQuality.None
        )
    }

    // Keep the authored grip fixed while rotating or mirroring the entire
    // overlay. FilterQuality.None preserves the pixel-art contract.
    val degrees = normalizedQuarterTurns(transform.quarterTurnsClockwise) * 90f
    if (transform.flipX) {
        scale(scaleX = -1f, scaleY = 1f, pivot = position) {
            rotate(degrees = degrees, pivot = position) { drawFrame() }
        }
    } else {
        rotate(degrees = degrees, pivot = position) { drawFrame() }
    }
}

private fun normalizedQuarterTurns(turns: Int): Int = ((turns % 4) + 4) % 4
