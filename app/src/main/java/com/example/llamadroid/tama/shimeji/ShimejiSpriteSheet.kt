package com.example.llamadroid.tama.shimeji

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.LruCache
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.ui.TamaAnimationEntry
import com.example.llamadroid.tama.ui.TamaAnimationManifest
import com.example.llamadroid.tama.ui.parseTamaAnimationManifest

/** Frame strip for one clip: a shared sheet bitmap plus the cells to play in order. */
class ShimejiClipArt(
    val sheet: Bitmap,
    val frameWidth: Int,
    val frameHeight: Int,
    private val columns: Int,
    private val cells: List<Int>,
    val frameMs: Int
) {
    val frameCount: Int get() = cells.size

    fun srcRect(index: Int, out: Rect) {
        val cell = cells[index.coerceIn(0, cells.lastIndex)]
        val left = (cell % columns) * frameWidth
        val top = (cell / columns) * frameHeight
        out.set(left, top, left + frameWidth, top + frameHeight)
    }
}

internal data class LoadedSheet(val entry: TamaAnimationEntry, val bitmap: Bitmap)

/**
 * Resolves [ShimejiClipArt] for one (species, stage). Shimeji-only clips come from
 * `assets/tama/shimeji/manifest.json`; reused clips come from the home animation manifest.
 * When the shimeji manifest or a clip is missing, a closest home clip is used, and as a last
 * resort the static base sprite.
 */
class ShimejiSpriteSheet private constructor(
    private val shimeji: LoadedSheet?,
    private val home: LoadedSheet?,
    private val staticArt: ShimejiClipArt?
) {
    private val cache = HashMap<ShimejiClip, ShimejiClipArt?>()

    fun clip(clip: ShimejiClip): ShimejiClipArt? = cache.getOrPut(clip) {
        for ((fromHome, action) in candidates(clip)) {
            val sheet = (if (fromHome) home else shimeji) ?: continue
            val art = artFor(sheet, action)
            if (art != null) return@getOrPut art
        }
        staticArt
    }

    private fun artFor(sheet: LoadedSheet, action: String): ShimejiClipArt? {
        val entry = sheet.entry
        val clip = entry.clips.firstOrNull { it.action.equals(action, ignoreCase = true) } ?: return null
        return ShimejiClipArt(sheet.bitmap, entry.frameWidth, entry.frameHeight, entry.columns, clip.frames, clip.frameMs)
    }

    companion object {
        private const val HOME_MANIFEST = "tama/animations/manifest.json"
        private const val SHIMEJI_MANIFEST = "tama/shimeji/manifest.json"
        private const val BYTE_LIMIT = 32 * 1024 * 1024

        /** (fromHome, action) pairs tried in order; home actions use [com.example.llamadroid.tama.data.PetSpriteState] names. */
        internal fun candidates(clip: ShimejiClip): List<Pair<Boolean, String>> {
            val fallbacks: List<String> = when (clip) {
                ShimejiClip.JUMP -> listOf("HAPPY", "IDLE")
                ShimejiClip.FALL, ShimejiClip.LAND -> listOf("IDLE")
                ShimejiClip.DRAG -> listOf("HURT_TIRED", "IDLE")
                ShimejiClip.CLIMB -> listOf("WALK", "IDLE")
                ShimejiClip.SIT_EDGE -> listOf("SIT", "IDLE")
                ShimejiClip.REACT -> listOf("HAPPY", "IDLE")
                ShimejiClip.BATH -> listOf("CLEAN", "IDLE")
                else -> listOf("IDLE")
            }
            val list = ArrayList<Pair<Boolean, String>>()
            // Shimeji-authored rows win (e.g. the WALK/RUN stride cycles); home clips are the fallback.
            list += false to clip.name
            if (clip.fromHome) list += true to clip.name
            if (clip.isEgg) {
                // Egg clips are authored as WOBBLE/BOUNCE/... inside the egg sheet.
                list += false to clip.name.removePrefix("EGG_")
                list += true to "IDLE"
            } else {
                fallbacks.forEach { list += true to it }
            }
            return list
        }

        private val bitmaps = object : LruCache<String, Bitmap>(BYTE_LIMIT) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }
        private val manifests = HashMap<String, TamaAnimationManifest?>()

        /** Blocking (asset IO + decode): call from a background dispatcher. */
        fun load(context: Context, species: PetSpeciesLine, stage: GrowthStage): ShimejiSpriteSheet {
            val assets = context.applicationContext.assets
            val stageKey = stage.name.lowercase()
            val shimeji = loadSheet(assets, SHIMEJI_MANIFEST, "shimeji_${species.id}_$stageKey")
            val home = loadSheet(assets, HOME_MANIFEST, "home_${species.id}_$stageKey")
            val static = decode(assets, "tama/pets/${species.id}/$stageKey/idle_0.png")?.let {
                ShimejiClipArt(it, it.width, it.height, 1, listOf(0), 1000)
            }
            return ShimejiSpriteSheet(shimeji, home, static)
        }

        private fun manifest(assets: AssetManager, path: String): TamaAnimationManifest? = synchronized(manifests) {
            if (manifests.containsKey(path)) return manifests[path]
            val parsed = runCatching {
                assets.open(path).bufferedReader().use { parseTamaAnimationManifest(it.readText()) }
            }.getOrNull()?.takeIf { it.schemaVersion == 1 }
            // Only cache hits: the shimeji manifest may be added by an update and a miss is cheap.
            if (parsed != null) manifests[path] = parsed
            parsed
        }

        private fun loadSheet(assets: AssetManager, manifestPath: String, id: String): LoadedSheet? {
            val entry = manifest(assets, manifestPath)?.assets?.firstOrNull { it.id == id } ?: return null
            if (entry.frameWidth <= 0 || entry.frameHeight <= 0 || entry.columns <= 0 ||
                entry.clips.any { it.frames.isEmpty() || it.frameMs <= 0 || it.frames.any { f -> f < 0 } }) return null
            val bitmap = decode(assets, entry.path) ?: return null
            val rows = bitmap.height / entry.frameHeight
            val largest = entry.clips.flatMap { it.frames }.maxOrNull() ?: return null
            if (bitmap.width != entry.columns * entry.frameWidth || bitmap.height % entry.frameHeight != 0 ||
                largest >= entry.columns * rows) return null
            return LoadedSheet(entry, bitmap)
        }

        private fun decode(assets: AssetManager, path: String): Bitmap? {
            synchronized(bitmaps) { bitmaps.get(path) }?.let { return it }
            val decoded = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                    bounds.outWidth.toLong() * bounds.outHeight * 4 > BYTE_LIMIT) return@runCatching null
                assets.open(path).use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    })
                }
            }.getOrNull() ?: return null
            synchronized(bitmaps) { bitmaps.put(path, decoded) }
            return decoded
        }
    }
}
