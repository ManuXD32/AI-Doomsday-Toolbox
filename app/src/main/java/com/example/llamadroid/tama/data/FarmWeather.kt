package com.example.llamadroid.tama.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * Deterministic farm weather and daylight (pure functions, no Android types).
 *
 * Weather is derived from a per-pet seed, the local epoch day and the local hour, so it needs no storage and
 * every screen/engine tick agrees. Rain comes in 1-3 h showers (at most FARM_MAX_RAIN_HOURS_PER_DAY a day).
 */

const val FARM_MAX_RAIN_HOURS_PER_DAY = 4
const val FARM_RAIN_LOOKBACK_MS = 48L * 60L * 60L * 1000L
private const val HOUR_MS = 3_600_000L

enum class FarmWeatherKind { SUNNY, CLOUDY, WINDY, RAIN }

enum class FarmDaylightPhase { DAWN, DAY, DUSK, NIGHT }

/** [windStrength] is 0..1 (drives crop sway and cloud drift speed). */
data class FarmWeather(val kind: FarmWeatherKind, val windStrength: Float)

/** Max concurrent ambient critters per kind for the UI layer. */
data class FarmCritterMix(val butterflies: Int, val bees: Int, val birds: Int, val fireflies: Int) {
    val total: Int get() = butterflies + bees + birds + fireflies
}

/** Stable per-pet seed from any id string. */
fun farmWeatherSeed(petKey: String): Long = mix64(petKey.hashCode().toLong() xor 0x5DEECE66DL)

private fun mix64(input: Long): Long {
    var z = input + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}

private fun roll(seed: Long, a: Long, b: Long, c: Long): Long =
    mix64(mix64(mix64(seed xor a) xor b) xor c) and Long.MAX_VALUE

private fun unit(seed: Long, a: Long, b: Long, c: Long): Float =
    (roll(seed, a, b, c) % 1_000_000L) / 1_000_000f

/** Which of the 24 local hours of [epochDay] are rainy. Always at most FARM_MAX_RAIN_HOURS_PER_DAY. */
fun farmRainHours(seed: Long, epochDay: Long): BooleanArray {
    val rain = BooleanArray(24)
    if (unit(seed, epochDay, 1, 0) >= 0.9f) return rain
    val showers = if (unit(seed, epochDay, 2, 0) < 0.4f) 1 else 2
    var count = 0
    for (s in 0 until showers) {
        val start = (roll(seed, epochDay, 3, s.toLong()) % 24).toInt()
        val length = 1 + (roll(seed, epochDay, 4, s.toLong()) % 3).toInt()
        for (i in 0 until length) {
            val h = start + i
            if (h > 23 || count >= FARM_MAX_RAIN_HOURS_PER_DAY) break
            if (!rain[h]) {
                rain[h] = true
                count++
            }
        }
    }
    return rain
}

fun farmWeatherAt(seed: Long, epochDay: Long, hour: Int): FarmWeather {
    val h = hour.coerceIn(0, 23)
    val windBlock = h / 3
    val windRoll = unit(seed, epochDay, 10, windBlock.toLong())
    val windStrong = windRoll < 0.15f
    val smooth = unit(seed, epochDay, 11, h.toLong())
    if (farmRainHours(seed, epochDay)[h]) {
        return FarmWeather(FarmWeatherKind.RAIN, 0.2f + 0.4f * smooth)
    }
    if (windStrong) return FarmWeather(FarmWeatherKind.WINDY, 0.55f + 0.45f * smooth)
    val cloudy = unit(seed, epochDay, 12, (h / 4).toLong()) < 0.35f
    val base = 0.35f * smooth
    return FarmWeather(if (cloudy) FarmWeatherKind.CLOUDY else FarmWeatherKind.SUNNY, base)
}

fun farmWeatherAt(seed: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): FarmWeather {
    val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
    return farmWeatherAt(seed, local.toLocalDate().toEpochDay(), local.hour)
}

