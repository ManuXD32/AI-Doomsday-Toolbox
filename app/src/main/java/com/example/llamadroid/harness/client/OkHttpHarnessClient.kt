package com.example.llamadroid.harness.client

import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okio.buffer

/** OkHttp implementation of the published alpha2 HTTP and Remote mux contracts. */
class OkHttpHarnessClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    onTransportEvent: (HarnessTransportEvent) -> Unit = {},
) : HarnessClient {
    private val mutableState = MutableStateFlow(HarnessConnectionState.DISCONNECTED)
    private val receipts = HarnessRequestReceipts()
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val authMutex = Mutex()
    /** Bounded asynchronous sink: diagnostics can never block OkHttp callbacks. */
    private val transportEventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val transportEvents = Channel<HarnessTransportEvent>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        transportEventScope.launch {
            for (event in transportEvents) runCatching { onTransportEvent(event) }
        }
    }
    private val authHttpClient = httpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val mux = HarnessMux(
        httpClient = authHttpClient,
        webSocketUrl = { webSocketUrl() },
        cookieHeader = { cookieHeader },
        originHeader = { origin },
        onTransportEvent = ::emitTransportEvent,
    )

    @Volatile
    private var origin: String? = null

    @Volatile
    private var cookieHeader: String? = null

    override val state: StateFlow<HarnessConnectionState> = mutableState

    override suspend fun authenticate(launchUrl: String): HarnessAuthResult = authMutex.withLock {
        if (mutableState.value == HarnessConnectionState.CLOSED) return@withLock HarnessAuthResult.Failure("client/closed", "Harness client is closed")
        updateConnection(HarnessConnectionState.AUTHENTICATING)
        try {
            val launch = parseLoopbackUrl(launchUrl)
            val root = launch.newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
            val exchange = Request.Builder().url(launch).get().build()
            val exchangeResponse = executeAuthenticatedRequest(exchange)
            val setCookie = exchangeResponse.use { response ->
                if (response.code != 303) {
                    return@withLock HarnessAuthResult.Failure(
                        "auth/exchange-${response.code}",
                        "Harness launch URL was rejected"
                    ).also { updateConnection(HarnessConnectionState.FAILED) }
                }
                response.headers("Set-Cookie")
                    .asSequence()
                    .mapNotNull(::cookiePair)
                    .firstOrNull()
            }
            if (setCookie == null) {
                updateConnection(HarnessConnectionState.FAILED)
                return@withLock HarnessAuthResult.Failure(
                    "auth/missing-cookie",
                    "Harness did not issue a session cookie"
                )
            }
            cookieHeader = setCookie
            origin = root.toString().trimEnd('/')
            mux.reset()
            receipts.clear()

            // The clean request proves that the cookie is accepted before native
            // screens receive a READY state. The response body is intentionally dropped.
            val proof = Request.Builder().url(root).header("Cookie", setCookie).get().build()
            val proofResponse = executeAuthenticatedRequest(proof)
            proofResponse.use { response ->
                if (!response.isSuccessful) {
                    updateConnection(HarnessConnectionState.FAILED)
                    return@withLock HarnessAuthResult.Failure(
                        "auth/cookie-rejected",
                        "Harness session cookie was rejected"
                    )
                }
            }
            updateConnection(HarnessConnectionState.READY)
            HarnessAuthResult.Success(origin = origin ?: error("Harness origin was not retained"))
        } catch (cancelled: CancellationException) {
            updateConnection(HarnessConnectionState.DISCONNECTED)
            throw cancelled
        } catch (error: Throwable) {
            updateConnection(HarnessConnectionState.FAILED)
            HarnessAuthResult.Failure("auth/transport", safeMessage(error))
        }
    }

    override fun adoptAuthenticatedEndpoint(
        origin: String,
        cookieHeader: String
    ): HarnessAuthResult {
        if (mutableState.value == HarnessConnectionState.CLOSED) return HarnessAuthResult.Failure("client/closed", "Harness client is closed")
        val endpointOrigin = origin
        val endpointCookie = cookieHeader
        return try {
            val parsed = endpointOrigin.toHttpUrlOrNull()
                ?: throw IllegalArgumentException("Harness origin is invalid")
            require(parsed.scheme == "http") { "Harness origin must use loopback HTTP" }
            require(parsed.host == "127.0.0.1" || parsed.host == "::1") {
                "Harness origin must use a loopback host"
            }
            require(parsed.encodedPath == "/" || parsed.encodedPath.isEmpty()) {
                "Harness origin must not contain a path"
            }
            require(parsed.querySize == 0 && parsed.fragment == null) {
                "Harness origin must not contain query or fragment"
            }
            require(cookiePair(endpointCookie) == endpointCookie.trim()) {
                "Harness cookie header is invalid"
            }
            this.origin = parsed.newBuilder().encodedPath("/").build().toString().trimEnd('/')
            this.cookieHeader = endpointCookie.trim()
            mux.reset()
            receipts.clear()
            updateConnection(HarnessConnectionState.READY)
            HarnessAuthResult.Success(requireNotNull(this.origin))
        } catch (error: Throwable) {
            updateConnection(HarnessConnectionState.FAILED)
            HarnessAuthResult.Failure("auth/invalid-endpoint", safeMessage(error))
        }
    }

    override suspend fun call(
        namespace: String,
        method: String,
        args: JsonObject,
        policy: HarnessCallPolicy,
        requestId: String
    ): HarnessRpcResult {
        val endpoint = try {
            HarnessEndpoint(namespace, method)
        } catch (error: IllegalArgumentException) {
            return HarnessRpcResult.Failure(HarnessRpcError("client/invalid-endpoint", error.message.orEmpty()))
        }
        if (mutableState.value == HarnessConnectionState.CLOSED) {
            return HarnessRpcResult.Failure(HarnessRpcError("client/closed", "Harness client is closed"))
        }
        if (requestId.length !in 1..128 || requestId.any { it.isWhitespace() || it.isISOControl() }) {
            return HarnessRpcResult.Failure(HarnessRpcError("client/invalid-request-id", "Request ID is invalid"))
        }
        val currentOrigin = origin
            ?: return HarnessRpcResult.Failure(
                HarnessRpcError("transport/not-authenticated", "Harness is not authenticated")
            )
        val cookie = cookieHeader
            ?: return HarnessRpcResult.Failure(
                HarnessRpcError("transport/not-authenticated", "Harness session cookie is unavailable")
            )

        return receipts.execute(requestId, endpoint.endpoint + "\n" + args) {
            callWithPolicy(currentOrigin, cookie, endpoint, args, requestId, policy)
        }
    }

    private suspend fun callWithPolicy(
        currentOrigin: String, cookie: String, endpoint: HarnessEndpoint, args: JsonObject,
        requestId: String, policy: HarnessCallPolicy
    ): HarnessRpcResult {
        val startedAt = System.nanoTime()
        emitTransportEvent(
            HarnessTransportEvent(
                kind = HarnessTransportKind.RPC,
                phase = HarnessTransportPhase.START,
                namespace = endpoint.namespace,
                method = endpoint.method,
            )
        )
        var attempt = 1
        var backoff = policy.initialBackoffMs
        while (true) {
            val result = withContext(Dispatchers.IO) {
                executeUnary(currentOrigin, cookie, endpoint, args, requestId, policy)
            }
            when (result) {
                is AttemptResult.Success -> {
                    updateConnection(HarnessConnectionState.READY)
                    val logicalError = (result.value as? HarnessRpcResult.Failure)?.error
                    emitTransportEvent(
                        HarnessTransportEvent(
                            kind = HarnessTransportKind.RPC,
                            phase = if (logicalError == null) HarnessTransportPhase.SUCCESS else HarnessTransportPhase.FAILURE,
                            namespace = endpoint.namespace,
                            method = endpoint.method,
                            outcome = if (logicalError == null) "success" else "failure",
                            durationMs = elapsedMs(startedAt),
                            httpStatus = result.httpStatus,
                            errorCode = logicalError?.code?.take(MAX_DIAGNOSTIC_CODE_LENGTH),
                        )
                    )
                    return result.value
                }
                is AttemptResult.Failure -> {
                    if (attempt >= policy.maxAttempts || !result.retryable) {
                        if (result.connectionFailure) updateConnection(HarnessConnectionState.FAILED)
                        else updateConnection(HarnessConnectionState.READY)
                        emitTransportEvent(
                            HarnessTransportEvent(
                                kind = HarnessTransportKind.RPC,
                                phase = HarnessTransportPhase.FAILURE,
                                namespace = endpoint.namespace,
                                method = endpoint.method,
                                outcome = "failure",
                                durationMs = elapsedMs(startedAt),
                                httpStatus = result.httpStatus,
                                errorCode = (result.value as? HarnessRpcResult.Failure)?.error?.code
                                    ?.take(MAX_DIAGNOSTIC_CODE_LENGTH),
                            )
                        )
                        return result.value
                    }
                }
            }
            updateConnection(HarnessConnectionState.RECONNECTING)
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(policy.maxBackoffMs)
            attempt += 1
            updateConnection(HarnessConnectionState.READY)
        }
    }

    override suspend fun fetchJson(
        path: String, query: Map<String, String>, body: JsonObject?, requestId: String
    ): HarnessRpcResult {
        if (!path.matches(API_ROUTE_PATTERN) || path.endsWith("/..") ||
            query.size > 32 || query.any { it.key.length > 128 || it.value.length > 16_384 } ||
            requestId.length !in 1..128 || requestId.any { it.isWhitespace() || it.isISOControl() }) {
            return HarnessRpcResult.Failure(HarnessRpcError("client/invalid-endpoint", "Harness HTTP route is invalid"))
        }
        if (mutableState.value == HarnessConnectionState.CLOSED) {
            return HarnessRpcResult.Failure(HarnessRpcError("client/closed", "Harness client is closed"))
        }
        val base = origin
        val cookie = cookieHeader
        if (base == null || cookie == null) return HarnessRpcResult.Failure(
            HarnessRpcError("transport/not-authenticated", "Harness is not authenticated")
        )
        val url = base.toHttpUrl().newBuilder().encodedPath(path).apply {
            query.toSortedMap().forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        return receipts.execute(requestId, "http\n$url\n${body ?: "GET"}") {
            val (diagnosticNamespace, diagnosticMethod) = diagnosticRoute(path)
            val startedAt = System.nanoTime()
            emitTransportEvent(
                HarnessTransportEvent(
                    kind = HarnessTransportKind.RPC,
                    phase = HarnessTransportPhase.START,
                    namespace = diagnosticNamespace,
                    method = diagnosticMethod,
                )
            )
            var httpStatus: Int? = null
            val result = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).header("Cookie", cookie)
                    .header("Accept", "application/json").apply {
                        if (body == null) get() else post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    }.build()
                try {
                    executeAuthenticatedRequest(request).use { response ->
                        httpStatus = response.code
                        if (!response.isSuccessful) return@withContext HarnessRpcResult.Failure(
                            HarnessRpcError("transport/http-${response.code}", "Harness route returned HTTP ${response.code}",
                                buildJsonObject { put("status", response.code) })
                        )
                        if (response.code == 204) return@withContext HarnessRpcResult.Success()
                        val source = response.body?.source() ?: return@withContext HarnessRpcResult.Failure(
                            HarnessRpcError("transport/empty-response", "Harness response was empty")
                        )
                        require(!source.request(MAX_RESPONSE_BYTES + 1)) { "HARNESS_RESPONSE_TOO_LARGE" }
                        HarnessRpcResult.Success(HarnessJson.parseToJsonElement(source.readUtf8()))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IOException) {
                    HarnessRpcResult.Failure(HarnessRpcError("transport/io", "Harness connection failed"))
                } catch (_: Exception) {
                    HarnessRpcResult.Failure(HarnessRpcError("transport/invalid-response", "Harness response was invalid"))
                }
            }
            val logicalError = (result as? HarnessRpcResult.Failure)?.error
            emitTransportEvent(
                HarnessTransportEvent(
                    kind = HarnessTransportKind.RPC,
                    phase = if (logicalError == null) HarnessTransportPhase.SUCCESS else HarnessTransportPhase.FAILURE,
                    namespace = diagnosticNamespace,
                    method = diagnosticMethod,
                    outcome = if (logicalError == null) "success" else "failure",
                    durationMs = elapsedMs(startedAt),
                    httpStatus = httpStatus,
                    errorCode = logicalError?.code?.take(MAX_DIAGNOSTIC_CODE_LENGTH),
                )
            )
            result
        }
    }

    override fun stream(
        namespace: String,
        method: String,
        args: JsonObject,
        policy: HarnessStreamPolicy
    ): Flow<kotlinx.serialization.json.JsonElement> {
        val endpoint = try {
            HarnessEndpoint(namespace, method)
        } catch (error: IllegalArgumentException) {
            return flow { throw error }
        }
        return flow {
            val seen = LinkedHashSet<String>()
            var reconnects = 0
            var backoff = policy.initialBackoffMs
            while (currentCoroutineContext().isActive) {
                try {
                    mux.open(endpoint, args).collect { value ->
                        // A delivered frame proves that this physical generation is healthy.
                        // Reset delay, while keeping the total logical-stream retry budget
                        // bounded even if a server emits one frame per failed generation.
                        backoff = policy.initialBackoffMs
                        val key = policy.deduplicationKey?.invoke(value)?.takeIf { it.length <= 256 }
                        if (key == null || seen.add(key)) {
                            if (seen.size > 4096) seen.iterator().run { next(); remove() }
                            emit(value)
                        }
                    }
                    return@flow
                } catch (transport: HarnessTransportException) {
                    if (mutableState.value == HarnessConnectionState.CLOSED) throw transport
                    if (reconnects >= policy.maxReconnects) {
                        updateConnection(HarnessConnectionState.FAILED)
                        throw transport
                    }
                    reconnects += 1
                    emitTransportEvent(
                        HarnessTransportEvent(
                            kind = HarnessTransportKind.WEBSOCKET,
                            phase = HarnessTransportPhase.RECONNECT,
                            outcome = "retry",
                            errorCode = "transport/ws-failure",
                        )
                    )
                    updateConnection(HarnessConnectionState.RECONNECTING)
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(policy.maxBackoffMs)
                    updateConnection(HarnessConnectionState.READY)
                }
            }
        }
    }

    override fun close() {
        if (mutableState.value == HarnessConnectionState.CLOSED) return
        mutableState.value = HarnessConnectionState.CLOSED
        activeCalls.toList().forEach(Call::cancel)
        receipts.clear()
        mux.close()
        transportEvents.close()
    }

    private fun updateConnection(value: HarnessConnectionState) {
        mutableState.update { if (it == HarnessConnectionState.CLOSED) it else value }
    }

    private fun emitTransportEvent(event: HarnessTransportEvent) {
        transportEvents.trySend(event)
    }

    private suspend fun executeUnary(
        baseOrigin: String,
        cookie: String,
        endpoint: HarnessEndpoint,
        args: JsonObject,
        requestId: String,
        policy: HarnessCallPolicy
    ): AttemptResult {
        val request = Request.Builder()
            .url("$baseOrigin${HarnessWire.API_CHANNEL}/${endpoint.endpoint}")
            .header("Content-Type", "application/json")
            .header("Cookie", cookie)
            .post(rpcRequestJson(requestId, endpoint, args).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return try {
            executeAuthenticatedRequest(request).use { response ->
                if (!response.isSuccessful) {
                    val shouldRetry = response.code in policy.retryHttpStatuses
                    return AttemptResult.Failure(
                        HarnessRpcResult.Failure(
                            HarnessRpcError(
                                code = "transport/http-${response.code}",
                                message = "Harness RPC returned HTTP ${response.code}",
                                details = buildJsonObject { put("status", response.code) },
                            )
                        ),
                        retryable = shouldRetry,
                        httpStatus = response.code,
                        connectionFailure = shouldRetry || response.code == 401 || response.code == 403,
                    )
                }
                val responseBody = response.body
                    ?: return AttemptResult.Failure(
                        HarnessRpcResult.Failure(HarnessRpcError("transport/empty-response", "Harness response was empty")),
                        retryable = false,
                        connectionFailure = true,
                    )
                try {
                    val source = responseBody.source()
                    require(!source.request(MAX_RESPONSE_BYTES + 1)) { "HARNESS_RESPONSE_TOO_LARGE" }
                    val body = source.readUtf8()
                    AttemptResult.Success(parseRpcResponse(body, requestId), httpStatus = response.code)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    AttemptResult.Failure(
                        HarnessRpcResult.Failure(
                            HarnessRpcError("transport/invalid-response", "Harness response was invalid")
                        ),
                        retryable = false,
                        httpStatus = response.code,
                        connectionFailure = true,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            AttemptResult.Failure(
                HarnessRpcResult.Failure(HarnessRpcError("transport/io", "Harness connection failed")),
                retryable = true,
                connectionFailure = true,
            )
        }
    }

    /** The call remains cancellable through body consumption, not only until headers arrive. */
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun executeAuthenticatedRequest(request: Request): Response {
        val call = authHttpClient.newCall(request)
        activeCalls.add(call)
        if (mutableState.value == HarnessConnectionState.CLOSED) {
            call.cancel(); activeCalls.remove(call)
            throw IOException("Harness client is closed")
        }
        val parent = currentCoroutineContext()[Job]
        val cancellation = parent?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (it != null) call.cancel()
        }
        return try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        activeCalls.remove(call)
                        cancellation?.dispose()
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val original = response.body
                        if (original == null) { activeCalls.remove(call); cancellation?.dispose() }
                        val tracked = original?.let { body -> object : okhttp3.ResponseBody() {
                            private val source = object : okio.ForwardingSource(body.source()) {
                                override fun close() {
                                    try { super.close() } finally { activeCalls.remove(call); cancellation?.dispose() }
                                }
                            }.buffer()
                            override fun contentType() = body.contentType()
                            override fun contentLength() = body.contentLength()
                            override fun source() = source
                        } }
                        val result = response.newBuilder().body(tracked).build()
                        continuation.resume(result) { _, responseToClose, _ -> responseToClose.close() }
                    }
                })
            }
        } catch (error: Throwable) {
            call.cancel(); activeCalls.remove(call); cancellation?.dispose()
            throw error
        }
    }

    private fun webSocketUrl(): String {
        val base = origin ?: throw HarnessTransportException("Harness is not authenticated")
        val parsed = base.toHttpUrl()
        return parsed.newBuilder()
            // OkHttp's HttpUrl.Builder accepts HTTP(S) schemes here. Its
            // newWebSocket call performs the HTTP(S) to WS(S) upgrade.
            .scheme(parsed.scheme)
            .encodedPath(HarnessWire.REMOTE_MUX_PATH)
            .query(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun parseLoopbackUrl(raw: String): HttpUrl {
        val parsed = raw.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Harness launch URL is invalid")
        require(parsed.scheme == "http") { "Harness launch URL must use loopback HTTP" }
        require(parsed.host == "127.0.0.1" || parsed.host == "::1") {
            "Harness launch URL must use a loopback host"
        }
        require(parsed.queryParameter("token")?.isNotBlank() == true) {
            "Harness launch URL has no process token"
        }
        return parsed
    }

    private fun cookiePair(header: String): String? {
        val pair = header.substringBefore(';').trim()
        val separator = pair.indexOf('=')
        if (separator <= 0 || separator == pair.lastIndex) return null
        if (pair.any(Char::isISOControl)) return null
        return pair
    }

    private fun diagnosticRoute(path: String): Pair<String, String?> {
        val route = path.removePrefix(HarnessWire.API_CHANNEL).trimStart('/')
        val separator = route.indexOf('/')
        if (separator < 0) return route.take(MAX_DIAGNOSTIC_NAMESPACE_LENGTH) to null
        return route.substring(0, separator).take(MAX_DIAGNOSTIC_NAMESPACE_LENGTH) to
            route.substring(separator + 1).take(MAX_DIAGNOSTIC_METHOD_LENGTH).ifEmpty { null }
    }

    private sealed interface AttemptResult {
        data class Success(val value: HarnessRpcResult, val httpStatus: Int? = null) : AttemptResult
        data class Failure(
            val value: HarnessRpcResult,
            val retryable: Boolean,
            val httpStatus: Int? = null,
            val connectionFailure: Boolean = true,
        ) : AttemptResult
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 24L * 1024L * 1024L
        const val MAX_DIAGNOSTIC_CODE_LENGTH = 128
        const val MAX_DIAGNOSTIC_NAMESPACE_LENGTH = 128
        const val MAX_DIAGNOSTIC_METHOD_LENGTH = 256
        val API_ROUTE_PATTERN = Regex("""/api/(?:[A-Za-z0-9_$.-]+/)*[A-Za-z0-9_$.-]+""")
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun safeMessage(error: Throwable): String =
            error.message?.takeIf { it.isNotBlank() }?.take(240) ?: "Harness authentication failed"

        fun elapsedMs(startedAt: Long): Long =
            ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
    }
}

internal class HarnessTransportException(message: String, cause: Throwable? = null) : IOException(message, cause)

internal class HarnessRemoteException(val error: HarnessRpcError) : IOException(error.message)

/** Shared physical Remote mux. Overflow interrupts a logical stream so its owner refreshes a snapshot. */
internal class HarnessMux(
    private val httpClient: OkHttpClient,
    private val webSocketUrl: () -> String,
    private val cookieHeader: () -> String?,
    private val openSocket: (Request, WebSocketListener) -> WebSocket = httpClient::newWebSocket,
    private val originHeader: () -> String? = { null },
    private val onTransportEvent: (HarnessTransportEvent) -> Unit = {},
) {
    private val lock = Any()
    private data class QueuedFrame(val frame: HarnessStreamFrame, val size: Long)
    private val streams = ConcurrentHashMap<String, SendChannel<QueuedFrame>>()
    private val pendingBytes = java.util.concurrent.atomic.AtomicLong()
    private var socket: WebSocket? = null
    private var pendingSocket: WebSocket? = null
    private var connecting: kotlinx.coroutines.CompletableDeferred<WebSocket>? = null
    private var generation = 0L
    private var disposed = false

    fun open(endpoint: HarnessEndpoint, args: JsonObject): Flow<kotlinx.serialization.json.JsonElement> = callbackFlow {
        val physical = ensureSocket()
        val streamId = UUID.randomUUID().toString()
        val frames = kotlinx.coroutines.channels.Channel<QueuedFrame>(64, onUndeliveredElement = { pendingBytes.addAndGet(-it.size) })
        synchronized(lock) {
            if (socket !== physical || disposed) throw HarnessTransportException("Harness connection changed")
            if (streams.size >= 128) throw HarnessTransportException("Harness has too many streams")
            streams[streamId] = frames
            if (!physical.send(streamOpenJson(streamId, endpoint, args))) {
                streams.remove(streamId)
                frames.close(HarnessTransportException("Harness stream open was rejected"))
            }
        }
        val collector = launch {
            try {
                for (queued in frames) {
                    try { when (val frame = queued.frame) {
                        is HarnessStreamFrame.Item -> send(frame.value)
                        is HarnessStreamFrame.End -> { close(); break }
                        is HarnessStreamFrame.Error -> { close(HarnessRemoteException(frame.error)); break }
                    } } finally { pendingBytes.addAndGet(-queued.size) }
                }
                close()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                close(error)
            } finally {
                streams.remove(streamId)
                frames.cancel()
                sendCancel(physical, streamId)
            }
        }
        awaitClose {
            collector.cancel()
            frames.cancel()
            streams.remove(streamId)
            sendCancel(physical, streamId)
        }
    }.buffer(0)

    fun reset() = synchronized(lock) {
        generation++
        connecting?.completeExceptionally(HarnessTransportException("Harness connection reset"))
        connecting = null
        pendingSocket?.cancel(); pendingSocket = null
        val old = socket
        socket = null
        old?.cancel()
        failStreams(HarnessTransportException("Harness connection reset"))
    }

    fun close() = synchronized(lock) {
        disposed = true
        reset()
    }

    private suspend fun ensureSocket(): WebSocket {
        var startConnection = false
        var capturedGeneration = 0L
        val deferred = synchronized(lock) {
            if (disposed) throw HarnessTransportException("Harness client closed")
            socket?.let { return it }
            capturedGeneration = generation
            connecting ?: kotlinx.coroutines.CompletableDeferred<WebSocket>().also {
                connecting = it
                startConnection = true
            }
        }
        if (startConnection) {
            val startedAt = System.nanoTime()
            onTransportEvent(
                HarnessTransportEvent(
                    kind = HarnessTransportKind.WEBSOCKET,
                    phase = HarnessTransportPhase.START,
                )
            )
            try {
                val request = Request.Builder()
                    .url(webSocketUrl())
                    .header("Cookie", cookieHeader() ?: throw HarnessTransportException("Harness cookie is unavailable"))
                    .apply { originHeader()?.let { header("Origin", it) } }
                    .build()
                val pending = openSocket(request, Listener(deferred, capturedGeneration, startedAt))
                synchronized(lock) {
                    if (connecting === deferred && generation == capturedGeneration && !disposed) pendingSocket = pending
                    else if (socket !== pending) pending.cancel()
                }
            } catch (error: Exception) {
                onTransportEvent(
                    HarnessTransportEvent(
                        kind = HarnessTransportKind.WEBSOCKET,
                        phase = HarnessTransportPhase.FAILURE,
                        outcome = "failure",
                        durationMs = elapsedMs(startedAt),
                        errorCode = "transport/ws-failure",
                    )
                )
                synchronized(lock) {
                    if (connecting === deferred) connecting = null
                    deferred.completeExceptionally(HarnessTransportException("Harness WebSocket failed", error))
                }
            }
        }
        return try {
            deferred.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw if (error is HarnessTransportException) error
            else HarnessTransportException("Harness WebSocket failed", error)
        }
    }

    private fun sendCancel(physical: WebSocket, streamId: String) = synchronized(lock) {
        if (socket === physical) physical.send(streamCancelJson(streamId))
        Unit
    }

    private fun receive(physical: WebSocket, text: String) = synchronized(lock) {
        if (socket !== physical) return@synchronized
        try {
            require(text.length <= MAX_FRAME_CHARACTERS) { "Harness stream frame is too large" }
            val frame = parseStreamFrame(text)
            val channel = streams[frame.streamId] ?: return@synchronized
            when (frame) {
                is HarnessStreamFrame.Item -> {
                    val size = text.length * 2L
                    val withinBudget = pendingBytes.addAndGet(size) <= MAX_PENDING_BYTES
                    if (!withinBudget || channel.trySend(QueuedFrame(frame, size)).isFailure) {
                        pendingBytes.addAndGet(-size)
                        streams.remove(frame.streamId, channel)
                        channel.close(HarnessTransportException("Harness stream consumer fell behind"))
                        physical.send(streamCancelJson(frame.streamId))
                    }
                }
                is HarnessStreamFrame.End -> channel.close()
                is HarnessStreamFrame.Error -> channel.close(HarnessRemoteException(frame.error))
            }
        } catch (error: Exception) {
            failPhysical(physical, HarnessTransportException("Harness stream frame was invalid", error))
            physical.cancel()
        }
        Unit
    }

    private fun failPhysical(physical: WebSocket, error: HarnessTransportException) = synchronized(lock) {
        // A late failure/close from a replaced connection cannot interrupt the new generation.
        if (socket === physical) {
            socket = null
            failStreams(error)
        }
    }

    private fun failStreams(error: Throwable) {
        val current = streams.values.toList()
        streams.clear()
        current.forEach { it.close(error) }
    }

    private inner class Listener(
        private val deferred: kotlinx.coroutines.CompletableDeferred<WebSocket>,
        private val capturedGeneration: Long,
        private val startedAt: Long,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = synchronized(lock) {
            if (disposed || generation != capturedGeneration || connecting !== deferred) {
                webSocket.cancel()
                deferred.completeExceptionally(HarnessTransportException("Harness connection changed"))
            } else {
                socket = webSocket
                pendingSocket = null
                connecting = null
                deferred.complete(webSocket)
                onTransportEvent(
                    HarnessTransportEvent(
                        kind = HarnessTransportKind.WEBSOCKET,
                        phase = HarnessTransportPhase.OPEN,
                        outcome = "success",
                        durationMs = elapsedMs(startedAt),
                        webSocketStatus = response.code,
                    )
                )
            }
            Unit
        }

        override fun onMessage(webSocket: WebSocket, text: String) = receive(webSocket, text)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = synchronized(lock) {
            val error = HarnessTransportException("Harness WebSocket failed", t)
            if (isCurrent(webSocket)) {
                onTransportEvent(
                    HarnessTransportEvent(
                        kind = HarnessTransportKind.WEBSOCKET,
                        phase = HarnessTransportPhase.FAILURE,
                        outcome = "failure",
                        durationMs = elapsedMs(startedAt),
                        webSocketStatus = response?.code,
                        errorCode = "transport/ws-failure",
                    )
                )
            }
            if (connecting === deferred) {
                connecting = null
                pendingSocket = null
                deferred.completeExceptionally(error)
            }
            failPhysical(webSocket, error)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = synchronized(lock) {
            val error = HarnessTransportException("Harness WebSocket closed")
            if (isCurrent(webSocket)) {
                onTransportEvent(
                    HarnessTransportEvent(
                        kind = HarnessTransportKind.WEBSOCKET,
                        phase = HarnessTransportPhase.CLOSE,
                        outcome = "closed",
                        durationMs = elapsedMs(startedAt),
                        webSocketStatus = code,
                        errorCode = "transport/ws-closed",
                    )
                )
            }
            if (connecting === deferred) {
                connecting = null
                pendingSocket = null
                deferred.completeExceptionally(error)
            }
            failPhysical(webSocket, error)
            Unit
        }

        private fun isCurrent(webSocket: WebSocket): Boolean =
            !disposed && generation == capturedGeneration &&
                (socket === webSocket || pendingSocket === webSocket || connecting === deferred)
    }

    private companion object {
        const val MAX_FRAME_CHARACTERS = 4 * 1024 * 1024
        const val MAX_PENDING_BYTES = 16L * 1024L * 1024L

        fun elapsedMs(startedAt: Long): Long =
            ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
    }
}
