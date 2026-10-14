package com.example.llamadroid.harness

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessDelegatedSessionsTest {
    @After fun reset() = HarnessDelegatedSessions.clearForTest()

    @Test fun unregisteredSessionsAreNeverSuppressed() {
        assertFalse(HarnessDelegatedSessions.suppresses("rt", "s1", false))
        assertFalse(HarnessDelegatedSessions.suppresses("rt", null, true))
    }

    @Test fun askMeKeepsApprovalsVisible() {
        HarnessDelegatedSessions.register("rt", "s1", suppressApprovals = false)
        assertTrue(HarnessDelegatedSessions.suppresses("rt", "s1", false))
        assertFalse(HarnessDelegatedSessions.suppresses("rt", "s1", true))
    }

    @Test fun suppressedApprovalsAndRuntimeScoping() {
        HarnessDelegatedSessions.register("rt", "s1", suppressApprovals = true)
        assertTrue(HarnessDelegatedSessions.suppresses("rt", "s1", true))
        assertFalse(HarnessDelegatedSessions.suppresses("other", "s1", true))
        HarnessDelegatedSessions.unregister("rt", "s1")
        assertFalse(HarnessDelegatedSessions.suppresses("rt", "s1", false))
    }
}
