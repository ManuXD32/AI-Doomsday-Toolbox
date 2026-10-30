package com.example.llamadroid.tama.transfer

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TamaTransferFreezeTest {
    private val m = PetTransferStateMachine

    @Before @After fun reset() = TamaTransferFreeze.resetForTest()

    private fun state(role: TransferRole, status: TransferStatus) =
        TransferState(role, status, "t1", "pet1", "Pixel")

    @Test fun frozenStatusTable() {
        assertFalse(TamaTransferFreeze.isFrozenStatus(null))
        assertTrue(TamaTransferFreeze.isFrozenStatus(state(TransferRole.SENDER, TransferStatus.OUTBOUND_PENDING)))
        assertTrue(TamaTransferFreeze.isFrozenStatus(state(TransferRole.SENDER, TransferStatus.OUTCOME_UNKNOWN)))
        assertTrue(TamaTransferFreeze.isFrozenStatus(state(TransferRole.ORIGIN, TransferStatus.AWAY)))
        assertTrue(TamaTransferFreeze.isFrozenStatus(state(TransferRole.GUEST_HOST, TransferStatus.RETURN_PENDING)))
        assertTrue(TamaTransferFreeze.isFrozenStatus(state(TransferRole.GUEST_HOST, TransferStatus.OUTCOME_UNKNOWN)))
        assertFalse(TamaTransferFreeze.isFrozenStatus(state(TransferRole.GUEST_HOST, TransferStatus.HOSTING_GUEST)))
        assertFalse(TamaTransferFreeze.isFrozenStatus(state(TransferRole.RECEIVER, TransferStatus.OUTBOUND_PENDING)))
    }

    @Test fun syncFollowsPersistedRow() = runBlocking {
        val dao = FakeTransferDao()
        val store = PetTransferStore(dao)
        TamaTransferFreeze.sync(dao)
        assertFalse(TamaTransferFreeze.frozen)

        store.save(m.senderPrepared("t1", "pet1", "Pixel", "B", "dev-a", "/a.zip"))
        TamaTransferFreeze.sync(dao)
        assertTrue(TamaTransferFreeze.frozen)
        assertTrue(TamaTransferFreeze.flow.value)

        store.save(null)
        TamaTransferFreeze.sync(dao)
        assertFalse(TamaTransferFreeze.frozen)
    }

    @Test fun explicitHoldSurvivesSyncUntilReleased() = runBlocking {
        val dao = FakeTransferDao()
        TamaTransferFreeze.set(true)
        TamaTransferFreeze.sync(dao) // export before the row exists must stay frozen
        assertTrue(TamaTransferFreeze.frozen)
        TamaTransferFreeze.set(false)
        TamaTransferFreeze.sync(dao)
        assertFalse(TamaTransferFreeze.frozen)
    }

    @Test fun releasedHoldStillFrozenByAwayRow() = runBlocking {
        val dao = FakeTransferDao()
        PetTransferStore(dao).save(state(TransferRole.ORIGIN, TransferStatus.AWAY))
        TamaTransferFreeze.set(false)
        TamaTransferFreeze.sync(dao)
        assertEquals(true, TamaTransferFreeze.frozen)
    }
}
