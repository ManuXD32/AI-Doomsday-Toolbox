package com.example.llamadroid.tama.transfer

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Tunables, mostly shortened in tests. */
data class PetTransferServerConfig(
    val acceptTimeoutMs: Long = 120_000L,
    val idleTimeoutMs: Long = 10 * 60_000L,
    val postCommitLingerMs: Long = 60_000L,
    val idleCheckMs: Long = 5_000L,
    val maxBadAttempts: Int = 5,
    val maxZipBytes: Long = 4L * 1024 * 1024 * 1024,
    /** Free space must be at least this multiple of the declared zip size. */
    val spaceFactor: Double = 2.5
)

/** Receiver side of the pet hand-off: `/hello`, `/upload`, `/commit`, `/status/{id}`. */
class PetTransferServer(
    private val engine: PetTransferEngine,
    private val store: PetTransferStore,
    private val identity: DeviceIdentity,
    private val paths: PetTransferPaths,
    private val code: String,
    private val scope: CoroutineScope,
    private val config: PetTransferServerConfig = PetTransferServerConfig(),
    private val freeSpace: () -> Long = { jvmFreeSpace(paths.incomingDir) },
    /** Asks the user; returns true to accept. Runs on a server thread, may block until answered. */
    private val onIncoming: suspend (Hello) -> Boolean,
    private val onProgress: (Hello, Long, Long) -> Unit = { _, _, _ -> },
    private val onCommitted: (Hello) -> Unit = {},
    private val onFailure: (Hello, String) -> Unit = { _, _ -> },
    private val onStopped: () -> Unit = {},
    private val committer: PetTransferReceiveCommit = PetTransferReceiveCommit(engine, store, identity, paths),
    /** When set, a pairing offered in /hello is stored after a successful commit. */
    private val peerStore: PetTransferPeerStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Paired-device endpoints (`/link/...`, code-less hellos); a server with [link] accepts only paired auth. */
    private val link: PetLinkHost? = null,
    /** 0 = any free port. */
    private val listenPort: Int = 0,
    /** Link listener: no idle shutdown, no linger after a commit, stale sessions are dropped. */
    private val persistent: Boolean = false
) : NanoHTTPD(listenPort) {
    private enum class Phase { ACCEPTED, STAGED, COMMITTED, FENCED }

    private class Session(
        val hello: Hello,
        var phase: Phase,
        var staged: File? = null,
        var pairing: PairingResult? = null,
        var pairKeys: PairingKeys? = null,
        var remoteHost: String? = null,
        val pairedDeviceId: String? = null,
        @Volatile var touchedAt: Long = System.currentTimeMillis()
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private var session: Session? = null
    private var finished = false
    private val badAttempts = AtomicInteger(0)
    private val lastActivity = AtomicLong(System.currentTimeMillis())
    private var idleJob: Job? = null
    private val stopNotified = AtomicBoolean(false)
    private val verifier = link?.let { host ->
        PairedAuthVerifier({ id -> runBlocking { host.keyFor(id) } }, clock)
    }

    val port: Int get() = listeningPort

    fun begin() {
        start(60_000, false)
        touch()
        if (persistent) return
        idleJob = scope.launch {
            while (isActive) {
                delay(config.idleCheckMs)
                if (System.currentTimeMillis() - lastActivity.get() > config.idleTimeoutMs) {
                    shutdown()
                    break
                }
            }
        }
    }

    /** Stops listening and removes any uncommitted partial/staged data. */
    fun shutdown() {
        synchronized(lock) {
            finished = true
            session?.let { s ->
                if (s.phase != Phase.COMMITTED) {
                    paths.part(s.hello.transferId).delete()
                    paths.incoming(s.hello.transferId).delete()
                }
            }
        }
        idleJob?.cancel()
        runCatching { stop() }
        if (stopNotified.compareAndSet(false, true)) onStopped()
    }

    private fun touch() = lastActivity.set(System.currentTimeMillis())

    override fun serve(session: IHTTPSession): Response {
        touch()
        return try {
            when {
                session.method == Method.POST && session.uri == "/hello" -> hello(session)
                session.method == Method.PUT && session.uri == "/upload" -> upload(session)
                session.method == Method.POST && session.uri == "/commit" -> commit(session)
                session.method == Method.POST && session.uri == "/link/ping" && link != null -> linkPing(session, link)
                session.method == Method.POST && session.uri == "/link/reclaim" && link != null -> linkReclaim(session, link)
                session.method == Method.GET && session.uri.startsWith("/status/") ->
                    status(session.uri.removePrefix("/status/"))
                else -> reply(Response.Status.NOT_FOUND, "{}")
            }
        } catch (e: Exception) {
            reply(Response.Status.INTERNAL_ERROR, "{}").also { it.closeConnection(true) }
        } finally {
            touch()
        }
    }

    private fun reply(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, "application/json", body)

    private inline fun <reified T> ok(value: T): Response =
        reply(Response.Status.OK, json.encodeToString(value))

    // ---- /hello ----

    private fun hello(http: IHTTPSession): Response {
        val body = readSmallBody(http) ?: return reply(Response.Status.BAD_REQUEST, "{}")
        val hello = runCatching { json.decodeFromString<Hello>(body) }.getOrNull()
            ?: return reply(Response.Status.BAD_REQUEST, "{}")
        val pairedId: String? = if (link != null) {
            authPaired(http, PairedAuth.sha256Hex(body.toByteArray(Charsets.UTF_8))) ?: return unauthorized()
        } else null
        if (pairedId == null) {
            if (badAttempts.get() >= config.maxBadAttempts) return ok(HelloReply(false, REASON_LOCKED))
            if (!PetTransferAuth.verify(hello, code)) {
                badAttempts.incrementAndGet()
                return ok(HelloReply(false, REASON_BAD_CODE))
            }
        }
        synchronized(lock) {
            if (finished) return ok(HelloReply(false, REASON_FINISHED))
            dropStaleSession()
            val existing = session
            if (existing != null) {
                if (existing.pairedDeviceId == pairedId &&
                    existing.hello.transferId == hello.transferId && sameTransfer(existing.hello, hello) &&
                    existing.phase != Phase.FENCED && existing.phase != Phase.COMMITTED
                ) {
                    // sender retry after a dropped connection; it may carry a fresh pairing key
                    existing.touchedAt = clock()
                    return ok(acceptedReply(existing, hello, http, pairedId))
                }
                return ok(HelloReply(false, PetTransferStateMachine.REASON_BUSY))
            }
        }
        if (hello.zipBytes <= 0 || hello.zipBytes > config.maxZipBytes || hello.zipSha256.length != 64) {
            return ok(HelloReply(false, REASON_INVALID))
        }
        val guard = runBlocking { PetTransferStateMachine.canReceive(store.get(), hello, identity.deviceId()) }
        if (!guard.allowed) return ok(HelloReply(false, guard.reason))
        if (freeSpace() < (hello.zipBytes * config.spaceFactor).toLong()) {
            return ok(HelloReply(false, REASON_NO_SPACE))
        }
        // Reserve the slot before asking the user so a second sender sees "busy".
        val fresh = Session(hello, Phase.FENCED, pairedDeviceId = pairedId)
        synchronized(lock) {
            if (session != null) return ok(HelloReply(false, PetTransferStateMachine.REASON_BUSY))
            session = fresh
        }
        val accepted = runBlocking {
            withTimeoutOrNull(config.acceptTimeoutMs) {
                if (pairedId != null && link != null) link.shouldAutoAccept(pairedId, hello) else onIncoming(hello)
            }
        }
        synchronized(lock) {
            if (accepted == true && !finished) {
                fresh.phase = Phase.ACCEPTED
                fresh.touchedAt = clock()
                return ok(acceptedReply(fresh, hello, http, pairedId))
            }
            session = null
        }
        return ok(HelloReply(false, if (accepted == null) REASON_TIMEOUT else REASON_DECLINED))
    }

    /** Hellos match when everything except the per-attempt pairing fields is equal. */
    private fun sameTransfer(a: Hello, b: Hello): Boolean =
        a.copy(deviceId = "", pairPub = "", pairMac = "") == b.copy(deviceId = "", pairPub = "", pairMac = "")

    /** Accepted reply; adds our pairing answer when the sender offered a valid one (caller holds no lock). */
    private fun acceptedReply(s: Session, hello: Hello, http: IHTTPSession, pairedId: String?): HelloReply {
        if (pairedId != null) return HelloReply(true)
        if (peerStore == null || !PetPairing.helloOfferValid(hello, code)) return HelloReply(true)
        val keys = s.pairKeys ?: PetPairing.generate().also { s.pairKeys = it }
        s.pairing = PetPairing.finishAsReceiver(hello, code, keys, identity.deviceId())
        s.remoteHost = http.remoteIpAddress
        if (s.pairing == null) return HelloReply(true)
        return PetPairing.answer(hello, code, identity.deviceId(), identity.deviceName(), keys)
    }

    private fun recordPairing(s: Session) {
        val store = peerStore ?: return
        val pairing = s.pairing ?: return
        val host = s.remoteHost
        val addresses = if (host.isNullOrBlank()) emptyList() else
            listOf(PeerAddress(host, 0, PeerAddressSource.LAN, clock()))
        runCatching {
            runBlocking { store.recordPairing(pairing.peerDeviceId, pairing.peerName, pairing.key, addresses) }
        }
    }

    // ---- /upload ----

    private fun authorised(http: IHTTPSession, transferId: String?): Boolean {
        if (transferId.isNullOrEmpty()) return false
        if (link != null) {
            val device = authPaired(http, PairedSigner.transferBodyHash(transferId)) ?: return false
            return synchronized(lock) { session?.takeIf { it.hello.transferId == transferId }?.pairedDeviceId == device }
        }
        if (badAttempts.get() >= config.maxBadAttempts) return false
        val ok = PetTransferAuth.verifyProof(transferId, code, http.headers["x-adt-auth"])
        if (!ok) badAttempts.incrementAndGet()
        return ok
    }

    private fun upload(http: IHTTPSession): Response {
        val id = http.headers["x-transfer-id"]
        if (!authorised(http, id)) return ok(StagedReply(false, REASON_BAD_CODE)).also { it.closeConnection(true) }
        val s = synchronized(lock) { session?.takeIf { it.hello.transferId == id } }
            ?: return ok(StagedReply(false, REASON_NOT_ACCEPTED)).also { it.closeConnection(true) }
        val hello = s.hello
        val phase = synchronized(lock) { s.phase }
        if (phase == Phase.STAGED || phase == Phase.COMMITTED) {
            // Already staged (sender retry after a receiver-side rollback or lost reply): consume the
            // body so the keep-alive connection is not left with unread bytes, which made the next
            // request on it (the retried /commit) fail with HTTP 400.
            drainBody(http)
            return ok(StagedReply(true))
        }
        synchronized(lock) {
            when (s.phase) {
                Phase.STAGED, Phase.COMMITTED -> Unit
                Phase.FENCED -> return ok(StagedReply(false, REASON_NOT_ACCEPTED)).also { it.closeConnection(true) }
                Phase.ACCEPTED -> Unit
            }
        }
        val declared = http.headers["content-length"]?.toLongOrNull()
        if (declared == null || declared != hello.zipBytes) {
            return ok(StagedReply(false, REASON_SIZE)).also { it.closeConnection(true) }
        }
        val part = paths.part(hello.transferId)
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        try {
            part.parentFile?.mkdirs()
            part.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                val input: InputStream = http.inputStream
                while (received < declared) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), declared - received).toInt())
                    if (n < 0) throw IOException("sender disconnected")
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    received += n
                    touch()
                    s.touchedAt = clock()
                    onProgress(hello, received, declared)
                }
            }
        } catch (e: IOException) {
            part.delete()
            onFailure(hello, REASON_INCOMPLETE)
            return ok(StagedReply(false, REASON_INCOMPLETE)).also { it.closeConnection(true) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(hello.zipSha256, ignoreCase = true)) {
            part.delete()
            onFailure(hello, REASON_HASH_MISMATCH)
            return ok(StagedReply(false, REASON_HASH_MISMATCH))
        }
        val staged = paths.incoming(hello.transferId)
        staged.delete()
        if (!part.renameTo(staged)) {
            part.delete()
            return ok(StagedReply(false, REASON_IO))
        }
        val valid = runBlocking { engine.validateZip(staged) }
        if (!valid) {
            staged.delete()
            onFailure(hello, REASON_INVALID_ZIP)
            return ok(StagedReply(false, REASON_INVALID_ZIP))
        }
        synchronized(lock) {
            s.staged = staged
            s.phase = Phase.STAGED
        }
        return ok(StagedReply(true))
    }

    // ---- /commit ----

    private fun commit(http: IHTTPSession): Response {
        val id = http.headers["x-transfer-id"]
        if (!authorised(http, id)) return ok(CommitReply(false, REASON_BAD_CODE))
        if (paths.isCompleted(id!!)) return ok(CommitReply(true))
        val s = synchronized(lock) { session?.takeIf { it.hello.transferId == id } }
            ?: return ok(CommitReply(false, REASON_NOT_ACCEPTED))
        val staged: File
        synchronized(lock) {
            if (s.phase == Phase.COMMITTED) return ok(CommitReply(true))
            if (s.phase != Phase.STAGED) return ok(CommitReply(false, REASON_NOT_STAGED))
            staged = s.staged ?: return ok(CommitReply(false, REASON_NOT_STAGED))
        }
        val reply = runBlocking { committer.commit(s.hello, staged) }
        if (reply.committed) {
            synchronized(lock) { s.phase = Phase.COMMITTED }
            recordPairing(s)
            onCommitted(s.hello)
            val device = s.pairedDeviceId
            if (device != null && link != null) link.onCommitted(device, s.hello)
            if (persistent) {
                synchronized(lock) { if (session === s) session = null }
            } else {
                scope.launch {
                    delay(config.postCommitLingerMs)
                    shutdown()
                }
            }
        } else {
            onFailure(s.hello, reply.reason ?: PetTransferReceiveCommit.REASON_COMMIT_FAILED)
        }
        return ok(reply)
    }

    // ---- /status/{id} (unauthenticated: the id is an unguessable UUID and the answer is not secret) ----

    private fun status(id: String): Response {
        if (paths.isCompleted(id)) return ok(StatusReply(TransferWireState.COMMITTED))
        val state = synchronized(lock) {
            val s = session?.takeIf { it.hello.transferId == id }
            when {
                s == null -> TransferWireState.NONE
                s.phase == Phase.COMMITTED -> TransferWireState.COMMITTED
                else -> {
                    // Fence: the sender is treating this transfer as undecided, so a delayed
                    // /commit must not land after it concluded "not committed".
                    val staged = s.phase == Phase.STAGED
                    s.phase = Phase.FENCED
                    if (staged) TransferWireState.STAGED else TransferWireState.NONE
                }
            }
        }
        return ok(StatusReply(state))
    }

    // ---- paired-device plumbing ----

    private fun unauthorized(): Response = reply(Response.Status.UNAUTHORIZED, "{}")

    /** Paired device id when the request carries valid, fresh, unreplayed signature headers; else null. */
    private fun authPaired(http: IHTTPSession, bodySha256: String): String? {
        val check = verifier ?: return null
        val h = http.headers
        val headers = PairedAuthHeaders(
            deviceId = h[PairedAuth.HEADER_DEVICE.lowercase()].orEmpty(),
            timestamp = h[PairedAuth.HEADER_TIMESTAMP.lowercase()]?.toLongOrNull() ?: return null,
            nonce = h[PairedAuth.HEADER_NONCE.lowercase()].orEmpty(),
            signature = h[PairedAuth.HEADER_SIGNATURE.lowercase()].orEmpty()
        )
        val result = check.verify(headers, http.method.name, http.uri, bodySha256)
        return if (result == PairedAuthResult.OK) headers.deviceId else null
    }

    /** Link listener only: a session nobody touched for [STALE_SESSION_MS] must not block the next visit forever. */
    private fun dropStaleSession() {
        val s = session ?: return
        if (!persistent || s.phase == Phase.COMMITTED) return
        if (clock() - s.touchedAt < STALE_SESSION_MS) return
        paths.part(s.hello.transferId).delete()
        paths.incoming(s.hello.transferId).delete()
        session = null
    }

    private fun linkPing(http: IHTTPSession, host: PetLinkHost): Response {
        val body = readSmallBody(http) ?: return reply(Response.Status.BAD_REQUEST, "{}")
        val device = authPaired(http, PairedAuth.sha256Hex(body.toByteArray(Charsets.UTF_8))) ?: return unauthorized()
        val ping = runCatching { json.decodeFromString<LinkPing>(body) }.getOrNull()
            ?: return reply(Response.Status.BAD_REQUEST, "{}")
        val pong = runBlocking { host.onPing(device, remoteHostOf(http), ping) }
        val text = json.encodeToString(pong)
        val response = reply(Response.Status.OK, text)
        val key = runBlocking { host.keyFor(device) } ?: return unauthorized()
        val nonce = http.headers[PairedAuth.HEADER_NONCE.lowercase()].orEmpty()
        val ts = clock()
        response.addHeader(PairedAuth.HEADER_DEVICE, identity.deviceId())
        response.addHeader(PairedAuth.HEADER_TIMESTAMP, ts.toString())
        response.addHeader(PairedAuth.HEADER_NONCE, nonce)
        response.addHeader(
            PairedAuth.HEADER_SIGNATURE,
            PairedAuth.mac(
                key, PairedSigner.REPLY_METHOD, http.uri, ts, nonce,
                PairedAuth.sha256Hex(text.toByteArray(Charsets.UTF_8))
            )
        )
        return response
    }

    private fun linkReclaim(http: IHTTPSession, host: PetLinkHost): Response {
        val body = readSmallBody(http) ?: return reply(Response.Status.BAD_REQUEST, "{}")
        val device = authPaired(http, PairedAuth.sha256Hex(body.toByteArray(Charsets.UTF_8))) ?: return unauthorized()
        val request = runCatching { json.decodeFromString<ReclaimRequest>(body) }.getOrNull()
            ?: return reply(Response.Status.BAD_REQUEST, "{}")
        val answer = runBlocking { host.onReclaim(device, remoteHostOf(http), request) }
        return reply(
            if (answer.accepted) Response.Status.ACCEPTED else Response.Status.CONFLICT,
            json.encodeToString(answer)
        )
    }

    private fun remoteHostOf(http: IHTTPSession): String? =
        http.remoteIpAddress?.substringBefore('%')?.takeIf { it.isNotBlank() }

    private fun drainBody(http: IHTTPSession) {
        var left = http.headers["content-length"]?.toLongOrNull() ?: return
        val buffer = ByteArray(64 * 1024)
        try {
            while (left > 0) {
                val n = http.inputStream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n < 0) return
                left -= n
                touch()
            }
        } catch (_: IOException) {
        }
    }

    private fun readSmallBody(http: IHTTPSession): String? {
        val len = http.headers["content-length"]?.toIntOrNull() ?: return null
        if (len <= 0 || len > MAX_HELLO_BYTES) return null
        val bytes = ByteArray(len)
        var off = 0
        val input = http.inputStream
        while (off < len) {
            val n = input.read(bytes, off, len - off)
            if (n < 0) return null
            off += n
        }
        return String(bytes, Charsets.UTF_8)
    }

    companion object {
        private const val MAX_HELLO_BYTES = 64 * 1024
        private const val STALE_SESSION_MS = 2 * 60_000L
        const val REASON_BAD_CODE = "bad_code"
        const val REASON_LOCKED = "locked"
        const val REASON_FINISHED = "finished"
        const val REASON_INVALID = "invalid_request"
        const val REASON_NO_SPACE = "insufficient_space"
        const val REASON_TIMEOUT = "timeout"
        const val REASON_DECLINED = "declined"
        const val REASON_NOT_ACCEPTED = "not_accepted"
        const val REASON_NOT_STAGED = "not_staged"
        const val REASON_SIZE = "size_mismatch"
        const val REASON_INCOMPLETE = "incomplete"
        const val REASON_HASH_MISMATCH = "hash_mismatch"
        const val REASON_INVALID_ZIP = "invalid_zip"
        const val REASON_IO = "io_error"
    }
}