// ---- Daylight ----

fun farmDaylightPhase(hour: Int, minute: Int = 0): FarmDaylightPhase {
    val m = hour * 60 + minute
    return when {
        m in 360 until 480 -> FarmDaylightPhase.DAWN
        m in 480 until 1140 -> FarmDaylightPhase.DAY
        m in 1140 until 1260 -> FarmDaylightPhase.DUSK
        else -> FarmDaylightPhase.NIGHT
    }
}

fun farmDaylightPhase(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): FarmDaylightPhase {
    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
    return farmDaylightPhase(t.hour, t.minute)
}

private fun smoothstep(x: Float): Float {
    val c = x.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/** 0 = full day, 1 = full night; smooth through dawn (06-08) and dusk (19-21). */
fun farmNightAmount(minuteOfDay: Int): Float {
    val m = minuteOfDay.coerceIn(0, 1439)
    return when {
        m < 360 -> 1f
        m < 480 -> 1f - smoothstep((m - 360) / 120f)
        m < 1140 -> 0f
        m < 1260 -> smoothstep((m - 1140) / 120f)
        else -> 1f
    }
}

fun farmNightAmount(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Float {
    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
    return farmNightAmount(t.hour * 60 + t.minute)
}

private val SKY_KEYS = listOf(
    0 to 0x99101840.toInt(),
    360 to 0x99101840.toInt(),
    420 to 0x40FF9A60,
    480 to 0x00FFFFFF,
    1140 to 0x00FFFFFF,
    1200 to 0x40FF6A3A,
    1260 to 0x99101840.toInt(),
    1440 to 0x99101840.toInt()
)

private fun lerpArgb(a: Int, b: Int, t: Float): Int {
    var out = 0
    for (shift in intArrayOf(24, 16, 8, 0)) {
        val ca = (a ushr shift) and 0xFF
        val cb = (b ushr shift) and 0xFF
        out = out or ((ca + ((cb - ca) * t)).toInt().coerceIn(0, 255) shl shift)
    }
    return out
}

/** Source-over composite of [top] on [bottom] (both non-premultiplied ARGB). */
private fun overArgb(bottom: Int, top: Int): Int {
    val ta = (top ushr 24) / 255f
    val ba = (bottom ushr 24) / 255f
    val oa = ta + ba * (1 - ta)
    if (oa <= 0f) return 0
    var out = (oa * 255).toInt().coerceIn(0, 255) shl 24
    for (shift in intArrayOf(16, 8, 0)) {
        val tc = (top ushr shift) and 0xFF
        val bc = (bottom ushr shift) and 0xFF
        val c = (tc * ta + bc * ba * (1 - ta)) / oa
        out = out or (c.toInt().coerceIn(0, 255) shl shift)
    }
    return out
}

/** ARGB overlay tint for the sky/field: warm at dawn/dusk, deep blue at night, greyer under cloud and rain. */
fun farmSkyTintArgb(minuteOfDay: Int, kind: FarmWeatherKind = FarmWeatherKind.SUNNY): Int {
    val m = minuteOfDay.coerceIn(0, 1439)
    var tint = SKY_KEYS.last().second
    for (i in 0 until SKY_KEYS.size - 1) {
        val (m0, c0) = SKY_KEYS[i]
        val (m1, c1) = SKY_KEYS[i + 1]
        if (m in m0..m1) {
            tint = lerpArgb(c0, c1, if (m1 == m0) 0f else (m - m0) / (m1 - m0).toFloat())
            break
        }
    }
    val weatherTint = when (kind) {
        FarmWeatherKind.RAIN -> 0x40303C50
        FarmWeatherKind.CLOUDY -> 0x1C404858
        else -> 0
    }
    return if (weatherTint == 0) tint else overArgb(weatherTint, tint)
}

fun farmSkyTintArgb(nowMs: Long, kind: FarmWeatherKind, zone: ZoneId = ZoneId.systemDefault()): Int {
    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
    return farmSkyTintArgb(t.hour * 60 + t.minute, kind)
}

/** 0..1 density of drifting cloud shadows over the field (fades out at night). */
fun farmCloudShadowDensity(kind: FarmWeatherKind, nightAmount: Float = 0f): Float {
    val base = when (kind) {
        FarmWeatherKind.SUNNY -> 0.1f
        FarmWeatherKind.WINDY -> 0.3f
        FarmWeatherKind.CLOUDY -> 0.6f
        FarmWeatherKind.RAIN -> 0.85f
    }
    return base * (1f - nightAmount.coerceIn(0f, 1f) * 0.8f)
}

/** Ambient critters by daylight phase; rain clears the sky and wind halves the small flyers. */
fun farmCritterMix(phase: FarmDaylightPhase, kind: FarmWeatherKind): FarmCritterMix {
    if (kind == FarmWeatherKind.RAIN) return FarmCritterMix(0, 0, 0, 0)
    val base = when (phase) {
        FarmDaylightPhase.DAWN -> FarmCritterMix(1, 0, 3, 0)
        FarmDaylightPhase.DAY -> FarmCritterMix(3, 2, 2, 0)
        FarmDaylightPhase.DUSK -> FarmCritterMix(0, 0, 1, 2)
        FarmDaylightPhase.NIGHT -> FarmCritterMix(0, 0, 0, 5)
    }
    return if (kind == FarmWeatherKind.WINDY) base.copy(butterflies = base.butterflies / 2, bees = base.bees / 2) else base
}

// ---- Rain watering ----

/** Minimal tile view for rain watering; [hasObstacle] plots are shielded. */
data class FarmRainTile(
    val id: Int,
    val status: TileStatus,
    val lastWateredTime: Long?,
    val hasObstacle: Boolean = false
)

/** One watering to apply (in list order) to [tileId] at [wateredAt]. */
data class FarmRainWatering(val tileId: Int, val wateredAt: Long)

/**
 * Rain waterings for tiles over [lastCheckMs, nowMs], looking back at most 48 h. One event per tile per
 * overlapping rainy hour, timestamp = min(now, end of that hour), sorted by time then tile id. Only tilled
 * (FARMLAND / WET_FARMLAND) plots without obstacles are watered; a tile already watered at or after an
 * event's time is skipped. The engine should advance each tile to wateredAt and call farmWaterTile.
 */
fun farmRainWaterings(
    seed: Long,
    tiles: List<FarmRainTile>,
    lastCheckMs: Long,
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault()
): List<FarmRainWatering> {
    if (nowMs <= lastCheckMs) return emptyList()
    val eligible = tiles.filter { !it.hasObstacle && it.status != TileStatus.SOIL }
    if (eligible.isEmpty()) return emptyList()
    val from = maxOf(lastCheckMs, nowMs - FARM_RAIN_LOOKBACK_MS)
    val lastWatered = HashMap<Int, Long>()
    eligible.forEach { tile -> tile.lastWateredTime?.let { lastWatered[tile.id] = it } }
    val out = ArrayList<FarmRainWatering>()
    var hourStart = Math.floorDiv(from, HOUR_MS) * HOUR_MS
    while (hourStart < nowMs) {
        val hourEnd = hourStart + HOUR_MS
        if (hourEnd > from && farmWeatherAt(seed, hourStart + HOUR_MS / 2, zone).kind == FarmWeatherKind.RAIN) {
            val at = minOf(nowMs, hourEnd)
            for (tile in eligible.sortedBy { it.id }) {
                val prev = lastWatered[tile.id]
                if (prev != null && prev >= at) continue
                lastWatered[tile.id] = at
                out.add(FarmRainWatering(tile.id, at))
            }
        }
        hourStart = hourEnd
    }
    return out
}
