package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ServerSocket

class SdHelperProbeTest {
    private fun serve(banner: String?, rpcPort: Int): Pair<ServerSocket, Thread> {
        val server = ServerSocket(SdHelperProbe.heartbeatPort(rpcPort))
        val t = Thread {
            runCatching {
                server.accept().use { s ->
                    if (banner != null) s.getOutputStream().apply { write((banner + "\n").toByteArray()); flush() }
                }
            }
        }.apply { isDaemon = true; start() }
        return server to t
    }

    private fun freeRpcPort(): Int = ServerSocket(0).use { it.localPort.coerceAtMost(65000) }

    @Test
    fun heartbeatPortIsRpcPlusOne() {
        assertEquals(50053, SdHelperProbe.heartbeatPort(50052))
    }

    @Test
    fun bannerIsHeartbeat() {
        val rpc = freeRpcPort()
        val (server, _) = serve(SdHelperProbe.BANNER, rpc)
        server.use { assertEquals(SdHelperLiveness.HEARTBEAT, SdHelperProbe.probe("127.0.0.1", rpc, 1000)) }
    }

    @Test
    fun connectedWithoutBannerIsHostAlive() {
        val rpc = freeRpcPort()
        val (server, _) = serve(null, rpc)
        server.use { assertEquals(SdHelperLiveness.HOST_ALIVE, SdHelperProbe.probe("127.0.0.1", rpc, 500)) }
    }

    @Test
    fun wrongBannerIsHostAlive() {
        val rpc = freeRpcPort()
        val (server, _) = serve("hello", rpc)
        server.use { assertEquals(SdHelperLiveness.HOST_ALIVE, SdHelperProbe.probe("127.0.0.1", rpc, 500)) }
    }

    @Test
    fun refusedIsHostAlive() {
        val rpc = freeRpcPort()
        assertEquals(SdHelperLiveness.HOST_ALIVE, SdHelperProbe.probe("127.0.0.1", rpc, 500))
    }

    @Test
    fun unknownHostIsUnreachable() {
        assertEquals(SdHelperLiveness.UNREACHABLE, SdHelperProbe.probe("no-such-host.invalid", 50052, 500))
    }
}
