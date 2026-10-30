package com.example.llamadroid.tama.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FarmObstacleDataTest {
    private val day1 = LocalDate.of(2026, 10, 22)
    private val day2 = day1.plusDays(1)

    private fun page(vararg pages: Int, status: TileStatus = TileStatus.SOIL): List<FarmTile> =
        pages.flatMap { farmTileIdsForPage(it) }.map { FarmTile(it, status) }

    private fun List<FarmTile>.obstaclesOnPage(page: Int) = filter { farmPageForTileId(it.id) == page && it.obstacle != null }

    @Test fun firstRunSeedsOneStumpAndOneRockPerPageOnce() {
        val (tiles, state) = FarmObstacles.plan(FarmObstacleState(), page(0, 1), day1, 42L)
        listOf(0, 1).forEach { p ->
            val found = tiles.obstaclesOnPage(p).mapNotNull { it.obstacle }.sorted()
            assertEquals(listOf(FarmObstacle.STUMP, FarmObstacle.ROCK).sorted(), found)
        }
        assertEquals(setOf(0, 1), state.seededPages)
        assertEquals(day1.toString(), state.lastSpawnDay)
        // Running again the same day changes nothing.
        val (again, sameState) = FarmObstacles.plan(state, tiles, day1, 42L)
        assertEquals(tiles, again)
        assertEquals(state, sameState)
    }

    @Test fun neverSpawnsOnTilledOrPlantedPlots() {
        val crop = PlantedCrop("wheat", plantedTime = 1L, lastStageUpdateTime = 1L)
        val tiles = farmTileIdsForPage(0).map {
            when (it % 3) {
                0 -> FarmTile(it, TileStatus.FARMLAND)
                1 -> FarmTile(it, TileStatus.WET_FARMLAND, crop)
                else -> FarmTile(it, TileStatus.FARMLAND, crop)
            }
        }
        var state = FarmObstacleState(lastSpawnDay = day1.toString())
        var current = tiles
        for (i in 1..60) {
            val result = FarmObstacles.plan(state, current, day1.plusDays(i.toLong()), 7L)
            current = result.first
            state = result.second
        }
        assertTrue(current.all { it.obstacle == null })
    }

    @Test fun dailyRollIsCappedAtTwoPerPageAndOnlyOncePerDay() {
        var state = FarmObstacleState(lastSpawnDay = day1.toString(), seededPages = setOf(0))
        var tiles = page(0)
        for (i in 1..200) {
            val (next, nextState) = FarmObstacles.plan(state, tiles, day1.plusDays(i.toLong()), 99L)
            assertTrue(next.obstaclesOnPage(0).size <= FARM_OBSTACLES_MAX_PER_PAGE)
            tiles = next
            state = nextState
        }
        assertEquals(FARM_OBSTACLES_MAX_PER_PAGE, tiles.obstaclesOnPage(0).size)
        // Same day again: no new roll even when a slot opens up.
        val freed = tiles.map { if (it.obstacle != null) it.copy(obstacle = null) else it }
        val (sameDay, _) = FarmObstacles.plan(state, freed, LocalDate.parse(state.lastSpawnDay), 99L)
        assertEquals(freed, sameDay)
    }

    @Test fun dailyRollIsDeterministicAndMixesKinds() {
        val seeded = FarmObstacleState(lastSpawnDay = day1.toString(), seededPages = setOf(0, 1, 2))
        val a = FarmObstacles.plan(seeded, page(0, 1, 2), day2, 5L)
        val b = FarmObstacles.plan(seeded, page(0, 1, 2), day2, 5L)
        assertEquals(a, b)
        assertEquals(FarmObstacle.WEEDS, FarmObstacles.rollKind(0))
        assertEquals(FarmObstacle.WEEDS, FarmObstacles.rollKind(59))
        assertEquals(FarmObstacle.STUMP, FarmObstacles.rollKind(60))
        assertEquals(FarmObstacle.STUMP, FarmObstacles.rollKind(79))
        assertEquals(FarmObstacle.ROCK, FarmObstacles.rollKind(80))
        assertEquals(FarmObstacle.ROCK, FarmObstacles.rollKind(99))
    }

    @Test fun existingObstaclesAreKeptAndPageSeedingRespectsTheCap() {
        val withTwo = page(0).mapIndexed { i, t -> if (i < 2) t.copy(obstacle = FarmObstacle.WEEDS) else t }
        val (tiles, state) = FarmObstacles.plan(FarmObstacleState(), withTwo, day1, 1L)
        assertEquals(withTwo, tiles)
        assertEquals(setOf(0), state.seededPages)
    }

    @Test fun seedingWaitsWhileThePageHasNoIdlePlots() {
        val busy = page(0, status = TileStatus.FARMLAND)
        val (tiles, state) = FarmObstacles.plan(FarmObstacleState(), busy, day1, 1L)
        assertEquals(busy, tiles)
        assertTrue(state.seededPages.isEmpty())
    }

    @Test fun yieldIsTwoToFourAndKindsMapToToolsAndMaterials() {
        val seen = (0 until 300).map { farmObstacleYield(it * 7919 - 1000) }.toSet()
        assertEquals(setOf(2, 3, 4), seen)
        assertEquals("axe", FarmObstacle.STUMP.toolFamily)
        assertEquals("wood", FarmObstacle.STUMP.yieldItemId)
        assertEquals("pickaxe_required", FarmObstacle.ROCK.missingToolCode)
        assertEquals("stone", FarmObstacle.ROCK.yieldItemId)
        assertNull(FarmObstacle.WEEDS.toolFamily)
        assertNull(FarmObstacle.fromName(null))
        assertNull(FarmObstacle.fromName("nonsense"))
        assertEquals(FarmObstacle.ROCK, FarmObstacle.fromName("ROCK"))
    }

    @Test fun materialCostsAreModestAndMissingIsComputed() {
        assertEquals(FarmMaterialCost(2, 1), FarmMaterialCosts.wellCapacity(1))
        assertEquals(FarmMaterialCost(9, 4), FarmMaterialCosts.wellCapacity(8))
        assertEquals(FarmMaterialCost(3, 2), FarmMaterialCosts.composterCapacity(1))
        assertEquals(FarmMaterialCost(0, 2), FarmMaterialCosts.wellSpeed(0))
        assertEquals(FarmMaterialCost(0, 8), FarmMaterialCosts.wellSpeed(3))
        assertEquals(FarmMaterialCost(20, 15), FarmMaterialCosts.farmland(0))
        assertEquals(FarmMaterialCost(40, 30), FarmMaterialCosts.farmland(1))
        assertEquals(FarmMaterialCost(4, 1), FarmMaterialCost(10, 5).missing(6, 4))
        assertTrue(FarmMaterialCost(1, 1).missing(5, 5).isFree)
        assertFalse(FarmMaterialCosts.livestock(FarmLivestockType.BARN).isFree)
    }

    @Test fun materialsSellForLessThanTheyCostAndNeverSoftLock() {
        listOf(FARM_WOOD_ID, FARM_STONE_ID).forEach { id ->
            val buy = requireNotNull(FarmMaterialPrices.buyPrice(id))
            val sell = requireNotNull(FarmMaterialPrices.sellPrice(id))
            assertTrue(sell < buy)
            assertEquals(buy, FarmShopCatalog.materialBuyPrice(id))
            assertEquals(sell, TamaCommerceCatalog.sellPrice(id))
        }
        assertNull(FarmMaterialPrices.buyPrice("water"))
    }
}
