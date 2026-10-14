package com.example.llamadroid.service.delegation

import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessLatestPageTest {
    @Test fun knownSequenceComesFirstThenMax() {
        assertEquals(listOf(42L, HarnessLatestPage.MAX_SAFE_SEQ), HarnessLatestPage.throughCandidates(42L))
    }

    @Test fun unknownOrNegativeSequenceUsesOnlyMax() {
        assertEquals(listOf(HarnessLatestPage.MAX_SAFE_SEQ), HarnessLatestPage.throughCandidates(null))
        assertEquals(listOf(HarnessLatestPage.MAX_SAFE_SEQ), HarnessLatestPage.throughCandidates(-1L))
    }

    @Test fun zeroIsAKnownSequence() {
        assertEquals(listOf(0L, HarnessLatestPage.MAX_SAFE_SEQ), HarnessLatestPage.throughCandidates(0L))
    }
}
