package com.example.llamadroid.tama.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.llamadroid.tama.data.FarmDaylightPhase
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.PetSpeciesLine
import com.example.llamadroid.tama.data.TamaPet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/*
 * Farm ambient layer: a living backdrop behind (and a thin sparkle pass in front of) the plot grid.
 *
 *  - Back pass ([FarmAmbientBackLayer]): day/night tint and the pet, which walks a lane along the free margin of
 *    the field using the home animation clips. It sits behind the plots, so it can never hide plot content.
 *  - Front pass ([FarmAmbientFrontLayer]): cloud shadows, critters, fireflies and rain. Non-interactive canvas.
 *
 * A single frame clock (the back layer's withFrameNanos loop, paused below STARTED) advances everything; critter
 * paths are stateless functions of that clock. Nothing is composed or animated under reduced motion except the
 * static tint. Missing critter art draws nothing.
 */

/** Pet footprint in the lane. */
private val FarmPetSize = 64.dp
private const val CRITTER_BUTTERFLY_DP = 20
private const val CRITTER_BEE_DP = 18
private const val CRITTER_BIRD_DP = 30
private const val LOW_ENERGY_NAP = 20f

@Stable
internal class FarmAmbientState(val reducedMotion: Boolean, petKey: String) {
    /** Per-session seed: critter and cloud paths differ between visits but are stable within one. */
    val sessionSeed: Long = Random.nextLong()

    /** Animation time in ms; only advances while the screen is STARTED and motion is allowed. */
    val clockMs = mutableLongStateOf(0L)
    val wind = mutableFloatStateOf(0f)
    var snapshot by mutableStateOf<FarmAmbientSnapshot?>(null)

    /** Plot column (grid plus page controls) in window coordinates and the grid's square edge in px. */
    var plotBounds by mutableStateOf<Rect?>(null)
    var plotSidePx by mutableFloatStateOf(0f)
    var layerOrigin by mutableStateOf(Offset.Zero)
    var layerSize by mutableStateOf(Size.Zero)

    val petX = mutableFloatStateOf(0.5f)
    val petMode = mutableStateOf(FarmPetMode.IDLE)
    val petFacingRight = mutableStateOf(true)
    private val brain = FarmPetBrain(farmAmbientSeed(petKey))

    /** The user acted on plot [plotIndex] (any tile id): the pet walks to its column and works briefly. */
    fun onPlotAction(plotIndex: Int) {
        if (!reducedMotion) brain.requestPlot(farmPlotLaneFraction(plotIndex))
    }

    fun onPetTap() {
        if (!reducedMotion) brain.tap()
    }

    internal fun tick(dtMs: Long, napWanted: Boolean, stationary: Boolean) {
        clockMs.longValue += dtMs
        brain.step(dtMs, napWanted, stationary)
        petX.floatValue = brain.x
        petMode.value = brain.mode
        petFacingRight.value = brain.facingRight
    }
}

private fun farmAmbientSeed(petKey: String): Long = petKey.hashCode().toLong() * 31L + 7L

/** Provides the ambient state to crop sway and any descendant that wants to react to plot actions. */
internal val LocalFarmAmbient = staticCompositionLocalOf<FarmAmbientState?> { null }

/**
 * Gentle wind sway around the bottom centre of a crop image, amplitude from the weather wind. A no-op under
 * reduced motion or without an ambient state. The angle is read inside the graphics layer, so frames only redraw.
 */
