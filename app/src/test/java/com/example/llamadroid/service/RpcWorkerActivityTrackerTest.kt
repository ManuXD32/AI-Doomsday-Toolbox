package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcWorkerActivityTrackerTest {
    private val tracker = RpcWorkerActivityTracker(
        linkedMapOf("10.0.0.2:50052" to "RPC0", "10.0.0.3:50052" to "RPC1")
    )

    @Test
    fun tensorActivityConfirmsOnlyMappedWorker() {
        val events = tracker.consume("rpc backend RPC1 set_tensor completed")
        assertEquals(1, events.size)
        assertEquals("10.0.0.3:50052", events.single().address)
        assertEquals(RpcWorkerStatus.ONLINE, events.single().status)
    }

    @Test
    fun explicitFailureMarksMappedWorkerFailed() {
        val event = tracker.consume("RPC0 recv failed: connection reset").single()
        assertEquals("10.0.0.2:50052", event.address)
        assertEquals(RpcWorkerStatus.FAILED, event.status)
    }

    @Test
    fun silenceNeverCreatesOfflineEvent() {
        assertTrue(tracker.consume("slot timing: 2.3 tokens per second").isEmpty())
    }

    @Test
    fun completedLoadConfirmsEverySelectedWorker() {
        val events = tracker.consume("llama_server: model loaded")
        assertEquals(setOf("10.0.0.2:50052", "10.0.0.3:50052"), events.map { it.address }.toSet())
        assertTrue(events.all { it.status == RpcWorkerStatus.ONLINE })
    }
}
