package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class SdHelperWatchdogTest {
    private val helperA = SdHelper("Phone A", "10.0.0.2", 50052)

    private class Clock(var t: Long = 1_000_000L) {
        fun now() = t
    }

    private fun dog(
        probe: (SdHelper) -> SdHelperLiveness,
        clock: Clock,
        lastOutput: () -> Long,
        lost: MutableList<SdHelper> = mutableListOf(),
        unverifiable: MutableList<SdHelper> = mutableListOf()
    ) = SdHelperWatchdog(
        helpers = listOf(helperA),
        probe = probe,
        lastOutputAtMs = lastOutput,
        now = clock::now,
        onUnverifiable = { unverifiable += it },
        onLost = { lost += it }
    )

    @Test
    fun heldRpcPortDoesNotLoseHelper() {
        ServerSocket(0, 1).use { rpc ->
            val held = mutableListOf<java.net.Socket>()
            val acceptor = Thread {
                runCatching { held += rpc.accept() }
            }.apply { isDaemon = true; start() }
            val client = java.net.Socket("127.0.0.1", rpc.localPort)
            acceptor.join(2000)
            // Nothing listens on port + 1 (refused) -> HOST_ALIVE; the held RPC connection is never touched.
            val liveness = SdHelperProbe.probe("127.0.0.1", rpc.localPort, 500)
            assertEquals(SdHelperLiveness.HOST_ALIVE, liveness)
            val clock = Clock()
            val lost = mutableListOf<SdHelper>()
            val h = SdHelper("local", "127.0.0.1", rpc.localPort)
            val d = SdHelperWatchdog(listOf(h), lastOutputAtMs = { 0L }, now = clock::now, onLost = { lost += it })
            repeat(20) { clock.t += 10_000; d.tick() }
            assertTrue(lost.isEmpty())
            client.close()
            held.forEach { it.close() }
        }
    }

    @Test
    fun unreachableWithFreshOutputIsNotLost() {
        val clock = Clock()
        val lost = mutableListOf<SdHelper>()
        val results = ArrayDeque(listOf(SdHelperLiveness.HEARTBEAT) + List(10) { SdHelperLiveness.UNREACHABLE })
        val d = dog({ results.removeFirst() }, clock, { clock.t }, lost)
        repeat(8) { clock.t += 10_000; d.tick() }
        assertTrue(lost.isEmpty())
        assertFalse(d.lost)
    }

    @Test
    fun unreachableWithSilenceIsLostOnce() {
        val clock = Clock()
        val lost = mutableListOf<SdHelper>()
        val results = ArrayDeque(listOf(SdHelperLiveness.HEARTBEAT) + List(20) { SdHelperLiveness.UNREACHABLE })
        val last = clock.t
        val d = dog({ results.removeFirst() }, clock, { last }, lost)
        repeat(12) { clock.t += 10_000; d.tick() }
        assertEquals(listOf(helperA), lost)
        assertTrue(d.lost)
    }

    @Test
    fun reachableResetsUnreachableCount() {
        val clock = Clock()
        val lost = mutableListOf<SdHelper>()
        val seq = listOf(SdHelperLiveness.HEARTBEAT) + List(5) { SdHelperLiveness.UNREACHABLE } +
            listOf(SdHelperLiveness.HEARTBEAT) + List(5) { SdHelperLiveness.UNREACHABLE }
        val results = ArrayDeque(seq)
        val last = clock.t
        val d = dog({ results.removeFirst() }, clock, { last }, lost)
        repeat(seq.size) { clock.t += 20_000; d.tick() }
        assertTrue(lost.isEmpty())
    }

    @Test
    fun heartbeatThenTwoHostAliveIsLost() {
        val clock = Clock()
        val lost = mutableListOf<SdHelper>()
        val results = ArrayDeque(listOf(SdHelperLiveness.HEARTBEAT, SdHelperLiveness.HOST_ALIVE, SdHelperLiveness.HOST_ALIVE))
        val d = dog({ results.removeFirst() }, clock, { clock.t }, lost)
        repeat(3) { d.tick() }
        assertEquals(listOf(helperA), lost)
    }

    @Test
    fun hostAliveBlipBetweenHeartbeatsIsNotLost() {
        val clock = Clock()
        val lost = mutableListOf<SdHelper>()
        val results = ArrayDeque(
            listOf(
                SdHelperLiveness.HEARTBEAT, SdHelperLiveness.HOST_ALIVE,
                SdHelperLiveness.HEARTBEAT, SdHelperLiveness.HOST_ALIVE
            )
        )
        val d = dog({ results.removeFirst() }, clock, { clock.t }, lost)
        repeat(4) { d.tick() }
        assertTrue(lost.isEmpty())
    }

    @Test
    fun hostAliveWithoutEverHeartbeatIsNotLost() {
        val clock = Clock()
        val lost = mutableListOf<SdHelper>()
        val d = dog({ SdHelperLiveness.HOST_ALIVE }, clock, { clock.t }, lost)
        repeat(10) { d.tick() }
        assertTrue(lost.isEmpty())
    }

    @Test
    fun firstProbeUnreachableIsUnverifiableAndNeverLost() {
        val clock = Clock()
        val lost = mutableListOf<SdHelper>()
        val unverifiable = mutableListOf<SdHelper>()
        val last = clock.t
        val d = dog({ SdHelperLiveness.UNREACHABLE }, clock, { last }, lost, unverifiable)
        repeat(30) { clock.t += 10_000; d.tick() }
        assertTrue(lost.isEmpty())
        assertEquals(listOf(helperA), unverifiable)
    }

    @Test
    fun stopPreventsFurtherProbes() {
        var probes = 0
        val d = dog({ probes++; SdHelperLiveness.UNREACHABLE }, Clock(), { 0L })
        d.stop()
        d.tick()
        assertEquals(0, probes)
    }

    @Test
    fun parseHelpersNamesKnownWorkers() {
        val known = listOf(SdDistributedWorkerRuntime("10.0.0.2", 50052, "Pixel", 8000, 4))
        val helpers = SdHelperWatchdog.parseHelpers("10.0.0.2:50052, 10.0.0.3:50053,bad", known)
        assertEquals(listOf("Pixel", "10.0.0.3"), helpers.map { it.name })
        assertEquals(50053, helpers[1].port)
    }
}
