package com.example.llamadroid.tama.data

import com.example.llamadroid.tama.game.withCycleTimestampsShifted
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FarmMoistureTest {
    private val hour = 3_600_000L
    private val t0 = 1_000_000_000L

    private fun crop(type: String, tracked: Boolean = true, fertilized: Boolean = false) =
        PlantedCrop(type, plantedTime = t0, lastStageUpdateTime = t0, isFertilized = fertilized, moistureTracked = tracked)

    private fun wetTile(type: String) = FarmTile(0, TileStatus.WET_FARMLAND, crop(type), lastWateredTime = t0)
    private fun dryTile(type: String) = FarmTile(0, TileStatus.FARMLAND, crop(type))

    @Test fun wateredCropGrowsAtFullSpeed() {
        assertEquals(2, advanceFarmTileCrop(wetTile("wheat"), t0 + 5 * hour).crop?.stage)
        assertEquals(3, advanceFarmTileCrop(wetTile("wheat"), t0 + 6 * hour).crop?.stage)
    }

    @Test fun dryCropGrowsAtHalfSpeedInsteadOfDying() {
        assertEquals(2, advanceFarmTileCrop(dryTile("wheat"), t0 + 11 * hour).crop?.stage)
        val ripe = advanceFarmTileCrop(dryTile("wheat"), t0 + 12 * hour)
        assertEquals(3, ripe.crop?.stage)
        assertFalse(ripe.crop!!.isDecayed)
        assertEquals(t0 + 12 * hour, farmCropReadyAt(dryTile("wheat")))
    }

    @Test fun waterRunsOutMidGrowthAndTheCropSlowsDown() {
        // Melon: 18 h of growth. Wet for the first 8 h, then half speed: 8 h + 10 h * 2 = 28 h.
        val melon = wetTile("melon")
        assertEquals(t0 + 28 * hour, farmCropReadyAt(melon))
        assertEquals(2, advanceFarmTileCrop(melon, t0 + 28 * hour - 1).crop?.stage)
        assertEquals(3, advanceFarmTileCrop(melon, t0 + 28 * hour).crop?.stage)
        assertEquals(TileStatus.FARMLAND, advanceFarmTileCrop(melon, t0 + 9 * hour).status)
        assertEquals(TileStatus.WET_FARMLAND, advanceFarmTileCrop(melon, t0 + 7 * hour).status)
    }

    @Test fun advancingInStepsEqualsAdvancingDirectly() {
        val direct = advanceFarmTileCrop(dryTile("carrot"), t0 + 9 * hour)
        var stepped = dryTile("carrot")
        for (h in 1..9) stepped = advanceFarmTileCrop(stepped, t0 + h * hour)
        assertEquals(direct, stepped)
        assertEquals(direct, advanceFarmTileCrop(direct, t0 + 9 * hour))
    }

    @Test fun wateringKeepsProgressMadeWhileDry() {
        // Dry for 4 h: 2 h of virtual progress. Water at 4 h: wheat (6 h) is then ripe 4 h later, at 8 h.
        val dryAfter4h = advanceFarmTileCrop(dryTile("wheat"), t0 + 4 * hour)
        val watered = farmWaterTile(dryAfter4h, t0 + 4 * hour)
        assertEquals(TileStatus.WET_FARMLAND, watered.status)
        assertEquals(t0 + 8 * hour, farmCropReadyAt(watered))
        assertEquals(2, advanceFarmTileCrop(watered, t0 + 8 * hour - 1).crop?.stage)
        assertEquals(3, advanceFarmTileCrop(watered, t0 + 8 * hour).crop?.stage)
    }

    @Test fun cropsPlantedBeforeTheMoistureModelNeverSlow() {
        val legacyDry = FarmTile(0, TileStatus.FARMLAND, crop("wheat", tracked = false))
        assertEquals(3, advanceFarmTileCrop(legacyDry, t0 + 6 * hour).crop?.stage)
        val legacyWet = FarmTile(0, TileStatus.WET_FARMLAND, crop("wheat", tracked = false), lastWateredTime = null)
        val advanced = advanceFarmTileCrop(legacyWet, t0 + 30 * hour)
        assertEquals(TileStatus.WET_FARMLAND, advanced.status)
        assertTrue(legacyWet.isWet(t0 + 30 * hour))
        // The old JSON has no moistureTracked key and decodes as untracked.
        val decoded = Json.decodeFromString<PlantedCrop>(
            """{"type":"wheat","plantedTime":1,"lastStageUpdateTime":1}""")
        assertFalse(decoded.moistureTracked)
    }

    @Test fun emptyWetPlotFlipsDryAfterEightHours() {
        val wet = FarmTile(0, TileStatus.WET_FARMLAND, null, lastWateredTime = t0)
        assertEquals(TileStatus.WET_FARMLAND, advanceFarmTileCrop(wet, t0 + 8 * hour - 1).status)
        assertEquals(TileStatus.FARMLAND, advanceFarmTileCrop(wet, t0 + 8 * hour).status)
    }

    @Test fun cycleFreezeShiftKeepsGrowthIdentical() {
        val shift = 5 * hour
        val tile = advanceFarmTileCrop(wetTile("melon"), t0 + 10 * hour)
        val shifted = tile.withCycleTimestampsShifted(shift)
        assertEquals(farmCropReadyAt(tile)!! + shift, farmCropReadyAt(shifted))
        assertEquals(
            advanceFarmTileCrop(tile, t0 + 25 * hour).crop?.stage,
            advanceFarmTileCrop(shifted, t0 + 25 * hour + shift).crop?.stage
        )
    }

    @Test fun needsWaterOnlyForDryGrowingTrackedCrops() {
        assertTrue(farmTileNeedsWater(dryTile("wheat"), t0))
        assertFalse(farmTileNeedsWater(wetTile("wheat"), t0))
        assertFalse(farmTileNeedsWater(FarmTile(0, TileStatus.FARMLAND, crop("wheat", tracked = false)), t0))
        val ripe = FarmTile(0, TileStatus.FARMLAND, crop("wheat").copy(stage = 3))
        assertFalse(farmTileNeedsWater(ripe, t0))
        assertNull(farmCropReadyAt(ripe))
    }

    @Test fun harvestYieldAveragesTheModifierPlusFertilizerBonus() {
        CropDefinitions.CROPS.keys.forEach { id ->
            listOf(false, true).forEach { fertilized ->
                val crop = crop(id, fertilized = fertilized)
                val samples = 20_000
                val mean = (0 until samples).sumOf { farmHarvestYield(crop, it / samples.toFloat()) } / samples.toDouble()
                assertEquals("$id fertilized=$fertilized", farmExpectedYield(id, fertilized), mean, 0.06)
            }
        }
    }

    @Test fun rottenCropsYieldOne() {
        assertEquals(1, farmHarvestYield(crop("melon").copy(isDecayed = true, isFertilized = true), 0.01f))
    }
}
