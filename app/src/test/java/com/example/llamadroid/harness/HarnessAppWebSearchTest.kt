package com.example.llamadroid.harness

import com.example.llamadroid.service.parseDuckDuckGoSearchResults
import com.example.llamadroid.service.APP_WEB_SEARCH_USER_AGENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class HarnessAppWebSearchTest {
    @Test
    fun enabledAppSearchReturnsBoundedStructuredResultsWithCitations() = runBlocking {
        val response = executeHarnessAppWebSearch(
            enabled = true,
            query = "  Kotlin on Android  ",
            maxResults = 4,
        ) { query, limit ->
            assertEquals("Kotlin on Android", query)
            assertEquals(4, limit)
            listOf(
                com.example.llamadroid.service.DuckDuckGoSearchResult(
                    title = "Official docs",
                    url = "https://developer.android.com/",
                    snippet = "Build Android apps.",
                ),
            )
        }

        assertEquals("DuckDuckGo", response.getString("source"))
        assertEquals("Kotlin on Android", response.getString("query"))
        assertEquals(1, response.getInt("resultCount"))
        val result = response.getJSONArray("results").getJSONObject(0)
        assertEquals("Official docs", result.getString("title"))
        assertEquals("https://developer.android.com/", result.getString("url"))
        assertTrue(result.getString("citation").contains("[Official docs](https://developer.android.com/)"))
    }

    @Test
    fun disabledAppSearchRejectsBeforeCallingTheSearchAdapter() = runBlocking {
        var called = false
        val error = runCatching {
            executeHarnessAppWebSearch(enabled = false, query = "query", maxResults = 3) { _, _ ->
                called = true
                emptyList()
            }
        }.exceptionOrNull()

        assertEquals("WEB_SEARCH_DISABLED", error?.message)
        assertFalse(called)
    }

    @Test
    fun disabledImageGenerationHasAnExplicitBridgeRejectionCode() {
        val error = runCatching {
            requireHarnessToolEnabled(false, "IMAGE_GENERATION_TOOL_DISABLED")
        }.exceptionOrNull()

        assertEquals("IMAGE_GENERATION_TOOL_DISABLED", error?.message)
    }

    @Test
    fun parserFiltersUnsafeCitationsAndLimitsResultAndMarkupSize() {
        val html = """
            <div class="result results_links results_links_deep web-result">
              <div class="links_main links_deep result__body">
                <h2 class="result__title"><a class="result__a" href="https://localhost/private">Local</a></h2>
                <a class="result__snippet js-result-snippet">Private result snippet.</a>
              </div>
            </div>
            <div class="result results_links results_links_deep web-result">
              <div class="links_main links_deep result__body">
                <h2 class="result__title"><a class="result__a" href="javascript:alert(1)">Script</a></h2>
                <a class="result__snippet js-result-snippet">Unsafe script result snippet.</a>
              </div>
            </div>
            <div class="result results_links results_links_deep web-result">
              <div class="links_main links_deep result__body">
                <h2 class="result__title"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fdocs&amp;rut=1">Docs &amp; API</a></h2>
                <a class="result__snippet js-result-snippet">First &amp; trusted-looking snippet</a>
              </div>
            </div>
            <div class="result results_links results_links_deep web-result">
              <div class="links_main links_deep result__body">
                <h2 class="result__title"><a class="result__a" href="https://example.org/other">Second</a></h2>
                <a class="result__snippet js-result-snippet">Second snippet</a>
              </div>
            </div>
            <div class="result results_links results_links_deep web-result">
              <div class="links_main links_deep result__body">
                <h2 class="result__title"><a class="result__a" href="https://example.net/third">Third</a></h2>
                <a class="result__snippet js-result-snippet">Third snippet</a>
              </div>
            </div>
        """.trimIndent()

        val results = parseDuckDuckGoSearchResults(html, maxResults = 2)

        assertEquals(2, results.size)
        assertEquals("Docs & API", results[0].title)
        assertEquals("https://example.com/docs", results[0].url)
        assertEquals("First & trusted-looking snippet", results[0].snippet)
        assertEquals("Second", results[1].title)
        assertEquals("Second snippet", results[1].snippet)
        assertFalse(results.any { it.url.contains("localhost") || it.url.startsWith("javascript:") })

        val oversized = "x".repeat(300_001) + "<a class=\"result__a\" href=\"https://late.example\">Late</a>"
        assertTrue(parseDuckDuckGoSearchResults(oversized, 5).isEmpty())
        val protocolRelative = parseDuckDuckGoSearchResults(
            "<a class=\"result__a\" href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fdocs\">Docs</a>",
            1,
        )
        assertEquals("https://example.com/docs", protocolRelative.single().url)
    }

    @Test
    fun adapterReturnsParsedSnippetsAndHandlesEmptyAndHttpErrorResponses() = runBlocking {
        val html = """
            <a class="result__a" href="https://example.com/docs">Example docs</a>
            <a class="result__snippet">A short result snippet.</a>
        """.trimIndent()
        TestSearchServer(200, html).use { server ->
            val response = executeHarnessAppWebSearch(
                enabled = true,
                query = "Kotlin & Android",
                maxResults = 3,
            ) { query, limit -> HarnessAppWebSearch(endpoint = server.url).search(query, limit) }
            assertEquals("GET /html/?q=Kotlin%20%26%20Android HTTP/1.1", server.requestLine.get())
            assertEquals(APP_WEB_SEARCH_USER_AGENT, server.userAgent.get())
            assertEquals(1, response.getInt("resultCount"))
            val result = response.getJSONArray("results").getJSONObject(0)
            assertEquals("Example docs", result.getString("title"))
            assertEquals("https://example.com/docs", result.getString("url"))
            assertEquals("A short result snippet.", result.getString("snippet"))
            assertEquals("[Example docs](https://example.com/docs)", result.getString("citation"))
        }

        TestSearchServer(200, "<html>No matching results</html>").use { server ->
            assertTrue(HarnessAppWebSearch(endpoint = server.url).search("nothing", 2).isEmpty())
        }

        TestSearchServer(503, "temporarily unavailable").use { server ->
            val error = runCatching { HarnessAppWebSearch(endpoint = server.url).search("retry", 1) }.exceptionOrNull()
            assertEquals("APP_WEB_SEARCH_HTTP_503", error?.message)
        }

        TestSearchServer(202, "<script src=\"/anomaly.js\"></script>").use { server ->
            val error = runCatching { HarnessAppWebSearch(endpoint = server.url).search("blocked", 1) }.exceptionOrNull()
            assertEquals("APP_WEB_SEARCH_UNAVAILABLE", error?.message)
        }

        TestSearchServer(200, "<html><script src=\"/anomaly.js\"></script></html>").use { server ->
            val error = runCatching { HarnessAppWebSearch(endpoint = server.url).search("challenge", 1) }.exceptionOrNull()
            assertEquals("APP_WEB_SEARCH_UNAVAILABLE", error?.message)
        }

        val tooLarge = "<a class=\"result__a\" href=\"https://example.com\">Hidden by oversize body</a>" + "x".repeat(300_001)
        TestSearchServer(200, tooLarge).use { server ->
            val error = runCatching { HarnessAppWebSearch(endpoint = server.url).search("large", 1) }.exceptionOrNull()
            assertEquals("APP_WEB_SEARCH_RESPONSE_TOO_LARGE", error?.message)
        }
    }

    @Test
    fun adapterHonorsHttpTimeoutAndCoroutineCancellation() = runBlocking {
        TestSearchServer(200, "", holdResponse = true).use { server ->
            val client = OkHttpClient.Builder().callTimeout(100, TimeUnit.MILLISECONDS).build()
            val error = runCatching { HarnessAppWebSearch(endpoint = server.url, client = client).search("slow", 1) }
                .exceptionOrNull()
            assertTrue(error is java.io.IOException)
        }

        TestSearchServer(200, "", holdResponse = true).use { server ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val job = scope.launch { HarnessAppWebSearch(endpoint = server.url).search("cancel", 1) }
                assertTrue(server.requestReceived.await(2, TimeUnit.SECONDS))
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun appSearchResponseHasNoEnglishOnlyStatusSentence() = runBlocking {
        val value = executeHarnessAppWebSearch(true, "q", 2) { _, _ -> emptyList() }
        assertEquals(setOf("source", "query", "resultCount", "results"), value.keys().asSequence().toSet())
        assertEquals(0, value.getJSONArray("results").length())
        assertFalse(value.has("message"))
    }

    private class TestSearchServer(
        status: Int,
        body: String,
        private val holdResponse: Boolean = false,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/html/".toHttpUrl()
        val requestReceived = CountDownLatch(1)
        val requestLine = AtomicReference<String>()
        val userAgent = AtomicReference<String>()
        private val releaseResponse = CountDownLatch(1)
        private val responseStatus = status
        private val responseBytes = body.toByteArray(StandardCharsets.UTF_8)
        private val thread = Thread {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    requestLine.set(reader.readLine())
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("User-Agent:", ignoreCase = true)) {
                            userAgent.set(header.substringAfter(':').trim())
                        }
                    }
                    requestReceived.countDown()
                    if (holdResponse) releaseResponse.await(2, TimeUnit.SECONDS)
                    val phrase = if (responseStatus == 200) "OK" else "Unavailable"
                    val headers = "HTTP/1.1 $responseStatus $phrase\r\n" +
                        "Content-Type: text/html; charset=utf-8\r\n" +
                        "Content-Length: ${responseBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(headers.toByteArray(StandardCharsets.US_ASCII))
                        write(responseBytes)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            releaseResponse.countDown()
            runCatching { server.close() }
            thread.join(1_000)
        }
    }
}