@Composable
internal fun Modifier.farmCropSway(plotId: Int = 0): Modifier {
    val ambient = LocalFarmAmbient.current ?: return this
    if (ambient.reducedMotion) return this
    val phase = plotId * 0.9f
    return this.graphicsLayer {
        val wind = ambient.wind.floatValue
        transformOrigin = TransformOrigin(0.5f, 1f)
        val t = ambient.clockMs.longValue / 1000f
        rotationZ = sin(t * (1.4f + wind * 1.6f) + phase) * (0.8f + 3.6f * wind)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Back pass
// ---------------------------------------------------------------------------------------------------------------

@Composable
internal fun FarmAmbientBackLayer(state: FarmAmbientState, pet: TamaPet, modifier: Modifier = Modifier) {
    val snapshot = state.snapshot
    val density = LocalDensity.current
    val petPx = with(density) { FarmPetSize.toPx() }
    val isEgg = pet.stage == GrowthStage.EGG
    val napWanted by rememberUpdatedState(
        pet.isSleeping || pet.stats.energy < LOW_ENERGY_NAP ||
            snapshot?.phase == FarmDaylightPhase.NIGHT
    )
    val stationary by rememberUpdatedState(isEgg)

    if (!state.reducedMotion) {
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(state, lifecycleOwner) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var last = 0L
                while (isActive) {
                    withFrameNanos { nanos ->
                        if (last != 0L) state.tick((nanos - last) / 1_000_000L, napWanted, stationary)
                        last = nanos
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned {
                state.layerOrigin = it.positionInWindow()
                state.layerSize = Size(it.size.width.toFloat(), it.size.height.toFloat())
            }
    ) {
        if (snapshot != null) FarmWeatherTint(snapshot.tintArgb, state.reducedMotion)
        if (!state.reducedMotion && state.plotBounds != null) {
            FarmPetSprite(state, pet, petPx)
        }
    }
}

@Composable
private fun FarmPetSprite(state: FarmAmbientState, pet: TamaPet, petPx: Float) {
    val speciesLine = remember(pet.species, pet.genetics.bodyStyle) {
        PetSpeciesLine.fromSpeciesId(pet.species, pet.genetics.bodyStyle)
    }
    val isEgg = pet.stage == GrowthStage.EGG
    val mode by state.petMode
    Box(
        modifier = Modifier
            .size(FarmPetSize)
            .offset {
                val rect = state.plotBounds ?: return@offset IntOffset.Zero
                val origin = state.layerOrigin
                val side = state.plotSidePx
                val gridLeft = rect.left - origin.x + (rect.width - side) / 2f
                val gridTop = rect.top - origin.y
                val feet = farmPetFeetY(state.layerSize.height, gridTop, rect.bottom - origin.y, petPx)
                IntOffset(
                    (gridLeft + state.petX.floatValue * side - petPx / 2f).roundToInt(),
                    (feet - 0.875f * petPx).roundToInt()
                )
            }
            .graphicsLayer {
                scaleX = if (state.petFacingRight.value) 1f else -1f
                if (isEgg) {
                    transformOrigin = TransformOrigin(0.5f, 0.875f)
                    rotationZ = sin(state.clockMs.longValue / 1000f * 3f) * 4f
                }
            }
            .pointerInput(state) { detectTapGestures { state.onPetTap() } }
            .clearAndSetSemantics {}
    ) {
        TamaFrameAnimation(
            speciesLine = speciesLine,
            stage = pet.stage,
            spriteState = if (isEgg) farmPetSpriteState(FarmPetMode.IDLE) else farmPetSpriteState(mode),
            frozen = false,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Front pass
// ---------------------------------------------------------------------------------------------------------------

private class FarmCritterSprites(val butterfly: ImageBitmap?, val bee: ImageBitmap?, val bird: ImageBitmap?)

private fun loadCritterSprites(context: Context) = FarmCritterSprites(
    loadFarmFxBitmap(context, "critter_butterfly"),
    loadFarmFxBitmap(context, "critter_bee"),
    loadFarmFxBitmap(context, "critter_bird")
)

private val FireflyGlow = Color(0xFFFFF59D)
private val RainColor = Color(0xFFBFD9F2)

@Composable
internal fun FarmAmbientFrontLayer(state: FarmAmbientState, modifier: Modifier = Modifier) {
    if (state.reducedMotion) return
    val context = LocalContext.current
    // Decoded once per screen visit off the main thread; the strips are tiny and shared by every critter.
    val sprites by produceState<FarmCritterSprites?>(null, context) {
        value = withContext(Dispatchers.IO) { loadCritterSprites(context) }
    }
    val density = LocalDensity.current.density
    Canvas(modifier = modifier.fillMaxSize().clearAndSetSemantics {}) {
        val snap = state.snapshot ?: return@Canvas
        val t = state.clockMs.longValue / 1000f
        drawCloudShadows(state, snap, t)
        sprites?.let { drawFlyers(state, snap, it, t, density) }
        drawFireflies(state, snap, t, density)
        if (snap.kind == com.example.llamadroid.tama.data.FarmWeatherKind.RAIN) drawRain(state, snap, density, t)
    }
}

private fun DrawScope.drawCloudShadows(state: FarmAmbientState, snap: FarmAmbientSnapshot, t: Float) {
    val count = farmCloudShadowCount(snap.cloudDensity)
    if (count == 0) return
    val layerAlpha = 0.045f * (0.6f + snap.cloudDensity)
    for (i in 0 until count) {
        val pose = farmCloudShadowPose(i, state.sessionSeed, t, snap.wind)
        val rx = size.width * pose.size
        val ry = rx * 0.5f
        val cx = pose.x * size.width
        val cy = pose.y * size.height
        // Three stacked ovals fake a soft edge without allocating a brush per frame.
        for (scale in floatArrayOf(1f, 0.72f, 0.46f)) {
            drawOval(
                color = Color.Black.copy(alpha = layerAlpha),
                topLeft = Offset(cx - rx * scale, cy - ry * scale),
                size = Size(rx * 2f * scale, ry * 2f * scale)
            )
        }
    }
}

private fun DrawScope.drawFlyers(
    state: FarmAmbientState,
    snap: FarmAmbientSnapshot,
    sprites: FarmCritterSprites,
    t: Float,
    density: Float
) {
    sprites.butterfly?.let { drawCritters(it, FarmCritterKind.BUTTERFLY, snap.mix.butterflies, state, t, CRITTER_BUTTERFLY_DP * density, 140) }
    sprites.bee?.let { drawCritters(it, FarmCritterKind.BEE, snap.mix.bees, state, t, CRITTER_BEE_DP * density, 70) }
    sprites.bird?.let { drawCritters(it, FarmCritterKind.BIRD, snap.mix.birds, state, t, CRITTER_BIRD_DP * density, 120) }
}

private fun DrawScope.drawCritters(
    strip: ImageBitmap,
    kind: FarmCritterKind,
    count: Int,
    state: FarmAmbientState,
    t: Float,
    sizePx: Float,
    frameMs: Int
) {
    val cell = strip.width / FARM_FX_STRIP_FRAMES
    val dst = sizePx.roundToInt().coerceAtLeast(1)
    for (i in 0 until count) {
        val pose = farmCritterPose(kind, i, state.sessionSeed, t)
        val cx = pose.x * size.width
        val cy = pose.y * size.height
        val frame = farmCritterFrame(t, frameMs, pose.phase)
        withTransform({ if (!pose.facingRight) scale(-1f, 1f, Offset(cx, cy)) }) {
            drawImage(
                image = strip,
                srcOffset = IntOffset(frame * cell, 0),
                srcSize = IntSize(cell, strip.height),
                dstOffset = IntOffset((cx - dst / 2f).roundToInt(), (cy - dst / 2f).roundToInt()),
                dstSize = IntSize(dst, dst),
                filterQuality = FilterQuality.None
            )
        }
    }
}

private fun DrawScope.drawFireflies(state: FarmAmbientState, snap: FarmAmbientSnapshot, t: Float, density: Float) {
    val count = snap.mix.fireflies
    if (count == 0) return
    val fade = (snap.nightAmount * 2f).coerceIn(0f, 1f)
    for (i in 0 until count) {
        val pose = farmCritterPose(FarmCritterKind.FIREFLY, i, state.sessionSeed, t)
        val pulse = 0.5f + 0.5f * sin(t * 1.7f + pose.phase * 6.2831855f)
        val alpha = (0.3f + 0.7f * pulse) * fade
        val center = Offset(pose.x * size.width, pose.y * size.height)
        drawCircle(FireflyGlow.copy(alpha = alpha * 0.22f), radius = 9f * density, center = center)
        drawCircle(FireflyGlow.copy(alpha = alpha * 0.9f), radius = 2f * density, center = center)
    }
}

private fun DrawScope.drawRain(state: FarmAmbientState, snap: FarmAmbientSnapshot, density: Float, t: Float) {
    val len = 14f * density
    val lean = len * (0.25f + snap.wind * 0.5f)
    val color = RainColor.copy(alpha = 0.5f)
    for (i in 0 until FARM_RAIN_DROPS) {
        val drop = farmRainDrop(i, state.sessionSeed, t, snap.wind)
        val x = drop.x * size.width
        val y = drop.y * size.height
        drawLine(color, Offset(x, y), Offset(x - lean, y - len), strokeWidth = 1.5f * density, cap = StrokeCap.Round)
    }
}

/** Reports the plot column's window bounds and the grid's edge so the pet lane follows the field. */
internal fun Modifier.farmReportPlotBounds(state: FarmAmbientState, sidePx: Float): Modifier =
    this.onGloballyPositioned {
        state.plotBounds = it.boundsInWindow()
        state.plotSidePx = sidePx
    }
