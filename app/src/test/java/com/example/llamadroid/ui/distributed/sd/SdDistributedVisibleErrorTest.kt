package com.example.llamadroid.ui.distributed.sd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SdDistributedVisibleErrorTest {
    @Test
    fun blockedLaunchErrorIsExposedForRunCard() {
        assertEquals("Prompt required", sdDistributedVisibleError("Prompt required", "run failed"))
    }

    @Test
    fun runErrorShownWhenNoLaunchError() {
        assertEquals("run failed", sdDistributedVisibleError(null, "run failed"))
        assertEquals("run failed", sdDistributedVisibleError(" ", "run failed"))
    }

    @Test
    fun noErrorYieldsNull() {
        assertNull(sdDistributedVisibleError(null, null))
        assertNull(sdDistributedVisibleError("", ""))
    }
}
