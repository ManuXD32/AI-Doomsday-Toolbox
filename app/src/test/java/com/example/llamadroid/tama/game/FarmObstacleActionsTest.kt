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
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Obstacle clearing, material costs, drone skipping and the farm-refresh spawn path. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FarmObstacleActionsTest {
    private lateinit var context: Context
    private lateinit var database: TamaDatabase
    private lateinit var farm: FarmRepository
    private lateinit var engine: TamaGameEngine
    private val petId = "obstacle-farm"

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, TamaDatabase::class.java).allowMainThreadQueries().build()
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

    private val axe = InventoryItem("axe", "Axe", ItemType.TOOL, durability = 10, maxDurability = 100)
    private val pickaxe = InventoryItem("pickaxe", "Pickaxe", ItemType.TOOL, durability = 10, maxDurability = 100)
    private val hoe = InventoryItem("hoe", "Hoe", ItemType.TOOL, durability = 10, maxDurability = 100)

    @Test fun axeClearsAStumpForWoodAndSpendsDurability() = runBlocking {
        seedPet(listOf(axe))
        saveTile(FarmTile(0, obstacle = FarmObstacle.STUMP))
        val result = FarmSmartActions.smartAction(context, engine, 0)
        assertTrue(result.success)
        assertEquals(FarmPlotAction.CLEAR_OBSTACLE, result.action)
        assertNull(tile(0).obstacle)
        val wood = savedPet().inventory.first { it.id == "wood" }.quantity
        assertTrue(wood in FARM_OBSTACLE_MIN_YIELD..FARM_OBSTACLE_MAX_YIELD)
        assertEquals(9, savedPet().inventory.first { it.id == "axe" }.durability)
    }

    @Test fun missingToolIsATypedResultAndNothingChanges() = runBlocking {
        seedPet(emptyList())
        saveTile(FarmTile(0, obstacle = FarmObstacle.ROCK))
        val result = FarmSmartActions.smartAction(context, engine, 0)
        assertFalse(result.success)
        assertEquals("pickaxe_required", result.limitCode)
        assertEquals(context.getString(com.example.llamadroid.R.string.tama_farm_err_pickaxe), result.message)
        assertEquals(FarmObstacle.ROCK, tile(0).obstacle)
    }

    @Test fun weedsClearByHandOrAsTheHoeTills() = runBlocking {
        seedPet(listOf(hoe))
        saveTile(FarmTile(0, obstacle = FarmObstacle.WEEDS))
        saveTile(FarmTile(1, obstacle = FarmObstacle.WEEDS))
        assertEquals(FarmPlotAction.CLEAR_OBSTACLE, FarmSmartActions.smartAction(context, engine, 0).action)
        assertNull(tile(0).obstacle)
        assertEquals(TileStatus.SOIL, tile(0).status)
        assertEquals(10, savedPet().inventory.first { it.id == "hoe" }.durability)
        ClassicFarmPlots.request(context, engine, 1, FarmPlotAction.TILL_SOIL)
        assertNull(tile(1).obstacle)
        assertEquals(TileStatus.FARMLAND, tile(1).status)
    }

    @Test fun stumpsAndRocksBlockTillingAndPlanting() = runBlocking {
        seedPet(listOf(hoe))
        saveTile(FarmTile(0, obstacle = FarmObstacle.STUMP))
        val msg = ClassicFarmPlots.request(context, engine, 0, FarmPlotAction.TILL_SOIL)
        assertEquals(context.getString(com.example.llamadroid.R.string.tama_farm_err_obstacle), msg)
        assertEquals(FarmObstacle.STUMP, tile(0).obstacle)
        assertEquals(0, FarmSmartActions.tillAll(context, engine, 0).count)
    }

    @Test fun clearAllSkipsPlotsWhoseToolIsMissing() = runBlocking {
        seedPet(listOf(axe))
        saveTile(FarmTile(0, obstacle = FarmObstacle.STUMP))
        saveTile(FarmTile(1, obstacle = FarmObstacle.ROCK))
        saveTile(FarmTile(2, obstacle = FarmObstacle.WEEDS))
        val result = FarmSmartActions.clearObstaclesAll(context, engine, 0)
        assertEquals(2, result.count)
        assertEquals(FarmObstacle.ROCK, tile(1).obstacle)
    }

    @Test fun upgradeNeedsMaterialsThenSpendsThem() = runBlocking {
        seedPet(emptyList(), money = 5000)
        val missing = WorldFarmMaintenance.request(context, engine, "buy_upgrade", mapOf("type" to "well"))
        assertFalse(missing.success)
        assertEquals(FARM_MATERIALS_REQUIRED, missing.action)
        assertEquals(5000L, savedPet().money)
        seedPet(listOf(InventoryItem("wood", "Wood", ItemType.MATERIAL, quantity = 7),
            InventoryItem("stone", "Stone", ItemType.MATERIAL, quantity = 4)), money = 5000)
        assertTrue(WorldFarmMaintenance.request(context, engine, "buy_upgrade", mapOf("type" to "well")).success)
        assertEquals(1, savedPet().inventory.first { it.id == "wood" }.quantity)
        assertTrue(savedPet().inventory.none { it.id == "stone" })
    }

    @Test fun planterDroneNeverWorksAnObstaclePlot() {
        val tiles = listOf(FarmTile(0, obstacle = FarmObstacle.WEEDS), FarmTile(1))
        val planter = PlantingDroneState(enabled = true, fuel = 50, water = 5, lastUpdatedAt = 1L,
            seeds = listOf(DroneSeedStock("wheat", 3)),
            hoe = DroneToolState("hoe", "hoe", 10, 500), wateringCan = DroneToolState("watering_can", "watering_can", 10, 500))
        val result = simulateFarmDrones(tiles, planter, HarvesterDroneState(), 10L * 3_600_000L)
        assertNull(result.tiles.first { it.id == 0 }.crop)
        assertEquals(FarmObstacle.WEEDS, result.tiles.first { it.id == 0 }.obstacle)
    }

    @Test fun farmRefreshSeedsStarterObstaclesOnceAndTheyPersist() = runBlocking {
        seedPet(emptyList())
        FarmEngine(farm).updateFarm(petId, System.currentTimeMillis())
        val first = farm.getTiles(petId).mapNotNull { it.obstacle }.sorted()
        assertEquals(listOf(FarmObstacle.STUMP, FarmObstacle.ROCK).sorted(), first)
        FarmEngine(farm).updateFarm(petId, System.currentTimeMillis())
        assertEquals(first, farm.getTiles(petId).mapNotNull { it.obstacle }.sorted())
        assertNotNull(farm.getUpgrade(petId, FARM_OBSTACLE_STATE_ID))
    }

    @Test fun obstacleSurvivesTheTransferBundleAndOldBundlesImportClear() {
        val entity = com.example.llamadroid.tama.db.FarmTileEntity(0, petId, "SOIL", null, null, "ROCK")
        assertEquals("ROCK", TamaTransferFarmTile.fromEntity(entity).toEntity().obstacle)
        val legacy = kotlinx.serialization.json.Json.decodeFromString<TamaTransferFarmTile>(
            """{"id":1,"petId":"p","status":"SOIL"}""")
        assertNull(legacy.obstacle)
        assertNull(legacy.toEntity().obstacle)
    }

    private suspend fun seedPet(inventory: List<InventoryItem>, money: Long = 0L) {
        database.tamaDao().savePet(PetMapper.toEntity(TamaPet(id = petId, name = "Pixel",
            stage = GrowthStage.BABY, inventory = inventory, money = money)))
        engine.reloadPersistedPet()
    }

    private suspend fun saveTile(tile: FarmTile) = farm.saveTile(petId, tile,
        rescheduleNotifications = false, syncDroneWatchers = false)
    private suspend fun tile(id: Int) = farm.getTiles(petId).first { it.id == id }
    private suspend fun savedPet() = PetMapper.toDomain(requireNotNull(database.tamaDao().getPet(petId)))
}
