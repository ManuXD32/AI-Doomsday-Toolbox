package com.example.llamadroid.tama.world.runtime

import android.app.Application
import androidx.room.Room
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.data.EventType
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.PetStats
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.TamaPotionCatalog
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.PetMapper
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import com.example.llamadroid.tama.world.core.ActionId
import com.example.llamadroid.tama.world.core.ActionState
import com.example.llamadroid.tama.world.core.ActorType
import com.example.llamadroid.tama.world.core.AutonomyLevel
import com.example.llamadroid.tama.world.core.AutonomyPolicy
import com.example.llamadroid.tama.world.core.GoalId
import com.example.llamadroid.tama.world.core.PresenceMode
import com.example.llamadroid.tama.world.core.RelationshipProjection
import com.example.llamadroid.tama.world.core.StructureType
import com.example.llamadroid.tama.world.core.WorldActor
import com.example.llamadroid.tama.world.core.WorldCommand
import com.example.llamadroid.tama.world.core.WorldState
import com.example.llamadroid.tama.world.persistence.TamaWorldRelationshipEntity
import com.example.llamadroid.tama.world.persistence.WorldStateStore
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WorldHospitalVisitIntegrationTest {
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
    fun visitHospitalSelectsOneAffordableDoseAndPersistsDoctorRelationship() = runBlocking {
        val pet = TamaPet(
            id = "hospital-treatment-pet",
            name = "Pixel",
            stage = GrowthStage.ADULT,
            currentLocationId = "fixed_3_0",
            money = 900L,
            stats = PetStats(health = 63f)
        )
        prepareAtClinic(pet)

        val result = engine.completeWorldCanonicalAction(WorldHospitalVisit.CANONICAL_ACTION, emptyMap())

        assertTrue(result.success)
        val treated = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
        assertEquals(100f, treated.stats.health, 0f)
        assertEquals(100L, treated.money)
        assertEquals(2, treated.relationships[WorldHospitalVisit.DEFAULT_DOCTOR_ID])
        val relationship = database.worldDao().relationships(pet.id).single()
        assertEquals(1f, relationship.familiarity, 0f)
        assertEquals(2f, relationship.friendship, 0f)
        assertEquals(1f, relationship.trust, 0f)
        assertEquals(1, relationship.sharedEventCount)
        val event = database.tamaDao().getAllEvents(pet.id).single()
        assertEquals(EventType.HEALED.name, event.eventType)
        assertEquals("fixed_3_0", event.locationId)
        assertEquals(WorldHospitalVisit.DEFAULT_DOCTOR_ID, event.npcId)
        assertTrue(event.details.contains("+37"))
    }

    @Test
    fun visitHospitalUsesTheLargestAffordableDoseWhenFullTreatmentIsOutOfReach() = runBlocking {
        val pet = TamaPet(
            id = "hospital-partial-pet",
            name = "Pixel",
            stage = GrowthStage.TEEN,
            currentLocationId = "fixed_3_0",
            money = 800L,
            stats = PetStats(health = 20f)
        )
        prepareAtClinic(pet)

        val result = engine.completeWorldCanonicalAction(WorldHospitalVisit.CANONICAL_ACTION, emptyMap())

        assertTrue(result.success)
        val treated = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
        assertEquals(60f, treated.stats.health, 0f)
        assertEquals(0L, treated.money)
    }

    @Test
    fun visitHospitalRejectsUnavailableDoctorWithoutChangingCanonicalPet() = runBlocking {
        val pet = TamaPet(
            id = "hospital-doctor-away-pet",
            name = "Pixel",
            stage = GrowthStage.ADULT,
            currentLocationId = "fixed_3_0",
            money = 900L,
            stats = PetStats(health = 63f)
        )
        prepareAtClinic(pet, doctorAtClinic = false)

        val result = engine.completeWorldCanonicalAction(WorldHospitalVisit.CANONICAL_ACTION, emptyMap())

        assertFalse(result.success)
        val unchanged = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
        assertEquals(pet.money, unchanged.money)
        assertEquals(pet.stats.health, unchanged.stats.health, 0f)
        assertTrue(database.worldDao().relationships(pet.id).isEmpty())
        assertTrue(database.tamaDao().getAllEvents(pet.id).isEmpty())
    }

    @Test
    fun autonomousVisitHospitalRequiresPurchasePermission() = runBlocking {
        val pet = TamaPet(
            id = "hospital-autonomy-pet",
            name = "Pixel",
            stage = GrowthStage.ADULT,
            currentLocationId = "fixed_3_0",
            money = 900L,
            stats = PetStats(health = 63f)
        )
        prepareAtClinic(pet)
        engine.world.setAutonomy(AutonomyPolicy(level = AutonomyLevel.SAFE, allowPurchases = false))

        val result = engine.completeWorldCanonicalAction(
            WorldHospitalVisit.CANONICAL_ACTION,
            mapOf("__worldAutonomous" to "true")
        )

        assertFalse(result.success)
        val unchanged = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
        assertEquals(pet.money, unchanged.money)
        assertEquals(pet.stats.health, unchanged.stats.health, 0f)
        assertTrue(database.worldDao().relationships(pet.id).isEmpty())
    }

    @Test
    fun relationshipWriteFailureRollsBackPaymentHealingAndEvent() = runBlocking {
        val pet = TamaPet(
            id = "hospital-rollback-pet",
            name = "Pixel",
            stage = GrowthStage.ADULT,
            currentLocationId = "fixed_3_0",
            money = 900L,
            stats = PetStats(health = 63f)
        )
        prepareAtClinic(pet)
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER hospital_relation_abort " +
                "BEFORE INSERT ON tama_world_relationships " +
                "WHEN NEW.petId = 'hospital-rollback-pet' " +
                "BEGIN SELECT RAISE(ABORT, 'hospital_relation_failure'); END"
        )

        try {
            engine.completeWorldCanonicalAction(WorldHospitalVisit.CANONICAL_ACTION, emptyMap())
            fail("hospital transaction should roll back when its relationship write fails")
        } catch (_: Exception) {
            // The controller normally catches this at its world commit boundary
            // and exposes its retry panel; this test checks Room atomicity.
        }

        engine.reloadPersistedPet()
        val unchanged = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
        assertEquals(pet.money, unchanged.money)
        assertEquals(pet.stats.health, unchanged.stats.health, 0f)
        assertTrue(database.worldDao().relationships(pet.id).isEmpty())
        assertTrue(database.tamaDao().getAllEvents(pet.id).isEmpty())
    }

    @Test
    fun controllerCompletesHospitalVisitAndReloadsEmbeddedDoctorRelationship() = runBlocking {
        val pet = TamaPet(
            id = "hospital-controller-pet",
            name = "Pixel",
            stage = GrowthStage.ADULT,
            currentLocationId = "fixed_3_0",
            money = 500L,
            stats = PetStats(health = 90f)
        )
        prepareAtClinic(pet)
        val clinic = requireNotNull(engine.world.state.value).structures.first { it.type == StructureType.HOSPITAL }
        val queued = engine.world.command(WorldCommand.PerformAction(
            action = ActionId.VISIT_HOSPITAL,
            targetId = clinic.id
        ))
        assertTrue(queued.acceptedCommand)

        repeat(8) {
            if (PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).stats.health < 100f) {
                val state = requireNotNull(engine.world.state.value)
                val currentPet = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
                engine.world.advance(currentPet, state.lastSimulatedAt + 100L)
            }
        }

        val treated = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
        assertEquals(100f, treated.stats.health, 0f)
        val expectedMoney = pet.money - requireNotNull(TamaPotionCatalog.byId(TamaPotionCatalog.HEAL_10_ID)).price
        assertEquals(expectedMoney, treated.money)
        assertNull(engine.world.error.value)
        val relationship = RelationshipProjection(1, 2, 1)
        assertEquals(relationship, WorldStateStore(database).load(pet.id)!!.npcs
            .first { it.id == WorldHospitalVisit.DEFAULT_DOCTOR_ID }.relationships[pet.id])

        engine.world.invalidate()
        assertTrue(engine.world.command(WorldCommand.Stop).acceptedCommand)
        val reloaded = requireNotNull(WorldStateStore(database).load(pet.id))
        assertEquals(relationship, reloaded.npcs
            .first { it.id == WorldHospitalVisit.DEFAULT_DOCTOR_ID }.relationships[pet.id])
        assertEquals(100f, PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).stats.health, 0f)
        assertEquals(expectedMoney, PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).money)
    }

    @Test
    fun controllerHospitalVisitAllowsCappedDoctorRelationship() = runBlocking {
        val pet = TamaPet(
            id = "hospital-capped-relationship-pet",
            name = "Pixel",
            stage = GrowthStage.ADULT,
            currentLocationId = "fixed_3_0",
            money = 900L,
            stats = PetStats(health = 63f)
        )
        val capped = RelationshipProjection(100, 100, 100)
        prepareAtClinic(pet, initialDoctorRelationship = capped)
        val clinic = requireNotNull(engine.world.state.value).structures.first { it.type == StructureType.HOSPITAL }
        assertTrue(engine.world.command(WorldCommand.PerformAction(
            action = ActionId.VISIT_HOSPITAL,
            targetId = clinic.id
        )).acceptedCommand)

        repeat(8) {
            if (PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).stats.health < 100f) {
                val state = requireNotNull(engine.world.state.value)
                val currentPet = PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
                engine.world.advance(currentPet, state.lastSimulatedAt + 100L)
            }
        }

        assertNull(engine.world.error.value)
        val row = database.worldDao().relationships(pet.id).single()
        assertEquals(100f, row.familiarity, 0f)
        assertEquals(100f, row.friendship, 0f)
        assertEquals(100f, row.trust, 0f)
        assertEquals(1, row.sharedEventCount)
        val persistedDoctor = WorldStateStore(database).load(pet.id)!!.npcs
            .first { it.id == WorldHospitalVisit.DEFAULT_DOCTOR_ID }
        assertEquals(capped, persistedDoctor.relationships[pet.id])
    }

    private suspend fun prepareAtClinic(
        pet: TamaPet,
        doctorAtClinic: Boolean = true,
        initialDoctorRelationship: RelationshipProjection? = null
    ): WorldState {
        database.tamaDao().savePet(PetMapper.toEntity(pet))
        engine.reloadPersistedPet()
        assertTrue(engine.world.command(WorldCommand.Stop).acceptedCommand)
        val current = requireNotNull(engine.world.state.value)
        val clinic = current.structures.first { it.type == StructureType.HOSPITAL }
        val now = System.currentTimeMillis()
        // Keep the fixture's live doctor at the clinic while the controller
        // reloads the snapshot; the production scheduler may move him later.
        val doctorClock = now + 60_000L
        val actor = current.actor.copy(
            actorId = pet.id,
            presence = PresenceMode.INTERIOR,
            structureId = clinic.id,
            goal = GoalId.IDLE,
            action = ActionId.WAIT,
            actionState = ActionState.IDLE,
            actionTicksRemaining = 0,
            pendingCommand = null,
            actionArguments = emptyMap(),
            pendingActivity = null
        )
        val doctor = current.npcs.first { it.id == WorldHospitalVisit.DEFAULT_DOCTOR_ID }
        val seededDoctor = initialDoctorRelationship?.let { relationship ->
            doctor.copy(relationships = doctor.relationships + (pet.id to relationship))
        } ?: doctor
        val updatedDoctor = if (!doctorAtClinic) seededDoctor.copy(
            currentGoal = GoalId.IDLE,
            currentAction = ActionId.WAIT,
            actionState = ActionState.IDLE,
            scheduleState = "off",
            execution = null,
            lastSimulatedAt = doctorClock
        ) else {
            val execution = WorldActor(
                actorId = doctor.id,
                actorType = ActorType.NPC,
                x = clinic.entrance.x,
                y = clinic.entrance.y,
                preciseX = clinic.entrance.x.toDouble(),
                preciseY = clinic.entrance.y.toDouble(),
                presence = PresenceMode.INTERIOR,
                structureId = clinic.id,
                goal = GoalId.WORK,
                action = ActionId.WORK,
                actionState = ActionState.RUNNING,
                actionTicksRemaining = 1
            )
            seededDoctor.copy(
                x = clinic.entrance.x,
                y = clinic.entrance.y,
                preciseX = clinic.entrance.x.toDouble(),
                preciseY = clinic.entrance.y.toDouble(),
                currentGoal = GoalId.WORK,
                currentAction = ActionId.WORK,
                actionState = ActionState.RUNNING,
                scheduleState = "working",
                destinationX = null,
                destinationY = null,
                path = emptyList(),
                execution = execution,
                lastSimulatedAt = doctorClock
            )
        }
        val next = current.copy(
            actor = actor,
            npcs = current.npcs.map { if (it.id == doctor.id) updatedDoctor else it },
            lastSimulatedAt = now,
            timezoneOffsetMinutes = 0
        )
        WorldStateStore(database).save(next, current)
        initialDoctorRelationship?.let { relationship ->
            database.worldDao().saveRelationships(listOf(TamaWorldRelationshipEntity(
                petId = pet.id,
                npcId = WorldHospitalVisit.DEFAULT_DOCTOR_ID,
                familiarity = relationship.familiarity.toFloat(),
                friendship = relationship.friendship.toFloat(),
                trust = relationship.trust.toFloat(),
                lastInteraction = now,
                sharedEventCount = 0
            )))
        }
        engine.world.invalidate()
        assertTrue(engine.world.command(WorldCommand.Stop).acceptedCommand)
        return requireNotNull(engine.world.state.value)
    }
}
