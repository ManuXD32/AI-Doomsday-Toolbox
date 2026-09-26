package com.example.llamadroid.harness.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessRuntimeReadinessTest {
    @Test
    fun `readiness exchanges token cookie and verifies session list rpc`() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val requests = mutableListOf<HttpRequest>()
        val responseSizes = mutableListOf<Int>()
        val origin = "http://127.0.0.1:${server.localPort}"
        val receipt = File.createTempFile("harness-ready-", ".json").apply {
            writeText(org.json.JSONObject().put("version", 1).put("nonce", "launch-nonce")
                .put("url", "$origin/?token=upstream-generated-token").toString())
        }
        val future = executor.submit {
            repeat(2) { index ->
                server.accept().use { socket ->
                    val request = readRequest(socket)
                    synchronized(requests) { requests += request }
                    val body = if (index == 0) {
                        "{}"
                    } else {
                        val rpcId = Regex("\\\"rpcId\\\":\\\"([^\\\"]+)\\\"")
                            .find(request.body)?.groupValues?.get(1)
                            ?: error("readiness request did not contain rpcId")
                        val largeSessionTitle = "x".repeat(70_000)
                        "{\"type\":\"server-response\",\"rpcId\":\"$rpcId\",\"result\":{\"ok\":true,\"value\":{\"items\":[{\"sessionId\":\"synthetic-session\",\"cwd\":\"/workspace/projects/default_project\",\"title\":\"$largeSessionTitle\"}]}}}"
                    }
                    val responseSize = body.toByteArray(StandardCharsets.UTF_8).size
                    synchronized(responseSizes) { responseSizes += responseSize }
                    val cookie = if (index == 0) "Set-Cookie: dsh_session=probe-cookie; HttpOnly\r\n" else ""
                    val response = "HTTP/1.1 200 OK\r\n" +
                        cookie +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${body.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
                        "Connection: close\r\n\r\n" + body
                    socket.getOutputStream().use { output ->
                        output.write(response.toByteArray(StandardCharsets.UTF_8))
                        output.flush()
                    }
                }
            }
        }

        try {
            val endpoint = kotlinx.coroutines.runBlocking {
                HttpHarnessReadinessProbe(500, 500, receipt).awaitReady(
                    origin = origin,
                    webToken = "launch-nonce",
                    readinessPath = "/",
                    timeoutMs = 2_000L
                )
            }

            assertEquals("http://127.0.0.1:${server.localPort}", endpoint.origin)
            assertEquals("dsh_session=probe-cookie", endpoint.cookieHeader)
            future.get(2, TimeUnit.SECONDS)
            val captured = synchronized(requests) { requests.toList() }
            assertEquals(2, captured.size)
            assertTrue(captured[0].target.startsWith("/?token=upstream-generated-token"))
            assertTrue("Authenticated readiness must remove the private launch receipt", !receipt.exists())
            assertEquals("GET", captured[0].method)
            assertEquals("POST", captured[1].method)
            assertEquals("/api/session/list", captured[1].target)
            assertTrue(captured[1].headers["cookie"].orEmpty().contains("dsh_session=probe-cookie"))
            assertTrue(captured[1].body.contains("\"method\":\"session/list\""))
            assertTrue(captured[1].body.contains("\"args\":{\"_request\":{}}"))
            val capturedResponseSizes = synchronized(responseSizes) { responseSizes.toList() }
            assertTrue(capturedResponseSizes[1] > 64 * 1024)
        } finally {
            receipt.delete()
            server.close()
            executor.shutdownNow()
        }
    }

    @Test fun `ready receipt rejects stale owners wrong origins and additional query fields`() {
        val origin = "http://127.0.0.1:39001"
        fun receipt(url: String, nonce: String = "current") = org.json.JSONObject()
            .put("version", 1).put("nonce", nonce).put("url", url).toString()
        assertEquals("generated-process-token", parseHarnessReadinessReceipt(receipt("$origin/?token=generated-process-token"), origin, "current"))
        listOf(
            receipt("$origin/?token=generated-process-token", "previous"),
            receipt("http://127.0.0.1:39002/?token=generated-process-token"),
            receipt("http://example.com:39001/?token=generated-process-token"),
            receipt("$origin/?token=generated-process-token&token=other"),
            receipt("$origin/api?token=generated-process-token"),
            receipt("$origin/?token=generated-process-token#fragment")
        ).forEach { invalid ->
            assertTrue(runCatching { parseHarnessReadinessReceipt(invalid, origin, "current") }.isFailure)
        }
    }

    @Test fun `ready receipt reader rejects links and oversized files`() {
        val directory = java.nio.file.Files.createTempDirectory("harness-ready-limit-").toFile()
        try {
            val file = File(directory, "large.json").apply { writeText("x".repeat(8193)) }
            assertTrue(runCatching { readHarnessReadinessToken(file, "http://127.0.0.1:39001", "current") }.isFailure)
            val link = File(directory, "link.json")
            java.nio.file.Files.createSymbolicLink(link.toPath(), file.toPath())
            assertTrue(runCatching { readHarnessReadinessToken(link, "http://127.0.0.1:39001", "current") }.isFailure)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `readiness preserves a missing receipt code after bounded retry`() {
        val receipt = File.createTempFile("harness-ready-missing-", ".json").apply { delete() }
        val error = kotlinx.coroutines.runBlocking {
            runCatching {
                HttpHarnessReadinessProbe(50, 50, receipt).awaitReady(
                    origin = "http://127.0.0.1:39001",
                    webToken = "launch-nonce",
                    readinessPath = "/",
                    timeoutMs = 1_000L
                )
            }.exceptionOrNull()
        }

        assertEquals("HARNESS_READY_RECEIPT_MISSING", (error as HarnessRuntimeException).code)
    }

    private data class HttpRequest(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
        val body: String
    )

    private fun readRequest(socket: Socket): HttpRequest {
        val input = socket.getInputStream()
        val headerBytes = ByteArrayOutputStream()
        var previous = 0
        var current: Int
        while (true) {
            current = input.read()
            if (current < 0) error("HTTP request ended before headers")
            headerBytes.write(current)
            if (previous == '\r'.code && current == '\n'.code) {
                val bytes = headerBytes.toByteArray()
                val size = bytes.size
                if (size >= 4 && bytes[size - 4] == '\r'.code.toByte() &&
                    bytes[size - 3] == '\n'.code.toByte() &&
                    bytes[size - 2] == '\r'.code.toByte() &&
                    bytes[size - 1] == '\n'.code.toByte()
                ) break
            }
            previous = current
        }
        val headerText = headerBytes.toString(StandardCharsets.UTF_8.name())
        val lines = headerText.removeSuffix("\r\n\r\n").split("\r\n")
        val requestLine = lines.first().split(' ')
        val headers = lines.drop(1).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.substring(0, separator).lowercase() to line.substring(separator + 1).trim()
        }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val bodyBytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bodyBytes, offset, length - offset)
            if (count < 0) error("HTTP request ended before body")
            offset += count
        }
        return HttpRequest(requestLine[0], requestLine[1], headers, String(bodyBytes, StandardCharsets.UTF_8))
    }
}
