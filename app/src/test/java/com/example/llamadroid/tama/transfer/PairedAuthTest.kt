package com.example.llamadroid.tama.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PairedAuthTest {
    private val key = ByteArray(32) { it.toByte() }
    private var now = 1_000_000_000L
    private val verifier = PairedAuthVerifier({ id -> if (id == "dev-a") key else null }, { now })
    private val body = PairedAuth.sha256Hex("hello".toByteArray())

    private fun signed(nonce: String = "n1", ts: Long = now, method: String = "POST", path: String = "/pair/reclaim") =
        PairedAuth.sign(key, "dev-a", method, path, body, ts, nonce)

    @Test fun validRequestAccepted() {
        assertEquals(PairedAuthResult.OK, verifier.verify(signed(), "POST", "/pair/reclaim", body))
    }

    @Test fun methodIsCaseInsensitiveOnly() {
        assertEquals(PairedAuthResult.OK, verifier.verify(signed(method = "post"), "POST", "/pair/reclaim", body))
    }

    @Test fun replayRejected() {
        val h = signed()
        assertEquals(PairedAuthResult.OK, verifier.verify(h, "POST", "/pair/reclaim", body))
        assertEquals(PairedAuthResult.REPLAY, verifier.verify(h, "POST", "/pair/reclaim", body))
    }

    @Test fun skewRejectedBothWaysButBoundaryAllowed() {
        assertEquals(PairedAuthResult.SKEWED, verifier.verify(signed("a", now - 5 * 60_000L - 1), "POST", "/pair/reclaim", body))
        assertEquals(PairedAuthResult.SKEWED, verifier.verify(signed("b", now + 5 * 60_000L + 1), "POST", "/pair/reclaim", body))
        assertEquals(PairedAuthResult.OK, verifier.verify(signed("c", now - 5 * 60_000L), "POST", "/pair/reclaim", body))
    }

    @Test fun skewedRequestDoesNotBurnItsNonce() {
        val h = signed("d", now + 6 * 60_000L)
        assertEquals(PairedAuthResult.SKEWED, verifier.verify(h, "POST", "/pair/reclaim", body))
        now += 2 * 60_000L
        assertEquals(PairedAuthResult.OK, verifier.verify(h, "POST", "/pair/reclaim", body))
    }

    @Test fun tamperRejected() {
        val h = signed()
        assertEquals(PairedAuthResult.BAD_SIGNATURE, verifier.verify(h, "GET", "/pair/reclaim", body))
        assertEquals(PairedAuthResult.BAD_SIGNATURE, verifier.verify(h, "POST", "/pair/other", body))
        assertEquals(PairedAuthResult.BAD_SIGNATURE, verifier.verify(h, "POST", "/pair/reclaim", PairedAuth.EMPTY_BODY_SHA256))
        assertEquals(PairedAuthResult.BAD_SIGNATURE, verifier.verify(h.copy(nonce = "n2"), "POST", "/pair/reclaim", body))
        assertEquals(PairedAuthResult.BAD_SIGNATURE, verifier.verify(h.copy(timestamp = now + 1), "POST", "/pair/reclaim", body))
        assertEquals(PairedAuthResult.BAD_SIGNATURE, verifier.verify(h.copy(signature = "00"), "POST", "/pair/reclaim", body))
    }

    @Test fun wrongKeyAndUnknownDevice() {
        val other = PairedAuth.sign(ByteArray(32) { 9 }, "dev-a", "POST", "/p", body, now, "z")
        assertEquals(PairedAuthResult.BAD_SIGNATURE, verifier.verify(other, "POST", "/p", body))
        assertEquals(PairedAuthResult.UNKNOWN_DEVICE, verifier.verify(signed().copy(deviceId = "dev-z"), "POST", "/pair/reclaim", body))
        assertEquals(PairedAuthResult.MALFORMED, verifier.verify(signed().copy(nonce = ""), "POST", "/pair/reclaim", body))
    }

    @Test fun nonceCacheIsBounded() {
        val small = PairedAuthVerifier({ key }, { now }, maxNonces = 3)
        repeat(5) { i ->
            assertEquals(PairedAuthResult.OK, small.verify(signed("n$i"), "POST", "/pair/reclaim", body))
        }
        // n0 was evicted (documented trade-off); the newest is still remembered.
        assertEquals(PairedAuthResult.REPLAY, small.verify(signed("n4"), "POST", "/pair/reclaim", body))
        assertEquals(PairedAuthResult.OK, small.verify(signed("n0"), "POST", "/pair/reclaim", body))
    }

    @Test fun signatureDependsOnEveryField() {
        assertNotEquals(signed("a").signature, signed("b").signature)
    }
}
