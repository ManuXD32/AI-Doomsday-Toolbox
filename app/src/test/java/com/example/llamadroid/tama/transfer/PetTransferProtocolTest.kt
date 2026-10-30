package com.example.llamadroid.tama.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.Socket

class PetTransferProtocolTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private val nodes = mutableListOf<TransferNode>()

    @Before fun setUp() { scope = CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    @After fun tearDown() {
        nodes.forEach { it.coordinator.stopReceiving() }
        scope.cancel()
    }

    private fun node(
        id: String, pet: FakePet?, client: PetTransferClient = PetTransferClient(),
        freeSpace: () -> Long = { Long.MAX_VALUE }
    ): TransferNode =
        TransferNode(tmp.newFolder(), id, FakePetEngine(pet), scope, client, freeSpace).also { nodes += it }

    private val pixel = FakePet("pet-a", "Pixel", "dragon", "BABY", "A-data")
    private val host = FakePet("pet-b", "Hosty", "kitsune", "ADULT", "B-data")

    private fun errorKey(n: TransferNode) = (n.coordinator.state.value as? PetTransferUiState.Error)?.reasonKey

    @Test fun successfulTransferPairsBothDevicesWithSameKey() {
        val a = node("dev-a", pixel)
        val b = node("dev-b", host)
        val (portB, codeB) = b.receiveAutoAccept(scope)

        runBlocking { a.coordinator.sendPet("127.0.0.1", portB, codeB) }

        val keyOnA = runBlocking { a.peerStore.keyFor("dev-b") }
        val keyOnB = runBlocking { b.peerStore.keyFor("dev-a") }
        assertNotNull(keyOnA)
        assertTrue(keyOnA!!.contentEquals(keyOnB))
        val peerB = runBlocking { a.peerStore.get("dev-b") }!!
        assertEquals("Phone-dev-b", peerB.displayName)
        assertEquals(PeerAddress("127.0.0.1", portB, PeerAddressSource.LAN, peerB.addresses[0].lastOkAt), peerB.addresses[0])
        assertEquals("Phone-dev-a", runBlocking { b.peerStore.get("dev-a") }!!.displayName)
    }

    @Test fun wrongCodeStoresNoPeer() {
        val a = node("dev-a", pixel)
        val b = node("dev-b", host)
        val (portB, _) = b.receiveAutoAccept(scope)

        runBlocking { a.coordinator.sendPet("127.0.0.1", portB, "000000") }

        assertNull(runBlocking { a.peerStore.get("dev-b") })
        assertNull(runBlocking { b.peerStore.get("dev-a") })
    }

    @Test fun peerWithoutPairingFieldsStillTransfers() {
        // An old sender: signs the legacy Hello and ignores pairing; the receiver must still commit.
        val b = node("dev-b", host)
        val (portB, codeB) = b.receiveAutoAccept(scope)
        val zip = tmp.newFile("old.zip").also { it.writeText("FAKEPET|pet-a|Pixel|dragon|BABY|A-data") }
        val hello = PetTransferAuth.sign(
            Hello("t-old", "pet-a", "Pixel", "dragon", "BABY", zip.length(), zip.sha256Hex(), 22, "dev-old", "Old"),
            codeB
        )
        val client = PetTransferClient()
        runBlocking {
            val reply = client.hello("127.0.0.1", portB, hello)
            assertTrue(reply.accepted)
            assertEquals("", reply.pairPub)
            assertTrue(client.upload("127.0.0.1", portB, "t-old", codeB, zip).staged)
            assertTrue(client.commit("127.0.0.1", portB, "t-old", codeB).committed)
            assertNull(b.peerStore.get("dev-old"))
        }
        assertEquals(pixel, b.engine.pet)
    }

    @Test fun happyGuestVisitThenReturn() {
        val a = node("dev-a", pixel)
        val b = node("dev-b", host)
        val (portB, codeB) = b.receiveAutoAccept(scope)

        runBlocking { a.coordinator.sendPet("127.0.0.1", portB, codeB) }

        assertTrue(a.coordinator.state.value is PetTransferUiState.Away)
        assertTrue(a.engine.frozenFlag)
        assertEquals(TransferRole.ORIGIN, a.row()!!.role)
        assertEquals(TransferStatus.AWAY, a.row()!!.status)
        assertTrue(File(a.row()!!.archivePath!!).exists()) // safety copy kept
        assertEquals(pixel, b.engine.pet)
        val bRow = b.row()!!
        assertEquals(TransferRole.GUEST_HOST, bRow.role)
        assertEquals("pet-b", bRow.pausedPetId)
        assertTrue(File(bRow.pausedArchivePath!!).exists())
        assertFalse(b.engine.frozenFlag)
        assertTrue(b.coordinator.state.value is PetTransferUiState.HostingGuest)

        // The guest changes while visiting, then goes home.
        b.coordinator.stopReceiving()
        b.engine.pet = b.engine.pet!!.copy(payload = "A-evolved")
        val (portA, codeA) = a.receiveAutoAccept(scope)
        runBlocking { b.coordinator.sendPet("127.0.0.1", portA, codeA) }

        assertEquals(host, b.engine.pet) // host pet restored
        assertNull(b.row())
        assertFalse(File(bRow.pausedArchivePath!!).exists())
        assertEquals("A-evolved", a.engine.pet!!.payload) // frozen copy replaced
        assertNull(a.row())
        assertFalse(a.engine.frozenFlag)
        assertTrue(a.coordinator.state.value is PetTransferUiState.Done)
    }

    @Test fun wrongCodeIsRejectedAndRetryWithRightCodeResumes() {
        val a = node("dev-a", pixel)
        val b = node("dev-b", host)
        val (portB, codeB) = b.receiveAutoAccept(scope)
        val wrong = if (codeB == "000000") "111111" else "000000"

        runBlocking { a.coordinator.sendPet("127.0.0.1", portB, wrong) }

        assertEquals("bad_code", errorKey(a))
        assertEquals(host, b.engine.pet)
        assertNull(b.row())
        assertEquals(TransferStatus.OUTBOUND_PENDING, a.row()!!.status)
        assertTrue(a.engine.frozenFlag)

        runBlocking { a.coordinator.sendPet("127.0.0.1", portB, codeB) }
        assertEquals(pixel, b.engine.pet)
        assertEquals(TransferStatus.AWAY, a.row()!!.status)
    }

    @Test fun hashMismatchIsRejectedAndLeavesNothingBehind() {
        val b = node("dev-b", host)
        val (portB, codeB) = b.receiveAutoAccept(scope)
        val client = object : PetTransferClient() {
            override suspend fun hello(host: String, port: Int, hello: Hello) = super.hello(
                host, port, PetTransferAuth.sign(hello.copy(zipSha256 = "0".repeat(64), hmac = ""), codeB)
            )
        }
        val a = node("dev-a", pixel, client)

        runBlocking { a.coordinator.sendPet("127.0.0.1", portB, codeB) }

        assertEquals("hash_mismatch", errorKey(a))
        assertEquals(host, b.engine.pet)
        assertTrue(b.paths.incomingDir.listFiles().orEmpty().isEmpty())
        assertEquals(TransferStatus.OUTBOUND_PENDING, a.row()!!.status)
    }

    @Test fun disconnectMidUploadCleansPartialAndRetryWorks() {
        val b = node("dev-b", host)
        val (portB, codeB) = b.receiveAutoAccept(scope)
        val zip = tmp.newFile("big.zip").apply { writeBytes(ByteArray(1_000_000) { (it % 251).toByte() }) }
        val hello = PetTransferAuth.sign(
            Hello("t-drop", "pet-a", "Pixel", "dragon", "BABY", zip.length(), zip.sha256Hex(), 22, "dev-a", "Phone-a"),
            codeB
        )
        val client = PetTransferClient()
        assertTrue(runBlocking { client.hello("127.0.0.1", portB, hello) }.accepted)

        Socket("127.0.0.1", portB).use { socket ->
            val out = socket.getOutputStream()
            out.write(
                ("PUT /upload HTTP/1.1\r\nHost: x\r\nX-Transfer-Id: t-drop\r\n" +
                    "X-Adt-Auth: ${PetTransferAuth.proof("t-drop", codeB)}\r\n" +
                    "Content-Length: ${zip.length()}\r\n\r\n").toByteArray()
            )
            out.write(zip.readBytes(), 0, 100_000)
            out.flush()
            val part = b.paths.part("t-drop")
            waitUntil(message = "partial file to appear") { part.exists() }
        } // closed mid-body
        waitUntil(message = "partial file cleanup") { !b.paths.part("t-drop").exists() }
        assertFalse(b.paths.incoming("t-drop").exists())
        assertEquals(host, b.engine.pet)

        // The session survives: a full retry of the same file is staged (FAKEPET validation fails
        // for random bytes, which is the expected, clean "invalid_zip" refusal).
        val retry = runBlocking { client.upload("127.0.0.1", portB, "t-drop", codeB, zip) }
        assertFalse(retry.staged)
        assertEquals("invalid_zip", retry.reason)
        assertTrue(b.paths.incomingDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun uploadFailureKeepsSenderPendingAndRetryable() {
        val b = node("dev-b", host)
        val (portB, codeB) = b.receiveAutoAccept(scope)
        var failUpload = true
        val client = object : PetTransferClient() {
            override suspend fun upload(
                host: String, port: Int, transferId: String, code: String, file: File,
                onProgress: (Long, Long) -> Unit
            ): StagedReply {
                if (failUpload) throw IOException("connection reset")
                return super.upload(host, port, transferId, code, file, onProgress)
            }
        }
        val a = node("dev-a", pixel, client)

        runBlocking { a.coordinator.sendPet("127.0.0.1", portB, codeB) }
        val err = a.coordinator.state.value as PetTransferUiState.Error
        assertEquals("connection_lost", err.reasonKey)
        assertTrue(err.retryable)
        assertEquals(TransferStatus.OUTBOUND_PENDING, a.row()!!.status)
        assertTrue(a.engine.frozenFlag)
        assertEquals(host, b.engine.pet)

        failUpload = false
        runBlocking { a.coordinator.sendPet("127.0.0.1", portB, codeB) }
        assertEquals(TransferStatus.AWAY, a.row()!!.status)
        assertEquals(pixel, b.engine.pet)
    }

    @Test fun lostCommitAckBecomesUnknownThenStatusResolvesCommitted() {
        val b = node("dev-b", host)
        val (portB, codeB) = b.receiveAutoAccept(scope)
        val client = object : PetTransferClient() {
            override suspend fun commit(host: String, port: Int, transferId: String, code: String): CommitReply {
                super.commit(host, port, transferId, code)
                throw IOException("ack lost")
            }
        }
        val a = node("dev-a", pixel, client)

        runBlocking { a.coordinator.sendPet("127.0.0.1", portB, codeB) }
        assertTrue(a.coordinator.state.value is PetTransferUiState.OutcomeUnknown)
        assertEquals(TransferStatus.OUTCOME_UNKNOWN, a.row()!!.status)
        assertEquals(pixel, b.engine.pet)

        runBlocking { a.coordinator.checkStatus("127.0.0.1", portB) }
        assertEquals(TransferRole.ORIGIN, a.row()!!.role)
        assertEquals(TransferStatus.AWAY, a.row()!!.status)
        assertTrue(a.coordinator.state.value is PetTransferUiState.Away)
    }

    @Test fun lostCommitThatNeverArrivedResolvesNotCommittedAndFencesLateCommit() {
        val b = node("dev-b", host)
        val (portB, codeB) = b.receiveAutoAccept(scope)
        val client = object : PetTransferClient() {
            override suspend fun commit(host: String, port: Int, transferId: String, code: String): CommitReply =
                throw IOException("never sent")
        }
        val a = node("dev-a", pixel, client)

        runBlocking { a.coordinator.sendPet("127.0.0.1", portB, codeB) }
        val id = a.row()!!.transferId!!
        assertEquals(TransferStatus.OUTCOME_UNKNOWN, a.row()!!.status)

        runBlocking { a.coordinator.checkStatus("127.0.0.1", portB) }
        assertNull(a.row())
        assertFalse(a.engine.frozenFlag)
        assertEquals(pixel, a.engine.pet)
        assertEquals(host, b.engine.pet)

        // A delayed commit for the same transfer must now be refused (no duplicate pet).
        val late = runBlocking { PetTransferClient().commit("127.0.0.1", portB, id, codeB) }
        assertFalse(late.committed)
        assertEquals(host, b.engine.pet)
    }

    @Test fun receiverFailureBetweenPauseAndImportRollsBackAndRetrySucceeds() {
        val a = node("dev-a", pixel)
        val b = node("dev-b", host)
        val (portB, codeB) = b.receiveAutoAccept(scope)
        b.engine.failImports = 1 // guest import fails; restoring the paused pet then succeeds

        runBlocking { a.coordinator.sendPet("127.0.0.1", portB, codeB) }

        assertEquals("import_failed", errorKey(a))
        assertEquals(host, b.engine.pet)
        assertNull(b.row())
        assertTrue(b.paths.pausedDir.listFiles().orEmpty().isEmpty())
        assertEquals(TransferStatus.OUTBOUND_PENDING, a.row()!!.status)

        runBlocking { a.coordinator.sendPet("127.0.0.1", portB, codeB) }
        assertEquals(pixel, b.engine.pet)
        assertEquals(TransferStatus.AWAY, a.row()!!.status)
    }

    @Test fun insufficientSpaceIsRejectedBeforeAnyUpload() {
        val b = node("dev-b", host, freeSpace = { 0L })
        val (portB, codeB) = b.receiveAutoAccept(scope)
        val a = node("dev-a", pixel)

        runBlocking { a.coordinator.sendPet("127.0.0.1", portB, codeB) }

        assertEquals("insufficient_space", errorKey(a))
        assertEquals(host, b.engine.pet)
        assertTrue(b.paths.incomingDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun declinedRequestChangesNothing() {
        val a = node("dev-a", pixel)
        val b = node("dev-b", host)
        val (portB, codeB) = b.receiveAutoAccept(scope, accept = false)

        runBlocking { a.coordinator.sendPet("127.0.0.1", portB, codeB) }

        assertEquals("declined", errorKey(a))
        assertEquals(host, b.engine.pet)
        assertNull(b.row())
    }

    @Test fun recoveryRestoresPausedPetAndCleansPartials() {
        val b = node("dev-b", host)
        val pausedZip = b.paths.paused("pet-b")
        runBlocking { b.engine.exportPetZip(pausedZip) }
        b.engine.pet = FakePet("pet-x", "Other", "unicorn", "EGG", "x") // guest import never recorded
        runBlocking {
            b.store.save(
                TransferState(
                    TransferRole.RECEIVER, TransferStatus.OUTBOUND_PENDING, "t-1", "pet-a", "Pixel", "Phone",
                    "dev-a", null, "pet-b", pausedZip.absolutePath
                )
            )
        }
        val leftover = b.paths.part("t-old").apply { writeText("junk") }

        runBlocking { b.coordinator.recoverOnStart() }

        assertEquals(host, b.engine.pet)
        assertNull(b.row())
        assertFalse(pausedZip.exists())
        assertFalse(leftover.exists())
        assertNotNull(b.coordinator.state.value as? PetTransferUiState.Idle)
    }

    @Test fun recoveryKeepsGuestWhenImportAlreadyCompleted() {
        val b = node("dev-b", FakePet("pet-a", "Pixel", "dragon", "BABY", "A-data"))
        val pausedZip = b.paths.paused("pet-b").apply { writeText("FAKEPET|pet-b|Hosty|kitsune|ADULT|B-data") }
        runBlocking {
            b.store.save(
                TransferState(
                    TransferRole.RECEIVER, TransferStatus.OUTBOUND_PENDING, "t-1", "pet-a", "Pixel", "Phone",
                    "dev-a", null, "pet-b", pausedZip.absolutePath
                )
            )
        }

        runBlocking { b.coordinator.recoverOnStart() }

        assertEquals("pet-a", b.engine.pet!!.id)
        assertEquals(TransferRole.GUEST_HOST, b.row()!!.role)
        assertTrue(pausedZip.exists())
    }
}
