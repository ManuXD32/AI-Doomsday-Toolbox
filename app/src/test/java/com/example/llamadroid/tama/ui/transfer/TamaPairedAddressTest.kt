package com.example.llamadroid.tama.ui.transfer

import com.example.llamadroid.tama.transfer.PeerAddress
import com.example.llamadroid.tama.transfer.PeerAddressSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TamaPairedAddressTest {
    @Test
    fun parsesHostsIpsAndPorts() {
        assertEquals(ManualAddress("100.64.0.7", 0), parseManualAddress(" 100.64.0.7 "))
        assertEquals(ManualAddress("phone.tailnet.ts.net", 47833), parseManualAddress("phone.tailnet.ts.net:47833"))
        assertEquals(ManualAddress("fd7a::1", 0), parseManualAddress("fd7a::1"))
        assertEquals(ManualAddress("fd7a::1", 9000), parseManualAddress("[fd7a::1]:9000"))
    }

    @Test
    fun rejectsBadInput() {
        listOf("", "  ", "host:0", "host:70000", "host:", "ht tp", "-bad.example", "a..b", "http://x", "[::1", "x:y:z")
            .forEach { assertNull(it, parseManualAddress(it)) }
    }

    @Test
    fun withManualReplacesAndDeduplicates() {
        val old = PeerAddress("a.example", 1, PeerAddressSource.MANUAL, 5L)
        val lan = PeerAddress("192.168.1.2", 47833, PeerAddressSource.LAN, 9L)
        val next = listOf(lan, old).withManual(old, ManualAddress("b.example", 0))
        assertEquals(listOf(lan, PeerAddress("b.example", 0, PeerAddressSource.MANUAL, 5L)), next)
        assertEquals(2, next.withManual(null, ManualAddress("b.example", 0)).size)
    }

    @Test
    fun displayAndBadge() {
        assertEquals("[fd7a::1]:9", PeerAddress("fd7a::1", 9).display())
        assertEquals("host", PeerAddress("host", 0).display())
        assertEquals(AddressBadge.VPN, PeerAddress("h", 1, PeerAddressSource.MANUAL).badge())
        assertEquals(AddressBadge.LEARNED, PeerAddress("h", 1, PeerAddressSource.ADVERTISED).badge())
    }
}
