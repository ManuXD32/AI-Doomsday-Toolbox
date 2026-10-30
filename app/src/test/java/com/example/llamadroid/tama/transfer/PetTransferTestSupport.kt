package com.example.llamadroid.tama.transfer

import com.example.llamadroid.tama.db.TamaTransferPeerDao
import com.example.llamadroid.tama.db.TamaTransferPeerEntity
import com.example.llamadroid.tama.db.TamaTransferStateDao
import com.example.llamadroid.tama.db.TamaTransferStateEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

class FakeTransferDao : TamaTransferStateDao {
    @Volatile var row: TamaTransferStateEntity? = null
    override suspend fun get() = row
    override fun observe(): Flow<TamaTransferStateEntity?> = flowOf(row)
    override suspend fun upsert(state: TamaTransferStateEntity) { row = state }
    override suspend fun clear() { row = null }
}

class FakePeerDao : TamaTransferPeerDao {
    private val rows = java.util.concurrent.ConcurrentHashMap<String, TamaTransferPeerEntity>()
    private val flow = kotlinx.coroutines.flow.MutableStateFlow<List<TamaTransferPeerEntity>>(emptyList())
    private fun publish() { flow.value = rows.values.sortedByDescending { it.lastSeenAt } }
    override suspend fun get(deviceId: String) = rows[deviceId]
    override suspend fun getAll() = flow.value
    override fun observeAll(): Flow<List<TamaTransferPeerEntity>> = flow
    override suspend fun upsert(peer: TamaTransferPeerEntity) { rows[peer.deviceId] = peer; publish() }
    override suspend fun delete(deviceId: String) { rows.remove(deviceId); publish() }
}

class FakeIdentity(private val id: String, private val name: String) : DeviceIdentity {
    override fun deviceId() = id
    override fun deviceName() = name
}

data class FakePet(val id: String, val name: String, val species: String, val stage: String, val payload: String)

/** In-memory pet: the "zip" is a text blob so tests can see exactly what was transferred. */
class FakePetEngine(var pet: FakePet?) : PetTransferEngine {
    @Volatile var frozenFlag = false
    @Volatile var failImports = 0
    @Volatile var validateOk = true
    @Volatile var failExport = false

    override suspend fun currentPetSummary() = pet?.let { PetSummary(it.id, it.name, it.species, it.stage) }

    override suspend fun exportPetZip(file: File): Boolean {
        val p = pet ?: return false
        if (failExport) return false
        file.parentFile?.mkdirs()
        file.writeText("FAKEPET|${p.id}|${p.name}|${p.species}|${p.stage}|${p.payload}")
        return true
    }

    private fun parse(file: File): FakePet? {
        val parts = runCatching { file.readText() }.getOrNull()?.split("|") ?: return null
        if (parts.size != 6 || parts[0] != "FAKEPET") return null
        return FakePet(parts[1], parts[2], parts[3], parts[4], parts[5])
    }

    override suspend fun validateZip(file: File) = validateOk && parse(file) != null

    override suspend fun importPetZip(file: File, applySettings: Boolean): Boolean {
        if (failImports > 0) { failImports--; return false }
        pet = parse(file) ?: return false
        return true
    }

    override suspend fun deletePetData(petId: String) { if (pet?.id == petId) pet = null }
    override fun setFrozen(frozen: Boolean) { this.frozenFlag = frozen }
}

class TransferNode(
    root: File, val deviceId: String, val engine: FakePetEngine, scope: CoroutineScope,
    client: PetTransferClient = PetTransferClient(),
    freeSpace: () -> Long = { Long.MAX_VALUE },
    linkConfig: PetLinkConfig = PetLinkConfig(connectTimeoutMs = 1_000, readTimeoutMs = 4_000, stopDelayMs = 200)
) {
    val dao = FakeTransferDao()
    val store = PetTransferStore(dao)
    val paths = PetTransferPaths(File(root, "tama_transfer"))
    val peerStore = PetTransferPeerStore(FakePeerDao(), InMemoryPeerKeyStore())
    val coordinator = PetTransferCoordinator(
        engine, store, FakeIdentity(deviceId, "Phone-$deviceId"), paths, scope, client,
        discovery = null,
        serverConfig = PetTransferServerConfig(
            acceptTimeoutMs = 10_000, idleTimeoutMs = 60_000, postCommitLingerMs = 15_000, idleCheckMs = 1_000
        ),
        localAddresses = { listOf("127.0.0.1") },
        freeSpace = freeSpace,
        peerStore = peerStore,
        linkConfig = linkConfig
    )

    /** Starts receiving and auto-accepts every incoming request; returns (port, code). */
    fun receiveAutoAccept(scope: CoroutineScope, accept: Boolean = true): Pair<Int, String> {
        check(runBlocking { coordinator.startReceiving() })
        val w = coordinator.state.value as PetTransferUiState.WaitingForPeer
        scope.launch {
            coordinator.state.collect {
                if (it is PetTransferUiState.IncomingRequest) coordinator.respondToIncoming(accept)
            }
        }
        return w.port to w.code
    }

    fun row(): TransferState? = runBlocking { store.get() }
}

fun waitUntil(timeoutMs: Long = 10_000, message: String = "condition", cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return
        runBlocking { delay(25) }
    }
    throw AssertionError("Timed out waiting for $message")
}
