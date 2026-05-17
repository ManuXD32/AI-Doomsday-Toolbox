package com.example.llamadroid.tama.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FarmDroneDataTest {
    @Test
    fun `farmland helpers unlock one page per upgrade level`() {
        assertEquals(1, farmPageCountForFarmlandLevel(0))
        assertEquals(9, farmTileCountForFarmlandLevel(0))
        assertEquals(2, farmPageCountForFarmlandLevel(1))
        assertEquals(18, farmTileCountForFarmlandLevel(1))
        assertEquals(3, farmPageCountForFarmlandLevel(2))
        assertEquals(27, farmTileCountForFarmlandLevel(2))
        assertEquals(15_000, farmlandUpgradeCostForLevel(0))
        assertEquals(35_000, farmlandUpgradeCostForLevel(1))
        assertNull(farmlandUpgradeCostForLevel(2))
    }

    @Test
    fun `drone fuel upgrade helpers scale capacity and transfer amount`() {
        assertEquals(500, farmDroneFuelCapacityForUpgradeLevel(0))
        assertEquals(1_000, farmDroneFuelCapacityForUpgradeLevel(1))
        assertEquals(2_000, farmDroneFuelCapacityForUpgradeLevel(2))
        assertEquals(50, farmDroneFuelTransferAmountForUpgradeLevel(0))
        assertEquals(100, farmDroneFuelTransferAmountForUpgradeLevel(1))
        assertEquals(200, farmDroneFuelTransferAmountForUpgradeLevel(2))
        assertEquals(3_000, farmDroneFuelUpgradeCostForLevel(0))
        assertEquals(6_000, farmDroneFuelUpgradeCostForLevel(1))
        assertNull(farmDroneFuelUpgradeCostForLevel(2))
    }

    @Test
    fun `farm tool durability helper merges starter and shop tools with cap`() {
        val inventory = listOf(
            InventoryItem("hoe_starter", "Hoe", ItemType.TOOL, durability = 300, maxDurability = 500),
            InventoryItem("hoe", "Hoe", ItemType.TOOL, durability = 250, maxDurability = 500),
            InventoryItem("watering_can", "Watering Can", ItemType.TOOL, durability = 120, maxDurability = 500)
        )

        assertEquals(500, farmToolTotalDurability(inventory, "hoe"))
        assertEquals(120, farmToolTotalDurability(inventory, "watering_can"))
    }

    @Test
    fun `planting drone waits five minutes then plants highest priority seed`() {
        val start = 1_000L
        val result = simulateFarmDrones(
            tiles = listOf(FarmTile(id = 0)),
            plantingDrone = PlantingDroneState(
                enabled = true,
                fuel = 20,
                hoe = DroneToolState("hoe", "Hoe", durability = 2, maxDurability = 2),
                wateringCan = DroneToolState("watering_can", "Watering Can", durability = 2, maxDurability = 2),
                water = 1,
                fertilizer = 1,
                seeds = listOf(DroneSeedStock("carrot", 1), DroneSeedStock("wheat", 1)),
                lastUpdatedAt = start
            ),
            harvesterDrone = HarvesterDroneState(),
            now = start + FARM_PLANTING_DRONE_EMPTY_WAIT_MS
        )

        val planted = result.tiles.single().crop
        assertNotNull(planted)
        assertEquals("carrot", planted?.type)
        assertTrue(planted?.isFertilized == true)
        assertEquals(TileStatus.WET_FARMLAND, result.tiles.single().status)
        assertEquals(10, result.plantingDrone.fuel)
        assertEquals(0, result.plantingDrone.water)
        assertEquals(0, result.plantingDrone.fertilizer)
        assertEquals(1, result.plantingDrone.hoe?.durability)
        assertEquals(1, result.plantingDrone.wateringCan?.durability)
        assertEquals(listOf(DroneSeedStock("wheat", 1)), result.plantingDrone.seeds)
    }

    @Test
    fun `planting drone can plant on hidden farmland pages`() {
        val start = 1_000L
        val occupiedVisibleTiles = (0 until 9).map { id ->
            FarmTile(
                id = id,
                crop = PlantedCrop(
                    type = "wheat",
                    stage = 0,
                    plantedTime = start,
                    lastStageUpdateTime = start
                )
            )
        }
        val hiddenTiles = (9 until 18).map { id -> FarmTile(id = id) }

        val result = simulateFarmDrones(
            tiles = occupiedVisibleTiles + hiddenTiles,
            plantingDrone = PlantingDroneState(
                enabled = true,
                fuel = 20,
                hoe = DroneToolState("hoe", "Hoe", durability = 2, maxDurability = FARM_TOOL_DURABILITY_CAP),
                wateringCan = DroneToolState("watering_can", "Watering Can", durability = 2, maxDurability = FARM_TOOL_DURABILITY_CAP),
                water = 1,
                seeds = listOf(DroneSeedStock("carrot", 1)),
                lastUpdatedAt = start
            ),
            harvesterDrone = HarvesterDroneState(),
            now = start + FARM_PLANTING_DRONE_EMPTY_WAIT_MS
        )

        assertEquals("carrot", result.tiles.first { it.id == 9 }.crop?.type)
    }

    @Test
    fun `planting drone disables itself when fuel is unavailable`() {
        val start = 1_000L
        val result = simulateFarmDrones(
            tiles = listOf(FarmTile(id = 0)),
            plantingDrone = PlantingDroneState(
                enabled = true,
                fuel = 0,
                hoe = DroneToolState("hoe", "Hoe", durability = 2, maxDurability = 2),
                wateringCan = DroneToolState("watering_can", "Watering Can", durability = 2, maxDurability = 2),
                water = 1,
                seeds = listOf(DroneSeedStock("wheat", 1)),
                lastUpdatedAt = start
            ),
            harvesterDrone = HarvesterDroneState(),
            now = start + FARM_PLANTING_DRONE_EMPTY_WAIT_MS
        )

        assertFalse(result.plantingDrone.enabled)
        assertEquals("fuel_empty", result.plantingDrone.statusKey)
        assertNull(result.tiles.single().crop)
    }

    @Test
    fun `harvester drone respects whitelist and stores harvested crops`() {
        val start = 1_000L
        val wheat = FarmTile(
            id = 0,
            status = TileStatus.WET_FARMLAND,
            crop = PlantedCrop(
                type = "wheat",
                stage = 3,
                plantedTime = start,
                lastStageUpdateTime = start,
                isFertilized = true
            )
        )

        val result = simulateFarmDrones(
            tiles = listOf(wheat),
            plantingDrone = PlantingDroneState(),
            harvesterDrone = HarvesterDroneState(
                enabled = true,
                fuel = FARM_HARVESTING_DRONE_FUEL_COST,
                mode = HarvesterDroneMode.WHITELIST,
                cropFilter = setOf("wheat"),
                lastUpdatedAt = start
            ),
            now = start
        )

        assertNull(result.tiles.single().crop)
        assertEquals(TileStatus.SOIL, result.tiles.single().status)
        assertEquals(0, result.harvesterDrone.fuel)
        assertEquals(listOf(DroneStoredCrop("crop_wheat", 2)), result.harvesterDrone.storage)
    }

    @Test
    fun `harvester drone blacklist leaves listed crops alone`() {
        val start = 1_000L
        val wheat = FarmTile(
            id = 0,
            crop = PlantedCrop(
                type = "wheat",
                stage = 3,
                plantedTime = start,
                lastStageUpdateTime = start
            )
        )

        val result = simulateFarmDrones(
            tiles = listOf(wheat),
            plantingDrone = PlantingDroneState(),
            harvesterDrone = HarvesterDroneState(
                enabled = true,
                fuel = FARM_HARVESTING_DRONE_FUEL_COST,
                mode = HarvesterDroneMode.BLACKLIST,
                cropFilter = setOf("wheat"),
                lastUpdatedAt = start
            ),
            now = start
        )

        assertNotNull(result.tiles.single().crop)
        assertTrue(result.harvesterDrone.storage.isEmpty())
        assertEquals(FARM_HARVESTING_DRONE_FUEL_COST, result.harvesterDrone.fuel)
    }

    @Test
    fun `drone offline work is capped at forty eight hours`() {
        val start = 1_000L
        val result = simulateFarmDrones(
            tiles = listOf(FarmTile(id = 0)),
            plantingDrone = PlantingDroneState(
                enabled = true,
                fuel = 500,
                hoe = DroneToolState("hoe", "Hoe", durability = 100, maxDurability = 100),
                wateringCan = DroneToolState("watering_can", "Watering Can", durability = 100, maxDurability = 100),
                water = 100,
                seeds = listOf(DroneSeedStock("wheat", 20)),
                lastUpdatedAt = start
            ),
            harvesterDrone = HarvesterDroneState(
                enabled = true,
                fuel = 500,
                mode = HarvesterDroneMode.WHITELIST,
                cropFilter = setOf("wheat"),
                lastUpdatedAt = start
            ),
            now = start + FARM_DRONE_OFFLINE_PLAN_MS + 60L * 60L * 1000L
        )

        assertEquals(listOf(DroneStoredCrop("crop_wheat", 7)), result.harvesterDrone.storage)
        assertNotNull(result.tiles.single().crop)
    }

    @Test
    fun `fuel bucket price is one coin`() {
        assertEquals(1, FarmShopCatalog.materialBuyPrice(FARM_FUEL_BUCKET_ID))
    }
}
