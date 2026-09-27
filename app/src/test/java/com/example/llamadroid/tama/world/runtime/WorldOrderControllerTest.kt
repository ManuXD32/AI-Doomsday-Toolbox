package com.example.llamadroid.tama.world.runtime

import android.app.Application
import androidx.room.Room
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.data.ActivityType
import com.example.llamadroid.tama.data.EventType
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.PetStats
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.AutonomyLevel
import com.example.llamadroid.tama.world.core.AutonomyPolicy
import com.example.llamadroid.tama.world.core.LegacyLocationAliases
import com.example.llamadroid.tama.world.core.NeedType
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.core.WorldCommand
import com.example.llamadroid.tama.world.core.WorldControlMode
import com.example.llamadroid.tama.world.core.WorldOrderBlocker
import com.example.llamadroid.tama.world.core.WorldOrderStatus
import com.example.llamadroid.tama.world.persistence.WorldStateStore
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Tests the Room/canonical gameplay boundary, not just the pure actor animation. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WorldOrderControllerTest {
    private lateinit var database: TamaDatabase
    private lateinit var engine: TamaGameEngine

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, TamaDatabase::class.java)
            .allowMainThreadQueries().build()
        val farm = FarmRepository(database.farmDao(), context)
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
    fun rejectedReplacementKeepsTheQueuedCanonicalActivityAndLocation() = runBlocking {
        start(TamaPet(id = "ordered-work", name = "Pixel", stage = GrowthStage.TEEN))
        assertTrue(engine.startWork("town_runner").success)
        val before = requireNotNull(engine.world.state.value)
        assertNotNull(before.actor.pendingActivity)
        val petBefore = savedPet()

        val rejected = engine.world.command(WorldCommand.GoTo(-100, -100))

        assertFalse(rejected.acceptedCommand)
        assertEquals(before.actor, rejected.state.actor)
        assertEquals(before.actor, requireNotNull(engine.world.state.value).actor)
        assertEquals(before.actor, requireNotNull(WorldStateStore(database).load(before.petId)).actor.copy(
            needs = before.actor.needs
        ))
        assertEquals(petBefore, savedPet())
    }

    @Test
    fun rejectingOrStoppingAQueuedArrivalDoesNotStartItsActivityDuringFlush() = runBlocking {
        start(TamaPet(id = "pending-arrival", name = "Pixel", stage = GrowthStage.TEEN))
        assertTrue(engine.startWork("town_runner").success)
        val queued = requireNotNull(engine.world.state.value)
        val intent = requireNotNull(queued.actor.pendingActivity)
        val destination = queued.structures.first { it.id == intent.destinationId }
        val arrival = queued.copy(actor = queued.actor.copy(
            x = destination.entrance.x, y = destination.entrance.y,
            preciseX = destination.entrance.x.toDouble(), preciseY = destination.entrance.y.toDouble(),
            presence = PresenceMode.INTERIOR, structureId = destination.id,
            action = ActionId.WAIT, path = emptyList(), actionTicksRemaining = 0,
            controlMode = WorldControlMode.HOLDING
        ))
        WorldStateStore(database).save(arrival, queued)
        engine.world.invalidate(resetAdventureSession = false)
        assertTrue(engine.enterSimulatedWorld().success)
        val eventsBefore = workEvents(queued.petId)

        val rejected = engine.world.command(WorldCommand.GoTo(-100, -100))

        assertFalse(rejected.acceptedCommand)
        assertEquals(intent, requireNotNull(engine.world.state.value).actor.pendingActivity)
        assertEquals(ActivityType.NONE, savedPet().currentActivity)
        assertEquals(eventsBefore, workEvents(queued.petId))

        assertTrue(engine.world.command(WorldCommand.Stop).acceptedCommand)
        assertNull(requireNotNull(engine.world.state.value).actor.pendingActivity)
        assertEquals(ActivityType.NONE, savedPet().currentActivity)
        assertEquals(eventsBefore, workEvents(queued.petId))
    }

    @Test
    fun interruptedQueuedArrivalCannotStartBeforeExplicitRetry() = runBlocking {
        start(TamaPet(id = "interrupted-arrival", name = "Pixel", stage = GrowthStage.TEEN))
        assertTrue(engine.startWork("town_runner").success)
        val queued = requireNotNull(engine.world.state.value)
        val intent = requireNotNull(queued.actor.pendingActivity)
        val destination = queued.structures.first { it.id == intent.destinationId }
        val arrival = queued.copy(actor = queued.actor.copy(
            x = destination.entrance.x, y = destination.entrance.y,
            preciseX = destination.entrance.x.toDouble(), preciseY = destination.entrance.y.toDouble(),
            presence = PresenceMode.INTERIOR, structureId = destination.id,
            action = ActionId.WAIT, path = emptyList(), actionTicksRemaining = 0,
            controlMode = WorldControlMode.ORDER_ACTIVE
        ))
        WorldStateStore(database).save(arrival, queued)
        engine.world.invalidate()
        assertTrue(engine.enterSimulatedWorld().success)
        assertEquals(WorldOrderBlocker.SESSION_INTERRUPTED,
            requireNotNull(engine.world.state.value).actor.orderBlocker)
        val eventsBefore = workEvents(queued.petId)

        repeat(3) { advance() }

        assertEquals(ActivityType.NONE, savedPet().currentActivity)
        assertEquals(intent, requireNotNull(engine.world.state.value).actor.pendingActivity)
        assertEquals(eventsBefore, workEvents(queued.petId))
        assertTrue(engine.world.retry().acceptedCommand)
        repeat(5) { advance() }
        assertEquals(ActivityType.WORKING, savedPet().currentActivity)
        assertNull(requireNotNull(engine.world.state.value).actor.pendingActivity)
    }

    @Test
    fun stopSettlesCanonicalWorkOnceAndHoldsPosition() = runBlocking {
        val pet = TamaPet(id = "stop-work", name = "Pixel", stage = GrowthStage.TEEN,
            currentLocationId = LegacyLocationAliases.WORKPLACE,
            currentActivity = ActivityType.WORKING,
            currentWorkJobId = "town_runner",
            activityStartTime = System.currentTimeMillis() - 3_600_000L,
            money = 100L)
        start(pet)
        engine.world.setAutonomy(AutonomyPolicy(level = AutonomyLevel.FULL))

        assertTrue(engine.world.command(WorldCommand.Stop).acceptedCommand)

        val settled = savedPet()
        val stopped = requireNotNull(engine.world.state.value).actor
        assertEquals(ActivityType.NONE, settled.currentActivity)
        assertNull(settled.activityStartTime)
        assertTrue(settled.money > pet.money)
        assertEquals(ActionId.WAIT, stopped.action)
        assertTrue(stopped.path.isEmpty())
        repeat(3) { advance() }
        assertEquals(stopped.coordinate, requireNotNull(engine.world.state.value).actor.coordinate)
        assertEquals(ActionId.WAIT, requireNotNull(engine.world.state.value).actor.action)
        assertEquals(settled.money, savedPet().money)
        assertTrue(engine.world.command(WorldCommand.Stop).acceptedCommand)
        assertEquals(settled.money, savedPet().money)
    }

    @Test
    fun classicStopControlSettlesWorldWorkOnce() = runBlocking {
        val pet = TamaPet(id = "classic-stop-work", name = "Pixel", stage = GrowthStage.TEEN,
            currentLocationId = LegacyLocationAliases.WORKPLACE,
            currentActivity = ActivityType.WORKING,
            currentWorkJobId = "town_runner",
            activityStartTime = System.currentTimeMillis() - 3_600_000L,
            money = 100L)
        start(pet)

        assertTrue(engine.stopActivity().success)

        val stopped = savedPet()
        assertEquals(ActivityType.NONE, stopped.currentActivity)
        assertTrue(stopped.money > pet.money)
        assertEquals(WorldControlMode.HOLDING, requireNotNull(engine.world.state.value).actor.controlMode)
        assertEquals(ActionId.WAIT, requireNotNull(engine.world.state.value).actor.action)
        repeat(3) { advance() }
        engine.stopActivity()
        assertEquals(stopped.money, savedPet().money)
        assertEquals(ActivityType.NONE, savedPet().currentActivity)
    }

    @Test
    fun stopWakesThroughCanonicalSleepLogic() = runBlocking {
        val pet = TamaPet(id = "stop-sleep", name = "Pixel", stage = GrowthStage.CHILD,
            isSleeping = true, sleepStartTime = System.currentTimeMillis() - 60_000L,
            stats = PetStats(energy = 40f))
        start(pet)

        assertTrue(engine.world.command(WorldCommand.Stop).acceptedCommand)

        val awake = savedPet()
        assertFalse(awake.isSleeping)
        assertNull(awake.sleepStartTime)
        assertTrue(awake.stats.energy > pet.stats.energy)
        val energy = awake.stats.energy
        assertTrue(engine.world.command(WorldCommand.Stop).acceptedCommand)
        assertEquals(energy, savedPet().stats.energy, 0f)
        assertEquals(ActionId.WAIT, requireNotNull(engine.world.state.value).actor.action)
    }

    @Test
    fun rejectedOrderFromAnInteriorDoesNotMoveTheCanonicalPetOutside() = runBlocking {
        start(TamaPet(id = "invalid-exit", name = "Pixel", stage = GrowthStage.CHILD))
        val before = requireNotNull(engine.world.state.value)
        val petBefore = savedPet()

        val rejected = engine.world.command(WorldCommand.GoTo(before.width + 50, before.height + 50))

        assertFalse(rejected.acceptedCommand)
        assertTrue(rejected.effects.isEmpty())
        assertEquals(before.actor, requireNotNull(engine.world.state.value).actor)
        assertEquals(petBefore.currentLocationId, savedPet().currentLocationId)
        assertEquals(petBefore.stats.energy, savedPet().stats.energy, 0f)
    }

    @Test
    fun coldWorldReentryRequiresExplicitRetryOfTheInterruptedOrder() = runBlocking {
        start(TamaPet(id = "interrupted-route", name = "Pixel", stage = GrowthStage.CHILD))
        assertTrue(engine.world.command(WorldCommand.GoToStructure(LegacyLocationAliases.SHOP)).acceptedCommand)
        val ordered = requireNotNull(engine.world.state.value)
        assertEquals(WorldControlMode.ORDER_ACTIVE, ordered.actor.controlMode)

        engine.world.invalidate()
        assertFalse(engine.isSimulatedWorldActive)
        assertTrue(engine.enterSimulatedWorld().success)

        val interrupted = requireNotNull(engine.world.state.value)
        assertEquals(WorldOrderStatus.BLOCKED, interrupted.actor.orderStatus)
        assertEquals(WorldOrderBlocker.SESSION_INTERRUPTED, interrupted.actor.orderBlocker)
        assertEquals(ordered.actor.explicitOrderId, interrupted.actor.explicitOrderId)
        advance()
        assertEquals(interrupted.actor.coordinate, requireNotNull(engine.world.state.value).actor.coordinate)
        assertTrue(engine.world.retry().acceptedCommand)
        assertEquals(WorldControlMode.ORDER_ACTIVE, requireNotNull(engine.world.state.value).actor.controlMode)
    }

    @Test
    fun failedCareCommitRetriesTheSameOrderWithoutDuplicatingItsReward() = runBlocking {
        start(TamaPet(id = "retry-care", name = "Pixel", stage = GrowthStage.CHILD,
            stats = PetStats(hunger = 40f)))
        assertTrue(engine.feed().success)
        val orderId = requireNotNull(engine.world.state.value).actor.explicitOrderId
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER care_event_abort BEFORE INSERT ON tama_events " +
                "WHEN NEW.petId = 'retry-care' AND NEW.eventType = 'FED' " +
                "BEGIN SELECT RAISE(ABORT, 'care_commit_failure'); END"
        )

        repeat(5) { advance() }

        val failed = requireNotNull(engine.world.state.value).actor
        assertEquals(WorldOrderStatus.BLOCKED, failed.orderStatus)
        assertEquals(WorldOrderBlocker.EFFECT_FAILED, failed.orderBlocker)
        assertEquals(40f, savedPet().stats.hunger, 0f)
        assertNull(failed.explicitOrder?.outcome?.needDeltas?.get(NeedType.HUNGER))

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER care_event_abort")
        assertTrue(engine.world.retry().acceptedCommand)
        repeat(5) { advance() }

        val completed = requireNotNull(engine.world.state.value).actor
        assertEquals(orderId, completed.explicitOrderId)
        assertEquals(WorldOrderStatus.COMPLETED, completed.orderStatus)
        assertEquals(70f, savedPet().stats.hunger, 0f)
        assertEquals(30f, requireNotNull(completed.explicitOrder?.outcome)
            .needDeltas.getValue(NeedType.HUNGER), 0.001f)
        val events = database.tamaDao().getAllEvents("retry-care").filter { it.eventType == EventType.FED.name }
        repeat(3) { advance() }
        assertEquals(events, database.tamaDao().getAllEvents("retry-care").filter { it.eventType == EventType.FED.name })
        assertEquals(70f, savedPet().stats.hunger, 0f)
    }

    @Test
    fun careOutcomeOnlyAppearsAfterCanonicalCommitAndDoesNotRepeat() = runBlocking {
        start(TamaPet(id = "care-outcome", name = "Pixel", stage = GrowthStage.CHILD,
            stats = PetStats(hunger = 40f)))
        assertTrue(engine.feed().success)
        val started = requireNotNull(engine.world.state.value).actor
        assertNotEquals(WorldOrderStatus.COMPLETED, started.orderStatus)
        assertEquals(40f, savedPet().stats.hunger, 0f)

        repeat(5) { advance() }

        val completed = requireNotNull(engine.world.state.value).actor
        assertEquals(WorldOrderStatus.COMPLETED, completed.orderStatus)
        assertEquals(WorldControlMode.HOLDING, completed.controlMode)
        val delta = requireNotNull(completed.explicitOrder?.outcome).needDeltas[NeedType.HUNGER]
        assertEquals(30f, requireNotNull(delta), 0.001f)
        assertEquals(70f, savedPet().stats.hunger, 0f)
        repeat(3) { advance() }
        assertEquals(30f, requireNotNull(engine.world.state.value?.actor?.explicitOrder?.outcome)
            .needDeltas.getValue(NeedType.HUNGER), 0.001f)
        assertEquals(70f, savedPet().stats.hunger, 0f)
    }

    private suspend fun start(pet: TamaPet) {
        database.tamaDao().savePet(PetMapper.toEntity(pet))
        engine.reloadPersistedPet()
        assertTrue(engine.enterSimulatedWorld().success)
    }

    private suspend fun workEvents(petId: String) = database.tamaDao().getAllEvents(petId)
        .filter { it.eventType == EventType.STARTED_WORK.name }

    private suspend fun savedPet(): TamaPet = PetMapper.toDomain(
        requireNotNull(database.tamaDao().getPet(requireNotNull(engine.pet.value).id))
    )

    private suspend fun advance() {
        val state = requireNotNull(engine.world.state.value)
        engine.world.advance(savedPet(), state.lastSimulatedAt + 2_000L)
    }
}
