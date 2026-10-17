package com.example.llamadroid.service.delegation

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun parsesCursorFromPastCursorRejection() {
        val msg = "gateway/bad-request: session page through seq 9007199254740991 is past cursor 15"
        assertEquals(15L, HarnessLatestPage.parsePastCursor(msg))
        assertTrue(HarnessLatestPage.isPastCursor(msg))
    }

    @Test fun parsesNegativeCursorAsEmptySession() {
        assertEquals(-1L, HarnessLatestPage.parsePastCursor("through seq 9 is past cursor -1"))
    }

    @Test fun otherMessagesHaveNoCursor() {
        assertNull(HarnessLatestPage.parsePastCursor("connection reset"))
        assertNull(HarnessLatestPage.parsePastCursor(null))
        assertFalse(HarnessLatestPage.isPastCursor("past cursor abc"))
    }

    @Test fun emptyPageIsSuccessfulWithNoRecords() {
        val value = HarnessLatestPage.emptyPage().value.jsonObject
        assertEquals(0, value["records"]!!.jsonArray.size)
        assertFalse(value["hasMore"]!!.jsonPrimitive.boolean)
    }
}
