package com.example.llamadroid.branchy.cluster

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchyClusterTokenTest {
    @Test
    fun `generated token is 64 lowercase hex and different each time`() {
        val a = BranchyClusterToken.generateHex()
        val b = BranchyClusterToken.generateHex()
        assertEquals(64, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(BranchyClusterToken.isValid(a))
        assertNotEquals(a, b)
    }

    @Test
    fun `a seeded generator is deterministic`() {
        // SHA1PRNG seeding is provider-dependent; use a fixed-sequence SecureRandom instead.
        fun seeded() = object : SecureRandom() {
            private val r = java.util.Random(42)
            override fun nextBytes(bytes: ByteArray) = r.nextBytes(bytes)
        }
        val r1 = seeded()
        val r2 = seeded()
        assertEquals(BranchyClusterToken.generateHex(r1), BranchyClusterToken.generateHex(r2))
    }

    @Test
    fun `validation rejects wrong length and characters`() {
        assertFalse(BranchyClusterToken.isValid(null))
        assertFalse(BranchyClusterToken.isValid(""))
        assertFalse(BranchyClusterToken.isValid("ab".repeat(31)))
        assertFalse(BranchyClusterToken.isValid("ab".repeat(33)))
        assertFalse(BranchyClusterToken.isValid("zz".repeat(32)))
        assertTrue(BranchyClusterToken.isValid("AB".repeat(32)))
    }

    @Test
    fun `input parsing accepts bare hex and the qr payload and normalizes case`() {
        val hex = "0123456789abcdef".repeat(4)
        assertEquals(hex, BranchyClusterToken.parseInput("  $hex\n"))
        assertEquals(hex, BranchyClusterToken.parseInput(hex.uppercase()))
        assertEquals(hex, BranchyClusterToken.parseInput(BranchyClusterToken.qrPayload(hex)))
        assertNull(BranchyClusterToken.parseInput("nope"))
        assertNull(BranchyClusterToken.parseInput(null))
        assertNull(BranchyClusterToken.parseInput(hex + "00"))
    }
}
