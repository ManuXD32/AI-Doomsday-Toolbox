package com.example.llamadroid.service.delegation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationThreadLivesTest {
    private val json = """[{"id":"s1","title":"Build"},{"id":"s2","title":""}]"""

    @Test fun parsesTitlesWithFallbackAndCurrent() {
        val parsed = DelegationThreadLives.parseThreads(json, "s3", "Task")
        assertEquals(listOf("s1" to "Build", "s2" to "Task", "s3" to "Task"), parsed)
        assertEquals(listOf("s9" to "T"), DelegationThreadLives.parseThreads("bad", "s9", "T"))
    }

    @Test fun assemblesRunningContextAndProgress() {
        val digests = mapOf("s1" to HarnessTaskDigest(running = true, ctxUsedTokens = 500L, ctxWindowTokens = 1000L))
        val list = DelegationThreadLives.assemble(
            DelegationThreadLives.parseThreads(json, null, "Task"), digests, mapOf("s1" to (45 to 100)))
        assertTrue(list[0].running)
        assertEquals(500, list[0].contextUsed)
        assertEquals(100, list[0].promptTotal)
        assertFalse(list[1].running)
        assertEquals(0, list[1].contextWindow)
    }

    @Test fun viewerListOverlaysLiveAndKeepsPersistedWithoutLiveData() {
        val live = listOf(DelegationThreadLive("s1", "Build", true, contextUsed = 10, contextWindow = 100))
        val list = DelegationThreadLives.forViewer(json, null, "Task", live)
        assertEquals(2, list.size)
        assertTrue(list[0].running)
        assertFalse(list[1].running)
        assertEquals(2, DelegationThreadLives.forViewer(json, null, "Task", emptyList()).size)
    }

    @Test fun contextPercentMath() {
        assertEquals(50, DelegationThreadLives.contextPercent(500, 1000))
        assertEquals(100, DelegationThreadLives.contextPercent(2000, 1000))
        assertNull(DelegationThreadLives.contextPercent(5, 0))
    }
}
