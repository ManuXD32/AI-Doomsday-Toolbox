package com.example.llamadroid.tama.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Tunables of the paired-device link; shortened in tests. */
data class PetLinkConfig(
    /** Listener port to try first (0 = any free port). The service passes its stored port. */
    val preferredPort: Int = 0,
    /** Port assumed for a peer address whose port is unknown, so the very first contact can work. */
    val defaultPort: Int = DEFAULT_PORT,
    val connectTimeoutMs: Long = 3_000L,
    val readTimeoutMs: Long = 8_000L,
    /** Delay before the listener is stopped after a finished visit (lets the last reply flush). */
    val stopDelayMs: Long = 3_000L
) {
    companion object {
        const val DEFAULT_PORT = 47833
    }
}

/**
 * Paired-device layer on top of the coordinator: a persistent listener that accepts `/link/...`
 * calls and code-less returns while a visit is active, the origin's remote reclaim, and the host's
 * send-home over every known address of the peer.
 */
class PetLinkController internal constructor(
    private val coordinator: PetTransferCoordinator,
    private val engine: PetTransferEngine,
    private val store: PetTransferStore,
    private val identity: DeviceIdentity,
    private val paths: PetTransferPaths,
    private val scope: CoroutineScope,
    private val client: PetTransferClient,
    private val peers: PetTransferPeerStore,
    private val visitPeers: VisitPeerRegistry,
    private val committer: PetTransferReceiveCommit,
    private val serverConfig: PetTransferServerConfig,
    private val config: PetLinkConfig,
    private val freeSpace: () -> Long
) : PetLinkHost {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _events = MutableSharedFlow<PetLinkEvent>(extraBufferCapacity = 16)
    private val _reclaim = MutableStateFlow<ReclaimState>(ReclaimState.Idle)
    private val returning = AtomicBoolean(false)
    private var server: PetTransferServer? = null

    val events: SharedFlow<PetLinkEvent> = _events.asSharedFlow()
    val reclaimState: StateFlow<ReclaimState> = _reclaim.asStateFlow()

    /** Paired devices, for the management UI (rename, manual addresses, forget). */
    val pairedDevices: PetTransferPeerStore get() = peers

    /** Port the listener is bound to; 0 when it is not running. */
    val listeningPort: Int get() = server?.port ?: 0

    fun resetReclaim() { _reclaim.value = ReclaimState.Idle }

    // ---- Listener ----

    /** Starts the persistent listener (idempotent); returns the bound port or 0 on failure. */
    @Synchronized
    fun startListening(preferredPort: Int = config.preferredPort): Int {
        server?.let { return it.port }
        for (port in listOf(preferredPort, 0).distinct()) {
            val created = createServer(port)
            try {
                created.begin()
            } catch (e: IOException) {
                continue
            }
            server = created
            return created.port
        }
        return 0
    }

    @Synchronized
    fun stopListening() {
        val s = server ?: return
        server = null
        s.shutdown()
    }

    private fun createServer(port: Int) = PetTransferServer(
        engine, store, identity, paths, PetTransferAuth.generateCode(), scope, serverConfig, freeSpace,
        onIncoming = { false },
        onProgress = { h, done, total ->
            coordinator.setUiState(PetTransferUiState.Transferring(false, h.petName, done, total))
        },
        onFailure = { _, reason -> coordinator.setUiState(PetTransferUiState.Error(reason, retryable = true)) },
        onStopped = { synchronized(this) { server = null } },
        committer = committer,
        link = this,
        listenPort = port,
        persistent = true
    )

    /** The active visit when it can use the link: this device is origin or host and the other side is paired. */
    suspend fun linkVisit(): LinkVisit? {
        val row = store.get() ?: return null
        val away = row.role == TransferRole.ORIGIN && row.status == TransferStatus.AWAY
        val hosting = row.role == TransferRole.GUEST_HOST && row.status != TransferStatus.OUTCOME_UNKNOWN
        if (!away && !hosting) return null
        val transferId = row.transferId ?: return null
        val peerId = (if (away) visitPeers.peerFor(transferId) else row.originDeviceId)
            ?.takeIf { it.isNotBlank() } ?: return null
        val peer = peers.get(peerId) ?: return null
        return LinkVisit(peerId, peer.displayName.ifBlank { row.peerName.orEmpty() }, row.petName.orEmpty(), transferId, away)
    }

    // ---- Reaching the peer ----

    private class Resolved(val address: PeerAddress?, val tried: List<String>)

    /**
     * Addresses to try, best first: the recently working LAN address, the user's MANUAL (VPN) host,
     * ADVERTISED ones, then other LAN entries. A host whose port is unknown, or that has no entry on
     * the default port yet, is also tried on [PetLinkConfig.defaultPort].
     */
    internal fun candidates(peer: PairedPeer, extras: List<PeerAddress>): List<PeerAddress> {
        val own = peer.addresses
        val ordered = extras +
            own.filter { it.source == PeerAddressSource.LAN && it.lastOkAt > 0 }.sortedByDescending { it.lastOkAt } +
            own.filter { it.source == PeerAddressSource.MANUAL } +
            own.filter { it.source == PeerAddressSource.ADVERTISED } +
            own.filter { it.source == PeerAddressSource.LAN && it.lastOkAt <= 0 }
        val withDefaults = ordered.map { if (it.port > 0) it else it.copy(port = config.defaultPort) } +
            ordered.map { it.copy(port = config.defaultPort) }
        return withDefaults.filter { it.host.isNotBlank() && it.port > 0 }.distinctBy { it.host to it.port }
    }

    /** Pings [addr]; the reply must be signed with the pairing key, otherwise it is not our peer. */
    private suspend fun probe(peer: PairedPeer, signer: PairedSigner, addr: PeerAddress): LinkPong? {
        val body = json.encodeToString(LinkPing(listeningPort, LinkAddresses.local(), identity.deviceName()))
        val answer = try {
            client.linkPost(addr.host, addr.port, PATH_PING, body, signer, config.connectTimeoutMs, config.readTimeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        if (answer.code != 200) return null
        val bodyHash = PairedAuth.sha256Hex(answer.body.toByteArray(Charsets.UTF_8))
        if (!signer.replyValid(answer.signature, PATH_PING, answer.nonce, bodyHash, peer.deviceId)) return null
        val pong = runCatching { json.decodeFromString<LinkPong>(answer.body) }.getOrNull() ?: return null
        val learned = mutableListOf(addr.copy(lastOkAt = System.currentTimeMillis()))
        if (pong.port > 0) {
            pong.addresses.filter(::isPlainAddress).take(MAX_ADVERTISED).forEach {
                learned += PeerAddress(it, pong.port, PeerAddressSource.ADVERTISED, 0L)
            }
        }
        peers.mergeAddresses(peer.deviceId, learned)
        return pong
    }

    private suspend fun resolve(peer: PairedPeer, signer: PairedSigner, extras: List<PeerAddress>): Resolved {
        val tried = mutableListOf<String>()
        for (candidate in candidates(peer, extras)) {
            tried += "${candidate.host}:${candidate.port}"
            if (probe(peer, signer, candidate) != null) return Resolved(candidate, tried)
        }
        return Resolved(null, tried)
    }

    private suspend fun signerFor(peerId: String): Pair<PairedPeer, PairedSigner>? {
        val peer = peers.get(peerId) ?: return null
        val key = peers.keyFor(peerId) ?: return null
        return peer to PairedSigner(identity.deviceId(), key)
    }

    /** Exchanges listener ports and addresses with the visit's peer; true once it answered. */
    suspend fun announce(): Boolean {
        val visit = linkVisit() ?: return false
        val (peer, signer) = signerFor(visit.peerDeviceId) ?: return false
        return resolve(peer, signer, emptyList()).address != null
    }

    /** Signed ping over every known address of [peerId]; reports the first that answers and how long it took. */
    suspend fun testPeer(peerId: String): PeerTestResult {
        val (peer, signer) = signerFor(peerId) ?: return PeerTestResult.Failed(REASON_NOT_PAIRED)
        val started = System.nanoTime()
        val found = resolve(peer, signer, emptyList())
        val addr = found.address ?: return PeerTestResult.Unreachable(found.tried)
        return PeerTestResult.Ok((System.nanoTime() - started) / 1_000_000L, "${addr.host}:${addr.port}")
    }

    // ---- Origin: remote reclaim ----

    /** Asks the hosting device to send the pet home; progress is in [reclaimState]. */
    suspend fun reclaim(): ReclaimState {
        val visit = linkVisit()
        val row = store.get()
        if (visit == null || !visit.awayAtPeer || row == null) return setReclaim(ReclaimState.Failed(REASON_NOT_AWAY))
        val (peer, signer) = signerFor(visit.peerDeviceId) ?: return setReclaim(ReclaimState.Failed(REASON_NOT_PAIRED))
        _reclaim.value = ReclaimState.Reclaiming
        val port = startListening()
        if (port == 0) return setReclaim(ReclaimState.Failed(REASON_LISTEN_FAILED))
        val found = resolve(peer, signer, emptyList())
        val addr = found.address ?: return setReclaim(ReclaimState.Unreachable(found.tried))
        val body = json.encodeToString(ReclaimRequest(visit.transferId, row.petId.orEmpty(), null, port))
        val answer = try {
            client.linkPost(addr.host, addr.port, PATH_RECLAIM, body, signer, config.connectTimeoutMs, config.readTimeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return setReclaim(ReclaimState.Unreachable(found.tried))
        }
        return setReclaim(
            when (answer.code) {
                202 -> ReclaimState.WaitingForReturn
                401 -> ReclaimState.Failed(REASON_NOT_PAIRED)
                else -> ReclaimState.Failed(
                    runCatching { json.decodeFromString<ReclaimReply>(answer.body).reason }.getOrNull() ?: REASON_REFUSED
                )
            }
        )
    }

    private fun setReclaim(next: ReclaimState): ReclaimState {
        _reclaim.value = next
        return next
    }

    // ---- Host: send the guest home ----

    /** Sends the guest back to its paired origin without any confirmation on either side. */
    suspend fun sendHome(extras: List<PeerAddress> = emptyList()): SendHomeResult {
        val row = store.get()
        if (row == null || row.role != TransferRole.GUEST_HOST ||
            (row.status != TransferStatus.HOSTING_GUEST && row.status != TransferStatus.RETURN_PENDING)
        ) return SendHomeResult.Failed(PetTransferStateMachine.REASON_NOT_HOSTING)
        val peerId = row.originDeviceId.orEmpty()
        val (peer, signer) = signerFor(peerId) ?: return SendHomeResult.Failed(REASON_NOT_PAIRED)
        val found = resolve(peer, signer, extras)
        val addr = found.address
        if (addr == null) {
            _events.tryEmit(PetLinkEvent.Unreachable(peer.displayName))
            return SendHomeResult.Unreachable(found.tried)
        }
        val ok = coordinator.sendGuestHomePaired(addr.host, addr.port, signer)
        if (!ok) {
            _events.tryEmit(PetLinkEvent.Unreachable(peer.displayName))
            val key = (coordinator.state.value as? PetTransferUiState.Error)?.reasonKey
            return SendHomeResult.Failed(key ?: REASON_REFUSED)
        }
        _events.tryEmit(PetLinkEvent.WentHome(row.petName.orEmpty(), peer.displayName))
        scheduleStopIfIdle()
        return SendHomeResult.Done
    }

    private fun scheduleStopIfIdle() {
        scope.launch {
            delay(config.stopDelayMs)
            if (linkVisit() == null) stopListening()
        }
    }

    // ---- PetLinkHost (server callbacks) ----

    override suspend fun keyFor(deviceId: String): ByteArray? = peers.keyFor(deviceId)

    override suspend fun onPing(peerDeviceId: String, remoteHost: String?, ping: LinkPing): LinkPong {
        if (ping.port in 1..65535) {
            val learned = ping.addresses.filter(::isPlainAddress).take(MAX_ADVERTISED)
                .map { PeerAddress(it, ping.port, PeerAddressSource.ADVERTISED, 0L) }.toMutableList()
            if (!remoteHost.isNullOrBlank() && isPlainAddress(remoteHost)) {
                learned += PeerAddress(remoteHost, ping.port, PeerAddressSource.LAN, System.currentTimeMillis())
            }
            peers.mergeAddresses(peerDeviceId, learned)
        }
        return LinkPong(identity.deviceId(), identity.deviceName(), listeningPort, LinkAddresses.local())
    }

    override suspend fun onReclaim(peerDeviceId: String, remoteHost: String?, request: ReclaimRequest): ReclaimReply {
        val row = store.get()
        val hosting = row != null && row.role == TransferRole.GUEST_HOST &&
            row.originDeviceId == peerDeviceId && row.petId == request.petId &&
            (row.status == TransferStatus.RETURN_PENDING ||
                (row.status == TransferStatus.HOSTING_GUEST && row.transferId == request.transferId))
        if (!hosting) return ReclaimReply(false, PetTransferStateMachine.REASON_NOT_HOSTING)
        if (!returning.compareAndSet(false, true)) return ReclaimReply(true) // already on its way home
        val extras = buildList {
            if (request.returnPort in 1..65535) {
                if (!remoteHost.isNullOrBlank()) add(PeerAddress(remoteHost, request.returnPort, PeerAddressSource.LAN, 0L))
                request.returnHost?.takeIf(::isPlainAddress)
                    ?.let { add(PeerAddress(it, request.returnPort, PeerAddressSource.ADVERTISED, 0L)) }
            }
        }
        scope.launch {
            try {
                sendHome(extras)
            } finally {
                returning.set(false)
            }
        }
        return ReclaimReply(true)
    }

    override suspend fun shouldAutoAccept(peerDeviceId: String, hello: Hello): Boolean {
        val row = store.get() ?: return false
        return row.role == TransferRole.ORIGIN && hello.isReturn &&
            hello.originDeviceId == identity.deviceId() && hello.petId == row.petId &&
            row.transferId != null && visitPeers.peerFor(row.transferId) == peerDeviceId
    }

    override fun onCommitted(peerDeviceId: String, hello: Hello) {
        if (!hello.isReturn) return
        coordinator.notifyPetChanged()
        coordinator.setUiState(PetTransferUiState.Done(hello.petName, returned = true))
        val r = _reclaim.value
        if (r is ReclaimState.Reclaiming || r is ReclaimState.WaitingForReturn) _reclaim.value = ReclaimState.Done
        _events.tryEmit(PetLinkEvent.PetBack(hello.petName))
        scheduleStopIfIdle()
    }

    private fun isPlainAddress(value: String): Boolean =
        value.length in 2..64 && value.all { it.isLetterOrDigit() || it == '.' || it == ':' || it == '-' }

    companion object {
        const val PATH_PING = "/link/ping"
        const val PATH_RECLAIM = "/link/reclaim"
        const val REASON_NOT_AWAY = "not_away"
        const val REASON_NOT_PAIRED = "not_paired"
        const val REASON_LISTEN_FAILED = "listen_failed"
        const val REASON_REFUSED = "refused"
        private const val MAX_ADVERTISED = 16
    }
}
