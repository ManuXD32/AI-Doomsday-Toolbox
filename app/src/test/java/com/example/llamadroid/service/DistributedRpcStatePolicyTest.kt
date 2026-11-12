package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DistributedRpcStatePolicyTest {
    @Test fun checkpointsOffWhenHelpersAreUsed() {
        assertEquals(0, DistributedRpcStatePolicy.contextCheckpoints(true, null))
        assertEquals(0, DistributedRpcStatePolicy.contextCheckpoints(true, "--temp 0.7"))
    }

    @Test fun localLaunchKeepsServerDefaults() {
        assertNull(DistributedRpcStatePolicy.contextCheckpoints(false, null))
        assertNull(DistributedRpcStatePolicy.defaultCacheRam(false, null))
    }

    @Test fun customFlagsWin() {
        assertNull(DistributedRpcStatePolicy.contextCheckpoints(true, "-ctxcp 4"))
        assertNull(DistributedRpcStatePolicy.contextCheckpoints(true, "--ctx-checkpoints=8"))
        assertNull(DistributedRpcStatePolicy.contextCheckpoints(true, "--swa-checkpoints 2"))
        assertNull(DistributedRpcStatePolicy.defaultCacheRam(true, "--cache-ram 2048"))
        assertNull(DistributedRpcStatePolicy.defaultCacheRam(true, "-cram 512"))
    }

    @Test fun cacheRamOffByDefaultWithHelpers() {
        assertEquals(0, DistributedRpcStatePolicy.defaultCacheRam(true, ""))
    }
}
