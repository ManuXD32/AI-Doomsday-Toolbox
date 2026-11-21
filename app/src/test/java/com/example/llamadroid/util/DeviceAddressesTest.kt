package com.example.llamadroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAddressesTest {
    private fun iface(name: String, vararg ips: String, up: Boolean = true, loopback: Boolean = false) =
        InterfaceAddresses(name, up, loopback, ips.toList())

    @Test
    fun `vpn interface comes before wifi regardless of enumeration order`() {
        val result = DeviceAddresses.resolve(
            listOf(iface("wlan0", "192.168.18.209"), iface("tun0", "10.8.0.4"))
        )
        assertEquals(listOf("10.8.0.4", "192.168.18.209"), result.map { it.ip })
        assertEquals(AddressKind.VPN, result[0].kind)
        assertEquals(AddressKind.LAN, result[1].kind)
        assertEquals("10.8.0.4", DeviceAddresses.vpnIp(result))
        assertEquals("192.168.18.209", DeviceAddresses.lanIp(result))
    }

    @Test
    fun `all vpn interface name prefixes are detected`() {
        listOf("tun1", "wg0", "tailscale0", "ppp0", "ipsec0", "TUN2").forEach { name ->
            val result = DeviceAddresses.resolve(listOf(iface(name, "10.1.2.3")))
            assertEquals(name, AddressKind.VPN, result.single().kind)
        }
        assertEquals(
            AddressKind.LAN,
            DeviceAddresses.resolve(listOf(iface("wlan0", "10.1.2.3"))).single().kind
        )
    }

    @Test
    fun `cgnat range is treated as vpn on any interface`() {
        assertTrue(DeviceAddresses.isCgnat("100.64.0.1"))
        assertTrue(DeviceAddresses.isCgnat("100.127.255.254"))
        assertFalse(DeviceAddresses.isCgnat("100.63.0.1"))
        assertFalse(DeviceAddresses.isCgnat("100.128.0.1"))
        val result = DeviceAddresses.resolve(listOf(iface("wlan0", "192.168.1.5"), iface("rmnet0", "100.100.1.2")))
        assertEquals("100.100.1.2", result.first().ip)
        assertEquals(AddressKind.VPN, result.first().kind)
    }

    @Test
    fun `connectivity manager addresses mark an arbitrary interface as vpn`() {
        val result = DeviceAddresses.resolve(
            listOf(iface("wlan0", "192.168.1.5"), iface("v4-custom0", "172.16.0.9")),
            vpnAddresses = setOf("172.16.0.9")
        )
        assertEquals("172.16.0.9", DeviceAddresses.vpnIp(result))
    }

    @Test
    fun `loopback down link-local and duplicates are ignored and lan prefers wlan`() {
        val result = DeviceAddresses.resolve(
            listOf(
                iface("lo", "127.0.0.1", loopback = true),
                iface("wlan1", "10.0.0.2", up = false),
                iface("rmnet0", "10.20.30.40"),
                iface("wlan0", "192.168.1.5", "169.254.1.1"),
                iface("wlan0", "192.168.1.5")
            )
        )
        assertEquals(listOf("192.168.1.5", "10.20.30.40"), result.map { it.ip })
        assertNull(DeviceAddresses.vpnIp(result))
    }

    @Test
    fun `worker endpoint uses vpn ip only when this device also has a vpn`() {
        assertEquals("10.8.0.4", DeviceAddresses.chooseWorkerEndpoint("192.168.1.9", "10.8.0.4", true))
        assertEquals("192.168.1.9", DeviceAddresses.chooseWorkerEndpoint("192.168.1.9", "10.8.0.4", false))
        assertEquals("192.168.1.9", DeviceAddresses.chooseWorkerEndpoint("192.168.1.9", null, true))
        assertEquals("192.168.1.9", DeviceAddresses.chooseWorkerEndpoint("192.168.1.9", "  ", true))
    }

    @Test
    fun `endpoint candidates keep vpn first then the other address`() {
        assertEquals(listOf("10.2.0.3", "192.168.1.9"), DeviceAddresses.endpointCandidates("192.168.1.9", "10.2.0.3", true))
        assertEquals(listOf("192.168.1.9", "10.2.0.3"), DeviceAddresses.endpointCandidates("192.168.1.9", "10.2.0.3", false))
        assertEquals(listOf("192.168.1.9"), DeviceAddresses.endpointCandidates("192.168.1.9", "  ", true))
        assertEquals(listOf("192.168.1.9"), DeviceAddresses.endpointCandidates("192.168.1.9", "192.168.1.9", true))
        assertEquals("10.2.0.3", DeviceAddresses.endpointCandidates("192.168.1.9", "10.2.0.3", true).first())
    }

    @Test
    fun `firstReachable skips a closed port and returns the one that connects`() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { open ->
            val closed = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            assertEquals("127.0.0.1", DeviceAddresses.firstReachable(listOf("127.0.0.1"), open.localPort, 500))
            assertNull(DeviceAddresses.firstReachable(listOf("127.0.0.1"), closed, 500))
            assertNull(DeviceAddresses.firstReachable(emptyList(), open.localPort, 500))
        }
    }

    @Test
    fun `auto keeps the stored order and drops blanks and duplicates`() {
        assertEquals(listOf("192.168.1.5", "100.64.0.2"), DeviceAddresses.orderedCandidates("auto", "192.168.1.5", "100.64.0.2"))
        assertEquals(listOf("192.168.1.5"), DeviceAddresses.orderedCandidates("auto", "192.168.1.5", "192.168.1.5"))
        assertEquals(listOf("192.168.1.5"), DeviceAddresses.orderedCandidates("bogus", "192.168.1.5", " "))
    }

    @Test
    fun `vpn and lan preferences put that kind first`() {
        assertEquals(listOf("100.64.0.2", "192.168.1.5"), DeviceAddresses.orderedCandidates("vpn", "192.168.1.5", "100.64.0.2"))
        assertEquals(listOf("192.168.1.5", "100.64.0.2"), DeviceAddresses.orderedCandidates("lan", "100.64.0.2", "192.168.1.5"))
        assertEquals(listOf("10.8.0.4", "192.168.1.5"), DeviceAddresses.orderedCandidates("VPN", "192.168.1.5", "10.8.0.4", setOf("10.8.0.4")))
    }

    @Test
    fun `preference keeps stored order when both addresses are the same kind`() {
        assertEquals(listOf("192.168.1.5", "192.168.1.6"), DeviceAddresses.orderedCandidates("vpn", "192.168.1.5", "192.168.1.6"))
        assertEquals(listOf("100.64.0.3", "100.64.0.2"), DeviceAddresses.orderedCandidates("lan", "100.64.0.3", "100.64.0.2"))
    }
}
