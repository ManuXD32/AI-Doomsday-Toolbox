package com.example.llamadroid.tama.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetTransferStateMachineTest {
    private val m = PetTransferStateMachine
    private val hello = Hello("t1", "pet1", "Pixel", "dragon", "BABY", 10, "ab", 22, "dev-a", "Phone A")

    private fun prepared() = m.senderPrepared("t1", "pet1", "Pixel", "Phone B", "dev-a", "/a.zip")

    @Test fun senderPreparedIsOutboundPending() {
        val s = prepared()
        assertEquals(TransferRole.SENDER, s.role)
        assertEquals(TransferStatus.OUTBOUND_PENDING, s.status)
        assertEquals("/a.zip", s.archivePath)
    }

    @Test fun commitAckedBecomesOriginAway() {
        val s = m.senderCommitAcked(prepared())!!
        assertEquals(TransferRole.ORIGIN, s.role)
        assertEquals(TransferStatus.AWAY, s.status)
        assertEquals("pet1", s.petId)
    }

    @Test fun permanentCommitAckedClears() {
        assertNull(m.senderCommitAcked(prepared(), keepAway = false))
    }

    @Test fun lostAckThenReceiverCommitted() {
        val unknown = m.senderCommitAckLost(prepared())
        assertEquals(TransferStatus.OUTCOME_UNKNOWN, unknown.status)
        val s = m.senderStatusResolved(unknown, receiverCommitted = true)!!
        assertEquals(TransferRole.ORIGIN, s.role)
        assertEquals(TransferStatus.AWAY, s.status)
    }

    @Test fun lostAckThenReceiverNeverCommittedKeepsPet() {
        assertNull(m.senderStatusResolved(m.senderCommitAckLost(prepared()), receiverCommitted = false))
    }

    @Test fun abandonOnlyBeforeCommit() {
        assertTrue(m.canAbandon(prepared()))
        assertNull(m.senderAbandon(prepared()))
        val unknown = m.senderCommitAckLost(prepared())
        assertFalse(m.canAbandon(unknown))
        assertEquals(unknown, m.senderAbandon(unknown))
    }

    @Test fun receiverCommittedAsGuestPersistsPause() {
        val s = m.receiverCommitted(null, hello, asGuest = true, asReturn = false, pausedPetId = "mine", pausedArchivePath = "/p.zip")!!
        assertEquals(TransferRole.GUEST_HOST, s.role)
        assertEquals(TransferStatus.HOSTING_GUEST, s.status)
        assertEquals("mine", s.pausedPetId)
        assertEquals("/p.zip", s.pausedArchivePath)
        assertEquals("dev-a", s.originDeviceId)
    }

    @Test fun receiverCommittedReturnOrPermanentClears() {
        assertNull(m.receiverCommitted(null, hello, asGuest = false, asReturn = false))
        assertNull(m.receiverCommitted(m.senderCommitAcked(prepared()), hello, asGuest = false, asReturn = true))
    }

    @Test fun guestReturnPathAcked() {
        val host = m.receiverCommitted(null, hello, true, false, "mine", "/p.zip")!!
        val pending = m.guestReturnPrepared(host, "/g.zip")
        assertEquals(TransferStatus.RETURN_PENDING, pending.status)
        assertNull(m.guestReturnedAcked(pending))
    }

    @Test fun guestReturnLostAckResolved() {
        val host = m.receiverCommitted(null, hello, true, false, "mine", "/p.zip")!!
        val unknown = m.guestReturnAckLost(m.guestReturnPrepared(host, "/g.zip"))
        assertEquals(TransferStatus.OUTCOME_UNKNOWN, unknown.status)
        assertNull(m.senderStatusResolved(unknown, receiverCommitted = true))
        assertEquals(TransferStatus.HOSTING_GUEST, m.senderStatusResolved(unknown, receiverCommitted = false)!!.status)
        assertEquals(TransferStatus.HOSTING_GUEST, m.guestReturnCancelled(unknown).status)
    }

    @Test fun canReceiveGuards() {
        assertTrue(m.canReceive(null, hello, "dev-b").allowed)
        assertEquals(m.REASON_UNEXPECTED_RETURN, m.canReceive(null, hello.copy(isReturn = true), "dev-a").reason)
        val host = m.receiverCommitted(null, hello, true, false)!!
        assertEquals(m.REASON_ALREADY_HOSTING, m.canReceive(host, hello.copy(transferId = "t2"), "dev-b").reason)
        assertEquals(m.REASON_BUSY, m.canReceive(prepared(), hello, "dev-a").reason)
    }

    @Test fun canReceiveReturnOnlyWhenMatching() {
        val away = m.senderCommitAcked(prepared())!!
        val ret = hello.copy(isReturn = true, originDeviceId = "dev-a")
        assertTrue(m.canReceive(away, ret, "dev-a").allowed)
        assertFalse(m.canReceive(away, ret.copy(petId = "other"), "dev-a").allowed)
        assertFalse(m.canReceive(away, ret.copy(originDeviceId = "dev-z"), "dev-a").allowed)
        assertEquals(m.REASON_PET_AWAY, m.canReceive(away, hello, "dev-a").reason)
    }

    @Test fun canSendAndReturnGuards() {
        assertTrue(m.canSend(null).allowed)
        assertFalse(m.canSend(prepared()).allowed)
        val host = m.receiverCommitted(null, hello, true, false)!!
        assertFalse(m.canSend(host).allowed)
        assertTrue(m.canReturnGuest(host).allowed)
        assertFalse(m.canReturnGuest(null).allowed)
        assertFalse(m.canReturnGuest(m.guestReturnPrepared(host, "/g.zip")).allowed)
    }
}
