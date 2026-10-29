package com.example.llamadroid.ui.components

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Largest power-of-two sample size so the longest side stays <= [maxDimensionPx]. */
private fun sampleSizeFor(width: Int, height: Int, maxDimensionPx: Int): Int {
    var sample = 1
    var longest = maxOf(width, height)
    while (longest > maxDimensionPx && longest > 1) {
        sample *= 2
        longest = (longest + 1) / 2
    }
    return sample
}

/** Decodes an image file off the main thread with bounded size; returns null on any failure (OOM included). */
@Composable
fun rememberSampledImageBitmap(path: String?, maxDimensionPx: Int = 2048): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, path, maxDimensionPx) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimensionPx)
                }
                BitmapFactory.decodeFile(path, options)?.asImageBitmap()
            } catch (_: Throwable) {
                null
            }
        }
    }.value

/** Byte-array variant of [rememberSampledImageBitmap]. */
@Composable
fun rememberSampledImageBitmap(data: ByteArray?, maxDimensionPx: Int = 2048): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, data, maxDimensionPx) {
        value = if (data == null) null else withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimensionPx)
                }
                BitmapFactory.decodeByteArray(data, 0, data.size, options)?.asImageBitmap()
            } catch (_: Throwable) {
                null
            }
        }
    }.value
