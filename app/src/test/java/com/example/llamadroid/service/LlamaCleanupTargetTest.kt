package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LlamaCleanupTargetTest {
    @Test
    fun `active controller port takes precedence over verified recorded owner`() {
        assertEquals(
            LlamaCleanupTarget(8080, LlamaCleanupPortSource.ACTIVE_CONTROLLER),
            resolveLlamaCleanupTarget(activeControllerPort = 8080, verifiedRecordedOwnerPort = 8087)
        )
    }

    @Test
    fun `verified recorded owner supplies port after service restart`() {
        assertEquals(
            LlamaCleanupTarget(8087, LlamaCleanupPortSource.VERIFIED_RECORDED_OWNER),
            resolveLlamaCleanupTarget(activeControllerPort = null, verifiedRecordedOwnerPort = 8087)
        )
    }

    @Test
    fun `missing or invalid ports produce no cleanup target`() {
        assertNull(resolveLlamaCleanupTarget(activeControllerPort = null, verifiedRecordedOwnerPort = null))
        assertNull(resolveLlamaCleanupTarget(activeControllerPort = 0, verifiedRecordedOwnerPort = -1))
        assertNull(resolveLlamaCleanupTarget(activeControllerPort = 65_536, verifiedRecordedOwnerPort = 0))
    }

    @Test
    fun `invalid active port falls back only to verified recorded port`() {
        assertEquals(
            LlamaCleanupTarget(49152, LlamaCleanupPortSource.VERIFIED_RECORDED_OWNER),
            resolveLlamaCleanupTarget(activeControllerPort = -1, verifiedRecordedOwnerPort = 49152)
        )
    }
}
