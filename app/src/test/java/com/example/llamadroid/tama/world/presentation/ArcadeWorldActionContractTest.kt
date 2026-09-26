package com.example.llamadroid.tama.world.presentation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcadeWorldActionContractTest {
    @Test
    fun unavailableBridgeDoesNotClaimAnActiveWorldLease() = runBlocking {
        val request = request()

        val lease = ArcadeWorldActionBridge.Unavailable.begin(request)

        assertEquals(request.petId, lease.petId)
        assertEquals(request.sessionId, lease.sessionId)
        assertEquals(ArcadeSessionLeaseStatus.UNAVAILABLE, lease.status)
        assertTrue(!lease.keepsPetAtArcade)
    }

    @Test
    fun unavailableBridgeNeverInventsAReward() = runBlocking {
        val request = request()

        val receipt = ArcadeWorldActionBridge.Unavailable.submit(request)

        assertEquals(request.sessionId, receipt.sessionId)
        assertEquals(ArcadeReceiptStatus.UNAVAILABLE, receipt.status)
        assertEquals(0, receipt.coins)
        assertEquals(0, receipt.happiness)
    }

    @Test
    fun hostBridgeReceivesStableIdempotencyKeyAndAllMetrics() = runBlocking {
        var received: ArcadeSessionRequest? = null
        val bridge = ArcadeWorldActionBridge { request ->
            received = request
            ArcadeSessionReceipt(
                sessionId = request.sessionId,
                receiptId = "receipt:${request.sessionId}",
                status = ArcadeReceiptStatus.SUCCEEDED,
                coins = 7,
                happiness = 4
            )
        }
        val request = request()

        val receipt = bridge.submit(request)

        assertEquals(request, received)
        assertTrue(receipt.receiptId.endsWith(request.sessionId))
        assertEquals(ArcadeReceiptStatus.SUCCEEDED, receipt.status)
        assertEquals(7, receipt.coins)
        assertEquals(4, receipt.happiness)
    }

    @Test
    fun hostLeaseLifecycleUsesOneSessionKey() = runBlocking {
        val request = request()
        val bridge = ArcadeWorldActionBridge.from(
            beginSession = { value ->
                ArcadeSessionLease(
                    petId = value.petId,
                    sessionId = value.sessionId,
                    gameId = value.gameId,
                    status = ArcadeSessionLeaseStatus.RUNNING
                )
            },
            submitSession = { value ->
                ArcadeSessionReceipt(sessionId = value.sessionId, status = ArcadeReceiptStatus.SUCCEEDED)
            },
            cancelSession = { value ->
                ArcadeSessionReceipt(sessionId = value.sessionId, status = ArcadeReceiptStatus.REJECTED)
            },
            reconcileSession = { null }
        )

        val lease = bridge.begin(request)
        val completion = bridge.submit(request)
        val cancellation = bridge.cancel(request)

        assertEquals(request.sessionId, lease.sessionId)
        assertTrue(lease.keepsPetAtArcade)
        assertEquals(request.sessionId, completion.sessionId)
        assertEquals(request.sessionId, cancellation.sessionId)
    }

    private fun request() = ArcadeSessionRequest(
        petId = "pet-1",
        sessionId = "session-1",
        gameId = "catch",
        score = 5,
        catches = 5,
        misses = 2,
        totalObjects = 7,
        pairsMatched = 0,
        turnsUsed = 0
    )
}
