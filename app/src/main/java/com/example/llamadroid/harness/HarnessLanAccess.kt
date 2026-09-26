package com.example.llamadroid.harness

import android.content.Context
import androidx.core.content.edit
import com.example.llamadroid.harness.runtime.HarnessEndpoint
import com.example.llamadroid.harness.runtime.HarnessRuntimeLogEvent
import com.example.llamadroid.harness.runtime.HarnessRuntimeState
import com.example.llamadroid.service.AiServerNetwork
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Presentation state for the optional app-owned LAN WebUI access server. */
data class HarnessLanAccessState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val urls: List<String> = emptyList(),
    /** Fixed metadata code suitable for UI and diagnostics; never an exception message. */
    val errorCode: String? = null
)

/**
 * Owns the optional LAN proxy for the app-managed Harness WebUI.
 *
 * The Harness process itself is deliberately never rebound: it stays on its
 * authenticated loopback endpoint. This proxy has its own short-lived token,
 * injects the loopback Harness cookie upstream, and is stopped as soon as the
 * runtime endpoint disappears.
 */
class HarnessLanAccessManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val runtime = HarnessAppRuntime.get(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(
        HarnessLanAccessState(enabled = preferences.getBoolean(KEY_ENABLED, false))
    )
    val state: StateFlow<HarnessLanAccessState> = mutableState.asStateFlow()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            publish(errorCode = classify(error))
        }
    )
    private val lifecycleMutex = Mutex()
    private var proxy: HarnessLanWebProxy? = null
    private var endpointWatcher: Job
    private var runtimeWatcher: Job

    init {
        endpointWatcher = scope.launch {
            runtime.endpoint.collectLatest { endpoint ->
                if (endpoint == null) stopProxy()
                else if (enabled()) startProxyIfReady(endpoint)
            }
        }
        runtimeWatcher = scope.launch {
            runtime.status.collectLatest { status ->
                if (status?.state != HarnessRuntimeState.RUNNING.name) stopProxy()
                else if (enabled()) runtime.endpoint.value?.let { startProxyIfReady(it) }
            }
        }
    }

    fun setEnabled(value: Boolean) {
        preferences.edit { putBoolean(KEY_ENABLED, value) }
        publish(enabled = value, errorCode = null)
        scope.launch {
            if (value) runtime.endpoint.value?.let { startProxyIfReady(it) } else stopProxy()
        }
    }

    /** Called by the owning screen when it is permanently discarded. */
    fun close() {
        endpointWatcher.cancel()
        runtimeWatcher.cancel()
        scope.launch { stopProxy() }
    }

    private fun enabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    private suspend fun startProxyIfReady(endpoint: HarnessEndpoint) {
        if (!enabled() || runtime.status.value?.state != HarnessRuntimeState.RUNNING.name) return
        lifecycleMutex.withLock {
            if (proxy?.isRunning == true) return
            proxy?.stop()
            proxy = null
            val token = createToken()
            val candidate = HarnessLanWebProxy(endpoint, token)
            try {
                candidate.start()
                proxy = candidate
                val urls = AiServerNetwork.localIpv4Addresses()
                    .map { (_, ip) -> HarnessLanAccessSupport.bootstrapUrl(ip, candidate.port, token) }
                    .ifEmpty {
                        listOf(HarnessLanAccessSupport.bootstrapUrl("127.0.0.1", candidate.port, token))
                    }
                publish(enabled = true, running = true, urls = urls, errorCode = null)
                record("STARTED")
            } catch (error: Exception) {
                candidate.stop()
                publish(enabled = true, running = false, urls = emptyList(), errorCode = classify(error))
                record("FAILED", classify(error))
            }
        }
    }

    private suspend fun stopProxy() {
        lifecycleMutex.withLock {
            val previous = proxy
            proxy = null
            previous?.stop()
            publish(enabled = enabled(), running = false, urls = emptyList(), errorCode = null)
            if (previous != null) record("STOPPED")
        }
    }

    private fun publish(
        enabled: Boolean = state.value.enabled,
        running: Boolean = state.value.running,
        urls: List<String> = state.value.urls,
        errorCode: String? = state.value.errorCode
    ) {
        mutableState.value = HarnessLanAccessState(
            enabled = enabled,
            running = running,
            urls = urls,
            errorCode = errorCode
        )
    }

    private fun record(status: String, errorCode: String? = null) {
        val row = runtime.status.value
        runtime.diagnostics.record(
            HarnessRuntimeLogEvent(
                event = "lan_access",
                environmentId = row?.environmentId ?: "deepseek-harness-shared",
                generation = row?.generation ?: "lan-access",
                state = row?.state?.let { value -> runCatching { HarnessRuntimeState.valueOf(value) }.getOrNull() },
                errorCode = errorCode ?: "LAN_ACCESS_$status"
            )
        )
    }

    private fun classify(error: Throwable): String = when (error) {
        is java.net.BindException -> "LAN_ACCESS_PORT_UNAVAILABLE"
        is SocketException -> "LAN_ACCESS_SOCKET_FAILED"
        else -> "LAN_ACCESS_START_FAILED"
    }

    private fun createToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFERENCES = "harness_lan_access"
        private const val KEY_ENABLED = "enabled"

        @Volatile private var instance: HarnessLanAccessManager? = null

        fun get(context: Context): HarnessLanAccessManager = instance ?: synchronized(this) {
            instance ?: HarnessLanAccessManager(context).also { instance = it }
        }
    }
}

