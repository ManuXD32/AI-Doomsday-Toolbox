package com.example.llamadroid.tama.game

import android.app.Application
import androidx.room.Room
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.LegacyLocationAliases
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import com.example.llamadroid.tama.db.TamaActionReceiptKind
import com.example.llamadroid.tama.db.TamaActionReceiptStatus
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

/** Exercises the classic Arcade sessions against the Room receipt boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TamaArcadeSessionsTest {
    private lateinit var database: TamaDatabase
    private lateinit var farm: FarmRepository
    private lateinit var engine: TamaGameEngine
    private lateinit var sessions: TamaArcadeSessions
    private val extraEngines = mutableListOf<TamaGameEngine>()

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, TamaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        farm = spyk(FarmRepository(database.farmDao(), context))
        mockkObject(TamaNotificationScheduler)
        coEvery { TamaNotificationScheduler.scheduleForPet(any(), any()) } just Runs
        engine = newEngine()
        sessions = TamaArcadeSessions(engine)
    }

    private fun newEngine(): TamaGameEngine = TamaGameEngine(
        RuntimeEnvironment.getApplication(),
        database.tamaDao(),
        FarmEngine(farm),
        farm,
        mockk<SettingsRepository>(relaxed = true),
        database,
        observeAndTick = false
    )

    @After
    fun tearDown() = runBlocking {
        engine.close().join()
        extraEngines.forEach { it.close().join() }
        database.close()
        unmockkAll()
    }

    @Test
    fun beginSubmitReplayPaysOnceAndRecoversAfterRestart() = runBlocking {
        val pet = startAtArcade(TamaPet(id = "arcade-replay", name = "Pixel", stage = GrowthStage.BABY, money = 3L))
        val identity = request(pet, "arcade-replay-1")
        assertEquals(ArcadeSessionLeaseStatus.RUNNING, sessions.beginArcadeSession(identity).status)
        assertEquals(TamaActionReceiptStatus.RUNNING, row(pet.id, identity.sessionId).status)
        assertEquals("classic", row(pet.id, identity.sessionId).worldId)

        // A second engine on the same database models a process restart.
        val restarted = TamaArcadeSessions(newEngine().also { extraEngines += it })
        val recovered = restarted.reconcileArcadeSession(pet.id)
        assertNotNull(recovered)
        assertEquals(ArcadeSessionLeaseStatus.RECONCILE_REQUIRED, recovered!!.status)
        assertEquals(ArcadeSessionRecoveryStatus.ACTIVE, restarted.recoverArcadeSession(pet.id).status)

        val terminal = identity.copy(score = 5, catches = 5, misses = 3, totalObjects = 8)
        assertEquals(ArcadeReceiptStatus.SUCCEEDED, restarted.submitArcadeSession(terminal).status)
        assertEquals(13L, money(pet.id))
        assertEquals(ArcadeReceiptStatus.SUCCEEDED, restarted.submitArcadeSession(terminal).status)
        assertEquals(13L, money(pet.id))
        assertEquals(ArcadeSessionLeaseStatus.TERMINAL, restarted.beginArcadeSession(identity).status)
        assertNull(restarted.reconcileArcadeSession(pet.id))
    }

    @Test
    fun concurrentSubmitsPayExactlyOnce() = runBlocking {
        val pet = startAtArcade(TamaPet(id = "arcade-concurrent", name = "Pixel", stage = GrowthStage.BABY, money = 1L))
        val identity = request(pet, "arcade-concurrent-1")
        sessions.beginArcadeSession(identity)
        val terminal = identity.copy(score = 5, catches = 5, misses = 3, totalObjects = 8)
        val results = (1..4).map { async { sessions.submitArcadeSession(terminal) } }.awaitAll()
        assertTrue(results.all { it.status == ArcadeReceiptStatus.SUCCEEDED })
        assertEquals(11L, money(pet.id))
    }

    @Test
    fun submitRollbackReloadsEngineAndKeepsRunningLeaseWithoutReward() = runBlocking {
        val pet = startAtArcade(TamaPet(id = "arcade-rollback", name = "Pixel", stage = GrowthStage.BABY, money = 19L))
        val identity = request(pet, "arcade-rollback-1")
        sessions.beginArcadeSession(identity)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER arcade_test_fail_terminal
            BEFORE UPDATE OF status ON tama_world_action_receipts
            WHEN NEW.status = 'SUCCEEDED'
            BEGIN SELECT RAISE(ABORT, 'arcade_test_rollback'); END
            """.trimIndent()
        )
        var failure: Throwable? = null
        try {
            sessions.submitArcadeSession(identity.copy(score = 5, catches = 5, misses = 3, totalObjects = 8))
        } catch (error: Throwable) {
            failure = error
        }
        assertNotNull(failure)
        assertEquals(19L, money(pet.id))
        assertEquals(19L, engine.pet.value!!.money)
        assertEquals(TamaActionReceiptStatus.RUNNING, row(pet.id, identity.sessionId).status)
    }

    @Test
    fun cancelOldSessionBeforeNewOneAllowsOnlyOneActiveArcade() = runBlocking {
        val pet = startAtArcade(TamaPet(id = "arcade-cancel", name = "Pixel", stage = GrowthStage.BABY))
        val first = request(pet, "arcade-cancel-1")
        val second = request(pet, "arcade-cancel-2")
        assertEquals(ArcadeSessionLeaseStatus.RUNNING, sessions.beginArcadeSession(first).status)
        assertEquals(ArcadeSessionLeaseStatus.UNAVAILABLE, sessions.beginArcadeSession(second).status)
        assertEquals(ArcadeReceiptStatus.REJECTED, sessions.cancelArcadeSession(first).status)
        assertEquals(TamaActionReceiptStatus.REJECTED, row(pet.id, first.sessionId).status)
        assertEquals(ArcadeSessionLeaseStatus.RUNNING, sessions.beginArcadeSession(second).status)
        assertEquals(1, database.actionReceiptDao().active(pet.id).count {
            it.kind == TamaActionReceiptKind.ARCADE_SESSION
        })
        // A cancelled session can never pay.
        val before = money(pet.id)
        val late = first.copy(score = 5, catches = 5, misses = 3, totalObjects = 8)
        assertEquals(0, sessions.submitArcadeSession(late).coins)
        assertEquals(before, money(pet.id))
    }

    @Test
    fun expiredSessionIsRejectedAndCannotPay() = runBlocking {
        val pet = startAtArcade(TamaPet(id = "arcade-timeout", name = "Pixel", stage = GrowthStage.BABY, money = 5L))
        val identity = request(pet, "arcade-timeout-1")
        sessions.beginArcadeSession(identity)
        val dao = database.actionReceiptDao()
        val stale = System.currentTimeMillis() - TamaArcadeSessionRules.SESSION_TIMEOUT_MS - 1_000L
        dao.save(row(pet.id, identity.sessionId).copy(updatedAt = stale))

        val terminal = identity.copy(score = 5, catches = 5, misses = 3, totalObjects = 8)
        assertEquals(ArcadeReceiptStatus.REJECTED, sessions.submitArcadeSession(terminal).status)
        assertEquals(TamaActionReceiptStatus.REJECTED, row(pet.id, identity.sessionId).status)
        assertEquals(5L, money(pet.id))

        val second = request(pet, "arcade-timeout-2")
        sessions.beginArcadeSession(second)
        dao.save(row(pet.id, second.sessionId).copy(updatedAt = stale))
        assertNull(sessions.reconcileArcadeSession(pet.id))
        assertEquals(TamaActionReceiptStatus.REJECTED, row(pet.id, second.sessionId).status)
    }

    @Test
    fun beginIsUnavailableWhenThePetIsAnEggOrUnknown() = runBlocking {
        val egg = startAtArcade(TamaPet(id = "arcade-egg", name = "Egg", stage = GrowthStage.EGG))
        assertEquals(ArcadeSessionLeaseStatus.UNAVAILABLE, sessions.beginArcadeSession(request(egg, "arcade-egg-1")).status)
        assertEquals(ArcadeSessionLeaseStatus.UNAVAILABLE,
            sessions.beginArcadeSession(request(egg.copy(id = "other-pet"), "arcade-egg-2")).status)
        assertNull(database.actionReceiptDao().byId(egg.id, "arcade-egg-1"))
    }

    @Test
    fun unknownAndPrunedReceiptsCannotPayOrReplay() = runBlocking {
        val pet = startAtArcade(TamaPet(id = "arcade-pruned", name = "Pixel", stage = GrowthStage.BABY, money = 7L))
        val unknown = request(pet, "arcade-unknown").copy(score = 5, catches = 5, misses = 3, totalObjects = 8)
        assertEquals(ArcadeReceiptStatus.REJECTED, sessions.submitArcadeSession(unknown).status)

        val identity = request(pet, "arcade-pruned-1")
        sessions.beginArcadeSession(identity)
        val terminal = identity.copy(score = 5, catches = 5, misses = 3, totalObjects = 8)
        assertEquals(ArcadeReceiptStatus.SUCCEEDED, sessions.submitArcadeSession(terminal).status)
        assertEquals(17L, money(pet.id))
        assertTrue(sessions.acknowledgeArcadeSession(terminal))
        database.actionReceiptDao().pruneAcknowledged(pet.id, Long.MAX_VALUE)
        assertEquals(ArcadeReceiptStatus.REJECTED, sessions.submitArcadeSession(terminal).status)
        assertEquals(17L, money(pet.id))
    }

    @Test
    fun terminalRecoveryRebuildsMetricsAndAcknowledgementHidesIt() = runBlocking {
        val pet = startAtArcade(TamaPet(id = "arcade-terminal", name = "Pixel", stage = GrowthStage.BABY))
        val identity = request(pet, "arcade-terminal-1")
        sessions.beginArcadeSession(identity)
        val terminal = identity.copy(score = 6, catches = 6, misses = 2, totalObjects = 8)
        sessions.submitArcadeSession(terminal)

        val restarted = TamaArcadeSessions(newEngine().also { extraEngines += it })
        val recovery = restarted.recoverArcadeSession(pet.id)
        assertEquals(ArcadeSessionRecoveryStatus.TERMINAL, recovery.status)
        assertNotNull(recovery.terminal)
        assertEquals(terminal, recovery.terminal!!.request)
        assertEquals(10, recovery.terminal!!.receipt.coins)
        assertTrue(restarted.acknowledgeArcadeSession(terminal))
        assertEquals(ArcadeSessionRecoveryStatus.NONE, restarted.recoverArcadeSession(pet.id).status)
    }

    @Test
    fun bridgeDelegatesToTheSessions() = runBlocking {
        val pet = startAtArcade(TamaPet(id = "arcade-bridge", name = "Pixel", stage = GrowthStage.BABY))
        val bridge = sessions.bridge()
        val identity = request(pet, "arcade-bridge-1")
        assertEquals(ArcadeSessionLeaseStatus.RUNNING, bridge.begin(identity).status)
        assertEquals(ArcadeReceiptStatus.REJECTED, bridge.cancel(identity).status)
    }

    private suspend fun startAtArcade(pet: TamaPet): TamaPet {
        database.tamaDao().savePet(PetMapper.toEntity(pet.copy(currentLocationId = LegacyLocationAliases.ARCADE)))
        engine.reloadPersistedPet()
        return PetMapper.toDomain(database.tamaDao().getPet(pet.id)!!)
    }

    private suspend fun money(petId: String) = PetMapper.toDomain(database.tamaDao().getPet(petId)!!).money

    private fun request(pet: TamaPet, id: String) =
        ArcadeSessionRequest(petId = pet.id, sessionId = id, gameId = "catch", score = 0)

    private suspend fun row(petId: String, sessionId: String) =
        requireNotNull(database.actionReceiptDao().byId(petId, sessionId))
}
