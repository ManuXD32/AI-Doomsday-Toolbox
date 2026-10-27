package com.example.llamadroid.tama.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

class PetLinkTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private val nodes = mutableListOf<TransferNode>()

    @Before fun setUp() { scope = CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    @After fun tearDown() {
        nodes.forEach {
            it.coordinator.stopReceiving()
            it.coordinator.link?.stopListening()
        }
        scope.cancel()
    }

    private val pixel = FakePet("pet-a", "Pixel", "dragon", "BABY", "A-data")
    private val host = FakePet("pet-b", "Hosty", "kitsune", "ADULT", "B-data")

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private class Visit(val a: TransferNode, val b: TransferNode, val portA: Int, val portB: Int)

    /** A visits B (code transfer, so they pair); both link listeners are configured for each other. */
    private fun visit(): Visit {
        val pA = freePort()
        var pB = freePort()
        while (pB == pA) pB = freePort()
        val a = node("dev-a", pixel, PetLinkConfig(preferredPort = pA, defaultPort = pB, connectTimeoutMs = 1_000, readTimeoutMs = 4_000, stopDelayMs = 200))
        val b = node("dev-b", host, PetLinkConfig(preferredPort = pB, defaultPort = pA, connectTimeoutMs = 1_000, readTimeoutMs = 4_000, stopDelayMs = 200))
        val (codePort, code) = b.receiveAutoAccept(scope)
        runBlocking { a.coordinator.sendPet("127.0.0.1", codePort, code) }
        b.coordinator.stopReceiving()
        assertEquals(TransferRole.ORIGIN, a.row()!!.role)
        assertEquals(TransferRole.GUEST_HOST, b.row()!!.role)
        return Visit(a, b, pA, pB)
    }

    private fun node(id: String, pet: FakePet?, config: PetLinkConfig) =
        TransferNode(tmp.newFolder(), id, FakePetEngine(pet), scope, linkConfig = config).also { nodes += it }

    private fun collect(n: TransferNode): MutableList<PetLinkEvent> {
        val out = CopyOnWriteArrayList<PetLinkEvent>()
        scope.launch { n.coordinator.link!!.events.collect { out += it } }
        runBlocking { delay(100) }
        return out
    }

    private fun linkOf(n: TransferNode) = n.coordinator.link!!

    private val http = OkHttpClient()

    private fun rawPost(port: Int, path: String, body: String, signer: PairedSigner): Int {
        val h = signer.headers("POST", path, PairedAuth.sha256Hex(body.toByteArray()))
        return rawPost(port, path, body, h)
    }

    private fun rawPost(port: Int, path: String, body: String, h: PairedAuthHeaders): Int {
        val request = Request.Builder().url("http://127.0.0.1:$port$path")
            .header(PairedAuth.HEADER_DEVICE, h.deviceId)
            .header(PairedAuth.HEADER_TIMESTAMP, h.timestamp.toString())
            .header(PairedAuth.HEADER_NONCE, h.nonce)
            .header(PairedAuth.HEADER_SIGNATURE, h.signature)
            .post(body.toRequestBody())
            .build()
        return http.newCall(request).execute().use { it.code }
    }

    private fun signerA(v: Visit) = PairedSigner("dev-a", runBlocking { v.a.peerStore.keyFor("dev-b") }!!)

    @Test fun reclaimEndToEndSendsGuestHomeAndRestoresHostPet() {
        val v = visit()
        val eventsA = collect(v.a)
        val eventsB = collect(v.b)
        assertEquals(v.portA, linkOf(v.a).startListening())
        assertEquals(v.portB, linkOf(v.b).startListening())
        v.b.engine.pet = v.b.engine.pet!!.copy(payload = "A-evolved")
        val changedBefore = v.a.coordinator.petChanged.value

        val state = runBlocking { linkOf(v.a).reclaim() }

        assertEquals(ReclaimState.WaitingForReturn, state)
        waitUntil(message = "both rows cleared") { v.a.row() == null && v.b.row() == null }
        waitUntil(message = "reclaim done") { linkOf(v.a).reclaimState.value == ReclaimState.Done }
        assertEquals(host, v.b.engine.pet) // host pet restored, no user confirmation anywhere
        assertEquals("A-evolved", v.a.engine.pet!!.payload)
        assertFalse(v.a.engine.frozenFlag)
        assertTrue(v.a.coordinator.petChanged.value > changedBefore)
        waitUntil(message = "events") { eventsA.isNotEmpty() && eventsB.isNotEmpty() }
        assertEquals(PetLinkEvent.PetBack("Pixel"), eventsA.single())
        assertEquals(PetLinkEvent.WentHome("Pixel", "Phone-dev-a"), eventsB.single())
    }

    @Test fun reclaimLearnsAddressesFromPing() {
        val v = visit()
        linkOf(v.a).startListening()
        linkOf(v.b).startListening()
        assertTrue(runBlocking { linkOf(v.a).announce() })
        val onB = runBlocking { v.b.peerStore.get("dev-a") }!!
        assertTrue(onB.addresses.any { it.host == "127.0.0.1" && it.port == v.portA })
        val onA = runBlocking { v.a.peerStore.get("dev-b") }!!
        assertTrue(onA.addresses.any { it.host == "127.0.0.1" && it.port == v.portB })
    }

    @Test fun hostSendsHomeWhileOriginHasOnlyTheBackgroundListener() {
        val v = visit()
        val eventsB = collect(v.b)
        linkOf(v.a).startListening() // the origin's app UI is absent: no code server, no screen
        v.b.engine.pet = v.b.engine.pet!!.copy(payload = "A-visited")

        val result = runBlocking { linkOf(v.b).sendHome() }

        assertEquals(SendHomeResult.Done, result)
        assertNull(v.a.row())
        assertNull(v.b.row())
        assertEquals(host, v.b.engine.pet)
        assertEquals("A-visited", v.a.engine.pet!!.payload)
        waitUntil(message = "went home event") { eventsB.isNotEmpty() }
        assertEquals(PetLinkEvent.WentHome("Pixel", "Phone-dev-a"), eventsB.single())
    }

    @Test fun badSignatureAndUnknownDeviceAreRejectedWithoutDetail() {
        val v = visit()
        linkOf(v.b).startListening()
        val body = """{"port":0}"""
        val wrong = PairedSigner("dev-a", ByteArray(32) { 1 })
        val ghost = PairedSigner("ghost", ByteArray(32) { 2 })
        assertEquals(401, rawPost(v.portB, "/link/ping", body, wrong))
        assertEquals(401, rawPost(v.portB, "/link/ping", body, ghost))
        val reclaim = """{"transferId":"x","petId":"pet-a","returnPort":${v.portA}}"""
        assertEquals(401, rawPost(v.portB, "/link/reclaim", reclaim, wrong))
        assertEquals(TransferStatus.HOSTING_GUEST, v.b.row()!!.status) // nothing started
        // a signature over a different body does not verify either
        val h = signerA(v).headers("POST", "/link/ping", PairedAuth.sha256Hex("other".toByteArray()))
        assertEquals(401, rawPost(v.portB, "/link/ping", body, h))
        assertEquals(200, rawPost(v.portB, "/link/ping", body, signerA(v)))
    }

    @Test fun replayedRequestIsRejected() {
        val v = visit()
        linkOf(v.b).startListening()
        val body = """{"port":0}"""
        val h = signerA(v).headers("POST", "/link/ping", PairedAuth.sha256Hex(body.toByteArray()))
        assertEquals(200, rawPost(v.portB, "/link/ping", body, h))
        assertEquals(401, rawPost(v.portB, "/link/ping", body, h))
    }

    @Test fun unpairedPeerCannotReclaimOrHelloWithoutCode() {
        val v = visit()
        linkOf(v.b).startListening()
        val outsider = PairedSigner("dev-c", ByteArray(32) { 3 })
        val reclaim = """{"transferId":"${v.b.row()!!.transferId}","petId":"pet-a","returnPort":${v.portA}}"""
        assertEquals(401, rawPost(v.portB, "/link/reclaim", reclaim, outsider))
        assertEquals(TransferStatus.HOSTING_GUEST, v.b.row()!!.status)
        // a code-signed hello is useless on the link listener (it has no code)
        val hello = PetTransferAuth.sign(
            Hello("t-x", "pet-a", "Pixel", "dragon", "BABY", 10, "a".repeat(64), 22, "dev-a", "A", isReturn = true), "123456"
        )
        try {
            runBlocking { PetTransferClient().hello("127.0.0.1", v.portB, hello) }
            throw AssertionError("expected a refusal")
        } catch (_: IOException) {
        }
        assertNull(v.a.row()?.takeIf { it.role != TransferRole.ORIGIN })
    }

    @Test fun pairedButNotTheOriginCannotReclaim() {
        val v = visit()
        linkOf(v.b).startListening()
        val keyC = ByteArray(32) { 5 }
        runBlocking { v.b.peerStore.recordPairing("dev-c", "Phone C", keyC, emptyList()) }
        val reclaim = """{"transferId":"${v.b.row()!!.transferId}","petId":"pet-a","returnPort":${v.portA}}"""
        assertEquals(409, rawPost(v.portB, "/link/reclaim", reclaim, PairedSigner("dev-c", keyC)))
        assertEquals(TransferStatus.HOSTING_GUEST, v.b.row()!!.status)
    }

    @Test fun unreachablePeerReportsAddressesTried() {
        val v = visit() // B never starts its link listener and its code server is gone
        val eventsB = collect(v.b)
        linkOf(v.a).startListening()

        val state = runBlocking { linkOf(v.a).reclaim() }

        assertTrue(state is ReclaimState.Unreachable)
        val tried = (state as ReclaimState.Unreachable).addressesTried
        assertTrue(tried.any { it == "127.0.0.1:${v.portB}" })
        assertEquals(state, linkOf(v.a).reclaimState.value)
        assertEquals(TransferStatus.AWAY, v.a.row()!!.status)

        val result = runBlocking { linkOf(v.b).sendHome() } // A's listener is up, so B reaches it
        assertEquals(SendHomeResult.Done, result)
        assertTrue(eventsB.none { it is PetLinkEvent.Unreachable })
    }

    @Test fun sendHomeToUnreachableOriginKeepsGuestAndEmitsEvent() {
        val v = visit()
        val eventsB = collect(v.b)

        val result = runBlocking { linkOf(v.b).sendHome() }

        assertTrue(result is SendHomeResult.Unreachable)
        assertEquals(TransferStatus.HOSTING_GUEST, v.b.row()!!.status)
        assertEquals(pixel, v.b.engine.pet)
        waitUntil(message = "unreachable event") { eventsB.isNotEmpty() }
        assertEquals(PetLinkEvent.Unreachable("Phone-dev-a"), eventsB.single())
    }

    @Test fun reclaimWhenNotAwayFails() {
        val a = node("dev-a", pixel, PetLinkConfig())
        assertEquals(ReclaimState.Failed(PetLinkController.REASON_NOT_AWAY), runBlocking { linkOf(a).reclaim() })
    }

    @Test fun manualAddressSurvivesAdvertisedMergeAndEviction() {
        val dao = FakePeerDao()
        val store = PetTransferPeerStore(dao, InMemoryPeerKeyStore())
        runBlocking {
            store.recordPairing("dev-b", "B", ByteArray(32), listOf(PeerAddress("vpn.example", 5000, PeerAddressSource.MANUAL, 1)))
            store.mergeAddresses("dev-b", listOf(PeerAddress("vpn.example", 5000, PeerAddressSource.ADVERTISED, 9)))
            store.mergeAddresses("dev-b", (1..12).map { PeerAddress("10.0.0.$it", 4000, PeerAddressSource.ADVERTISED, 100L + it) })
            val addrs = store.get("dev-b")!!.addresses
            assertEquals(PeerAddressSource.MANUAL, addrs.single { it.host == "vpn.example" }.source)
            assertEquals(PetTransferPeerStore.MAX_ADDRESSES, addrs.size)
        }
    }

    @Test fun knownPortReplacesUnknownPortEntry() {
        val dao = FakePeerDao()
        val store = PetTransferPeerStore(dao, InMemoryPeerKeyStore())
        runBlocking {
            store.recordPairing("dev-b", "B", ByteArray(32), listOf(PeerAddress("192.168.1.5", 0, PeerAddressSource.LAN, 5)))
            store.mergeAddresses("dev-b", listOf(PeerAddress("192.168.1.5", 47833, PeerAddressSource.LAN, 9)))
            assertEquals(listOf(47833), store.get("dev-b")!!.addresses.map { it.port })
        }
    }
}