/**
 * Minimal HTTP/1.1 reverse proxy. It intentionally handles one request per
 * connection except WebSocket upgrades; browsers open further connections for
 * subsequent assets. This keeps the implementation bounded and easy to stop.
 */
internal class HarnessLanWebProxy(
    private val endpoint: HarnessEndpoint,
    private val accessToken: String
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var workers: ExecutorService? = null
    private val clients = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())

    val port: Int get() = serverSocket?.localPort ?: 0
    val isRunning: Boolean get() = running

    fun start() {
        check(!running) { "LAN proxy already running" }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0), 64)
        serverSocket = socket
        workers = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "harness-lan-client").apply { isDaemon = true }
        }
        running = true
        acceptThread = Thread({ acceptLoop(socket) }, "harness-lan-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.toList().forEach { runCatching { it.close() } }
        clients.clear()
        workers?.shutdownNow()
        workers = null
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            try {
                val client = socket.accept()
                clients += client
                workers?.execute {
                    try { serve(client) }
                    catch (_: Exception) { /* connection metadata is intentionally not logged */ }
                    finally {
                        clients -= client
                        runCatching { client.close() }
                    }
                }
            } catch (_: SocketException) {
                if (running) continue
            } catch (_: Exception) {
                if (running) continue
            }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 15_000
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        val requestHead = readHeader(input) ?: return
        val request = parseRequest(requestHead)
        val cookie = request.headers.firstOrNull { it.name.equals("Cookie", true) }?.value
        if (!HarnessLanAccessSupport.isAuthorized(request.target, cookie, accessToken)) {
            writeSimpleResponse(output, 401, "Unauthorized", "LAN access requires the authenticated URL from the app.")
            return
        }
        if (request.hasInvalidTrustHeader(endpoint.origin)) {
            writeSimpleResponse(output, 403, "Forbidden", "The browser origin is not trusted for this LAN endpoint.")
            return
        }
        val upstream = Socket()
        try {
            upstream.connect(
                InetSocketAddress(HarnessLanAccessSupport.endpointHost(endpoint.origin), HarnessLanAccessSupport.endpointPort(endpoint.origin)),
                10_000
            )
            upstream.soTimeout = 15_000
            val upstreamInput = BufferedInputStream(upstream.getInputStream())
            val upstreamOutput = BufferedOutputStream(upstream.getOutputStream())
            writeUpstreamRequest(request, input, upstreamOutput)
            upstreamOutput.flush()
            val responseHead = readHeader(upstreamInput) ?: return
            val response = parseResponse(responseHead)
            val patchedIndex = readPatchedIndex(upstreamInput, request, response)
            writeClientResponse(output, responseHead, request.isWebSocket, patchedIndex)
            output.flush()
            if (response.status == 101 && request.isWebSocket) {
                tunnel(client, upstream)
            } else if (patchedIndex != null) {
                output.write(patchedIndex)
                output.flush()
            } else {
                relayBody(upstreamInput, output, response)
                output.flush()
            }
        } finally {
            runCatching { upstream.close() }
        }
    }

    private fun writeUpstreamRequest(request: HttpRequest, input: InputStream, output: OutputStream) {
        val headers = request.headers.filterNot {
            it.name.equals("Host", true) || it.name.equals("Cookie", true) || it.name.equals("Accept-Encoding", true) ||
                it.name.equals("Origin", true) || it.name.equals("Referer", true)
        }
            .filterNot { request.isWebSocket && it.name.equals("Connection", true) }
            .toMutableList()
        headers += Header("Host", "${HarnessLanAccessSupport.endpointHost(endpoint.origin)}:${HarnessLanAccessSupport.endpointPort(endpoint.origin)}")
        headers += Header("Cookie", endpoint.cookieHeader)
        headers += Header("Accept-Encoding", "identity")
        request.headers.filter { it.name.equals("Origin", true) || it.name.equals("Referer", true) }.forEach { header ->
            val rewritten = HarnessLanAccessSupport.rewriteTrustedHeader(
                header.name, header.value, request.browserHost, endpoint.origin
            ) ?: throw IllegalArgumentException("Untrusted browser origin")
            headers += Header(header.name, rewritten)
        }
        if (request.isWebSocket) headers += Header("Connection", "Upgrade")
        else headers += Header("Connection", "close")
        val target = HarnessLanAccessSupport.stripBootstrapToken(request.target)
        val builder = StringBuilder()
            .append(request.method).append(' ').append(target).append(" HTTP/1.1\r\n")
        headers.forEach { builder.append(it.name).append(": ").append(it.value).append("\r\n") }
        builder.append("\r\n")
        output.write(builder.toString().toByteArray(StandardCharsets.ISO_8859_1))
        when {
            request.contentLength != null -> copyExactly(input, output, request.contentLength ?: 0L)
            request.chunked -> relayChunked(input, output)
        }
    }

    private fun writeClientResponse(
        output: OutputStream,
        original: ByteArray,
        webSocket: Boolean,
        rewrittenBody: ByteArray?
    ) {
        val text = original.toString(StandardCharsets.ISO_8859_1)
        val separator = text.indexOf("\r\n\r\n")
        if (separator < 0) return
        val headers = text.substring(0, separator).split("\r\n").toMutableList()
        headers.removeAll { it.substringBefore(':').equals("Connection", true) }
        if (rewrittenBody != null) {
            headers.removeAll {
                it.substringBefore(':').equals("Content-Length", true) ||
                    it.substringBefore(':').equals("Transfer-Encoding", true) ||
                    it.substringBefore(':').equals("Cache-Control", true) ||
                    it.substringBefore(':').equals("ETag", true) ||
                    it.substringBefore(':').equals("Last-Modified", true)
            }
            headers += "Content-Length: ${rewrittenBody.size}"
            headers += "Cache-Control: no-store"
        }
        if (!webSocket) {
            headers += "Set-Cookie: ${HarnessLanAccessSupport.COOKIE_NAME}=$accessToken; HttpOnly; SameSite=Lax; Path=/"
            headers += "Connection: close"
        } else {
            headers += "Connection: Upgrade"
        }
        output.write((headers.joinToString("\r\n") + "\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun readPatchedIndex(input: InputStream, request: HttpRequest, response: HttpResponse): ByteArray? {
        if (!request.isIndex || !response.isHtml || response.status !in 200..299) return null
        val bytes = readHarnessWebIndexBody(input, response.contentLength, response.chunked, MAX_INDEX_BYTES)
        return HarnessLanAccessSupport.patchIndexForLan(String(bytes, Charsets.UTF_8))
            .toByteArray(Charsets.UTF_8)
    }

    private fun relayBody(input: InputStream, output: OutputStream, response: HttpResponse) {
        if (response.status in 100..199 || response.status == 204 || response.status == 304) return
        val contentLength = response.contentLength
        when {
            contentLength != null -> copyExactly(input, output, contentLength)
            response.chunked -> relayChunked(input, output)
            else -> copyToEnd(input, output)
        }
    }

    private fun relayChunked(input: InputStream, output: OutputStream) {
        while (true) {
            val line = readLine(input) ?: return
            output.write(line)
            val size = line.toString(StandardCharsets.ISO_8859_1).trim().substringBefore(';').toLongOrNull(16) ?: return
            if (size == 0L) {
                var trailer = readLine(input) ?: return
                output.write(trailer)
                while (trailer.size > 2) {
                    trailer = readLine(input) ?: return
                    output.write(trailer)
                }
                return
            }
            copyExactly(input, output, size)
            output.write(copyExactly(input, null, 2))
        }
    }

    private fun tunnel(client: Socket, upstream: Socket) {
        client.soTimeout = 0
        upstream.soTimeout = 0
        val first = Thread({ pipe(client, upstream) }, "harness-lan-ws-c2u").apply { isDaemon = true }
        val second = Thread({ pipe(upstream, client) }, "harness-lan-ws-u2c").apply { isDaemon = true }
        first.start()
        second.start()
        first.join()
        second.interrupt()
    }

    private fun pipe(source: Socket, destination: Socket) {
        try {
            source.getInputStream().use { input ->
                destination.getOutputStream().use { output -> input.copyTo(output); output.flush() }
            }
        } catch (_: Exception) { }
    }

    private fun writeSimpleResponse(output: OutputStream, status: Int, reason: String, body: String) {
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        output.write("HTTP/1.1 $status $reason\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write(payload)
        output.flush()
    }

    private fun readHeader(input: InputStream): ByteArray? {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        while (bytes.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) return null
            bytes.write(value)
            matched = when {
                matched == 0 && value == '\r'.code -> 1
                matched == 1 && value == '\n'.code -> 2
                matched == 2 && value == '\r'.code -> 3
                matched == 3 && value == '\n'.code -> return bytes.toByteArray()
                value == '\r'.code -> 1
                else -> 0
            }
        }
        throw IllegalArgumentException("HTTP headers too large")
    }

    private fun readLine(input: InputStream): ByteArray? {
        val bytes = ByteArrayOutputStream()
        var previous = -1
        while (bytes.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) return null
            bytes.write(value)
            if (previous == '\r'.code && value == '\n'.code) return bytes.toByteArray()
            previous = value
        }
        return null
    }

    private fun copyExactly(input: InputStream, output: OutputStream?, count: Long): ByteArray {
        if (count < 0 || count > MAX_BODY_BYTES) throw IllegalArgumentException("HTTP body too large")
        val buffer = ByteArray(16 * 1024)
        var remaining = count
        val captured = ByteArrayOutputStream(if (output == null) count.toInt() else 0)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("HTTP body ended early")
            output?.write(buffer, 0, read)
            if (output == null) captured.write(buffer, 0, read)
            remaining -= read
        }
        return captured.toByteArray()
    }

    private fun copyToEnd(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            output.write(buffer, 0, read)
        }
    }

    private data class Header(val name: String, val value: String)

    private data class HttpRequest(
        val method: String,
        val target: String,
        val headers: List<Header>
    ) {
        private fun header(name: String): String? = headers.firstOrNull { it.name.equals(name, true) }?.value
        val contentLength: Long? get() = header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
        val chunked: Boolean get() = header("Transfer-Encoding")?.contains("chunked", true) == true
        val isWebSocket: Boolean get() = header("Upgrade")?.equals("websocket", true) == true
        val isIndex: Boolean get() = method.equals("GET", true) &&
            target.substringBefore('?').let { it == "/" || it == "/index.html" }
        val browserHost: String? get() = header("Host")

        fun hasInvalidTrustHeader(endpointOrigin: String): Boolean = headers
            .filter { it.name.equals("Origin", true) || it.name.equals("Referer", true) }
            .any {
                HarnessLanAccessSupport.rewriteTrustedHeader(it.name, it.value, browserHost, endpointOrigin) == null
            }
    }

    private data class HttpResponse(
        val status: Int,
        val headers: List<Header>
    ) {
        private fun header(name: String): String? = headers.firstOrNull { it.name.equals(name, true) }?.value
        val contentLength: Long? get() = header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }
        val chunked: Boolean get() = header("Transfer-Encoding")?.contains("chunked", true) == true
        val isHtml: Boolean get() = header("Content-Type")?.contains("text/html", true) == true
    }

    private fun parseRequest(bytes: ByteArray): HttpRequest {
        val lines = bytes.toString(StandardCharsets.ISO_8859_1).trimEnd('\r', '\n').split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ', limit = 3) ?: error("Invalid HTTP request")
        require(requestLine.size == 3) { "Invalid HTTP request line" }
        return HttpRequest(requestLine[0], requestLine[1], lines.drop(1).mapNotNull(::parseHeader))
    }

    private fun parseResponse(bytes: ByteArray): HttpResponse {
        val lines = bytes.toString(StandardCharsets.ISO_8859_1).trimEnd('\r', '\n').split("\r\n")
        val status = lines.firstOrNull()?.split(' ', limit = 3)?.getOrNull(1)?.toIntOrNull() ?: error("Invalid HTTP response")
        return HttpResponse(status, lines.drop(1).mapNotNull(::parseHeader))
    }

    private fun parseHeader(line: String): Header? {
        val separator = line.indexOf(':')
        if (separator <= 0) return null
        return Header(line.substring(0, separator).trim(), line.substring(separator + 1).trim())
    }

    companion object {
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_BODY_BYTES = 256L * 1024L * 1024L
        private const val MAX_INDEX_BYTES = 1024 * 1024
    }
}
