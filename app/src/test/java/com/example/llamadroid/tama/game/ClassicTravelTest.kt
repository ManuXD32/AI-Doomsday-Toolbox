package com.example.llamadroid.tama.game

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.data.ActivityType
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.LegacyLocationAliases
import com.example.llamadroid.tama.data.PetStats
import com.example.llamadroid.tama.data.TamaPet
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Classic travel arrives immediately; stale legacy location ids normalize to home. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ClassicTravelTest {
    private lateinit var context: Context
    private lateinit var database: TamaDatabase
    private lateinit var farm: FarmRepository
    private lateinit var engine: TamaGameEngine

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, TamaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        farm = FarmRepository(database.farmDao(), context)
        mockkObject(TamaNotificationScheduler)
        coEvery { TamaNotificationScheduler.scheduleForPet(any(), any()) } just Runs
        engine = TamaGameEngine(context, database.tamaDao(), FarmEngine(farm), farm,
            mockk<SettingsRepository>(relaxed = true), database, observeAndTick = false)
    }

    @After
    fun tearDown() = runBlocking {
        engine.close().join()
        database.close()
        unmockkAll()
    }

    @Test
    fun classicTravelArrivesImmediatelyAndHomeIsFree() = runBlocking {
        val pet = TamaPet(id = "classic-travel", name = "Pixel", stage = GrowthStage.BABY,
            stats = PetStats(energy = 50f))
        seedPet(pet)

        assertTrue(engine.travelToId(LegacyLocationAliases.SHOP).success)
        val atShop = savedPet()
        assertEquals(LegacyLocationAliases.SHOP, atShop.currentLocationId)
        assertEquals(48f, atShop.stats.energy, 0f)
        assertEquals(LegacyLocationAliases.SHOP, engine.currentLocation.value?.id)

        assertTrue(engine.travelToId(LegacyLocationAliases.HOME).success)
        val atHome = savedPet()
        assertEquals(LegacyLocationAliases.HOME, atHome.currentLocationId)
        assertEquals(48f, atHome.stats.energy, 0f)
    }

    @Test
    fun staleWorldLocationNormalizesHomeWithoutClearingWorkOrSleep() = runBlocking {
        val working = TamaPet(id = "classic-stale-location", name = "Pixel", stage = GrowthStage.TEEN,
            currentLocationId = "world", currentActivity = ActivityType.WORKING,
            currentWorkJobId = "town_runner", activityStartTime = 123L)
        seedPet(working)
        val normalizedWork = savedPet()
        assertEquals(LegacyLocationAliases.HOME, normalizedWork.currentLocationId)
        assertEquals(ActivityType.WORKING, normalizedWork.currentActivity)
        assertEquals("town_runner", normalizedWork.currentWorkJobId)
        assertEquals(123L, normalizedWork.activityStartTime)

        val sleeping = working.copy(currentActivity = ActivityType.NONE, currentWorkJobId = null,
            activityStartTime = null, isSleeping = true, sleepStartTime = 456L, currentLocationId = "world")
        database.tamaDao().savePet(PetMapper.toEntity(sleeping))
        engine.reloadPersistedPet()
        val normalizedSleep = savedPet()
        assertEquals(LegacyLocationAliases.HOME, normalizedSleep.currentLocationId)
        assertTrue(normalizedSleep.isSleeping)
        assertEquals(456L, normalizedSleep.sleepStartTime)
    }

    @Test
    fun legacyLocationAliasesNormalizeBareDungeonAndUnknownIds() {
        assertEquals(LegacyLocationAliases.DUNGEON_A, LegacyLocationAliases.normalizeLocation("Dungeon"))
        assertEquals(LegacyLocationAliases.DUNGEON_B, LegacyLocationAliases.normalizeLocation("dungeon_b"))
        assertEquals(LegacyLocationAliases.SHOP, LegacyLocationAliases.normalizeLocation(" Store "))
        assertEquals(null, LegacyLocationAliases.normalizeLocation("world"))
        assertEquals(null, LegacyLocationAliases.normalizeLocation(null))
    }

    private suspend fun seedPet(pet: TamaPet) {
        database.tamaDao().savePet(PetMapper.toEntity(pet))
        engine.reloadPersistedPet()
    }

    private suspend fun savedPet(): TamaPet =
        PetMapper.toDomain(requireNotNull(database.tamaDao().getPet(requireNotNull(engine.pet.value).id)))
}
