package com.example.llamadroid.ui.ai.llama.chat

import com.example.llamadroid.service.KvCacheEligibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatKvCacheSectionTest {
    @Test
    fun eligibleAndDisabledAllowToggleWithoutReason() {
        for (e in listOf(KvCacheEligibility.ELIGIBLE, KvCacheEligibility.DISABLED)) {
            for (on in listOf(true, false)) {
                val s = kvCacheToggleState(e, on)
                assertTrue(s.enabled)
                assertNull(s.reasonRes)
            }
        }
    }

    @Test
    fun restartNeededLocksSwitchOnlyWhenOff() {
        val off = kvCacheToggleState(KvCacheEligibility.SERVER_NEEDS_RESTART, false)
        assertFalse(off.enabled)
        assertEquals(KvCacheEligibility.SERVER_NEEDS_RESTART.reasonRes, off.reasonRes)
        val on = kvCacheToggleState(KvCacheEligibility.SERVER_NEEDS_RESTART, true)
        assertTrue(on.enabled)
        assertEquals(KvCacheEligibility.SERVER_NEEDS_RESTART.reasonRes, on.reasonRes)
    }

    @Test
    fun remoteAndUnsupportedStayLockedWithReason() {
        for (e in listOf(KvCacheEligibility.REMOTE_SERVER, KvCacheEligibility.UNSUPPORTED_ENGINE)) {
            val s = kvCacheToggleState(e, false)
            assertFalse(s.enabled)
            assertEquals(e.reasonRes, s.reasonRes)
            // An enabled cache can still be switched off on an ineligible server.
            assertTrue(kvCacheToggleState(e, true).enabled)
        }
    }
}
