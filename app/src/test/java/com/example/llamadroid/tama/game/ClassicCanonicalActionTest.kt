package com.example.llamadroid.tama.game

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.data.ActivityType
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.InventoryItem
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.LegacyLocationAliases
import com.example.llamadroid.tama.data.PetStats
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.TamaWorkCatalog
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Classic (immediate) behaviour of the canonical action validation, ported from the world suite. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ClassicCanonicalActionTest {
    private lateinit var context: Context
    private lateinit var database: TamaDatabase
    private lateinit var farm: FarmRepository
    private lateinit var engine: TamaGameEngine

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

    private suspend fun start(pet: TamaPet) {
        database.tamaDao().savePet(PetMapper.toEntity(pet))
        engine.reloadPersistedPet()
    }

    private suspend fun saved(id: String) = PetMapper.toDomain(requireNotNull(database.tamaDao().getPet(id)))

    @Test fun inventedOffersAndZeroPriceSalesAreRejectedWithoutRemovingItems() = runBlocking {
        val crop = InventoryItem("crop_wheat", "Wheat", ItemType.CROP, quantity = 2)
        start(TamaPet(id = "invalid-trade", name = "Pixel", stage = GrowthStage.BABY,
            currentLocationId = LegacyLocationAliases.SHOP, money = 100L, inventory = listOf(crop)))
        assertFalse(engine.sellItem(crop, 1, 0L).success)
        assertFalse(engine.sellItem(crop, 1, Long.MAX_VALUE).success)
        assertFalse(engine.buyItem(InventoryItem("water", "Water", ItemType.MATERIAL), 1, 5, "unknown_vendor").success)
        assertEquals(100L, engine.pet.value!!.money)
        assertEquals(listOf(crop), engine.pet.value!!.inventory)
    }

    @Test fun inventoryUseRejectsToolsAndFrozenCare() = runBlocking {
        val tool = InventoryItem("axe", "Axe", ItemType.TOOL, durability = 100)
        val food = InventoryItem("apple", "Apple", ItemType.FOOD, quantity = 2)
        val pet = TamaPet(id = "inventory-use", name = "Pixel", stage = GrowthStage.BABY,
            stats = PetStats(hunger = 40f), inventory = listOf(tool, food))
        start(pet)
        assertFalse(engine.useInventoryItem("axe").success)
        assertFalse(engine.useInventoryItem("apple", 2).success)
        assertFalse(engine.wakeUp().success)
        assertEquals(pet.inventory, engine.pet.value!!.inventory)
        assertTrue(engine.freezeCycle().success)
        assertFalse(engine.useInventoryItem("apple").success)
        assertTrue(engine.unfreezeCycle().success)
        assertTrue(engine.useInventoryItem("apple").success)
        assertEquals(1, engine.pet.value!!.inventory.single { it.id == "apple" }.quantity)
        assertEquals(tool, engine.pet.value!!.inventory.single { it.id == "axe" })
    }

    @Test fun sleepAndWakeAreImmediateAndRestoreEnergyOnce() = runBlocking {
        val pet = TamaPet(id = "sleep-pet", name = "Pixel", stage = GrowthStage.BABY, stats = PetStats(energy = 40f))
        start(pet)
        assertTrue(engine.goToBed().success)
        assertTrue(saved(pet.id).isSleeping)
        engine.wakeUp()
        val awake = saved(pet.id)
        assertFalse(awake.isSleeping)
        assertEquals(50f, awake.stats.energy, 0f)
        engine.wakeUp()
        assertEquals(50f, saved(pet.id).stats.energy, 0f)
    }

    @Test fun selectedWorkJobStartsImmediatelyAndFinishesWithOnePayment() = runBlocking {
        val job = TamaWorkCatalog.jobs.first()
        val pet = TamaPet(id = "working-pet", name = "Pixel", stage = GrowthStage.TEEN,
            currentLocationId = LegacyLocationAliases.WORKPLACE, money = 0L,
            educationLevel = job.requiredEducation.toFloat())
        start(pet)
        assertTrue(engine.startWork(job.id).success)
        val working = saved(pet.id)
        assertEquals(ActivityType.WORKING, working.currentActivity)
        assertEquals(job.id, working.currentWorkJobId)
        database.tamaDao().savePet(PetMapper.toEntity(working.copy(activityStartTime = System.currentTimeMillis() - 3_600_000L)))
        engine.reloadPersistedPet()
        assertEquals(job.hourlyPay.toLong(), engine.finishWork())
        assertEquals(job.hourlyPay.toLong(), engine.pet.value!!.money)
        assertEquals(ActivityType.NONE, engine.pet.value!!.currentActivity)
        assertEquals(0L, engine.finishWork())
        assertEquals(job.hourlyPay.toLong(), engine.pet.value!!.money)
    }
}
