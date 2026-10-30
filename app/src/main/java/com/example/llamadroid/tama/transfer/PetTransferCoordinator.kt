package com.example.llamadroid.tama.transfer

import android.annotation.SuppressLint
import com.example.llamadroid.tama.game.TAMA_TRANSFER_VERSION
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

sealed interface PetTransferUiState {
    object Idle : PetTransferUiState
    data class WaitingForPeer(val code: String, val addresses: List<String>, val port: Int) : PetTransferUiState
    data class IncomingRequest(val hello: Hello) : PetTransferUiState
    data class Transferring(
        val outgoing: Boolean, val petName: String, val bytesDone: Long, val bytesTotal: Long
    ) : PetTransferUiState
    /** A send was prepared but not completed; [PetTransferCoordinator.sendPet] resumes it. */
    data class SendPending(val petName: String, val peerName: String?) : PetTransferUiState
    data class Away(val petName: String, val peerName: String?, val since: Long = 0L) : PetTransferUiState
    data class OutcomeUnknown(val petName: String, val peerName: String?) : PetTransferUiState
    data class HostingGuest(val petName: String, val originName: String?, val since: Long = 0L) : PetTransferUiState
    data class Error(val reasonKey: String, val retryable: Boolean) : PetTransferUiState
    data class Done(val petName: String, val returned: Boolean) : PetTransferUiState
}

/** JVM/test default free-space probe; the Android wiring injects a StorageManager-based provider instead. */
@SuppressLint("UsableSpace")
internal fun jvmFreeSpace(dir: java.io.File): Long = dir.usableSpace

