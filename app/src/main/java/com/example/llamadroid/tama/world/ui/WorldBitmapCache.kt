package com.example.llamadroid.tama.world.ui

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/** Shared by visible atlases and one-shot effects; evicted bitmaps remain safe for an active draw. */
internal object WorldBitmapCache {
    private const val BYTE_CAP = 24 * 1024 * 1024
    private val cache = object : LruCache<String, Bitmap>(BYTE_CAP) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    @Synchronized
    fun decode(assets: AssetManager, path: String): Bitmap? {
        val key = "${System.identityHashCode(assets)}:$path"
        cache.get(key)?.let { return it }
        val bitmap = runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight * 4 <= BYTE_CAP)
            assets.open(path).use { BitmapFactory.decodeStream(it, null,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) }
        }.getOrNull() ?: return null
        if (bitmap.allocationByteCount <= BYTE_CAP) cache.put(key, bitmap)
        return bitmap
    }
}
