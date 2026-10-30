package com.example.llamadroid.tama.ui

import com.example.llamadroid.tama.data.FarmCritterMix
import com.example.llamadroid.tama.data.FarmDaylightPhase
import com.example.llamadroid.tama.data.FarmWeatherKind
import com.example.llamadroid.tama.data.PetSpriteState
import com.example.llamadroid.tama.data.farmCloudShadowDensity
import com.example.llamadroid.tama.data.farmCritterMix
import com.example.llamadroid.tama.data.farmDaylightPhase
import com.example.llamadroid.tama.data.farmNightAmount
import com.example.llamadroid.tama.data.farmSkyTintArgb
import com.example.llamadroid.tama.data.farmWeatherAt
import com.example.llamadroid.tama.data.farmWeatherSeed
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/*
 * Pure model behind the farm ambient layer (no Android or Compose types, so it is unit-testable):
 * the weather/daylight snapshot, the pet's lane behaviour, critter paths and the lane geometry.
 */

/** The five header chip texts: night replaces sunny/cloudy after dark. */
internal enum class FarmWeatherLabel { SUNNY, CLOUDY, WINDY, RAIN, NIGHT }

internal data class FarmAmbientSnapshot(
    val kind: FarmWeatherKind,
    val wind: Float,
    val phase: FarmDaylightPhase,
    val nightAmount: Float,
    val tintArgb: Int,
    val cloudDensity: Float,
    val mix: FarmCritterMix,
    val label: FarmWeatherLabel
)

/** Highest tint alpha (0..255) the field overlay may use, so plot contents stay readable at night. */
internal const val FARM_TINT_MAX_ALPHA = 140

internal fun farmCapTintAlpha(argb: Int, maxAlpha: Int = FARM_TINT_MAX_ALPHA): Int {
    val alpha = (argb ushr 24).coerceAtMost(maxAlpha)
    return (alpha shl 24) or (argb and 0x00FFFFFF)
}

internal fun farmWeatherLabel(kind: FarmWeatherKind, phase: FarmDaylightPhase): FarmWeatherLabel = when {
    kind == FarmWeatherKind.RAIN -> FarmWeatherLabel.RAIN
    kind == FarmWeatherKind.WINDY -> FarmWeatherLabel.WINDY
    phase == FarmDaylightPhase.NIGHT -> FarmWeatherLabel.NIGHT
    kind == FarmWeatherKind.CLOUDY -> FarmWeatherLabel.CLOUDY
    else -> FarmWeatherLabel.SUNNY
}

