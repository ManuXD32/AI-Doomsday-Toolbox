package com.example.llamadroid.tama.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.llamadroid.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val HATCH_ASSET = "tama/effects/fx_hatch.png"
private const val HATCH_FRAME_SIZE = 64
private const val HATCH_FRAME_COUNT = 4
private const val HATCH_FRAME_MS = 140L

/**
 * Plays the authored egg-to-baby effect once (4 frames of 64 px at 140 ms). The strip is
 * drawn as-is, never by deforming a pet bitmap. A missing or malformed asset finishes silently.
 */
@Composable
fun TamaHatchEffect(
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {}
) {
    val description = stringResource(R.string.tama_hatch_effect_description)
    val assets = LocalContext.current.assets
    // null = still loading; Result with null bitmap = unavailable.
    val loaded by produceState<Result<Bitmap?>?>(null, assets) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                assets.open(HATCH_ASSET).use { BitmapFactory.decodeStream(it) }
                    ?.takeIf { it.width >= HATCH_FRAME_SIZE * HATCH_FRAME_COUNT && it.height >= HATCH_FRAME_SIZE }
            }
        }
    }
    val finish by rememberUpdatedState(onFinished)
    var frame by remember { mutableIntStateOf(0) }
    val bitmap = loaded?.getOrNull()

    LaunchedEffect(loaded) {
        if (loaded == null) return@LaunchedEffect
        if (bitmap == null) {
            finish()
            return@LaunchedEffect
        }
        for (index in 0 until HATCH_FRAME_COUNT) {
            frame = index
            delay(HATCH_FRAME_MS)
        }
        finish()
    }

    if (bitmap == null) return
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(modifier = modifier.semantics { contentDescription = description }) {
        val scale = minOf(size.width / HATCH_FRAME_SIZE, size.height / HATCH_FRAME_SIZE)
        val side = (HATCH_FRAME_SIZE * scale).toInt().coerceAtLeast(1)
        drawImage(
            image = image,
            srcOffset = IntOffset(frame.coerceIn(0, HATCH_FRAME_COUNT - 1) * HATCH_FRAME_SIZE, 0),
            srcSize = IntSize(HATCH_FRAME_SIZE, HATCH_FRAME_SIZE),
            dstOffset = IntOffset(((size.width - side) / 2f).toInt(), ((size.height - side) / 2f).toInt()),
            dstSize = IntSize(side, side),
            filterQuality = FilterQuality.None
        )
    }
}
