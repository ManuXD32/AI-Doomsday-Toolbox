package com.example.llamadroid.tama.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Walk cycle speed (frames per second). */
internal const val FARM_ANIMAL_WALK_FPS = 8f

/** Graze/peck cycle speed (frames per second). */
internal const val FARM_ANIMAL_IDLE_FPS = 5f

/** Where an animal is at a point in time: [x] 0..1 across its walkable range. */
internal data class FarmAnimalSample(val x: Float, val facingRight: Boolean, val walking: Boolean)

/**
 * Deterministic wander schedule: walk right, pause, walk left, pause, repeating. Every animal gets its own
 * durations (and starting offset) from a seed, so a barn full of animals never moves in lockstep.
 * Being a pure function of time keeps the motion testable and needs no per-animal state.
 */
internal class FarmAnimalWander(seed: Int) {
    private val rnd = java.util.Random(seed.toLong() * 7919L + 17L)
    val walkSeconds: Float = 3.5f + rnd.nextFloat() * 3f
    val pauseRightSeconds: Float = 1.5f + rnd.nextFloat() * 3f
    val pauseLeftSeconds: Float = 1.5f + rnd.nextFloat() * 3f
    val periodSeconds: Float = 2f * walkSeconds + pauseRightSeconds + pauseLeftSeconds
    private val offsetSeconds: Float = rnd.nextFloat() * periodSeconds

    fun sampleAt(seconds: Float): FarmAnimalSample {
        var t = (seconds + offsetSeconds) % periodSeconds
        if (t < 0f) t += periodSeconds
        if (t < walkSeconds) return FarmAnimalSample(t / walkSeconds, facingRight = true, walking = true)
        t -= walkSeconds
        if (t < pauseRightSeconds) return FarmAnimalSample(1f, facingRight = true, walking = false)
        t -= pauseRightSeconds
        if (t < walkSeconds) return FarmAnimalSample(1f - t / walkSeconds, facingRight = false, walking = true)
        return FarmAnimalSample(0f, facingRight = false, walking = false)
    }

    /** Frame 0..3 for [seconds]; the walk strip cycles faster than the graze/peck strip. */
    fun frameAt(seconds: Float, walking: Boolean): Int {
        val fps = if (walking) FARM_ANIMAL_WALK_FPS else FARM_ANIMAL_IDLE_FPS
        return abs((seconds * fps).toInt()) % FARM_FX_STRIP_FRAMES
    }
}

/**
 * Walk and idle strips of one species. Chickens have no idle strip (round 15: the generated peck changed the
 * body between frames), so they hold walk frame 0 with a small nod while paused.
 */
internal class FarmAnimalStrips(val walk: ImageBitmap, val idle: ImageBitmap?)

private val farmLivestockStripCache = ConcurrentHashMap<String, ImageBitmap>()

private fun loadLivestockStrip(context: Context, name: String): ImageBitmap? {
    farmLivestockStripCache[name]?.let { return it }
    val bitmap = runCatching {
        context.applicationContext.assets.open("farm/livestock/$name.png").use {
            BitmapFactory.decodeStream(it)?.asImageBitmap()
        }
    }.getOrNull() ?: return null
    farmLivestockStripCache[name] = bitmap
    return bitmap
}

/** Strips for [species] ("chicken" or "cow"); null while loading or when an asset is missing (callers fall back). */
@Composable
internal fun rememberFarmAnimalStrips(species: String): FarmAnimalStrips? {
    val context = LocalContext.current
    val idleName = if (species == "cow") "cow_graze" else null
    val state = produceState<FarmAnimalStrips?>(
        initialValue = loadCachedStrips(species, idleName),
        species
    ) {
        if (value != null) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val walk = loadLivestockStrip(context, "${species}_walk")
            val idle = idleName?.let { loadLivestockStrip(context, it) }
            if (walk != null && (idleName == null || idle != null)) FarmAnimalStrips(walk, idle) else null
        }
    }
    return state.value
}

private fun loadCachedStrips(species: String, idleName: String?): FarmAnimalStrips? {
    val walk = farmLivestockStripCache["${species}_walk"] ?: return null
    val idle = idleName?.let { farmLivestockStripCache[it] ?: return null }
    return FarmAnimalStrips(walk, idle)
}

/**
 * One shared animation clock in seconds. It only ticks while the lifecycle is at least STARTED and stays
 * at 0 (static first frame) when [enabled] is false, e.g. under reduced motion.
 */
@Composable
internal fun rememberFarmAnimalClock(enabled: Boolean): State<Float> {
    val clock = remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(enabled, lifecycle) {
        if (!enabled) {
            clock.floatValue = 0f
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var last = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { now ->
                    if (last != 0L) clock.floatValue += (now - last) / 1_000_000_000f
                    last = now
                }
            }
        }
    }
    return clock
}

/**
 * Draws one animal at its [sample] in a band of the current scope: [cellPx] square, bottom-aligned, walking
 * across the full width. Left-facing is the horizontal mirror of the right-facing art.
 */
internal fun DrawScope.drawFarmAnimal(
    strips: FarmAnimalStrips,
    sample: FarmAnimalSample,
    frame: Int,
    cellPx: Float,
    seconds: Float = 0f
) {
    val idleStrip = strips.idle
    val holdWalkFrame = !sample.walking && idleStrip == null
    val strip = if (sample.walking || idleStrip == null) strips.walk else idleStrip
    val frameWidth = strip.width / FARM_FX_STRIP_FRAMES
    val left = (size.width - cellPx).coerceAtLeast(0f) * sample.x
    // Paused animals without an idle strip nod: a small dip on a ~0.8 s rhythm.
    val nod = if (holdWalkFrame) cellPx * 0.03f * sin(seconds * 2f * PI.toFloat() / 0.8f).coerceAtLeast(0f) else 0f
    val top = size.height - cellPx + nod
    val index = if (holdWalkFrame) 0 else frame.coerceIn(0, FARM_FX_STRIP_FRAMES - 1)
    val draw: DrawScope.() -> Unit = {
        drawImage(
            image = strip,
            srcOffset = IntOffset(index * frameWidth, 0),
            srcSize = IntSize(frameWidth, strip.height),
            dstOffset = IntOffset(left.toInt(), top.toInt()),
            dstSize = IntSize(cellPx.toInt(), cellPx.toInt()),
            filterQuality = FilterQuality.None
        )
    }
    if (sample.facingRight) draw() else scale(-1f, 1f, pivot = Offset(left + cellPx / 2f, 0f), block = draw)
}
