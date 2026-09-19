package com.example.llamadroid.tama.world.runtime

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import com.example.llamadroid.tama.world.core.ActionId
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

/** Direct plot controls never require a route and cannot partially spend inventory. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ClassicFarmActionsTest {
    private lateinit var context: Context
    private lateinit var database: TamaDatabase
    private lateinit var farm: FarmRepository
    private lateinit var engine: TamaGameEngine
    private val petId = "classic-farm"

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

    @Test fun tillPlantAndWaterAreImmediateWithoutCreatingAWorld() = runBlocking {
        seedPet(listOf(
            InventoryItem("hoe", "Hoe", ItemType.TOOL, durability = 10, maxDurability = 10),
            InventoryItem("watering_can", "Can", ItemType.TOOL, durability = 10, maxDurability = 10),
            InventoryItem("water", "Water", ItemType.MATERIAL, quantity = 2),
            InventoryItem("seed_carrot", "Carrot", ItemType.SEED)
        ))
        saveTile(FarmTile(0))
        WorldFarmActions.request(context, engine, 0, ActionId.TILL_SOIL)
        assertEquals(TileStatus.FARMLAND, tile().status)
        assertEquals(9, savedPet().inventory.first { it.id == "hoe" }.durability)
        WorldFarmActions.request(context, engine, 0, ActionId.PLANT, mapOf("cropId" to "carrot"))
        assertEquals("carrot", tile().crop?.type)
        assertFalse(savedPet().inventory.any { it.id == "seed_carrot" })
        WorldFarmActions.request(context, engine, 0, ActionId.WATER)
        assertEquals(TileStatus.WET_FARMLAND, tile().status)
        assertEquals(1, savedPet().inventory.first { it.id == "water" }.quantity)
        assertEquals(9, savedPet().inventory.first { it.id == "watering_can" }.durability)
        assertNull(database.worldDao().worldForPet(petId))
        assertFalse(engine.isSimulatedWorldActive)
    }

    @Test fun failedWateringRollsBackTheWaterAndTile() = runBlocking {
        seedPet(listOf(InventoryItem("water", "Water", ItemType.MATERIAL, quantity = 2)))
        saveTile(FarmTile(0, TileStatus.FARMLAND))
        WorldFarmActions.request(context, engine, 0, ActionId.WATER)
        assertEquals(TileStatus.FARMLAND, tile().status)
        assertEquals(2, savedPet().inventory.single().quantity)
        assertEquals(savedPet().inventory, engine.pet.value?.inventory)
        assertNull(database.worldDao().worldForPet(petId))
    }

    @Test fun repeatingHarvestCannotPayTwice() = runBlocking {
        seedPet(emptyList())
        saveTile(FarmTile(0, TileStatus.WET_FARMLAND,
            PlantedCrop("carrot", stage = 3, plantedTime = 10L, lastStageUpdateTime = 20L)))
        WorldFarmActions.request(context, engine, 0, ActionId.HARVEST_CROP)
        val first = savedPet().inventory.first { it.id == "crop_carrot" }.quantity
        assertTrue(first > 0)
        WorldFarmActions.request(context, engine, 0, ActionId.HARVEST_CROP)
        assertEquals(first, savedPet().inventory.first { it.id == "crop_carrot" }.quantity)
        assertNull(tile().crop)
        assertEquals(TileStatus.SOIL, tile().status)
    }

    @Test fun frozenPetCannotMutateAPlotOrSpendTools() = runBlocking {
        seedPet(listOf(InventoryItem("hoe", "Hoe", ItemType.TOOL, durability = 10)), frozen = true)
        saveTile(FarmTile(0))
        WorldFarmActions.request(context, engine, 0, ActionId.TILL_SOIL)
        assertEquals(TileStatus.SOIL, tile().status)
        assertEquals(10, savedPet().inventory.single().durability)
    }

    @Test fun classicCareAndShoppingCompleteWithoutWorldTravel() = runBlocking {
        seedPet(emptyList())
        database.tamaDao().savePet(PetMapper.toEntity(savedPet().copy(
            money = 1000,
            stats = PetStats(hunger = 20f, hygiene = 20f, happiness = 20f)
        )))
        assertTrue(engine.feed().success)
        assertTrue(engine.clean().success)
        assertTrue(engine.play().success)
        assertTrue(savedPet().stats.hunger > 20f)
        assertTrue(savedPet().stats.hygiene > 20f)
        assertTrue(savedPet().stats.happiness > 20f)
        val offer = requireNotNull(TamaCommerceCatalog.offer(context, "water",
            com.example.llamadroid.tama.world.core.LegacyLocationAliases.SHOP))
        val before = savedPet().money
        assertTrue(engine.buyItem(offer.item, 2, offer.price).success)
        assertEquals(before - 2L * offer.price, savedPet().money)
        assertEquals(2, savedPet().inventory.first { it.id == "water" }.quantity)
        assertNull(database.worldDao().worldForPet(petId))
        assertFalse(engine.isSimulatedWorldActive)
    }

    @Test fun classicFarmUpgradeChargesOnceAndNeedsNoWorld() = runBlocking {
        seedPet(emptyList())
        database.tamaDao().savePet(PetMapper.toEntity(savedPet().copy(money = 1000)))
        val first = com.example.llamadroid.tama.game.WorldFarmMaintenance.request(
            context, engine, "buy_upgrade", mapOf("type" to "well"))
        assertTrue(first.success)
        val remaining = savedPet().money
        assertTrue(remaining < 1000)
        assertTrue(farm.getUpgrade(petId, "well")?.isPurchased == true)
        val repeated = com.example.llamadroid.tama.game.WorldFarmMaintenance.request(
            context, engine, "buy_upgrade", mapOf("type" to "well"))
        assertFalse(repeated.success)
        assertEquals(remaining, savedPet().money)
        assertNull(database.worldDao().worldForPet(petId))
    }

    private suspend fun seedPet(inventory: List<InventoryItem>, frozen: Boolean = false) {
        database.tamaDao().savePet(PetMapper.toEntity(TamaPet(id = petId, name = "Pixel",
            stage = GrowthStage.BABY, inventory = inventory, cycleFrozen = frozen)))
        engine.reloadPersistedPet()
    }

    private suspend fun saveTile(tile: FarmTile) = farm.saveTile(petId, tile,
        rescheduleNotifications = false, syncDroneWatchers = false)
    private suspend fun tile() = farm.getTiles(petId).first { it.id == 0 }
    private suspend fun savedPet() = PetMapper.toDomain(requireNotNull(database.tamaDao().getPet(petId)))
}
