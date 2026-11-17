package com.example.llamadroid.service

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SdDistributedHeartbeatTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun readBanner(port: Int): String? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 1000)
            s.soTimeout = 1000
            s.getInputStream().bufferedReader().readLine()
        }
    }.getOrNull()

    @Test
    fun heartbeatAnswersBannerWhileRpcPortIsBusyAndStopsAfterClose() {
        val rpcPort = freePort()
        val hbPort = SdHelperProbe.heartbeatPort(rpcPort)
        // Skip if the neighbouring port happens to be taken.
        val busyRpc = ServerSocket(rpcPort, 1)
        val server = SdHelperHeartbeatServer(hbPort)
        if (!server.start()) { busyRpc.close(); return }
        try {
            // A client holds the RPC port, as sd-cli does during a run.
            val holder = Socket("127.0.0.1", rpcPort)
            try {
                assertEquals(SdHelperProbe.BANNER, readBanner(hbPort))
                assertEquals(SdHelperProbe.BANNER, readBanner(hbPort))
            } finally {
                holder.close()
            }
        } finally {
            server.close()
            busyRpc.close()
        }
        assertEquals(null, readBanner(hbPort))
    }

    @Test
    fun bindFailureIsReportedNotThrown() {
        ServerSocket(0).use { taken ->
            assertFalse(SdHelperHeartbeatServer(taken.localPort).start())
        }
    }

    @Test
    fun mergeKeepsConnectionStateForUnchangedEndpoints() {
        val seen = SdDistributedWorkerRuntime("10.0.0.2", 50062, "A", 1, 1, isConnected = true, lastSeenAt = 1234L)
        val fresh = SdDistributedWorkerRuntime("10.0.0.2", 50062, "A2", 1, 2)
        val other = SdDistributedWorkerRuntime("10.0.0.3", 50062, "B", 1, 1)
        val merged = SdDistributedService.mergeActiveWorkers(listOf(seen), listOf(fresh, other))
        assertTrue(merged[0].isConnected)
        assertEquals(1234L, merged[0].lastSeenAt)
        assertEquals(2, merged[0].threads)
        assertFalse(merged[1].isConnected)
        assertSame(other, merged[1])
    }
}
