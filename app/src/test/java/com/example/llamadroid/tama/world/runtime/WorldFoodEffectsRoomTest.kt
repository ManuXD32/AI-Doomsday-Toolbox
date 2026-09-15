package com.example.llamadroid.tama.world.runtime

import android.app.Application
import androidx.room.Room
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.data.EventType
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.InventoryItem
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.PetStats
import com.example.llamadroid.tama.data.TamaFoodCatalog
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import com.example.llamadroid.tama.world.core.AutonomyLevel
import com.example.llamadroid.tama.world.core.AutonomyPolicy
import com.example.llamadroid.tama.world.core.NeedType
import com.example.llamadroid.tama.world.core.WorldCommand
import com.example.llamadroid.tama.world.core.WorldEffectRequest
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WorldFoodEffectsRoomTest {
    private lateinit var database: TamaDatabase
    private lateinit var farm: FarmRepository
    private lateinit var engine: TamaGameEngine

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, TamaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        farm = spyk(FarmRepository(database.farmDao(), context))
        mockkObject(TamaNotificationScheduler)
        coEvery { TamaNotificationScheduler.scheduleForPet(any(), any()) } just Runs
        engine = TamaGameEngine(
            context,
            database.tamaDao(),
            FarmEngine(farm),
            farm,
            mockk<SettingsRepository>(relaxed = true),
            database,
            observeAndTick = false
        )
    }

    @After
    fun tearDown() = runBlocking {
        engine.close().join()
        database.close()
        unmockkAll()
    }

    @Test
    fun shopFoodUsesCatalogGainsAndConsumesExactlyOnceAtRoomBoundary() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val apple = requireNotNull(TamaFoodCatalog.byId("apple"))
        val pet = TamaPet(
            id = "world-food-apple",
            name = "Pixel",
            stage = GrowthStage.BABY,
            stats = PetStats(hunger = 40f, happiness = 10f, energy = 80f),
            ownerBondLevel = 50f,
            inventory = listOf(InventoryItem(apple.id, "Apple", ItemType.FOOD, quantity = 2))
        )
        start(pet)
        val state = requireNotNull(engine.world.state.value)
        val effects = listOf(
            WorldEffectRequest.NeedDelta(NeedType.HUNGER, 25f, "action:eat"),
            WorldEffectRequest.NeedDelta(NeedType.HAPPINESS, 2f, "action:eat"),
            WorldEffectRequest.NeedDelta(NeedType.ENERGY, -3f, "world:decay"),
            WorldEffectRequest.InventoryDelta(apple.id, -1, "eat")
        )

        val committed = WorldEffectCommitter(context, database, farm, engine)
            .commit(pet, state, effects)
        val stored = PetMapper.toDomain(requireNotNull(database.tamaDao().getPet(pet.id)))

        assertEquals(55f, stored.stats.hunger, 0f)
        assertEquals(15f, stored.stats.happiness, 0f)
        assertEquals(77f, stored.stats.energy, 0.01f)
        assertEquals(51f, stored.ownerBondLevel, 0f)
        assertEquals(1, stored.inventory.single { it.id == apple.id }.quantity)
        assertEquals(stored.stats, committed.stats)
        assertEquals(stored.inventory, committed.inventory)
        assertEquals(1, database.tamaDao().getAllEvents(pet.id).count { it.eventType == EventType.FED.name })
    }

    @Test
    fun freeWildAndAmbiguousEatEffectsRemainGeneric() {
        val genericNeeds = listOf(
            WorldEffectRequest.NeedDelta(NeedType.HUNGER, 25f, "action:eat"),
            WorldEffectRequest.NeedDelta(NeedType.HAPPINESS, 2f, "action:eat")
        )
        val decay = WorldEffectRequest.NeedDelta(NeedType.ENERGY, -1f, "world:decay")

        val free = genericNeeds + decay + WorldEffectRequest.InventoryDelta("lettuce", -1, "eat")
        assertEquals(free, WorldFoodEffects.normalize(free))

        val wild = genericNeeds + decay + WorldEffectRequest.InventoryDelta("berry", -1, "eat")
        assertEquals(wild, WorldFoodEffects.normalize(wild))

        val ambiguous = genericNeeds +
            WorldEffectRequest.InventoryDelta("apple", -1, "eat") +
            WorldEffectRequest.InventoryDelta("berry", -1, "eat")
        assertEquals(ambiguous, WorldFoodEffects.normalize(ambiguous))

        val normalized = WorldFoodEffects.normalize(genericNeeds + decay +
            WorldEffectRequest.InventoryDelta("apple", -1, "eat"))
        val action = normalized.filterIsInstance<WorldEffectRequest.CanonicalAction>().single()
        assertEquals("feedWithFood", action.action)
        assertEquals(
            mapOf("foodId" to "apple", "hungerGain" to "15", "happinessGain" to "5"),
            action.arguments
        )
        assertTrue(normalized.contains(decay))
        assertFalse(normalized.any { it is WorldEffectRequest.InventoryDelta && it.reason == "eat" })
        assertFalse(normalized.any { it is WorldEffectRequest.NeedDelta && it.reason == "action:eat" })
    }

    private suspend fun start(pet: TamaPet) {
        database.tamaDao().savePet(PetMapper.toEntity(pet))
        engine.reloadPersistedPet()
        assertTrue(engine.world.command(WorldCommand.Stop).acceptedCommand)
        engine.world.setAutonomy(AutonomyPolicy(level = AutonomyLevel.OFF))
    }
}
