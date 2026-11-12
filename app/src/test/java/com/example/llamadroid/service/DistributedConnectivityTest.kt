package com.example.llamadroid.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistributedConnectivityTest {
    private val rawConnect =
        "/home/runner/work/llama.cpp/ggml/src/ggml-rpc/ggml-rpc.cpp:653: Failed to connect to 192.168.18.209:50052"

    private fun readable(lines: List<String>, exit: Int?) = DistributedConnectivity.readableRuntimeError(
        lines = lines,
        exitCode = exit,
        nameFor = { if (it == "192.168.18.209:50052") "Pixel" else it },
        unreachableLine = { name, address -> "Can't reach $name ($address)." },
        hint = "HINT",
        abort = "ABORT",
        abortWithLine = { "ABORT: $it" }
    )

    @Test
    fun connectFailureMapsToReadableMessageWithoutPath() {
        val message = readable(listOf("loading", rawConnect), 134)!!
        assertEquals("Can't reach Pixel (192.168.18.209:50052).\nHINT", message)
        assertFalse(message.contains("/home"))
        assertFalse(message.contains(".cpp"))
    }

    @Test
    fun prefixOnlyConnectLineIsRecognised() {
        assertEquals(
            "10.0.0.2:50052",
            DistributedConnectivity.parseConnectFailure("ggml-rpc.cpp:653: Failed to connect to 10.0.0.2:50052")
        )
    }

    @Test
    fun exit134WithoutCauseUsesLastCleanLogLine() {
        val message = readable(listOf("first", "/b/x/ggml.c:12: something odd", ""), 134)!!
        assertEquals("ABORT: something odd", message)
        assertEquals("ABORT", readable(emptyList(), 134))
    }

    @Test
    fun unknownFailureIsNotMapped() {
        assertNull(readable(listOf("fine"), 1))
        assertNull(readable(listOf("fine"), null))
    }

    @Test
    fun sanitizeStripsSourcePrefix() {
        assertEquals("Failed to connect to a:1", DistributedConnectivity.sanitizeLine(rawConnect).replace("192.168.18.209:50052", "a:1"))
        assertEquals("plain line", DistributedConnectivity.sanitizeLine("plain line"))
    }

    @Test
    fun preflightBlocksLaunchWhenAnyEndpointFails() = runBlocking {
        val probe = { host: String, _: Int, _: Int ->
            if (host == "10.0.0.9") ProbeResult(false, null, "SocketTimeoutException") else ProbeResult(true, 12, null)
        }
        val blocked = DistributedConnectivity.preflightFailure(
            addresses = listOf("10.0.0.1:50052", "10.0.0.9:50052"),
            nameFor = { if (it.startsWith("10.0.0.9")) "Tablet" else it },
            line = { name, address -> "No $name ($address)" },
            hint = "HINT",
            probeFn = probe
        )
        assertEquals("No Tablet (10.0.0.9:50052)\nHINT", blocked)
    }

    @Test
    fun preflightPassesWhenAllReachableOrNoHelpers() = runBlocking {
        val ok = { _: String, _: Int, _: Int -> ProbeResult(true, 3, null) }
        assertNull(DistributedConnectivity.preflightFailure(listOf("a:1", "b:2"), { it }, { n, a -> n + a }, "H", probeFn = ok))
        assertNull(DistributedConnectivity.preflightFailure(emptyList(), { it }, { n, a -> n + a }, "H", probeFn = ok))
    }

    @Test
    fun malformedAddressCountsAsUnreachable() = runBlocking {
        val results = DistributedConnectivity.probeAll(listOf("nonsense")) { _, _, _ -> ProbeResult(true, 1, null) }
        assertFalse(results.getValue("nonsense").reachable)
        assertNotNull(results.getValue("nonsense").errorClass)
    }

    @Test
    fun probeResultFormatting() {
        val ok = ProbeResult(true, 12, null)
        val bad = ProbeResult(false, null, "ConnectException")
        assertEquals("Reachable · 12 ms", DistributedConnectivity.formatProbe(ok, { "Reachable · $it ms" }, "Not reachable"))
        assertEquals("Not reachable", DistributedConnectivity.formatProbe(bad, { "Reachable · $it ms" }, "Not reachable"))
        assertTrue(DistributedConnectivity.splitAddress("1.2.3.4:50052") == ("1.2.3.4" to 50052))
        assertNull(DistributedConnectivity.splitAddress("1.2.3.4"))
    }
}
