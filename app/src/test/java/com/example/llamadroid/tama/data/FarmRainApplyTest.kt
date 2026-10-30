package com.example.llamadroid.tama.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FarmRainApplyTest {
    @Test
    fun rainWatersDryTilledPlotsInOrder() {
        val dry = FarmTile(id = 0, status = TileStatus.FARMLAND)
        val other = FarmTile(id = 1, status = TileStatus.SOIL)
        val tiles = listOf(dry, other)
        val out = farmApplyRainWaterings(tiles, listOf(FarmRainWatering(0, 1_000L), FarmRainWatering(0, 5_000L)))
        assertEquals(TileStatus.WET_FARMLAND, out[0].status)
        assertEquals(5_000L, out[0].lastWateredTime)
        assertSame(other, out[1])
    }

    @Test
    fun noEventsKeepsTheSameList() {
        val tiles = listOf(FarmTile(id = 0, status = TileStatus.FARMLAND))
        assertSame(tiles, farmApplyRainWaterings(tiles, emptyList()))
    }

    @Test
    fun obstacleAndSoilPlotsAreShielded() {
        val seed = 7L
        val tiles = listOf(
            FarmRainTile(0, TileStatus.FARMLAND, null, hasObstacle = true),
            FarmRainTile(1, TileStatus.SOIL, null)
        )
        val waterings = farmRainWaterings(seed, tiles, 0L, 48L * 3_600_000L)
        assertEquals(emptyList<FarmRainWatering>(), waterings)
    }
}
