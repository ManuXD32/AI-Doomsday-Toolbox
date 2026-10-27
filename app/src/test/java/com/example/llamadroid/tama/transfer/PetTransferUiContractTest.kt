package com.example.llamadroid.tama.transfer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** UI-facing contract of the coordinator: pet-change signal, `since` mapping, error acknowledgement. */
class PetTransferUiContractTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private val nodes = mutableListOf<TransferNode>()

    @Before fun setUp() { scope = CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    @After fun tearDown() {
        nodes.forEach { it.coordinator.stopReceiving() }
        scope.cancel()
    }

    private fun node(id: String, pet: FakePet?) =
        TransferNode(tmp.newFolder(), id, FakePetEngine(pet), scope).also { nodes += it }

    private val pixel = FakePet("pet-a", "Pixel", "dragon", "BABY", "A-data")
    private val host = FakePet("pet-b", "Hosty", "kitsune", "ADULT", "B-data")

    @Test fun guestImportAndAbandonEmitPetChanged() {
        val a = node("dev-a", pixel)
        val b = node("dev-b", host)
        assertEquals(0, b.coordinator.petChanged.value)
        val (portB, codeB) = b.receiveAutoAccept(scope)

        runBlocking { a.coordinator.sendPet("127.0.0.1", portB, codeB) }

        waitUntil(message = "guest import signal") { b.coordinator.petChanged.value >= 1 }
        val before = a.coordinator.petChanged.value
        runBlocking { a.coordinator.abandon() }
        assertEquals(before + 1, a.coordinator.petChanged.value)
        assertNull(a.engine.pet)
    }

    @Test fun awayStateCarriesRowUpdatedAt() {
        val a = node("dev-a", pixel)
        val b = node("dev-b", host)
        val (portB, codeB) = b.receiveAutoAccept(scope)

        runBlocking { a.coordinator.sendPet("127.0.0.1", portB, codeB) }

        val away = a.coordinator.state.value as PetTransferUiState.Away
        assertTrue(away.since > 0L)
        assertEquals(a.dao.row!!.updatedAt, away.since)
        assertEquals(away.since, a.row()!!.since)
    }

    @Test fun acknowledgeErrorReturnsToWaitingWhileServerRuns() {
        val a = node("dev-a", pixel)
        check(runBlocking { a.coordinator.startReceiving() })
        val waiting = a.coordinator.state.value as PetTransferUiState.WaitingForPeer

        val ok = runBlocking { a.coordinator.importTransferFile { "not a pet".byteInputStream() } }

        assertTrue(!ok)
        assertTrue(a.coordinator.state.value is PetTransferUiState.Error)
        runBlocking { a.coordinator.acknowledgeError() }
        assertEquals(waiting, a.coordinator.state.value)
    }

    @Test fun acknowledgeErrorRefreshesFromStoreWithoutServer() {
        val a = node("dev-a", pixel)
        val b = node("dev-b", host)
        val (portB, codeB) = b.receiveAutoAccept(scope)
        val wrong = if (codeB == "000000") "111111" else "000000"
        runBlocking { a.coordinator.sendPet("127.0.0.1", portB, wrong) }
        assertTrue(a.coordinator.state.value is PetTransferUiState.Error)

        runBlocking { a.coordinator.acknowledgeError() }

        assertTrue(a.coordinator.state.value is PetTransferUiState.SendPending)
    }
}
