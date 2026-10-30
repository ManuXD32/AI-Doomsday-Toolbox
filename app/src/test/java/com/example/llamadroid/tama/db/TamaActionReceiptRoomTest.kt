package com.example.llamadroid.tama.db

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import com.example.llamadroid.tama.game.ArcadeReceiptStatus
import com.example.llamadroid.tama.game.ArcadeSessionRequest
import com.example.llamadroid.tama.game.TamaArcadeSessionRules
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
class TamaActionReceiptRoomTest {
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
        val dao = database.actionReceiptDao()
        val begin = ArcadeSessionRequest("pet-1", "arcade-1", "catch", score = 0)
        val terminal = begin.copy(score = 8, catches = 8, misses = 2, totalObjects = 10)
        val request = TamaArcadeSessionRules.receiptRequest(begin, 100L, "world-1")
        val row = TamaActionReceiptEntity(
            petId = begin.petId,
            id = begin.sessionId,
            worldId = "world-1",
            kind = TamaActionReceiptKind.ARCADE_SESSION,
            requestJson = TamaArcadeSessionRules.encodeRequest(request),
            status = TamaActionReceiptStatus.RUNNING,
            createdAt = 100L,
            updatedAt = 100L
        )
        assertTrue(dao.createOrGet(row).first)
        assertFalse(dao.createOrGet(row).first)

        val terminalRequest = TamaArcadeSessionRules.receiptRequest(terminal, 200L, "world-1", 100L)
        assertEquals(1, dao.updateActiveRequest(
            begin.petId, begin.sessionId,
            TamaArcadeSessionRules.encodeRequest(terminalRequest), 200L
        ))
        val reward = TamaArcadeSessionRules.rewardFor(terminal)
        val result = TamaActionReceiptResult(
            success = true,
            action = TamaActionReceiptKind.ARCADE_SESSION,
            completedAt = 200L,
            rewardCoins = reward.coins,
            rewardHappiness = reward.happiness
        )
        assertEquals(1, dao.completeActive(
            begin.petId,
            begin.sessionId,
            TamaActionReceiptStatus.SUCCEEDED,
            200L,
            200L,
            TamaArcadeSessionRules.encodeResult(result)
        ))
        assertEquals(0, dao.updateActiveRequest(
            begin.petId, begin.sessionId,
            TamaArcadeSessionRules.encodeRequest(request), 300L
        ))
        val committed = dao.byId(begin.petId, begin.sessionId)!!
        assertEquals(ArcadeReceiptStatus.SUCCEEDED,
            TamaArcadeSessionRules.terminalReceipt(terminal, committed).status)
        assertEquals(reward.coins.toInt(), TamaArcadeSessionRules.terminalReceipt(terminal, committed).coins)
        assertNull(dao.byId(begin.petId, "unknown"))
        assertEquals(1, dao.acknowledge(begin.petId, begin.sessionId, 400L))
        assertEquals(1, dao.pruneAcknowledged(begin.petId, 500L))
        assertNull(dao.byId(begin.petId, begin.sessionId))
    }

    @Test
    fun failedRoomTransactionRollsBackActiveReceipt() = runBlocking {
        val dao = database.actionReceiptDao()
        val request = ArcadeSessionRequest("pet-2", "arcade-2", "catch", score = 0)
        val receipt = TamaArcadeSessionRules.receiptRequest(request, 100L, "world-2")
        dao.save(TamaActionReceiptEntity(
            petId = request.petId,
            id = request.sessionId,
            worldId = "world-2",
            kind = TamaActionReceiptKind.ARCADE_SESSION,
            requestJson = TamaArcadeSessionRules.encodeRequest(receipt),
            status = TamaActionReceiptStatus.RUNNING,
            createdAt = 100L,
            updatedAt = 100L
        ))
        runCatching {
            database.withTransaction {
                dao.completeActive(
                    request.petId,
                    request.sessionId,
                    TamaActionReceiptStatus.SUCCEEDED,
                    200L,
                    200L,
                    TamaArcadeSessionRules.encodeResult(TamaActionReceiptResult(
                        success = true,
                        action = TamaActionReceiptKind.ARCADE_SESSION,
                        completedAt = 200L,
                        rewardCoins = 50L,
                        rewardHappiness = 12f
                    ))
                )
                error("forced rollback")
            }
        }
        assertEquals(TamaActionReceiptStatus.RUNNING,
            dao.byId(request.petId, request.sessionId)?.status)
    }
}
