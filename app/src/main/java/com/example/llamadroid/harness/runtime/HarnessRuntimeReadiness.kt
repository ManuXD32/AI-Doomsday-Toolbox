package com.example.llamadroid.harness.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/**
 * Performs the DSH web-token bootstrap and then probes the manifest-declared authenticated RPC
 * path. It returns the cookie-backed endpoint consumed by the Android client.
 */
class HttpHarnessReadinessProbe(
    private val connectTimeoutMs: Int = 1_000,
    private val readTimeoutMs: Int = 1_000,
    /** Production uses a private, per-launch receipt. Null is the direct-token protocol test seam. */
    private val readinessReceipt: File? = null
) : HarnessReadinessProbe {
    private data class RpcReadiness(val status: Int, val accepted: Boolean)

    override suspend fun awaitReady(
        origin: String,
        webToken: String,
        readinessPath: String,
        timeoutMs: Long
    ): HarnessEndpoint = withContext(Dispatchers.IO) {
        val normalizedOrigin = validateOrigin(origin)
        val rpcPath = normalizeReadinessPath(readinessPath)
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var lastStatus: Int? = null
        var lastError: String? = null
        var lastErrorCode: String? = null
        while (System.nanoTime() < deadline) {
            try {
                val actualToken = if (readinessReceipt == null) webToken else
                    readHarnessReadinessToken(readinessReceipt, normalizedOrigin, webToken)
                val cookie = bootstrapCookie(normalizedOrigin, actualToken).getOrThrow()
                val endpoint = HarnessEndpoint(normalizedOrigin, cookie)
                val rpc = requestRpc(endpoint.url(rpcPath), endpoint.cookieHeader)
                lastStatus = rpc.status
                if (rpc.accepted) {
                    readinessReceipt?.delete()
                    return@withContext endpoint
                }
                lastError = "authenticated session/list readiness was rejected"
                lastErrorCode = "HARNESS_RPC_REJECTED"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // JSON/URL exceptions may echo a bootstrap URL. Keep authentication material
                // out of diagnostics and the recoverable error shown by the caller.
                lastError = error::class.java.simpleName
                lastErrorCode = classifyReadinessError(error)
            }
            delay(100L)
        }
        throw HarnessRuntimeException(
            lastErrorCode ?: "HARNESS_NOT_READY",
            buildString {
                append("DeepSeek Harness did not become ready")
                lastStatus?.let { append(" (HTTP ").append(it).append(')') }
                lastError?.let { append(": ").append(it) }
            }
        )
    }

    private fun classifyReadinessError(error: Throwable): String? {
        val message = error.message.orEmpty().lowercase()
        return when {
            "harness_ready_receipt_missing" in message -> "HARNESS_READY_RECEIPT_MISSING"
            "harness_ready_receipt_invalid" in message -> "HARNESS_READY_RECEIPT_INVALID"
            "no cookie" in message -> "HARNESS_AUTH_COOKIE_MISSING"
            "web-token bootstrap returned http" in message -> "HARNESS_AUTH_BOOTSTRAP_REJECTED"
            else -> null
        }
    }

    private fun bootstrapCookie(origin: String, webToken: String): Result<String> = runCatching {
        val encoded = URLEncoder.encode(webToken, Charsets.UTF_8.name())
        val connection = openConnection("$origin/?token=$encoded", null)
        try {
            val status = connection.responseCode
            require(status in 200..399) { "Harness web-token bootstrap returned HTTP $status" }
            val cookies = connection.headerFields.entries
                .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value.orEmpty() }
                .mapNotNull { it.substringBefore(';').trim().takeIf(String::isNotBlank) }
            require(cookies.isNotEmpty()) { "Harness web-token bootstrap returned no cookie" }
            // The client endpoint contract carries one authenticated DSH session pair. Returning
            // the first pair also avoids handing cookie attributes or an unrelated Set-Cookie
            // value to OkHttp's strict endpoint adoption validation.
            cookies.first()
        } finally {
            connection.disconnect()
        }
    }

    private fun requestRpc(url: String, cookie: String): RpcReadiness {
        val rpcId = UUID.randomUUID().toString()
        val connection = openConnection(url, cookie, method = "POST")
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        return try {
            connection.outputStream.use { output ->
                output.write(
                    "{\"type\":\"client-request\",\"rpcId\":\"$rpcId\",\"method\":\"session/list\",\"payload\":{\"args\":{\"_request\":{}}}}"
                        .toByteArray(Charsets.UTF_8)
                )
            }
            val status = connection.responseCode
            val body = if (status in 200..299) readBody(connection) else null
            RpcReadiness(
                status = status,
                accepted = status in 200..299 && body?.let { validRpcResponse(it, rpcId) } == true
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        url: String,
        cookie: String?,
        method: String = "GET"
    ): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.useCaches = false
        cookie?.let { connection.setRequestProperty("Cookie", it) }
        return connection
    }

    private fun normalizeReadinessPath(rawPath: String): String {
        val normalized = rawPath.trim()
        require(normalized.startsWith('/') && !normalized.contains("..") &&
            !normalized.contains('?') && !normalized.contains('#')) {
            "Harness readiness path is invalid"
        }
        // The root launch page is only the browser-token exchange. A real RPC proves that the
        // authenticated server and the session registry are usable before the client is released.
        return when (normalized) {
            "/", "/api/session/list" -> "/api/session/list"
            else -> throw IllegalArgumentException("Harness readiness must use session/list")
        }
    }

    private fun readBody(connection: HttpURLConnection): String? {
        val stream = connection.inputStream
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4 * 1024)
        var total = 0
        stream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RESPONSE_BYTES) return null
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun validRpcResponse(body: String, rpcId: String): Boolean = runCatching {
        val root = JSONObject(body)
        root.optString("type") == "server-response" &&
            root.optString("rpcId") == rpcId &&
            root.optJSONObject("result")?.optBoolean("ok", false) == true
    }.getOrDefault(false)

    private fun validateOrigin(rawOrigin: String): String {
        val normalized = rawOrigin.trim().trimEnd('/')
        val uri = URI(normalized)
        require(uri.scheme.equals("http", ignoreCase = true)) {
            "Harness origin must use HTTP"
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/")) {
            "Harness origin must not contain credentials or a path"
        }
        require(uri.host == "127.0.0.1" || uri.host == "::1" || uri.host == "[::1]") {
            "Harness origin must use loopback"
        }
        require(uri.port in 1024..65535) { "Harness origin port is invalid" }
        return normalized
    }

    private companion object {
        // Keep readiness aligned with the authenticated client response bound.
        const val MAX_RESPONSE_BYTES = 24 * 1024 * 1024
    }
}

