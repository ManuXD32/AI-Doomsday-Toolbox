package com.example.llamadroid.tama.game

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Smart tap, bulk page actions, well-fed watering can and market-priced store sales. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FarmSmartActionsTest {
    private lateinit var context: Context
    private lateinit var database: TamaDatabase
    private lateinit var farm: FarmRepository
    private lateinit var engine: TamaGameEngine
    private val petId = "smart-farm"

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, TamaDatabase::class.java)
            .allowMainThreadQueries().build()
        farm = FarmRepository(database.farmDao(), context)
        mockkObject(TamaNotificationScheduler)
        coEvery { TamaNotificationScheduler.scheduleForPet(any(), any()) } just Runs
        engine = TamaGameEngine(context, database.tamaDao(), FarmEngine(farm), farm,
            mockk<SettingsRepository>(relaxed = true), database, observeAndTick = false)
    }

    @After fun tearDown() = runBlocking {
        engine.close().join()
        database.close()
        unmockkAll()
    }

    private val hoe = InventoryItem("hoe", "Hoe", ItemType.TOOL, durability = 20, maxDurability = 20)
    private val can = InventoryItem("watering_can", "Can", ItemType.TOOL, durability = 20, maxDurability = 20)

    @Test fun smartTapWalksTillWaterPlantAndThenWaitsForTheCrop() = runBlocking {
        seedPet(listOf(hoe, can, InventoryItem("water", "Water", ItemType.MATERIAL, quantity = 3),
            InventoryItem("seed_carrot", "Carrot", ItemType.SEED, quantity = 2)))
        saveTile(FarmTile(0))
        assertEquals(FarmPlotAction.TILL_SOIL, FarmSmartActions.smartAction(context, engine, 0, "carrot").action)
        assertEquals(FarmPlotAction.WATER, FarmSmartActions.smartAction(context, engine, 0, "carrot").action)
        assertEquals(TileStatus.WET_FARMLAND, tile(0).status)
        assertEquals(FarmPlotAction.PLANT, FarmSmartActions.smartAction(context, engine, 0, "carrot").action)
        assertTrue(tile(0).crop?.moistureTracked == true)
        val idle = FarmSmartActions.smartAction(context, engine, 0, "carrot")
        assertFalse(idle.success)
        assertNull(idle.action)
        assertEquals(1, savedPet().inventory.first { it.id == "seed_carrot" }.quantity)
    }

    @Test fun smartTapPlantsDryWhenThereIsNoWaterAndHarvestsWhenRipe() = runBlocking {
        seedPet(listOf(InventoryItem("seed_carrot", "Carrot", ItemType.SEED)))
        saveTile(FarmTile(0, TileStatus.FARMLAND))
        assertEquals(FarmPlotAction.PLANT, FarmSmartActions.smartAction(context, engine, 0, "carrot").action)
        saveTile(FarmTile(1, TileStatus.WET_FARMLAND,
            PlantedCrop("carrot", stage = 3, plantedTime = 10L, lastStageUpdateTime = System.currentTimeMillis() - 1_000L),
            lastWateredTime = null))
        val harvest = FarmSmartActions.smartAction(context, engine, 1, null)
        assertEquals(FarmPlotAction.HARVEST_CROP, harvest.action)
        assertEquals(TileStatus.FARMLAND, tile(1).status)
        assertTrue(savedPet().inventory.any { it.id == "crop_carrot" })
    }

    @Test fun smartTapWithoutASeedExplainsInsteadOfPlanting() = runBlocking {
        seedPet(emptyList())
        saveTile(FarmTile(0, TileStatus.FARMLAND))
        val result = FarmSmartActions.smartAction(context, engine, 0, null)
        assertFalse(result.success)
        assertEquals("seed_required", result.limitCode)
    }

    @Test fun decisionTable() {
        val now = 10_000_000L
        val wet = FarmTile(0, TileStatus.WET_FARMLAND, null, lastWateredTime = now - 1_000L)
        val dry = FarmTile(0, TileStatus.FARMLAND)
        val growingDry = FarmTile(0, TileStatus.FARMLAND, PlantedCrop("wheat", plantedTime = now, lastStageUpdateTime = now, moistureTracked = true))
        assertEquals(FarmPlotAction.TILL_SOIL, FarmSmartActions.decide(FarmTile(0), now, false, false))
        assertNull(FarmSmartActions.decide(dry, now, false, true))
        assertEquals(FarmPlotAction.WATER, FarmSmartActions.decide(dry, now, true, true))
        assertEquals(FarmPlotAction.PLANT, FarmSmartActions.decide(dry, now, true, false))
        assertEquals(FarmPlotAction.PLANT, FarmSmartActions.decide(wet, now, true, true))
        assertEquals(FarmPlotAction.WATER, FarmSmartActions.decide(growingDry, now, false, true))
        assertNull(FarmSmartActions.decide(growingDry, now, false, false))
        val rotten = growingDry.copy(crop = growingDry.crop!!.copy(isDecayed = true, stage = 3))
        assertEquals(FarmPlotAction.REMOVE_DEAD_CROP, FarmSmartActions.decide(rotten, now, false, false))
    }

    @Test fun waterAllIsLimitedByWellAndCanDurability() = runBlocking {
        seedPet(listOf(can.copy(durability = 2, maxDurability = 20)))
        farm.buyUpgrade(petId, "well", rescheduleNotifications = false)
        val well = requireNotNull(farm.getUpgrade(petId, "well"))
        farm.saveUpgrade(well.copy(storedOutput = 1, extraDataJson = Json.encodeToString(
            WellUpgradeState(slots = listOf(WellSlot(hasWater = true, cycleStartedAt = 1L))))), false)
        (0..2).forEach { saveTile(dryGrowing(it)) }
        val result = FarmSmartActions.waterAll(context, engine, 0)
        assertEquals(1, result.count)            // one well charge, no inventory water allowed
        assertEquals("water_required", result.limitCode)
        assertEquals(1, savedPet().inventory.single().durability)
        assertEquals(0, farm.wellWaterAvailable(petId))
        assertEquals(1, farm.getTiles(petId).count { it.status == TileStatus.WET_FARMLAND })
    }

    @Test fun pouringIsNeverNeededWhenTheCanHasWellWater() = runBlocking {
        seedPet(listOf(can))
        farm.buyUpgrade(petId, "well", rescheduleNotifications = false)
        val well = requireNotNull(farm.getUpgrade(petId, "well"))
        farm.saveUpgrade(well.copy(storedOutput = 1, extraDataJson = Json.encodeToString(
            WellUpgradeState(slots = listOf(WellSlot(hasWater = true, cycleStartedAt = 1L))))), false)
        saveTile(dryGrowing(0))
        val result = FarmSmartActions.smartAction(context, engine, 0, null)
        assertEquals(FarmPlotAction.WATER, result.action)
        assertTrue(savedPet().inventory.none { it.id == "water" })
        assertEquals(19, savedPet().inventory.single().durability)
    }

    @Test fun waterAllWithItemsStopsWhenWaterRunsOutWithoutHalfSpending() = runBlocking {
        seedPet(listOf(can, InventoryItem("water", "Water", ItemType.MATERIAL, quantity = 2)))
        (0..3).forEach { saveTile(dryGrowing(it)) }
        val result = FarmSmartActions.waterAll(context, engine, 0, allowItems = true)
        assertEquals(2, result.count)
        assertEquals("water_required", result.limitCode)
        assertTrue(savedPet().inventory.none { it.id == "water" })
        assertEquals(18, savedPet().inventory.single().durability)
    }

    @Test fun plantAllUsesSeedsThenStops() = runBlocking {
        seedPet(listOf(InventoryItem("seed_wheat", "Wheat", ItemType.SEED, quantity = 3)))
        (0..4).forEach { saveTile(FarmTile(it, TileStatus.FARMLAND)) }
        val result = FarmSmartActions.plantAll(context, engine, 0, "wheat")
        assertEquals(3, result.count)
        assertEquals("seed_required", result.limitCode)
        assertEquals(3, farm.getTiles(petId).count { it.crop?.type == "wheat" })
        assertTrue(savedPet().inventory.none { it.id == "seed_wheat" })
    }

    @Test fun harvestAllCollectsRipeAndClearsDeadAndLeavesGrowingCrops() = runBlocking {
        seedPet(emptyList())
        val ripenedAt = System.currentTimeMillis() - 1_000L
        saveTile(FarmTile(0, TileStatus.WET_FARMLAND, PlantedCrop("carrot", stage = 3, plantedTime = 10L, lastStageUpdateTime = ripenedAt)))
        saveTile(FarmTile(1, TileStatus.FARMLAND, PlantedCrop("carrot", stage = 3, plantedTime = 10L, lastStageUpdateTime = ripenedAt, isDecayed = true)))
        saveTile(dryGrowing(2))
        val result = FarmSmartActions.harvestAll(context, engine, 0)
        assertEquals(2, result.count)
        assertTrue(result.skipped >= 1)
        assertNull(tile(0).crop)
        assertNull(tile(1).crop)
        assertNotNull(tile(2).crop)
        assertEquals(TileStatus.FARMLAND, tile(0).status)
        assertTrue(savedPet().inventory.any { it.id == "crop_carrot" })
        assertTrue(savedPet().inventory.any { it.id == "rotten_crop" })
    }

    @Test fun farmChoresGiveTheCappedPetBonus() = runBlocking {
        seedPet(listOf(InventoryItem("seed_wheat", "Wheat", ItemType.SEED, quantity = 1)))
        saveTile(FarmTile(0, TileStatus.FARMLAND))
        val before = savedPet()
        FarmSmartActions.smartAction(context, engine, 0, "wheat")
        val after = savedPet()
        assertTrue(after.stats.happiness >= before.stats.happiness)
        assertTrue(after.exerciseLevel > before.exerciseLevel)
        assertTrue(after.ownerBondLevel > before.ownerBondLevel)
    }

    @Test fun storeSalesUseTheWeeklyMarketPriceAndRecordTheSale() = runBlocking {
        val crop = InventoryItem("crop_wheat", "Wheat", ItemType.CROP, quantity = 2)
        seedPet(listOf(crop))
        val tinyPrice = 5L // the caller's price is ignored for farm goods
        assertTrue(engine.sellItem(crop, 2, tinyPrice).success)
        assertEquals(100L + 2L * TamaMarketPricing.maxPrice("crop_wheat"), savedPet().money)
        val quote = engine.getParkMarketBoard().quotes.first { it.itemId == "crop_wheat" }
        assertEquals(2, quote.unitsSoldSinceRefresh)
        assertTrue(quote.currentPrice * 2 >= quote.maxPrice)
    }

    private fun dryGrowing(id: Int) = FarmTile(id, TileStatus.FARMLAND,
        PlantedCrop("wheat", plantedTime = System.currentTimeMillis(), lastStageUpdateTime = System.currentTimeMillis(), moistureTracked = true))

    private suspend fun seedPet(inventory: List<InventoryItem>) {
        database.tamaDao().savePet(PetMapper.toEntity(TamaPet(id = petId, name = "Pixel",
            stage = GrowthStage.BABY, inventory = inventory)))
        engine.reloadPersistedPet()
        farm.ensureUnlockedFarmTiles(petId)
    }

    private suspend fun saveTile(tile: FarmTile) = farm.saveTile(petId, tile,
        rescheduleNotifications = false, syncDroneWatchers = false)
    private suspend fun tile(id: Int) = farm.getTiles(petId).first { it.id == id }
    private suspend fun savedPet() = PetMapper.toDomain(requireNotNull(database.tamaDao().getPet(petId)))
}
