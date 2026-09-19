package com.example.llamadroid.tama.world.runtime

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.data.ActivityType
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.InventoryItem
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.data.PetStats
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.ActionState
import com.example.llamadroid.tama.world.core.AutonomyLevel
import com.example.llamadroid.tama.world.core.AutonomyPolicy
import com.example.llamadroid.tama.world.core.LegacyLocationAliases
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptStatus
import com.example.llamadroid.tama.world.persistence.WorldStateStore
import com.example.llamadroid.tama.world.presentation.ArcadeSessionLeaseStatus
import com.example.llamadroid.tama.world.presentation.ArcadeSessionRequest
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Regression coverage for the classic-default/session boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ClassicWorldSessionTest {
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
        engine = newEngine(observeAndTick = false)
    }

    @After
    fun tearDown() = runBlocking {
        engine.close().join()
        database.close()
        unmockkAll()
    }

    @Test
    fun inactiveSessionDoesNotAdvancePersistedWorldFromEngineBackgroundLoop() = runBlocking {
        val pet = TamaPet(
            id = "classic-clock",
            name = "Pixel",
            stage = GrowthStage.BABY
        )
        seedPet(pet)
        assertTrue(engine.enterSimulatedWorld().success)
        assertTrue(engine.exitSimulatedWorld().success)
        val before = requireNotNull(WorldStateStore(database).load(pet.id))
        val beforeTime = before.lastSimulatedAt

        engine.close().join()
        engine = newEngine(observeAndTick = true)
        engine.reloadPersistedPet()
        Thread.sleep(300L)

        assertFalse(engine.isSimulatedWorldActive)
        assertFalse(engine.world.adventureActive.value)
        val after = requireNotNull(WorldStateStore(database).load(pet.id))
        assertEquals(beforeTime, after.lastSimulatedAt)
    }

    @Test
    fun classicTravelArrivesImmediatelyAndHomeIsFree() = runBlocking {
        val pet = TamaPet(
            id = "classic-travel",
            name = "Pixel",
            stage = GrowthStage.BABY,
            stats = PetStats(energy = 50f)
        )
        seedPet(pet)

        assertTrue(engine.travelToId(LegacyLocationAliases.SHOP).success)
        val atShop = savedPet()
        assertEquals(LegacyLocationAliases.SHOP, atShop.currentLocationId)
        assertEquals(48f, atShop.stats.energy, 0f)
        assertEquals(LegacyLocationAliases.SHOP, engine.currentLocation.value?.id)
        assertNull(database.worldDao().worldForPet(pet.id))

        assertTrue(engine.travelToId(LegacyLocationAliases.HOME).success)
        val atHome = savedPet()
        assertEquals(LegacyLocationAliases.HOME, atHome.currentLocationId)
        assertEquals(48f, atHome.stats.energy, 0f)
    }

    @Test
    fun staleWorldLocationNormalizesHomeWithoutClearingWorkOrSleep() = runBlocking {
        val working = TamaPet(
            id = "classic-stale-location",
            name = "Pixel",
            stage = GrowthStage.TEEN,
            currentLocationId = "world",
            currentActivity = ActivityType.WORKING,
            currentWorkJobId = "town_runner",
            activityStartTime = 123L
        )
        seedPet(working)
        val normalizedWork = savedPet()
        assertEquals(LegacyLocationAliases.HOME, normalizedWork.currentLocationId)
        assertEquals(ActivityType.WORKING, normalizedWork.currentActivity)
        assertEquals("town_runner", normalizedWork.currentWorkJobId)
        assertEquals(123L, normalizedWork.activityStartTime)

        val sleeping = working.copy(
            currentActivity = ActivityType.NONE,
            currentWorkJobId = null,
            activityStartTime = null,
            isSleeping = true,
            sleepStartTime = 456L,
            currentLocationId = "world"
        )
        database.tamaDao().savePet(PetMapper.toEntity(sleeping))
        engine.reloadPersistedPet()
        val normalizedSleep = savedPet()
        assertEquals(LegacyLocationAliases.HOME, normalizedSleep.currentLocationId)
        assertTrue(normalizedSleep.isSleeping)
        assertEquals(456L, normalizedSleep.sleepStartTime)
    }

    @Test
    fun explicitExitCommitsHomeAndCancelsReceiptsWithoutChangingWorldOrPetEconomy() = runBlocking {
        val pet = TamaPet(
            id = "classic-exit",
            name = "Pixel",
            stage = GrowthStage.BABY,
            currentLocationId = LegacyLocationAliases.ARCADE,
            money = 321L,
            inventory = listOf(InventoryItem("water", "Water", ItemType.MATERIAL, quantity = 2))
        )
        seedPet(pet)
        assertTrue(engine.enterSimulatedWorld().success)
        val before = requireNotNull(engine.world.state.value)
        val request = ArcadeSessionRequest(pet.id, "classic-exit-session", "catch", score = 0)
        assertEquals(ArcadeSessionLeaseStatus.RUNNING, engine.world.beginArcadeSession(request).status)
        assertNotNull(requireNotNull(engine.world.state.value).actor.pendingActivity)

        assertTrue(engine.exitSimulatedWorld().success)
        assertFalse(engine.isSimulatedWorldActive)
        val after = requireNotNull(WorldStateStore(database).load(pet.id))
        val afterPet = savedPet()
        val receipt = requireNotNull(database.worldActionReceiptDao().byId(pet.id, request.sessionId))

        assertEquals(before.seed, after.seed)
        assertEquals(before.worldId, after.worldId)
        assertEquals(PresenceMode.HOME, after.actor.presence)
        assertEquals(LegacyLocationAliases.HOME, after.actor.structureId)
        assertEquals(ActionId.WAIT, after.actor.action)
        assertEquals(ActionState.IDLE, after.actor.actionState)
        assertNull(after.actor.pendingActivity)
        assertEquals(TamaWorldActionReceiptStatus.REJECTED, receipt.status)
        assertEquals(LegacyLocationAliases.HOME, afterPet.currentLocationId)
        assertEquals(pet.money, afterPet.money)
        assertEquals(pet.inventory, afterPet.inventory)
    }

    @Test
    fun reentryRebasesClockInsteadOfCatchingUpClassicIdleTime() = runBlocking {
        val pet = TamaPet(id = "classic-reentry", name = "Pixel", stage = GrowthStage.BABY)
        seedPet(pet)
        assertTrue(engine.enterSimulatedWorld().success)
        assertTrue(engine.exitSimulatedWorld().success)
        val store = WorldStateStore(database)
        val exited = requireNotNull(store.load(pet.id))
        val stale = exited.copy(lastSimulatedAt = 1L)
        store.save(stale, exited)
        engine.world.invalidate()
        val entryStartedAt = System.currentTimeMillis()

        assertTrue(engine.enterSimulatedWorld().success)
        val reentered = requireNotNull(engine.world.state.value)
        assertTrue(reentered.lastSimulatedAt >= entryStartedAt)
        assertEquals(stale.actor.coordinate, reentered.actor.coordinate)
        assertEquals(stale.actor.action, reentered.actor.action)
        assertTrue(engine.exitSimulatedWorld().success)
    }

    @Test
    fun activeRollbackRecoveryPreservesSessionOptIn() = runBlocking {
        val pet = TamaPet(id = "classic-rollback", name = "Pixel", stage = GrowthStage.BABY, money = 77L)
        seedPet(pet)
        assertTrue(engine.enterSimulatedWorld().success)
        val before = requireNotNull(engine.world.state.value)

        engine.world.recoverAfterWorldTransactionFailure()

        assertTrue(engine.isSimulatedWorldActive)
        assertTrue(engine.world.adventureActive.value)
        val recovered = requireNotNull(WorldStateStore(database).load(pet.id))
        assertEquals(before.worldId, recovered.worldId)
        assertEquals(before.seed, recovered.seed)
        assertEquals(77L, savedPet().money)
        assertTrue(engine.exitSimulatedWorld().success)
    }

    @Test
    fun brainPresentationReadsSavedAutonomyWithoutHydratingOrActivatingWorld() = runBlocking {
        val pet = TamaPet(id = "classic-brain-presentation", name = "Pixel", stage = GrowthStage.BABY)
        seedPet(pet)
        assertNull(engine.world.loadSavedAutonomyForPresentation(pet.id))
        assertNull(database.worldDao().worldForPet(pet.id))
        assertNull(engine.world.state.value)
        assertFalse(engine.isSimulatedWorldActive)

        val requested = AutonomyPolicy(level = AutonomyLevel.SAFE, allowWork = true)
        engine.world.setAutonomy(requested)
        assertFalse(engine.isSimulatedWorldActive)
        val persisted = requireNotNull(WorldStateStore(database).load(pet.id))
        assertEquals(requested, persisted.autonomy)
        val savedTick = persisted.lastSimulatedAt

        engine.world.invalidate()
        assertNull(engine.world.state.value)
        assertEquals(requested, engine.world.loadSavedAutonomyForPresentation(pet.id))
        assertNull(engine.world.state.value)
        assertFalse(engine.isSimulatedWorldActive)
        assertEquals(savedTick, requireNotNull(WorldStateStore(database).load(pet.id)).lastSimulatedAt)
    }

    @Test
    fun classicArcadeBeginReplayRetainsDurableLease() = runBlocking {
        val pet = TamaPet(id = "classic-arcade", name = "Pixel", stage = GrowthStage.BABY)
        seedPet(pet)
        assertTrue(engine.travelToId(LegacyLocationAliases.ARCADE).success)
        val request = ArcadeSessionRequest(pet.id, "classic-arcade-session", "catch", score = 0)

        assertEquals(ArcadeSessionLeaseStatus.RUNNING, engine.world.beginArcadeSession(request).status)
        assertFalse(engine.isSimulatedWorldActive)
        assertEquals(request.sessionId, TamaArcadeWorldActions.leaseSessionId(requireNotNull(engine.world.state.value)))

        val beforeRetry = savedPet()
        assertTrue(engine.travelToId(LegacyLocationAliases.ARCADE).success)
        val afterRetry = savedPet()
        assertEquals(beforeRetry.stats.energy, afterRetry.stats.energy, 0f)
        assertEquals(beforeRetry.currentParkEncounter, afterRetry.currentParkEncounter)

        assertEquals(ArcadeSessionLeaseStatus.RUNNING, engine.world.beginArcadeSession(request).status)
        assertEquals(request.sessionId, TamaArcadeWorldActions.leaseSessionId(requireNotNull(engine.world.state.value)))

        engine.world.invalidate()
        assertEquals(ArcadeSessionLeaseStatus.RUNNING, engine.world.beginArcadeSession(request).status)
        assertEquals(request.sessionId, TamaArcadeWorldActions.leaseSessionId(requireNotNull(engine.world.state.value)))
    }

    private fun newEngine(observeAndTick: Boolean): TamaGameEngine = TamaGameEngine(
        context,
        database.tamaDao(),
        FarmEngine(farm),
        farm,
        mockk<SettingsRepository>(relaxed = true),
        database,
        observeAndTick = observeAndTick
    )

    private suspend fun seedPet(pet: TamaPet) {
        database.tamaDao().savePet(PetMapper.toEntity(pet))
        engine.reloadPersistedPet()
    }

    private suspend fun savedPet(): TamaPet =
        PetMapper.toDomain(requireNotNull(database.tamaDao().getPet(requireNotNull(engine.pet.value).id)))
}
