package com.example.llamadroid.data.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebianBootstrapChecksumTest {
    private val legacy = "4fca9c419bf46e2a639a635edd5f6c16da619a49ebb3b0d152a7378ea7cffeea"
    private val current = "73899844987f3261f80c0929187f4e6b25832845eec294356f93baf712b71bcf"

    @Test fun newAndLegacyEnvironmentsUseTheSignedBootstrapDeclaration() {
        assertEquals(current, DebianBootstrapChecksum.expected(null, current))
        assertEquals(current, DebianBootstrapChecksum.expected(legacy, current))
        assertEquals(current, DebianBootstrapChecksum.expected(legacy.uppercase(), current.uppercase()))
    }

    @Test fun arbitraryExplicitPinsAreNeverSilentlyReplaced() {
        val custom = "a".repeat(64)
        assertEquals(custom, DebianBootstrapChecksum.expected(custom, current))
        assertEquals(custom, DebianBootstrapChecksum.expected(custom.uppercase(), null))
    }

    @Test fun newAndLegacyEnvironmentsFailClosedWithoutValidSignedMetadata() {
        for (pin in listOf(null, legacy)) {
            for (metadata in listOf(null, "", "invalid")) {
                assertTrue(runCatching { DebianBootstrapChecksum.expected(pin, metadata) }.isFailure)
            }
        }
    }
}
