package com.example.llamadroid.tama.world.runtime

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptEntity
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptKind
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptStatus
import com.example.llamadroid.tama.world.persistence.TamaWorldActionReceiptResult
import com.example.llamadroid.tama.world.presentation.ArcadeReceiptStatus
import com.example.llamadroid.tama.world.presentation.ArcadeSessionRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class TamaArcadeReceiptRoomTest {
    private lateinit var database: TamaDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), TamaDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun terminalReceiptIsIdempotentAndPruningRemovesOnlyAcknowledgedRow() = runBlocking {
        val dao = database.worldActionReceiptDao()
        val begin = ArcadeSessionRequest("pet-1", "arcade-1", "catch", score = 0)
        val terminal = begin.copy(score = 8, catches = 8, misses = 2, totalObjects = 10)
        val request = TamaArcadeWorldActions.receiptRequest(begin, "world-1", 100L)
        val row = TamaWorldActionReceiptEntity(
            petId = begin.petId,
            id = begin.sessionId,
            worldId = "world-1",
            kind = TamaWorldActionReceiptKind.ARCADE_SESSION,
            requestJson = TamaArcadeWorldActions.encodeRequest(request),
            status = TamaWorldActionReceiptStatus.RUNNING,
            createdAt = 100L,
            updatedAt = 100L
        )
        assertTrue(dao.createOrGet(row).first)
        assertFalse(dao.createOrGet(row).first)

        val terminalRequest = TamaArcadeWorldActions.receiptRequest(terminal, "world-1", 200L, 100L)
        assertEquals(1, dao.updateActiveRequest(
            begin.petId, begin.sessionId,
            TamaArcadeWorldActions.encodeRequest(terminalRequest), 200L
        ))
        val reward = TamaArcadeWorldActions.rewardFor(terminal)
        val result = TamaWorldActionReceiptResult(
            success = true,
            action = TamaWorldActionReceiptKind.ARCADE_SESSION,
            completedAt = 200L,
            rewardCoins = reward.coins,
            rewardHappiness = reward.happiness
        )
        assertEquals(1, dao.completeActive(
            begin.petId,
            begin.sessionId,
            TamaWorldActionReceiptStatus.SUCCEEDED,
            200L,
            200L,
            TamaArcadeWorldActions.encodeResult(result)
        ))
        assertEquals(0, dao.updateActiveRequest(
            begin.petId, begin.sessionId,
            TamaArcadeWorldActions.encodeRequest(request), 300L
        ))
        val committed = dao.byId(begin.petId, begin.sessionId)!!
        assertEquals(ArcadeReceiptStatus.SUCCEEDED,
            TamaArcadeWorldActions.terminalReceipt(terminal, committed).status)
        assertEquals(reward.coins.toInt(), TamaArcadeWorldActions.terminalReceipt(terminal, committed).coins)
        assertNull(dao.byId(begin.petId, "unknown"))
        assertEquals(1, dao.acknowledge(begin.petId, begin.sessionId, 400L))
        assertEquals(1, dao.pruneAcknowledged(begin.petId, 500L))
        assertNull(dao.byId(begin.petId, begin.sessionId))
    }

    @Test
    fun failedRoomTransactionRollsBackActiveReceipt() = runBlocking {
        val dao = database.worldActionReceiptDao()
        val request = ArcadeSessionRequest("pet-2", "arcade-2", "catch", score = 0)
        val receipt = TamaArcadeWorldActions.receiptRequest(request, "world-2", 100L)
        dao.save(TamaWorldActionReceiptEntity(
            petId = request.petId,
            id = request.sessionId,
            worldId = "world-2",
            kind = TamaWorldActionReceiptKind.ARCADE_SESSION,
            requestJson = TamaArcadeWorldActions.encodeRequest(receipt),
            status = TamaWorldActionReceiptStatus.RUNNING,
            createdAt = 100L,
            updatedAt = 100L
        ))
        runCatching {
            database.withTransaction {
                dao.completeActive(
                    request.petId,
                    request.sessionId,
                    TamaWorldActionReceiptStatus.SUCCEEDED,
                    200L,
                    200L,
                    TamaArcadeWorldActions.encodeResult(TamaWorldActionReceiptResult(
                        success = true,
                        action = TamaWorldActionReceiptKind.ARCADE_SESSION,
                        completedAt = 200L,
                        rewardCoins = 50L,
                        rewardHappiness = 12f
                    ))
                )
                error("forced rollback")
            }
        }
        assertEquals(TamaWorldActionReceiptStatus.RUNNING,
            dao.byId(request.petId, request.sessionId)?.status)
    }
}