/** Reads a receipt produced by the bundled host plugin, never stdout or a persisted session file. */
internal fun readHarnessReadinessToken(receipt: File, origin: String, nonce: String): String {
    require(Files.isRegularFile(receipt.toPath(), LinkOption.NOFOLLOW_LINKS)) { "HARNESS_READY_RECEIPT_MISSING" }
    val bytes = receipt.inputStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 8 * 1024) { "HARNESS_READY_RECEIPT_INVALID" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    return parseHarnessReadinessReceipt(String(bytes, Charsets.UTF_8), origin, nonce)
}

internal fun parseHarnessReadinessReceipt(text: String, origin: String, nonce: String): String {
    require(text.length <= 8 * 1024) { "HARNESS_READY_RECEIPT_INVALID" }
    val receipt = JSONObject(text)
    require(receipt.optInt("version") == 1 && receipt.optString("nonce") == nonce) { "HARNESS_READY_RECEIPT_INVALID" }
    val expected = URI(origin)
    val url = URI(receipt.getString("url"))
    require(url.scheme == "http" && url.scheme == expected.scheme && url.host == expected.host &&
        url.port == expected.port && url.userInfo == null && url.fragment == null &&
        (url.path.isNullOrEmpty() || url.path == "/")) { "HARNESS_READY_RECEIPT_INVALID" }
    val query = requireNotNull(url.rawQuery) { "HARNESS_READY_RECEIPT_INVALID" }.split('&')
    require(query.size == 1 && query.single().substringBefore('=') == "token") { "HARNESS_READY_RECEIPT_INVALID" }
    val token = URLDecoder.decode(query.single().substringAfter('=', ""), Charsets.UTF_8.name())
    require(token.length in 16..1024 && token.none { it.isISOControl() }) { "HARNESS_READY_RECEIPT_INVALID" }
    return token
}
