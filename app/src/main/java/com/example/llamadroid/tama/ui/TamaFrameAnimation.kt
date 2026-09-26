package com.example.llamadroid.tama.ui

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.data.PetSpriteState
import com.example.llamadroid.tama.world.ui.WorldAssetEntry
import com.example.llamadroid.tama.world.ui.WorldAssetManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

private const val HOME_MANIFEST = "tama/animations/manifest.json"
private const val HOME_ATLAS_BYTE_LIMIT = 32 * 1024 * 1024

private data class HomeSheet(val definition: WorldAssetEntry, val bitmap: Bitmap)

/** One bounded shared decode cache. Animation selects rectangles without modifying pet pixels. */
private object HomeAnimationAssets {
    private val json = Json { ignoreUnknownKeys = true }
    private var manifest: WorldAssetManifest? = null
    private val bitmaps = object : LruCache<String, Bitmap>(HOME_ATLAS_BYTE_LIMIT) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun load(assets: AssetManager, id: String): HomeSheet? = withContext(Dispatchers.IO) {
        val catalog = synchronized(this@HomeAnimationAssets) { manifest } ?: runCatching {
            assets.open(HOME_MANIFEST).bufferedReader().use { json.decodeFromString<WorldAssetManifest>(it.readText()) }
        }.getOrNull()?.also { loaded -> synchronized(this@HomeAnimationAssets) { manifest = loaded } }
            ?: return@withContext null
        if (catalog.schemaVersion != 1) return@withContext null
        val definition = catalog.assets.firstOrNull { it.id == id && it.productionReady } ?: return@withContext null
        if (definition.frameWidth != 256 || definition.frameHeight != 256 || definition.columns <= 0 ||
            definition.footAnchor.x != 128f || definition.footAnchor.y != 224f ||
            definition.clips.any { it.frames.isEmpty() || it.frameMs <= 0 || it.frames.any { index -> index < 0 } }) return@withContext null
        val bitmap = synchronized(this@HomeAnimationAssets) { bitmaps.get(definition.path) } ?: runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            assets.open(definition.path).use { BitmapFactory.decodeStream(it, null, bounds) }
            require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight * 4 <= HOME_ATLAS_BYTE_LIMIT)
            assets.open(definition.path).use { stream -> BitmapFactory.decodeStream(stream, null,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) }
        }.getOrNull()?.also { decoded ->
            if (decoded.allocationByteCount <= HOME_ATLAS_BYTE_LIMIT) {
                synchronized(this@HomeAnimationAssets) { bitmaps.put(definition.path, decoded) }
            }
        } ?: return@withContext null
        val largestFrame = definition.clips.flatMap { it.frames }.maxOrNull() ?: return@withContext null
        if (!bitmap.hasAlpha() || bitmap.allocationByteCount > HOME_ATLAS_BYTE_LIMIT ||
            bitmap.width != definition.columns * definition.frameWidth ||
            bitmap.height % definition.frameHeight != 0 ||
            largestFrame >= definition.columns * (bitmap.height / definition.frameHeight)) return@withContext null
        HomeSheet(definition, bitmap)
    }
}

/** Eggs blink slowly using exactly two authored idle images; they never select locomotion frames. */
internal fun homeAnimationFrame(elapsedMs: Long, frameMs: Int, count: Int, egg: Boolean): Int {
    require(count > 0 && frameMs > 0)
    val elapsed = elapsedMs.coerceAtLeast(0)
    return if (egg) {
        if (count > 1 && elapsed % 2_400L >= 2_180L) 1 else 0
    } else ((elapsed / frameMs) % count).toInt()
}

@Composable
internal fun TamaFrameAnimation(
    speciesLine: PetSpeciesLine,
    stage: GrowthStage,
    spriteState: PetSpriteState,
    frozen: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val id = "home_${speciesLine.id}_${stage.name.lowercase()}"
    val sheet by produceState<HomeSheet?>(null, context.assets, id) {
        value = HomeAnimationAssets.load(context.assets, id)
    }
    val currentSheet = sheet
    val desiredAction = if (stage == GrowthStage.EGG) "IDLE" else spriteState.name
    val clip = currentSheet?.definition?.clips?.firstOrNull { it.action.equals(desiredAction, ignoreCase = true) }
    var frame by remember(id, desiredAction) { mutableIntStateOf(0) }
    LaunchedEffect(currentSheet, clip, frozen, stage) {
        frame = 0
        if (clip == null || frozen || clip.frames.size <= 1) return@LaunchedEffect
        val started = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                frame = homeAnimationFrame((now - started) / 1_000_000L, clip.frameMs, clip.frames.size,
                    egg = stage == GrowthStage.EGG)
            }
        }
    }
    if (currentSheet == null || clip == null) {
        // A static accepted base keeps development review usable while authored art is generated.
        // Production acceptance requires every manifest clip; warped legacy variants are never played.
        AsyncImage(model = "file:///android_asset/tama/pets/${speciesLine.id}/${stage.name.lowercase()}/idle_0.png",
            contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None)
        return
    }
    val bitmap = remember(currentSheet.bitmap) { currentSheet.bitmap.asImageBitmap() }
    val definition = currentSheet.definition
    val selected = clip.frames[frame.coerceIn(clip.frames.indices)]
    Canvas(modifier) {
        val side = minOf(size.width, size.height).roundToInt()
        val scale = side.toFloat() / definition.frameWidth
        drawImage(bitmap,
            srcOffset = IntOffset((selected % definition.columns) * definition.frameWidth,
                (selected / definition.columns) * definition.frameHeight),
            srcSize = IntSize(definition.frameWidth, definition.frameHeight),
            dstOffset = IntOffset((size.width / 2 - definition.footAnchor.x * scale).roundToInt(),
                (size.height * 0.875f - definition.footAnchor.y * scale).roundToInt()),
            dstSize = IntSize(side, side), filterQuality = FilterQuality.None)
    }
}
