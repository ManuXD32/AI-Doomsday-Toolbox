package com.example.llamadroid.branchy.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BranchyClusterAdvertTest {
    private fun attrs(vararg pairs: Pair<String, String>): Map<String, ByteArray?> =
        pairs.associate { it.first to it.second.toByteArray() }

    @Test
    fun `txt round trips through parse`() {
        val txt = BranchyAdvert.buildTxt(BranchyClusterRole.TRAIN, "Pixel 8", 5120, 23.456, "1.2.3", 50210, "100.64.0.2")
        assertEquals("train", txt["role"])
        assertEquals("23.5", txt["gflops"])
        val device = BranchyAdvert.parse("BranchyHelper-Pixel8", "192.168.1.5", 50210, attrs(*txt.toList().toTypedArray()), false)!!
        assertEquals("Pixel 8", device.name)
        assertEquals("192.168.1.5", device.host)
        assertEquals(50210, device.port)
        assertEquals(BranchyClusterRole.TRAIN, device.role)
        assertEquals(5120, device.ramMb)
        assertEquals(23.5, device.gflops!!, 0.0)
        assertEquals("1.2.3", device.appVersion)
        assertEquals("192.168.1.5-50210", device.id)
    }

    @Test
    fun `advertised vpn ip is used only when this device has a vpn`() {
        val a = attrs("vpn_ip" to "100.64.0.2")
        assertEquals("100.64.0.2", BranchyAdvert.parse("n", "192.168.1.5", 1, a, true)!!.host)
        assertEquals("192.168.1.5", BranchyAdvert.parse("n", "192.168.1.5", 1, a, false)!!.host)
    }

    @Test
    fun `advert keeps the other address as altHost`() {
        val a = attrs("vpn_ip" to "10.2.0.3")
        val vpnFirst = BranchyAdvert.parse("n", "192.168.1.5", 1, a, true)!!
        assertEquals("10.2.0.3", vpnFirst.host)
        assertEquals("192.168.1.5", vpnFirst.altHost)
        val lanFirst = BranchyAdvert.parse("n", "192.168.1.5", 1, a, false)!!
        assertEquals("192.168.1.5", lanFirst.host)
        assertEquals("10.2.0.3", lanFirst.altHost)
        assertNull(BranchyAdvert.parse("n", "192.168.1.5", 1, null, true)!!.altHost)
    }

    @Test
    fun `missing and bad values degrade gracefully`() {
        val d = BranchyAdvert.parse("BranchyHelper-Tab", "10.0.0.9", 50210, null, false)!!
        assertEquals("Tab", d.name)
        assertEquals(BranchyClusterRole.BOTH, d.role)
        assertNull(d.ramMb)
        assertNull(d.gflops)
        val bad = BranchyAdvert.parse("n", "10.0.0.9", 50210, attrs("ram_mb" to "-5", "gflops" to "abc", "role" to "weird"), false)!!
        assertNull(bad.ramMb)
        assertNull(bad.gflops)
        assertEquals(BranchyClusterRole.BOTH, bad.role)
    }

    @Test
    fun `invalid service port falls back to the txt port or is rejected`() {
        assertEquals(50210, BranchyAdvert.parse("n", "10.0.0.9", 0, attrs("port" to "50210"), false)!!.port)
        assertNull(BranchyAdvert.parse("n", "10.0.0.9", 0, null, false))
    }

    @Test
    fun `optional keys are omitted when unknown`() {
        val txt = BranchyAdvert.buildTxt(BranchyClusterRole.BOTH, "x", null, null, null, 50210)
        assertFalse(txt.containsKey("ram_mb"))
        assertFalse(txt.containsKey("gflops"))
        assertFalse(txt.containsKey("app_ver"))
        assertFalse(txt.containsKey("vpn_ip"))
        assertNotNull(txt["port"])
    }

    @Test
    fun `host and port parsing`() {
        assertEquals("192.168.1.5" to 50210, BranchyClusterIds.parseHostPort("192.168.1.5:50210"))
        assertEquals("host.local" to 50210, BranchyClusterIds.parseHostPort(" host.local "))
        assertEquals("fe80::1" to 6000, BranchyClusterIds.parseHostPort("[fe80::1]:6000"))
        assertNull(BranchyClusterIds.parseHostPort("1.2.3.4:0"))
        assertNull(BranchyClusterIds.parseHostPort("1.2.3.4:70000"))
        assertNull(BranchyClusterIds.parseHostPort("1.2.3.4:abc"))
        assertNull(BranchyClusterIds.parseHostPort("http://1.2.3.4:5"))
        assertNull(BranchyClusterIds.parseHostPort(":5"))
        assertNull(BranchyClusterIds.parseHostPort("a b"))
        assertNull(BranchyClusterIds.parseHostPort(""))
    }

    @Test
    fun `device id is native safe`() {
        assertEquals("host.local-50210", BranchyClusterIds.deviceId("Host.Local", 50210))
        assertEquals("fe80--1-6000", BranchyClusterIds.deviceId("fe80::1", 6000))
    }

    @Test
    fun `role parsing defaults to both`() {
        assertEquals(BranchyClusterRole.INFER, BranchyClusterRole.fromValue(" Infer "))
        assertEquals(BranchyClusterRole.BOTH, BranchyClusterRole.fromValue(null))
        assertEquals(true, BranchyClusterRole.BOTH.canTrain && BranchyClusterRole.BOTH.canInfer)
        assertEquals(false, BranchyClusterRole.INFER.canTrain)
    }
}
