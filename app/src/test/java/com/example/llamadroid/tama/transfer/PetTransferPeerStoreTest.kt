package com.example.llamadroid.tama.transfer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PetTransferPeerStoreTest {
    private var now = 100L
    private val dao = FakePeerDao()
    private val keyStore = InMemoryPeerKeyStore()
    private val store = PetTransferPeerStore(dao, keyStore) { now }
    private val key = ByteArray(32) { (it * 3).toByte() }

    @Test fun keyIsWrappedAtRestAndRoundTrips() = runBlocking {
        store.recordPairing("dev-b", "Phone B", key, listOf(PeerAddress("10.0.0.2", 4000, PeerAddressSource.LAN, 5)))
        val row = dao.get("dev-b")!!
        assertFalse(row.wrappedKey.contentEquals(key))
        assertEquals(12, row.keyIv.size)
        assertArrayEquals(key, store.keyFor("dev-b"))
        assertNull(store.keyFor("nobody"))
    }

    @Test fun blobMovedToAnotherDeviceDoesNotUnwrap() = runBlocking {
        store.recordPairing("dev-b", "B", key, emptyList())
        dao.upsert(dao.get("dev-b")!!.copy(deviceId = "dev-evil"))
        assertNull(store.keyFor("dev-evil"))
    }

    @Test fun otherKeystoreCannotUnwrap() = runBlocking {
        store.recordPairing("dev-b", "B", key, emptyList())
        val other = PetTransferPeerStore(dao, InMemoryPeerKeyStore()) { now }
        assertNull(other.keyFor("dev-b"))
    }

    @Test fun rePairingKeepsCreatedAtReplacesKeyMergesAddresses() = runBlocking {
        store.recordPairing("dev-b", "Phone B", key, listOf(PeerAddress("10.0.0.2", 4000, PeerAddressSource.LAN, 5)))
        now = 200L
        val newKey = ByteArray(32) { 7 }
        store.recordPairing("dev-b", "", newKey, listOf(PeerAddress("vpn.example", 4100, PeerAddressSource.MANUAL, 150)))
        val peer = store.get("dev-b")!!
        assertEquals(100L, peer.createdAt)
        assertEquals(200L, peer.lastSeenAt)
        assertEquals("Phone B", peer.displayName)
        assertEquals(listOf("vpn.example", "10.0.0.2"), peer.addresses.map { it.host })
        assertArrayEquals(newKey, store.keyFor("dev-b"))
    }

    @Test fun addressesMergeDedupAndLimitToEight() = runBlocking {
        store.recordPairing("dev-b", "B", key, (1..6).map { PeerAddress("h$it", 1, PeerAddressSource.LAN, it.toLong()) })
        store.mergeAddresses(
            "dev-b",
            (7..12).map { PeerAddress("h$it", 1, PeerAddressSource.ADVERTISED, it.toLong()) } +
                PeerAddress("h1", 1, PeerAddressSource.LAN, 99)
        )
        val hosts = store.get("dev-b")!!.addresses.map { it.host }
        assertEquals(8, hosts.size)
        assertEquals("h1", hosts.first())
        assertEquals(1, hosts.count { it == "h1" })
        assertFalse("h2" in hosts)
    }

    @Test fun mergeIgnoresUnknownPeerAndBlankHosts() = runBlocking {
        store.mergeAddresses("ghost", listOf(PeerAddress("a", 1)))
        assertNull(store.get("ghost"))
        store.recordPairing("dev-b", "B", key, listOf(PeerAddress(" ", 1)))
        assertEquals(0, store.get("dev-b")!!.addresses.size)
    }

    @Test fun removeDeletesPeer() = runBlocking {
        store.recordPairing("dev-b", "B", key, emptyList())
        assertNotNull(store.get("dev-b"))
        store.remove("dev-b")
        assertNull(store.get("dev-b"))
        assertNull(store.keyFor("dev-b"))
        assertEquals(0, store.list().size)
    }
}
