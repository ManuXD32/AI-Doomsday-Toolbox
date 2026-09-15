package com.example.llamadroid.tama.world.runtime

import android.app.Application
import androidx.room.Room
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.data.GrowthStage
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
import com.example.llamadroid.tama.world.core.WorldCommand
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptStatus
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptKind
import com.example.llamadroid.tama.world.presentation.ArcadeReceiptStatus
import com.example.llamadroid.tama.world.presentation.ArcadeSessionRecoveryStatus
import com.example.llamadroid.tama.world.presentation.ArcadeSessionRequest
import com.example.llamadroid.tama.world.presentation.ArcadeSessionLeaseStatus
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the controller and Room receipt boundary, rather than only DAO serialization. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TamaArcadeControllerIntegrationTest {
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
    fun beginReloadSubmitReplayPaysOnceAndRecoversCanonicalLease() = runBlocking {
        val pet = startAtArcade(TamaPet(
            id = "arcade-controller-replay",
            name = "Pixel",
            stage = GrowthStage.BABY,
            money = 3L
        ))
        val identity = request(pet, "arcade-replay-1")
        assertEquals(ArcadeSessionLeaseStatus.RUNNING, engine.world.beginArcadeSession(identity).status)
        assertEquals(TamaWorldActionReceiptStatus.RUNNING, row(pet.id, identity.sessionId).status)

        engine.world.invalidate()
        val recovered = engine.world.reconcileArcadeSession(pet.id)
        assertNotNull(recovered)
        assertEquals(ArcadeSessionLeaseStatus.RECONCILE_REQUIRED, recovered!!.status)

        val terminal = identity.copy(score = 5, catches = 5, misses = 3, totalObjects = 8)
        assertEquals(ArcadeReceiptStatus.SUCCEEDED, engine.world.submitArcadeSession(terminal).status)
        assertEquals(13L, PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).money)
        assertEquals(ArcadeReceiptStatus.SUCCEEDED, engine.world.submitArcadeSession(terminal).status)
        assertEquals(13L, PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).money)
        assertNull(engine.world.state.value!!.actor.pendingActivity)
    }

    @Test
    fun submitRollbackReloadsEngineAndKeepsRunningLeaseWithoutReward() = runBlocking {
        val pet = startAtArcade(TamaPet(
            id = "arcade-controller-rollback",
            name = "Pixel",
            stage = GrowthStage.BABY,
            money = 19L
        ))
        val identity = request(pet, "arcade-rollback-1")
        engine.world.beginArcadeSession(identity)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER arcade_test_fail_terminal
            BEFORE UPDATE OF status ON tama_world_action_receipts
            WHEN NEW.status = 'SUCCEEDED'
            BEGIN SELECT RAISE(ABORT, 'arcade_test_rollback'); END
            """.trimIndent()
        )

        val terminal = identity.copy(score = 5, catches = 5, misses = 3, totalObjects = 8)
        var failure: Throwable? = null
        try {
            engine.world.submitArcadeSession(terminal)
        } catch (error: Throwable) {
            failure = error
        }
        assertNotNull(failure)
        assertEquals(19L, PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).money)
        assertEquals(19L, engine.pet.value!!.money)
        assertEquals(TamaWorldActionReceiptStatus.RUNNING, row(pet.id, identity.sessionId).status)
        assertEquals(identity.sessionId, engine.world.state.value!!.actor.pendingActivity!!.arguments["arcadeSessionId"])
        assertEquals(ActionId.USE_ARCADE, engine.world.state.value!!.actor.action)
        assertEquals(ActionState.RUNNING, engine.world.state.value!!.actor.actionState)
    }

    @Test
    fun cancelOldLeaseBeforeNewSessionPreventsTwoActiveArcades() = runBlocking {
        val pet = startAtArcade(TamaPet(
            id = "arcade-controller-restart",
            name = "Pixel",
            stage = GrowthStage.BABY
        ))
        val first = request(pet, "arcade-restart-1")
        val second = request(pet, "arcade-restart-2")
        assertEquals(ArcadeSessionLeaseStatus.RUNNING, engine.world.beginArcadeSession(first).status)
        assertEquals(ArcadeSessionLeaseStatus.UNAVAILABLE, engine.world.beginArcadeSession(second).status)
        assertEquals(ArcadeReceiptStatus.REJECTED, engine.world.cancelArcadeSession(first).status)
        assertEquals(TamaWorldActionReceiptStatus.REJECTED, row(pet.id, first.sessionId).status)
        assertEquals(ArcadeSessionLeaseStatus.RUNNING, engine.world.beginArcadeSession(second).status)
        assertEquals(1, database.worldActionReceiptDao().active(pet.id).count {
            it.kind == TamaWorldActionReceiptKind.ARCADE_SESSION
        })
    }

    @Test
    fun unknownAndPrunedReceiptsCannotPayOrReplay() = runBlocking {
        val pet = startAtArcade(TamaPet(
            id = "arcade-controller-pruned",
            name = "Pixel",
            stage = GrowthStage.BABY,
            money = 7L
        ))
        val unknown = request(pet, "arcade-unknown")
            .copy(score = 5, catches = 5, misses = 3, totalObjects = 8)
        assertEquals(ArcadeReceiptStatus.REJECTED, engine.world.submitArcadeSession(unknown).status)

        val identity = request(pet, "arcade-pruned-1")
        engine.world.beginArcadeSession(identity)
        val terminal = identity.copy(score = 5, catches = 5, misses = 3, totalObjects = 8)
        assertEquals(ArcadeReceiptStatus.SUCCEEDED, engine.world.submitArcadeSession(terminal).status)
        assertEquals(17L, PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).money)
        assertTrue(engine.world.acknowledgeArcadeSession(terminal))
        database.worldActionReceiptDao().pruneAcknowledged(pet.id, Long.MAX_VALUE)
        assertEquals(ArcadeReceiptStatus.REJECTED, engine.world.submitArcadeSession(terminal).status)
        assertEquals(17L, PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!).money)
    }

    @Test
    fun terminalRecoveryRebuildsMetricsAndAcknowledgementHidesIt() = runBlocking {
        val pet = startAtArcade(TamaPet(
            id = "arcade-controller-terminal",
            name = "Pixel",
            stage = GrowthStage.BABY
        ))
        val identity = request(pet, "arcade-terminal-1")
        engine.world.beginArcadeSession(identity)
        val terminal = identity.copy(score = 6, catches = 6, misses = 2, totalObjects = 8)
        engine.world.submitArcadeSession(terminal)

        engine.world.invalidate()
        val recovery = engine.world.recoverArcadeSession(pet.id)
        assertEquals(ArcadeSessionRecoveryStatus.TERMINAL, recovery.status)
        assertNotNull(recovery.terminal)
        assertEquals(terminal, recovery.terminal!!.request)
        assertEquals(10, recovery.terminal!!.receipt.coins)
        assertTrue(engine.world.acknowledgeArcadeSession(terminal))
        assertEquals(ArcadeSessionRecoveryStatus.NONE, engine.world.recoverArcadeSession(pet.id).status)
    }

    private suspend fun startAtArcade(pet: TamaPet): TamaPet {
        database.tamaDao().savePet(PetMapper.toEntity(pet.copy(
            currentLocationId = LegacyLocationAliases.ARCADE
        )))
        engine.reloadPersistedPet()
        assertTrue(engine.world.command(WorldCommand.Stop).acceptedCommand)
        engine.world.setAutonomy(AutonomyPolicy(level = AutonomyLevel.OFF))
        val state = requireNotNull(engine.world.state.value)
        assertEquals(PresenceMode.INTERIOR, state.actor.presence)
        assertEquals(LegacyLocationAliases.ARCADE, state.actor.structureId)
        return PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
    }

    private fun request(pet: TamaPet, id: String): ArcadeSessionRequest = ArcadeSessionRequest(
        petId = pet.id,
        sessionId = id,
        gameId = "catch",
        score = 0
    )

    private suspend fun row(petId: String, sessionId: String) =
        requireNotNull(database.worldActionReceiptDao().byId(petId, sessionId))
}
