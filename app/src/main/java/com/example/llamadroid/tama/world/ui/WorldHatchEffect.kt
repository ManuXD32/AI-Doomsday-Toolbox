package com.example.llamadroid.tama.world.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.llamadroid.R
import kotlinx.coroutines.delay

private const val HATCH_EFFECT_ASSET_ID = "fx_hatch"

/**
 * Plays the authored egg-to-baby effect once. The effect is loaded from the
 * world manifest so hatching never fakes motion by deforming a pet bitmap.
 * Missing art completes silently; the world readiness banner remains the
 * source of truth for an incomplete production pack.
 */
@Composable
fun WorldHatchEffect(
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {}
) {
    val description = stringResource(R.string.tama_world_hatch_effect_description)
    val loaded = rememberWorldSpriteAtlas(
        expectedAssetIds = setOf(HATCH_EFFECT_ASSET_ID),
        visibleAssetIds = setOf(HATCH_EFFECT_ASSET_ID),
        validateFullManifest = false
    ).value
    val entry = loaded.atlas.entry(HATCH_EFFECT_ASSET_ID)
    val clip = entry?.clip("HATCH", WorldDirection.SOUTH)
    val finish by rememberUpdatedState(onFinished)
    var frame by remember(entry?.definition?.id, clip?.frames) { mutableIntStateOf(0) }

    LaunchedEffect(entry?.definition?.id, clip?.frames, clip?.frameMs, loaded.readiness.isLoading) {
        if (loaded.readiness.isLoading) return@LaunchedEffect
        frame = 0
        if (entry == null || clip == null || clip.frames.isEmpty()) {
            finish()
            return@LaunchedEffect
        }
        clip.frames.forEachIndexed { index, _ ->
            frame = index
            delay(clip.frameMs.toLong())
        }
        finish()
    }

    if (loaded.readiness.isLoading || entry == null || clip == null || clip.frames.isEmpty()) return
    val selectedFrame = clip.frames[frame.coerceIn(clip.frames.indices)]
    val image = remember(entry.image) { entry.image }
    Canvas(
        modifier = modifier.semantics {
            contentDescription = description
        }
    ) {
        val sourceWidth = entry.definition.frameWidth
        val sourceHeight = entry.definition.frameHeight
        val scale = minOf(size.width / sourceWidth, size.height / sourceHeight)
        val destinationWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val destinationHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
        drawImage(
            image = image,
            srcOffset = IntOffset(
                (selectedFrame % entry.definition.columns) * sourceWidth,
                (selectedFrame / entry.definition.columns) * sourceHeight
            ),
            srcSize = IntSize(sourceWidth, sourceHeight),
            dstOffset = IntOffset(
                ((size.width - destinationWidth) / 2f).toInt(),
                ((size.height - destinationHeight) / 2f).toInt()
            ),
            dstSize = IntSize(destinationWidth, destinationHeight),
            filterQuality = FilterQuality.None
        )
    }
}
