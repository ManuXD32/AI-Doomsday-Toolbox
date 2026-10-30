package com.example.llamadroid.tama.game

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.transfer.TamaTransferFreeze
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Offline farm refresh: drones replay time in order and freezes never move the clock. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class FarmEngineUpdateTest {
    private lateinit var context: Context
    private lateinit var database: TamaDatabase
    private lateinit var farm: FarmRepository
    private lateinit var engine: FarmEngine
    private val petId = "farm-update"
    private val hour = 3_600_000L
    private val t0 = 1_000_000_000L

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, TamaDatabase::class.java)
            .allowMainThreadQueries().build()
        farm = FarmRepository(database.farmDao(), context)
        engine = FarmEngine(farm)
    }

    @After fun tearDown() {
        TamaTransferFreeze.resetForTest()
        database.close()
    }

    /** Wheat matures 6 h after t0 and would rot at 21 h; the player is away for 30 h. */
    @Test fun harvesterAwayThirtyHoursHarvestsFreshOnceAndNeverRotten() = runBlocking {
        farm.ensureUnlockedFarmTiles(petId)
        farm.saveTile(petId, FarmTile(0, TileStatus.WET_FARMLAND,
            PlantedCrop("wheat", plantedTime = t0, lastStageUpdateTime = t0)),
            rescheduleNotifications = false, syncDroneWatchers = false)
        buyDrones(harvester = harvester(fuel = 100))

        engine.updateFarm(petId, t0 + 30 * hour)

        val storage = farm.decodeHarvesterDroneState(farm.getUpgrade(petId, FARM_HARVESTING_DRONE_ID)).storage
        assertEquals(listOf(DroneStoredCrop("crop_wheat", 1)), storage)
        assertNull(tile(0).crop)
    }

    @Test fun plantingAndHarvestingDroneHarvestEachCropAtMostOnce() = runBlocking {
        farm.ensureUnlockedFarmTiles(petId)
        buyDrones(
            planter = PlantingDroneState(
                enabled = true, fuel = 50,
                hoe = DroneToolState("hoe", "Hoe", 10, 10),
                wateringCan = DroneToolState("watering_can", "Can", 10, 10),
                water = 5, seeds = listOf(DroneSeedStock("wheat", 1)), lastUpdatedAt = t0
            ),
            harvester = harvester(fuel = 100)
        )

        engine.updateFarm(petId, t0 + 30 * hour)

        val storage = farm.decodeHarvesterDroneState(farm.getUpgrade(petId, FARM_HARVESTING_DRONE_ID)).storage
        assertEquals(listOf(DroneStoredCrop("crop_wheat", 1)), storage)
        assertTrue(farm.getTiles(petId).all { it.crop == null })
    }

    @Test fun rottenCropNeverUnRotsOnLaterRefreshes() = runBlocking {
        farm.ensureUnlockedFarmTiles(petId)
        farm.saveTile(petId, FarmTile(0, TileStatus.WET_FARMLAND,
            PlantedCrop("wheat", plantedTime = t0, lastStageUpdateTime = t0)),
            rescheduleNotifications = false, syncDroneWatchers = false)
        buyDrones(harvester = harvester(fuel = 100).copy(
            mode = HarvesterDroneMode.WHITELIST, cropFilter = setOf("carrot")))

        engine.updateFarm(petId, t0 + 30 * hour)
        assertTrue(tile(0).crop?.isDecayed == true)
        engine.updateFarm(petId, t0 + 31 * hour)
        assertTrue(tile(0).crop?.isDecayed == true)
        assertEquals(3, tile(0).crop?.stage)
    }

    @Test fun cycleFrozenRefreshLeavesTheFarmUntouched() = runBlocking {
        farm.ensureUnlockedFarmTiles(petId)
        val crop = PlantedCrop("wheat", plantedTime = t0, lastStageUpdateTime = t0)
        farm.saveTile(petId, FarmTile(0, TileStatus.WET_FARMLAND, crop),
            rescheduleNotifications = false, syncDroneWatchers = false)

        engine.updateFarm(petId, t0 + 30 * hour, cycleFrozen = true)
        assertEquals(crop, tile(0).crop)

        TamaTransferFreeze.set(true)
        engine.updateFarm(petId, t0 + 30 * hour)
        assertEquals(crop, tile(0).crop)

        TamaTransferFreeze.set(false)
        engine.updateFarm(petId, t0 + 30 * hour)
        assertTrue(tile(0).crop?.isDecayed == true)
    }

    private fun harvester(fuel: Int) = HarvesterDroneState(enabled = true, fuel = fuel, lastUpdatedAt = t0)

    private suspend fun buyDrones(
        planter: PlantingDroneState? = null,
        harvester: HarvesterDroneState? = null
    ) {
        planter?.let { farm.savePlantingDroneState(petId, it, rescheduleNotifications = false) }
        harvester?.let { farm.saveHarvesterDroneState(petId, it, rescheduleNotifications = false) }
    }

    private suspend fun tile(id: Int) = farm.getTiles(petId).first { it.id == id }
}
