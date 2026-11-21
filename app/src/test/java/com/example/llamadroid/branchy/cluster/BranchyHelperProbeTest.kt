package com.example.llamadroid.branchy.cluster

import com.example.llamadroid.util.HelperHeartbeat
import com.example.llamadroid.util.HelperHeartbeatServer
import com.example.llamadroid.util.HelperLiveness
import com.example.llamadroid.util.HelperProbeResult
import com.example.llamadroid.util.HelperSpecs
import com.example.llamadroid.util.NsdHelper
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchyHelperProbeTest {
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `specs json round trips`() {
        val specs = HelperSpecs("Pixel 8", "BranchyHelper-Pixel8", 3000, 8000, 8, 4, 23.5, "1.0")
        assertEquals(specs, HelperSpecs.parse(specs.toJsonLine()))
        assertNull(HelperSpecs.parse("not json"))
        assertNull(HelperSpecs.parse(null))
        assertNull(HelperSpecs.parse("""{"ram_free_mb":"x","cores":-2}""")!!.ramFreeMb)
    }

    @Test
    fun `heartbeat serves specs and banner only stays online`() {
        val withSpecs = freePort() - 1
        val banner = BranchyAdvert.HEARTBEAT_BANNER
        val server = HelperHeartbeatServer(withSpecs + 1, banner, "127.0.0.1") {
            HelperSpecs(name = "n", ramFreeMb = 1234, cores = 6).toJsonLine()
        }
        assertTrue(server.start())
        val r = HelperHeartbeat.probeDetailed("127.0.0.1", withSpecs, banner, 2000)
        server.close()
        assertEquals(HelperLiveness.HEARTBEAT, r.liveness)
        assertEquals(1234, r.specs!!.ramFreeMb)
        assertNotNull(r.rttMs)

        val plain = freePort() - 1
        val old = HelperHeartbeatServer(plain + 1, banner, "127.0.0.1")
        assertTrue(old.start())
        val r2 = HelperHeartbeat.probeDetailed("127.0.0.1", plain, banner, 2000)
        old.close()
        assertEquals(HelperLiveness.HEARTBEAT, r2.liveness)
        assertNull(r2.specs)
    }

    private fun device(host: String, alt: String?) = BranchyClusterDeviceEntity(
        id = "d", name = "d", host = host, port = 50210, role = "both", lastSeen = 0, altHost = alt
    )

    @Test
    fun `probe falls back to altHost and reports a swap`() {
        val specs = HelperSpecs(ramFreeMb = 900)
        val fn: (String, Int) -> HelperProbeResult = { h, _ ->
            if (h == "10.0.0.2") HelperProbeResult(HelperLiveness.HEARTBEAT, specs, 12) else HelperProbeResult(HelperLiveness.UNREACHABLE)
        }
        val ok = BranchyHelperProbe.probeDevice(device("192.168.1.2", "10.0.0.2"), fn, 5L)
        assertTrue(ok.status.online)
        assertTrue(ok.swapped)
        assertEquals("10.0.0.2", ok.status.answeredHost)
        assertEquals(12L, ok.status.rttMs)
        val down = BranchyHelperProbe.probeDevice(device("192.168.1.9", null), fn, 5L)
        assertFalse(down.status.online)
        assertFalse(down.swapped)
    }

    @Test
    fun `lan preference probes the lan address first`() {
        val probed = mutableListOf<String>()
        val fn: (String, Int) -> HelperProbeResult = { h, _ ->
            probed += h
            HelperProbeResult(HelperLiveness.HEARTBEAT, null, 3)
        }
        val dev = device("100.64.0.2", "192.168.1.2").copy(preferredInterface = "lan")
        val out = BranchyHelperProbe.probeDevice(dev, fn, 5L)
        assertEquals(listOf("192.168.1.2"), probed)
        assertEquals("192.168.1.2", out.status.answeredHost)
    }

    @Test
    fun `own device is filtered by name or ip`() {
        val names = setOf("BranchyHelper-Pixel8")
        val ips = setOf("192.168.1.5", "100.64.0.2")
        assertTrue(NsdHelper.isOwnService("BranchyHelper-Pixel8", null, names, ips))
        assertTrue(NsdHelper.isOwnService("Other", "100.64.0.2", names, ips))
        assertFalse(NsdHelper.isOwnService("Other", "192.168.1.9", names, ips))
        assertFalse(NsdHelper.isOwnService(null, null, names, ips))
    }

    @Test
    fun `worker state transitions`() {
        fun wait(cur: Boolean, line: String) =
            BranchyWorkerEventParser.waitingAfter(cur, BranchyWorkerEventParser.parse(line))
        assertTrue(wait(false, """{"type":"worker","state":"listening"}"""))
        assertTrue(wait(false, """{"type":"worker","state":"waiting_master"}"""))
        assertFalse(wait(true, """{"type":"worker","state":"session"}"""))
        assertTrue(wait(false, "worker: master closed the session"))
        assertFalse(wait(false, "worker: probe closed before login"))
        assertTrue(wait(true, "worker: probe closed before login"))
        assertTrue(wait(true, """{"type":"worker","state":"threads","threads":4}"""))
        assertFalse(wait(false, """{"type":"worker","state":"threads","threads":4}"""))
    }
}
