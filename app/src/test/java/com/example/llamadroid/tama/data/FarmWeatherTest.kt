package com.example.llamadroid.tama.data

import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FarmWeatherTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val seed = farmWeatherSeed("pet-1")

    @Test
    fun weatherIsDeterministic() {
        for (day in 19000L..19040L) for (h in 0..23) {
            assertEquals(farmWeatherAt(seed, day, h), farmWeatherAt(seed, day, h))
            val w = farmWeatherAt(seed, day, h)
            assertTrue(w.windStrength in 0f..1f)
        }
        assertEquals(farmWeatherSeed("a"), farmWeatherSeed("a"))
    }

    @Test
    fun rainHoursAreCappedAndRoughlyFrequent() {
        var rain = 0
        var windy = 0
        val days = 2000
        for (day in 20000L until 20000L + days) {
            val hours = farmRainHours(seed, day)
            val n = hours.count { it }
            assertTrue(n <= FARM_MAX_RAIN_HOURS_PER_DAY)
            rain += n
            for (h in 0..23) if (farmWeatherAt(seed, day, h).kind == FarmWeatherKind.WINDY) windy++
        }
        val rainShare = rain / (days * 24f)
        assertTrue("rain share $rainShare", rainShare in 0.05f..0.18f)
        assertTrue(windy / (days * 24f) in 0.05f..0.2f)
    }

    @Test
    fun phaseBoundaries() {
        assertEquals(FarmDaylightPhase.NIGHT, farmDaylightPhase(5, 59))
        assertEquals(FarmDaylightPhase.DAWN, farmDaylightPhase(6, 0))
        assertEquals(FarmDaylightPhase.DAWN, farmDaylightPhase(7, 59))
        assertEquals(FarmDaylightPhase.DAY, farmDaylightPhase(8, 0))
        assertEquals(FarmDaylightPhase.DAY, farmDaylightPhase(18, 59))
        assertEquals(FarmDaylightPhase.DUSK, farmDaylightPhase(19, 0))
        assertEquals(FarmDaylightPhase.DUSK, farmDaylightPhase(20, 59))
        assertEquals(FarmDaylightPhase.NIGHT, farmDaylightPhase(21, 0))
        assertEquals(1f, farmNightAmount(0), 0f)
        assertEquals(1f, farmNightAmount(360), 0.001f)
        assertEquals(0f, farmNightAmount(480), 0.001f)
        assertEquals(0f, farmNightAmount(1140), 0.001f)
        assertEquals(1f, farmNightAmount(1260), 0.001f)
        assertTrue(farmNightAmount(420) in 0.4f..0.6f)
        assertEquals(0, farmSkyTintArgb(720) ushr 24)
        assertTrue((farmSkyTintArgb(0) ushr 24) > 0x60)
        assertTrue(farmCritterMix(FarmDaylightPhase.NIGHT, FarmWeatherKind.SUNNY).fireflies > 0)
        assertEquals(0, farmCritterMix(FarmDaylightPhase.DAY, FarmWeatherKind.RAIN).total)
    }

    private fun findRainyDay(): Long =
        (20000L..21000L).first { d -> farmRainHours(seed, d).indexOfFirst { it } >= 1 }

    @Test
    fun rainWatersTilledTilesInWindow() {
        val day = findRainyDay()
        val hour = farmRainHours(seed, day).indexOfFirst { it }
        val dayStart = day * 24L * HOUR
        val rainStart = dayStart + hour * HOUR
        val tiles = listOf(
            FarmRainTile(1, TileStatus.FARMLAND, null),
            FarmRainTile(2, TileStatus.WET_FARMLAND, rainStart - 10 * HOUR),
            FarmRainTile(3, TileStatus.SOIL, null),
            FarmRainTile(4, TileStatus.FARMLAND, null, hasObstacle = true)
        )
        val result = farmRainWaterings(seed, tiles, rainStart - HOUR, rainStart + HOUR, utc)
        assertEquals(setOf(1, 2), result.map { it.tileId }.toSet())
        assertTrue(result.all { it.wateredAt == rainStart + HOUR })
        assertTrue(result.none { it.tileId == 3 || it.tileId == 4 })
    }

    @Test
    fun rainWindowIsBoundedTo48Hours() {
        val tiles = listOf(FarmRainTile(1, TileStatus.FARMLAND, null))
        val now = 21000L * 24L * HOUR
        val result = farmRainWaterings(seed, tiles, now - 1000L * HOUR, now, utc)
        assertTrue(result.all { it.wateredAt > now - 49 * HOUR })
        assertTrue(result.size <= 49)
        assertTrue(farmRainWaterings(seed, tiles, now, now, utc).isEmpty())
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
