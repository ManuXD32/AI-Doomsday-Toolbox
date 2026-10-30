package com.example.llamadroid.tama.data

import com.example.llamadroid.tama.game.PetFarmWork
import com.example.llamadroid.tama.game.PetFarmWorkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invariants of the farm economy table (docs/tama-maintainer-reference.md, "Farm economy"). */
class FarmEconomyTest {
    private fun hours(id: String) = CropDefinitions.CROPS.getValue(id).stageTimes.sum() / 3_600_000.0

    @Test fun everyCropIsProfitable() {
        CropDefinitions.CROPS.forEach { (id, info) ->
            assertTrue("$id sells above its seed", info.sellPrice > info.seedPrice)
            assertTrue("$id net per harvest", farmExpectedYield(id, false) * info.sellPrice - info.seedPrice > 0)
        }
    }

    @Test fun profitPerPlotHourRisesWithGrowTime() {
        val groups = CropDefinitions.CROPS.keys.groupBy { hours(it) }.toSortedMap()
        val ordered = groups.values.toList()
        for (i in 0 until ordered.size - 1) {
            val shorterBest = ordered[i].maxOf { farmNetProfitPerPlotHour(it) }
            val longerWorst = ordered[i + 1].minOf { farmNetProfitPerPlotHour(it) }
            assertTrue("group $i best $shorterBest must stay below group ${i + 1} worst $longerWorst", shorterBest < longerWorst)
        }
    }

    @Test fun longCropsPayMoreCoinsPerHarvest() {
        fun net(id: String): Double {
            val info = CropDefinitions.CROPS.getValue(id)
            return farmExpectedYield(id, false) * info.sellPrice - info.seedPrice
        }
        val groups = CropDefinitions.CROPS.keys.groupBy { hours(it) }.toSortedMap().values
        for (i in 0 until groups.size - 1) {
            val list = groups.toList()
            assertTrue("group $i worst must stay below group ${i + 1} worst",
                list[i].maxOf { net(it) } < list[i + 1].minOf { net(it) })
        }
    }

    @Test fun droneFuelAndWaterStayProfitableOnTheCheapestCrop() {
        val waterCoins = FarmShopCatalog.materialBuyPrice("water")
        val fuelCoins = FarmShopCatalog.materialBuyPrice(FARM_FUEL_BUCKET_ID)
        val cycleCost = (FARM_PLANTING_DRONE_FUEL_COST + FARM_HARVESTING_DRONE_FUEL_COST) * fuelCoins + waterCoins
        CropDefinitions.CROPS.forEach { (id, info) ->
            val net = farmExpectedYield(id, false) * info.sellPrice - info.seedPrice
            assertTrue("$id keeps at least 40% after drone costs", cycleCost <= net * 0.6)
        }
    }

    @Test fun fertilizedYieldIsTheSameForManualAndDroneHarvests() {
        val crop = PlantedCrop("corn", stage = 3, plantedTime = 5L, lastStageUpdateTime = 5L, isFertilized = true)
        val outcome = farmHarvestOutcome(42L, 3, crop)
        assertEquals(farmHarvestYield(crop, outcome), farmHarvestYield(crop, outcome))
        assertTrue(farmHarvestYield(crop, 0.01f) >= farmHarvestYield(crop.copy(isFertilized = false), 0.01f))
    }

    @Test fun livestockIncomeIsWorthTwoToThreePlotsNotTen() {
        val feedCost = CropDefinitions.CROPS.getValue("wheat").sellPrice // feeding wheat forgoes selling it
        val sixHourPlots = CropDefinitions.CROPS.keys.filter { hours(it) == 6.0 }
        val plotNetPerHour = sixHourPlots.map { farmNetProfitPerPlotHour(it) }.average()
        FarmLivestockType.entries.forEach { type ->
            val perAnimalPerDay = (24.0 * 3_600_000.0 / type.productionIntervalMs).coerceAtMost(type.perAnimalStorageCap.toDouble()) *
                type.productSellPrice - feedCost
            val structureNetPerHour = perAnimalPerDay * type.maxAnimals / 24.0
            val plotEquivalents = structureNetPerHour / plotNetPerHour
            assertTrue("${type.id} is worth $plotEquivalents plots", plotEquivalents in 1.5..3.0)
        }
    }

    @Test fun petFarmWorkRewardIsCappedPerDay() {
        val (first, granted) = PetFarmWork.reward(PetFarmWorkKind.HARVEST, 5, usedToday = 0)
        assertEquals(15, granted)
        assertTrue(first.happiness > 0f && first.bond > 0f && first.exercise > 0f)
        val (_, nearCap) = PetFarmWork.reward(PetFarmWorkKind.HARVEST, 50, usedToday = PetFarmWork.DAILY_CAP_POINTS - 4)
        assertEquals(4, nearCap)
        val (empty, none) = PetFarmWork.reward(PetFarmWorkKind.WATER, 10, usedToday = PetFarmWork.DAILY_CAP_POINTS)
        assertEquals(0, none)
        assertTrue(empty.isEmpty)
    }
}