/** Orchestrates both roles of the pet hand-off over the DAO row, the state machine and the engine. */
class PetTransferCoordinator(
    private val engine: PetTransferEngine,
    private val store: PetTransferStore,
    private val identity: DeviceIdentity,
    private val paths: PetTransferPaths,
    private val scope: CoroutineScope,
    private val client: PetTransferClient = PetTransferClient(),
    private val discovery: PetTransferDiscovery? = null,
    private val serverConfig: PetTransferServerConfig = PetTransferServerConfig(),
    private val localAddresses: () -> List<String> = { defaultLocalAddresses() },
    private val freeSpace: () -> Long = { jvmFreeSpace(paths.incomingDir) },
    /** Remembers devices after a successful transfer so they can later reach each other without the code. */
    private val peerStore: PetTransferPeerStore? = null,
    /** Which paired device an away pet is visiting (needed by the background link). */
    private val visitPeers: VisitPeerRegistry = InMemoryVisitPeerRegistry(),
    private val linkConfig: PetLinkConfig = PetLinkConfig()
) {
    private val _state = MutableStateFlow<PetTransferUiState>(PetTransferUiState.Idle)
    val state: StateFlow<PetTransferUiState> = _state.asStateFlow()
    val peers: Flow<List<PetTransferPeer>> get() = discovery?.peers ?: flowOf(emptyList())

    private val receiveCommit = PetTransferReceiveCommit(engine, store, identity, paths)
    private val opMutex = Mutex()
    private val _petChanged = MutableStateFlow(0)

    /** Counter bumped after every commit that changed the active pet; UI engines reload on change. */
    val petChanged: StateFlow<Int> = _petChanged.asStateFlow()
    internal fun notifyPetChanged() { _petChanged.value += 1 }
    internal fun setUiState(next: PetTransferUiState) { _state.value = next }

    /** Paired-device layer: background listener, remote reclaim, automatic send-home. Null without a peer store. */
    val link: PetLinkController? = peerStore?.let {
        PetLinkController(
            this, engine, store, identity, paths, scope, client, it, visitPeers, receiveCommit, serverConfig, linkConfig,
            freeSpace
        )
    }
    private var server: PetTransferServer? = null
    private var waiting: PetTransferUiState.WaitingForPeer? = null
    private var decision: CompletableDeferred<Boolean>? = null

    // ---- Receiving ----

    /** Starts the receive server (random port) and advertises it. Returns false when blocked. */
    suspend fun startReceiving(): Boolean {
        if (server != null) return true
        val row = store.get()
        if (row != null && row.role != TransferRole.ORIGIN) {
            _state.value = PetTransferUiState.Error(
                if (row.role == TransferRole.GUEST_HOST) PetTransferStateMachine.REASON_ALREADY_HOSTING
                else PetTransferStateMachine.REASON_BUSY,
                retryable = false
            )
            return false
        }
        val code = PetTransferAuth.generateCode()
        val created = PetTransferServer(
            engine, store, identity, paths, code, scope, serverConfig, freeSpace,
            onIncoming = ::askUser,
            onProgress = { h, done, total ->
                _state.value = PetTransferUiState.Transferring(false, h.petName, done, total)
            },
            onCommitted = { h ->
                notifyPetChanged()
                _state.value = if (h.isReturn) {
                    PetTransferUiState.Done(h.petName, returned = true)
                } else {
                    PetTransferUiState.HostingGuest(h.petName, h.senderDeviceName)
                }
                discovery?.unregister()
            },
            onFailure = { _, reason -> _state.value = PetTransferUiState.Error(reason, retryable = true) },
            committer = receiveCommit,
            peerStore = peerStore,
            onStopped = {
                server = null
                waiting = null
                discovery?.unregister()
                scope.launch {
                    val s = _state.value
                    if (s is PetTransferUiState.WaitingForPeer || s is PetTransferUiState.IncomingRequest ||
                        s is PetTransferUiState.Transferring || s is PetTransferUiState.Error
                    ) refreshFromStore()
                }
            }
        )
        try {
            created.begin()
        } catch (e: IOException) {
            _state.value = PetTransferUiState.Error("server_start_failed", retryable = true)
            return false
        }
        server = created
        val wait = PetTransferUiState.WaitingForPeer(code, localAddresses(), created.port)
        waiting = wait
        _state.value = wait
        discovery?.register(created.port, identity.deviceName())
        return true
    }

    fun stopReceiving() {
        server?.shutdown()
        decision?.complete(false)
    }

    fun startDiscovery() = discovery?.startDiscovery()
    fun stopDiscovery() = discovery?.stopDiscovery()

    private suspend fun askUser(hello: Hello): Boolean {
        val d = CompletableDeferred<Boolean>()
        decision = d
        _state.value = PetTransferUiState.IncomingRequest(hello)
        try {
            return d.await()
        } finally {
            decision = null
            if (_state.value is PetTransferUiState.IncomingRequest) {
                _state.value = waiting ?: PetTransferUiState.Idle
            }
        }
    }

    /** UI answer to [PetTransferUiState.IncomingRequest]. */
    fun respondToIncoming(accept: Boolean) {
        val pending = (_state.value as? PetTransferUiState.IncomingRequest)?.hello
        if (accept && pending != null) {
            _state.value = PetTransferUiState.Transferring(false, pending.petName, 0, pending.zipBytes)
        }
        decision?.complete(accept)
    }

    // ---- Sending ----

    /**
     * New visit when idle, send the guest home when hosting, or resume a pending send; the peer
     * is the receiving device's host/port and its current pairing [code].
     */
    suspend fun sendPet(host: String, port: Int, code: String, peerName: String? = null) = opMutex.withLock {
        val row = store.get()
        when {
            row == null -> startNewVisit(host, port, code, peerName)
            row.role == TransferRole.SENDER && row.status == TransferStatus.OUTBOUND_PENDING ->
                runOutbound(host, port, code, row)
            row.role == TransferRole.GUEST_HOST && row.status == TransferStatus.RETURN_PENDING ->
                runOutbound(host, port, code, row)
            PetTransferStateMachine.canReturnGuest(row).allowed -> startGuestReturn(host, port, code, row)
            else -> fail(PetTransferStateMachine.canSend(row).reason ?: PetTransferStateMachine.REASON_BUSY, false)
        }
    }

    private suspend fun startNewVisit(host: String, port: Int, code: String, peerName: String?) {
        val summary = engine.currentPetSummary() ?: return fail("no_pet", false)
        val id = UUID.randomUUID().toString()
        engine.setFrozen(true)
        val zip = paths.outbound(id)
        if (!engine.exportPetZip(zip)) {
            engine.setFrozen(false)
            zip.delete()
            return fail("export_failed", false)
        }
        val row = PetTransferStateMachine.senderPrepared(
            id, summary.petId, summary.name, peerName, identity.deviceId(), zip.absolutePath
        )
        store.save(row)
        runOutbound(host, port, code, row)
    }

    /**
     * Host: sends the guest home to a paired origin, authenticated by the pairing key (no code).
     * Returns true when the origin acknowledged and the paused pet is back.
     */
    internal suspend fun sendGuestHomePaired(host: String, port: Int, signer: PairedSigner): Boolean = opMutex.withLock {
        val row = store.get()
        when {
            row != null && row.role == TransferRole.GUEST_HOST && row.status == TransferStatus.RETURN_PENDING ->
                runOutbound(host, port, "", row, signer)
            PetTransferStateMachine.canReturnGuest(row).allowed -> startGuestReturn(host, port, "", row!!, signer)
            else -> fail(PetTransferStateMachine.REASON_NOT_HOSTING, false)
        }
        (_state.value as? PetTransferUiState.Done)?.returned == true
    }

    private suspend fun startGuestReturn(
        host: String, port: Int, code: String, hosting: TransferState, signer: PairedSigner? = null
    ) {
        val summary = engine.currentPetSummary()
        if (summary == null || summary.petId != hosting.petId) return fail("guest_missing", false)
        val id = UUID.randomUUID().toString()
        engine.setFrozen(true)
        val zip = paths.outbound(id)
        if (!engine.exportPetZip(zip)) {
            engine.setFrozen(false)
            zip.delete()
            return fail("export_failed", false)
        }
        val row = PetTransferStateMachine.guestReturnPrepared(hosting, zip.absolutePath).copy(transferId = id)
        store.save(row)
        runOutbound(host, port, code, row, signer)
    }

    private suspend fun runOutbound(host: String, port: Int, code: String, row: TransferState, signer: PairedSigner? = null) {
        val id = row.transferId ?: return fail("state_corrupt", false)
        val zip = row.archivePath?.let(::File)?.takeIf { it.exists() } ?: return fail("archive_missing", false)
        val summary = engine.currentPetSummary()
        if (summary == null || summary.petId != row.petId) return fail("pet_missing", false)
        engine.setFrozen(true)
        val isReturn = row.role == TransferRole.GUEST_HOST
        val total = zip.length()
        val pairKeys = if (peerStore != null && signer == null) PetPairing.generate() else null
        val base = Hello(
            transferId = id, petId = summary.petId, petName = summary.name, species = summary.species,
            stage = summary.stage, zipBytes = total, zipSha256 = zip.sha256Hex(),
            bundleVersion = TAMA_TRANSFER_VERSION,
            originDeviceId = if (isReturn) row.originDeviceId.orEmpty() else identity.deviceId(),
            senderDeviceName = identity.deviceName(), isReturn = isReturn,
            deviceId = if (signer != null) identity.deviceId() else ""
        )
        val signed = if (signer != null) base else PetTransferAuth.sign(base, code)
        val hello = if (pairKeys != null) PetPairing.attachToHello(signed, code, pairKeys, identity.deviceId()) else signed
        var pairing: PairingResult? = null
        _state.value = PetTransferUiState.Transferring(true, summary.name, 0, total)
        try {
            val helloReply = if (signer != null) client.helloPaired(host, port, hello, signer) else client.hello(host, port, hello)
            if (!helloReply.accepted) return fail(helloReply.reason ?: "declined", true)
            if (pairKeys != null) {
                pairing = PetPairing.finishAsSender(hello, helloReply, code, pairKeys, identity.deviceId())
                if (!isReturn) pairing?.let { visitPeers.set(id, it.peerDeviceId) }
            }
            val onProgress: (Long, Long) -> Unit = { done, all ->
                _state.value = PetTransferUiState.Transferring(true, summary.name, done, all)
            }
            val staged = if (signer != null) {
                client.uploadPaired(host, port, id, signer, zip, onProgress)
            } else {
                client.upload(host, port, id, code, zip, onProgress)
            }
            if (!staged.staged) return fail(staged.reason ?: "upload_failed", true)
        } catch (e: IOException) {
            return fail("connection_lost", true)
        }
        val reply = try {
            if (signer != null) client.commitPaired(host, port, id, signer) else client.commit(host, port, id, code)
        } catch (e: IOException) {
            markAckLost(row)
            return
        }
        if (!reply.committed) return fail(reply.reason ?: "commit_failed", true)
        pairing?.let { rememberPeer(it, host, port) }
        onCommitAcked(row)
    }

    private suspend fun rememberPeer(pairing: PairingResult, host: String, port: Int) {
        val store = peerStore ?: return
        runCatching {
            store.recordPairing(
                pairing.peerDeviceId, pairing.peerName, pairing.key,
                listOf(PeerAddress(host, port, PeerAddressSource.LAN, System.currentTimeMillis()))
            )
        }
    }

    private suspend fun markAckLost(row: TransferState) {
        val next = if (row.role == TransferRole.GUEST_HOST) {
            PetTransferStateMachine.guestReturnAckLost(row)
        } else {
            PetTransferStateMachine.senderCommitAckLost(row)
        }
        store.save(next)
        refreshFromStore()
    }

    private suspend fun onCommitAcked(row: TransferState) {
        if (row.role == TransferRole.GUEST_HOST) {
            restoreAfterReturn(row)
            return
        }
        store.save(PetTransferStateMachine.senderCommitAcked(row, keepAway = true))
        refreshFromStore()
    }

    /** The origin holds the guest again: bring our paused pet back, then clear the row. */
    private suspend fun restoreAfterReturn(row: TransferState) {
        val paused = row.pausedArchivePath?.let(::File)
        val restored = when {
            paused != null && paused.exists() -> engine.importPetZip(paused, applySettings = false)
            row.pausedPetId != null -> false // we should have a pet to restore but lost the archive
            else -> {
                row.petId?.let { engine.deletePetData(it) }
                true
            }
        }
        if (!restored) {
            store.save(PetTransferStateMachine.guestReturnAckLost(row))
            _state.value = PetTransferUiState.Error("restore_failed", retryable = true)
            return
        }
        paused?.delete()
        row.archivePath?.let { File(it).delete() }
        paths.markCompleted(row.transferId.orEmpty())
        store.save(PetTransferStateMachine.guestReturnedAcked(row))
        engine.setFrozen(false)
        notifyPetChanged()
        _state.value = PetTransferUiState.Done(row.petName.orEmpty(), returned = true)
    }

    // ---- Resolution of undecided / away states ----

    /** Ask the receiver whether it committed (OUTCOME_UNKNOWN). */
    suspend fun checkStatus(host: String, port: Int) = opMutex.withLock {
        val row = store.get() ?: return@withLock
        if (row.status != TransferStatus.OUTCOME_UNKNOWN) return@withLock
        val id = row.transferId ?: return@withLock
        val committed = try {
            client.status(host, port, id).state == TransferWireState.COMMITTED
        } catch (e: IOException) {
            return@withLock fail("peer_unreachable", true)
        }
        if (row.role == TransferRole.GUEST_HOST) {
            val next = PetTransferStateMachine.senderStatusResolved(row, committed)
            if (next == null) {
                restoreAfterReturn(row)
            } else {
                store.save(next.copy(archivePath = null))
                engine.setFrozen(false)
                row.archivePath?.let { File(it).delete() }
                refreshFromStore()
            }
            return@withLock
        }
        val next = PetTransferStateMachine.senderStatusResolved(row, committed)
        store.save(next)
        if (next == null) {
            engine.setFrozen(false)
            row.archivePath?.let { File(it).delete() }
        }
        refreshFromStore()
    }

    /** Take the pet back without the receiver's confirmation; may duplicate it (preferred to loss). */
    suspend fun reclaimAnyway() = opMutex.withLock {
        val row = store.get() ?: return@withLock
        if (row.role == TransferRole.GUEST_HOST) {
            store.save(PetTransferStateMachine.guestReturnCancelled(row).copy(archivePath = null))
            engine.setFrozen(false)
        } else if (row.role == TransferRole.SENDER || row.role == TransferRole.ORIGIN) {
            store.save(null)
            engine.setFrozen(false)
        } else {
            return@withLock
        }
        row.archivePath?.let { File(it).delete() }
        refreshFromStore()
    }

    /**
     * Give up. Before the commit was sent this just cancels (the pet stays); once the pet is away
     * it deletes the frozen local copy and the outbound zip for good.
     */
    suspend fun abandon() = opMutex.withLock {
        val row = store.get() ?: return@withLock
        when {
            PetTransferStateMachine.canAbandon(row) -> {
                store.save(PetTransferStateMachine.senderAbandon(row))
                engine.setFrozen(false)
            }
            row.role == TransferRole.ORIGIN && row.status == TransferStatus.AWAY -> {
                row.petId?.let { engine.deletePetData(it) }
                store.save(null)
                engine.setFrozen(false)
                notifyPetChanged()
            }
            row.role == TransferRole.GUEST_HOST && row.status == TransferStatus.RETURN_PENDING -> {
                store.save(PetTransferStateMachine.guestReturnCancelled(row).copy(archivePath = null))
                engine.setFrozen(false)
            }
            else -> return@withLock
        }
        row.archivePath?.let { File(it).delete() }
        refreshFromStore()
    }

    // ---- Startup ----

    suspend fun recoverOnStart() = opMutex.withLock {
        paths.incomingDir.listFiles()?.forEach { it.delete() }
        val row = store.get()
        if (row != null && row.role == TransferRole.RECEIVER) receiveCommit.recoverInterrupted(row)
        val after = store.get()
        if (after == null && engine.currentPetSummary() != null) {
            // Pet intact: a leftover pause archive from a crash before the import is redundant.
            paths.pausedDir.listFiles()?.forEach { it.delete() }
        }
        refreshFromStore()
        notifyPetChanged()
    }

    private suspend fun refreshFromStore() {
        val row = store.get()
        visitPeers.clearExcept(row?.takeIf { it.role == TransferRole.SENDER || it.role == TransferRole.ORIGIN }?.transferId)
        if (row == null) {
            engine.setFrozen(false)
            _state.value = PetTransferUiState.Idle
            return
        }
        val name = row.petName.orEmpty()
        val frozen = TamaTransferFreeze.isFrozenStatus(row)
        engine.setFrozen(frozen)
        _state.value = when {
            row.status == TransferStatus.OUTCOME_UNKNOWN -> PetTransferUiState.OutcomeUnknown(name, row.peerName)
            row.status == TransferStatus.AWAY -> PetTransferUiState.Away(name, row.peerName, row.since)
            row.status == TransferStatus.HOSTING_GUEST -> PetTransferUiState.HostingGuest(name, row.peerName, row.since)
            row.role == TransferRole.RECEIVER -> PetTransferUiState.Idle
            else -> PetTransferUiState.SendPending(name, row.peerName)
        }
    }

    private fun fail(reasonKey: String, retryable: Boolean) {
        _state.value = PetTransferUiState.Error(reasonKey, retryable)
    }

    /** Clears an [PetTransferUiState.Error]/[PetTransferUiState.Done] banner back to the persisted state. */
    suspend fun dismiss() {
        if (server == null) refreshFromStore()
    }

    /**
     * Leaves an Error banner: back to the pairing screen when the receive server still runs,
     * otherwise to the persisted state.
     */
    suspend fun acknowledgeError() {
        val wait = waiting
        if (server != null && wait != null) _state.value = wait else refreshFromStore()
    }

    // ---- File fallback ----

    /** Exports a copy of the current pet through [open]; the pet keeps living locally. */
    suspend fun exportTransferFile(open: () -> OutputStream): Boolean {
        val tmp = paths.outbound("file-export")
        return try {
            if (!engine.exportPetZip(tmp)) return false
            tmp.inputStream().use { input -> open().use { out -> input.copyTo(out) } }
            true
        } catch (e: IOException) {
            false
        } finally {
            tmp.delete()
        }
    }

    /**
     * Imports a pet ZIP from [open], replacing the local pet through the same staging, validation
     * and rollback-safe commit used by the LAN path.
     */
    suspend fun importTransferFile(open: () -> InputStream): Boolean = opMutex.withLock {
        val id = UUID.randomUUID().toString()
        val hello = Hello(id, "", "", "", "", 0, "", TAMA_TRANSFER_VERSION, "", "")
        val guard = PetTransferStateMachine.canReceive(store.get(), hello, identity.deviceId())
        if (!guard.allowed) {
            fail(guard.reason ?: PetTransferStateMachine.REASON_BUSY, false)
            return@withLock false
        }
        val staged = paths.incoming(id)
        try {
            staged.parentFile?.mkdirs()
            open().use { input -> staged.outputStream().use { out -> input.copyTo(out) } }
            if (staged.length() == 0L || !engine.validateZip(staged)) {
                fail("invalid_zip", false)
                return@withLock false
            }
            val reply = receiveCommit.commit(hello, staged, permanent = true)
            if (!reply.committed) {
                fail(reply.reason ?: "commit_failed", false)
                return@withLock false
            }
            notifyPetChanged()
            _state.value = PetTransferUiState.Done("", returned = false)
            true
        } catch (e: IOException) {
            fail("io_error", false)
            false
        } finally {
            staged.delete()
        }
    }

    companion object {
        fun defaultLocalAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLinkLocalAddress }
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }
}
