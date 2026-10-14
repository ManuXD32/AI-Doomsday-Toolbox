package com.example.llamadroid.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessModelFailureLogTest {
    private fun failure(
        code: String = "PROVIDER_INTERRUPTED",
        alive: Boolean? = null,
        restarted: Boolean = false,
        ram: Long? = null,
        low: Boolean = false,
        body: String? = null,
        at: Long = 1L,
    ) = HarnessModelFailure(
        timestampMs = at, code = code, exceptionClass = "IOException", body = body,
        serverAlive = alive, serverRestarted = restarted, freeRamBytes = ram, lowMemory = low,
    )

    @Test fun serverDeathIsReportedAsOutOfMemoryWhenRamIsLow() {
        val record = failure(alive = false, ram = 600L * 1024 * 1024)
        assertEquals(HarnessModelFailureKind.SERVER_DIED, HarnessModelFailureExplain.kind(record))
        assertTrue(HarnessModelFailureExplain.likelyOutOfMemory(record))
        assertTrue(HarnessModelFailureExplain.english(record).contains("likely out of memory"))
        assertTrue(HarnessModelFailureExplain.english(record).contains("0.6 GB"))
    }

    @Test fun restartedServerCountsAsDeathButEnoughRamIsNotOom() {
        val record = failure(restarted = true, ram = 3L * 1024 * 1024 * 1024)
        assertEquals(HarnessModelFailureKind.SERVER_DIED, HarnessModelFailureExplain.kind(record))
        assertFalse(HarnessModelFailureExplain.likelyOutOfMemory(record))
        assertFalse(HarnessModelFailureExplain.english(record).contains("out of memory"))
    }

    @Test fun contextRejectedConnectionAndAdapterKinds() {
        assertEquals(HarnessModelFailureKind.CONTEXT_TOO_LARGE,
            HarnessModelFailureExplain.kind(failure(code = "CONTEXT_WINDOW_EXCEEDED", alive = false)))
        val rejected = failure(code = "LLAMA_SERVER_REJECTED", alive = true, body = "bad grammar")
        assertEquals(HarnessModelFailureKind.SERVER_REJECTED, HarnessModelFailureExplain.kind(rejected))
        assertEquals("llama-server rejected the request: bad grammar.", HarnessModelFailureExplain.english(rejected))
        assertEquals(HarnessModelFailureKind.CONNECTION_LOST,
            HarnessModelFailureExplain.kind(failure(code = "LLAMA_SERVER_CONNECTION_LOST", alive = true)))
        assertEquals(HarnessModelFailureKind.ADAPTER_STREAM,
            HarnessModelFailureExplain.kind(failure(code = "ADAPTER_STREAM_ERROR", alive = true)))
        assertEquals(HarnessModelFailureKind.SERVER_UNSTABLE,
            HarnessModelFailureExplain.kind(failure(code = "MODEL_SERVER_UNSTABLE")))
        assertEquals(HarnessModelFailureKind.OTHER, HarnessModelFailureExplain.kind(failure(alive = true)))
    }

    @Test fun ringKeepsNewestTwentyAndBuildsDiagnostics() {
        val log = HarnessModelFailureLog.inMemory()
        (1L..25L).forEach { log.record(failure(at = it)) }
        assertEquals(20, log.records.value.size)
        assertEquals(25L, log.latest()?.timestampMs)
        val text = log.diagnosticsText()
        assertTrue(text.contains("code=PROVIDER_INTERRUPTED"))
        assertEquals(20, text.lines().count { it.startsWith("- ") })
        var sinkCalls = 0
        log.journalSink = { sinkCalls++ }
        log.record(failure(at = 26))
        assertEquals(1, sinkCalls)
        log.clear()
        assertNull(log.latest())
    }

    @Test fun unstableRuleTripsAfterTwoDeathsInFiveMinutesAndResets() {
        var now = 0L
        val rule = HarnessServerStabilityRule { now }
        rule.recordDeath(7)
        assertFalse(rule.isUnstable(7))
        now = 4 * 60_000L
        rule.recordDeath(7)
        assertTrue(rule.isUnstable(7))
        assertFalse(rule.isUnstable(8))
        now = 13 * 60_000L
        assertTrue("still within 10 min of the latest death", rule.isUnstable(7))
        now = 15 * 60_000L
        assertFalse("reset 10 minutes after the latest death", rule.isUnstable(7))
    }

    @Test fun deathsMoreThanFiveMinutesApartDoNotTrip() {
        var now = 0L
        val rule = HarnessServerStabilityRule { now }
        rule.recordDeath(1)
        now = 6 * 60_000L
        rule.recordDeath(1)
        assertFalse(rule.isUnstable(1))
    }

    @Test fun successClearsHistory() {
        var now = 0L
        val rule = HarnessServerStabilityRule { now }
        rule.recordDeath(1); now = 1000; rule.recordDeath(1)
        assertTrue(rule.isUnstable(1))
        rule.recordSuccess(1)
        assertFalse(rule.isUnstable(1))
    }
}