internal fun farmAmbientSnapshot(petKey: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): FarmAmbientSnapshot {
    val weather = farmWeatherAt(farmWeatherSeed(petKey), nowMs, zone)
    val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
    val phase = farmDaylightPhase(local.hour, local.minute)
    val night = farmNightAmount(local.hour * 60 + local.minute)
    return FarmAmbientSnapshot(
        kind = weather.kind,
        wind = weather.windStrength.coerceIn(0f, 1f),
        phase = phase,
        nightAmount = night,
        tintArgb = farmCapTintAlpha(farmSkyTintArgb(local.hour * 60 + local.minute, weather.kind)),
        cloudDensity = farmCloudShadowDensity(weather.kind, night),
        mix = farmCritterMix(phase, weather.kind),
        label = farmWeatherLabel(weather.kind, phase)
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Pet lane
// ---------------------------------------------------------------------------------------------------------------

internal enum class FarmPetMode { IDLE, WALK, RUN, WORK, NAP, HAPPY }

internal const val FARM_PET_WALK_SPEED = 0.09f
internal const val FARM_PET_RUN_SPEED = 0.2f
internal const val FARM_PET_WORK_MS = 1600L
internal const val FARM_PET_HAPPY_MS = 1200L

/** Home animation clip played for each lane mode (a missing clip falls back to the static base sprite). */
internal fun farmPetSpriteState(mode: FarmPetMode): PetSpriteState = when (mode) {
    FarmPetMode.IDLE -> PetSpriteState.IDLE
    FarmPetMode.WALK -> PetSpriteState.WALK
    FarmPetMode.RUN -> PetSpriteState.RUN
    FarmPetMode.WORK -> PetSpriteState.WORK
    FarmPetMode.NAP -> PetSpriteState.SLEEP
    FarmPetMode.HAPPY -> PetSpriteState.HAPPY
}

/** Horizontal lane fraction (0..1 across the plot grid) of the plot column that tile [tileId] sits in. */
internal fun farmPlotLaneFraction(tileId: Int): Float {
    val column = Math.floorMod(tileId, 3)
    return (column + 0.5f) / 3f
}

/**
 * Walks, works, naps and reacts along a one-dimensional lane. State is a lane fraction [x] plus a [mode];
 * [step] is driven by the single ambient frame clock.
 */
internal class FarmPetBrain(seed: Long) {
    private val rng = Random(seed)
    var x: Float = 0.5f
        private set
    var facingRight: Boolean = true
        private set
    var mode: FarmPetMode = FarmPetMode.IDLE
        private set

    private var target = x
    private var timerMs = 1_500L
    private var happyMs = 0L
    private var workAfterArrival = false
    private var pendingPlot: Float? = null

    /** The user acted on a plot: walk there, work briefly, then go back to idling. */
    fun requestPlot(laneFraction: Float) {
        pendingPlot = laneFraction.coerceIn(0.05f, 0.95f)
    }

    /** The user tapped the pet. */
    fun tap() {
        happyMs = FARM_PET_HAPPY_MS
    }

    fun step(dtMs: Long, napWanted: Boolean, stationary: Boolean) {
        val dt = dtMs.coerceIn(0L, 100L)
        if (stationary) {
            mode = FarmPetMode.IDLE
            return
        }
        if (happyMs > 0L) {
            happyMs -= dt
            mode = FarmPetMode.HAPPY
            return
        }
        if (mode == FarmPetMode.HAPPY) {
            mode = FarmPetMode.IDLE
            timerMs = 600L
        }
        pendingPlot?.let { plot ->
            pendingPlot = null
            target = plot
            workAfterArrival = true
            mode = if (abs(plot - x) > 0.45f) FarmPetMode.RUN else FarmPetMode.WALK
        }
        when (mode) {
            FarmPetMode.NAP -> if (!napWanted) {
                mode = FarmPetMode.IDLE
                timerMs = 800L
            }
            FarmPetMode.WORK -> {
                timerMs -= dt
                if (timerMs <= 0L) {
                    mode = FarmPetMode.IDLE
                    timerMs = idleDelay()
                }
            }
            FarmPetMode.WALK, FarmPetMode.RUN -> move(dt)
            FarmPetMode.IDLE, FarmPetMode.HAPPY -> {
                if (napWanted) {
                    mode = FarmPetMode.NAP
                } else {
                    timerMs -= dt
                    if (timerMs <= 0L) {
                        val next = 0.06f + rng.nextFloat() * 0.88f
                        if (abs(next - x) < 0.08f) {
                            timerMs = idleDelay()
                        } else {
                            target = next
                            mode = FarmPetMode.WALK
                        }
                    }
                }
            }
        }
    }

    private fun move(dt: Long) {
        val step = (if (mode == FarmPetMode.RUN) FARM_PET_RUN_SPEED else FARM_PET_WALK_SPEED) * dt / 1000f
        val dx = target - x
        if (dx != 0f) facingRight = dx > 0f
        if (abs(dx) <= step) {
            x = target
            if (workAfterArrival) {
                workAfterArrival = false
                mode = FarmPetMode.WORK
                timerMs = FARM_PET_WORK_MS
            } else {
                mode = FarmPetMode.IDLE
                timerMs = idleDelay()
            }
        } else {
            x += if (dx > 0f) step else -step
        }
    }

    private fun idleDelay(): Long = 2_500L + rng.nextInt(4_500)
}

/**
 * Vertical foot position (px) of the pet lane. The lane runs along the free margin below the plot column (grid plus
 * page controls) when it fits, otherwise above the grid. The layer is drawn behind the plots, so a tight layout
 * only ever lets the field cover the pet, never the other way round.
 */
internal fun farmPetFeetY(layerHeight: Float, gridTop: Float, columnBottom: Float, petPx: Float): Float {
    val below = layerHeight - columnBottom
    return if (below >= petPx * 0.7f || below >= gridTop) {
        minOf(columnBottom + petPx, layerHeight - 2f)
    } else {
        maxOf(gridTop, petPx)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Critters
// ---------------------------------------------------------------------------------------------------------------

internal enum class FarmCritterKind { BUTTERFLY, BEE, BIRD, FIREFLY }

/** [x]/[y] are fractions of the layer; [phase] is a stable 0..1 per-critter offset for animation. */
internal data class FarmCritterPose(val x: Float, val y: Float, val facingRight: Boolean, val phase: Float)

private fun mix64(input: Long): Long {
    var z = input + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}

private fun unitHash(seed: Long, a: Int, b: Int): Float =
    ((mix64(mix64(seed xor a.toLong()) xor (b.toLong() * 0x9E3779B1L)) and Long.MAX_VALUE) % 1_000_000L) / 1_000_000f

private const val TWO_PI = (2.0 * PI).toFloat()

/**
 * Stateless smooth path of critter [index] of [kind] at [tSec]: wanderers follow two summed sines per axis, birds
 * cross the layer in a straight line and wrap. The same inputs always give the same pose, so there is no per-critter
 * state to keep and nothing to reset when the count changes.
 */
internal fun farmCritterPose(kind: FarmCritterKind, index: Int, sessionSeed: Long, tSec: Float): FarmCritterPose {
    val k = kind.ordinal * 101 + index
    val p0 = unitHash(sessionSeed, k, 0)
    val p1 = unitHash(sessionSeed, k, 1)
    val p2 = unitHash(sessionSeed, k, 2)
    val p3 = unitHash(sessionSeed, k, 3)
    if (kind == FarmCritterKind.BIRD) {
        val speed = 0.05f + 0.04f * p1
        val travel = 1.4f
        val pos = ((tSec * speed + p0 * travel) % travel) - 0.2f
        val right = p2 < 0.5f
        return FarmCritterPose(
            x = if (right) pos else 1f - pos,
            y = 0.06f + 0.3f * p3 + 0.02f * sin(tSec * 2f + p0 * TWO_PI),
            facingRight = right,
            phase = p1
        )
    }
    val base = when (kind) {
        FarmCritterKind.BUTTERFLY -> 0.30f
        FarmCritterKind.BEE -> 0.75f
        else -> 0.2f
    }
    val w1 = base * (0.8f + 0.4f * p0)
    val w2 = w1 * 2.3f
    val w3 = base * (0.7f + 0.5f * p1)
    val w4 = w3 * 1.9f
    val a1 = p2 * TWO_PI
    val a2 = p3 * TWO_PI
    val x = 0.5f + 0.4f * sin(w1 * tSec + a1) + 0.08f * sin(w2 * tSec + a2)
    val y = 0.5f + 0.38f * sin(w3 * tSec + a2) + 0.07f * sin(w4 * tSec + a1)
    val dx = 0.4f * w1 * cos(w1 * tSec + a1) + 0.08f * w2 * cos(w2 * tSec + a2)
    return FarmCritterPose(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), dx >= 0f, p0)
}

/** Cloud shadow [index] of [count]: centre x (can be off-layer to enter/leave), y, and relative size 0..1. */
internal data class FarmCloudShadowPose(val x: Float, val y: Float, val size: Float)

internal fun farmCloudShadowPose(index: Int, sessionSeed: Long, tSec: Float, wind: Float): FarmCloudShadowPose {
    val p0 = unitHash(sessionSeed, 900 + index, 0)
    val p1 = unitHash(sessionSeed, 900 + index, 1)
    val p2 = unitHash(sessionSeed, 900 + index, 2)
    val p3 = unitHash(sessionSeed, 900 + index, 3)
    val speed = (0.012f + 0.03f * wind.coerceIn(0f, 1f)) * (0.7f + 0.6f * p1)
    val travel = 1.6f
    val pos = ((tSec * speed + p0 * travel) % travel) - 0.3f
    return FarmCloudShadowPose(pos, 0.15f + 0.7f * p2, 0.25f + 0.2f * p3)
}

/** Number of cloud shadows to draw for a 0..1 [density]. */
internal fun farmCloudShadowCount(density: Float): Int =
    kotlin.math.ceil(density.coerceIn(0f, 1f) * MAX_CLOUD_SHADOWS).toInt().coerceIn(0, MAX_CLOUD_SHADOWS)

private const val MAX_CLOUD_SHADOWS = 5

internal const val FARM_RAIN_DROPS = 64

/** Position (layer fractions) of rain drop [index] of [FARM_RAIN_DROPS]. */
internal data class FarmRainDrop(val x: Float, val y: Float)

internal fun farmRainDrop(index: Int, sessionSeed: Long, tSec: Float, wind: Float): FarmRainDrop {
    val p0 = unitHash(sessionSeed, 2000 + index, 0)
    val p1 = unitHash(sessionSeed, 2000 + index, 1)
    val p2 = unitHash(sessionSeed, 2000 + index, 2)
    val fall = 0.9f + 0.6f * p2
    val progress = (p1 + tSec * fall) % 1f
    return FarmRainDrop(x = (p0 + progress * 0.12f * (0.5f + wind)) % 1f, y = progress)
}

/** Frame (0..3) of a critter strip at [tSec]. */
internal fun farmCritterFrame(tSec: Float, frameMs: Int, phase: Float): Int =
    ((tSec * 1000f / frameMs + phase * 4f).toInt() % 4 + 4) % 4
