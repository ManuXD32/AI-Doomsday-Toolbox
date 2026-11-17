package com.example.llamadroid.service.search

import com.example.llamadroid.ui.settings.groupSearxngFailures
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class WebSearchBackendTest {
    private val json = """{"results":[
        {"title":"Docs  page","url":"https://example.com/docs","content":"A snippet"},
        {"title":"Dup","url":"https://example.com/docs","content":"dup"},
        {"title":"Local","url":"http://192.168.1.5/admin","content":"private"},
        {"title":"Second","url":"https://example.org/x","content":""}]}"""

    @Test
    fun parserKeepsPublicUniqueResultsAndHonoursLimit() {
        val results = parseSearxngResults(json, 5)
        assertEquals(listOf("https://example.com/docs", "https://example.org/x"), results.map { it.url })
        assertEquals("Docs page", results.first().title)
        assertEquals(1, parseSearxngResults(json, 1).size)
        assertTrue(parseSearxngResults("""{"results":[]}""", 3).isEmpty())
    }

    @Test
    fun baseUrlNormalisation() {
        assertEquals("http://127.0.0.1:8888", normalizeSearxngBaseUrl(" http://127.0.0.1:8888/ "))
        assertNull(normalizeSearxngBaseUrl("ftp://x"))
        assertNull(normalizeSearxngBaseUrl(""))
        assertNull(normalizeSearxngBaseUrl("https://x/?q=1"))
    }

    @Test
    fun searxngBackendQueriesJsonEndpoint() = runBlocking {
        val requestLine = AtomicReference<String>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    requestLine.set(reader.readLine())
                    while (reader.readLine().orEmpty().isNotEmpty()) Unit
                    val bytes = json.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
            }
            val results = SearxngBackend("http://127.0.0.1:${server.localPort}").search("a b", 3)
            worker.join(2_000)
            assertEquals("GET /search?q=a%20b&format=json HTTP/1.1", requestLine.get())
            assertEquals(2, results.size)
        }
    }

    @Test
    fun searchWithReportReadsBodyOffTheCallingThread() = runBlocking {
        val body = """{"results":[{"title":"A","url":"https://example.com/a","content":""}]}""".toByteArray()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine().orEmpty().isNotEmpty()) Unit
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
            val caller = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-main") }
            try {
                val report = withContext(caller.asCoroutineDispatcher()) {
                    SearxngBackend("http://127.0.0.1:${server.localPort}").searchWithReport("q", 3)
                }
                assertEquals(1, report.results.size)
            } finally {
                caller.shutdownNow()
            }
            worker.join(2_000)
        }
    }

    @Test
    fun fallbackUsedOnErrorAndOnEmpty() = runBlocking {
        val ddg = object : WebSearchBackend {
            override suspend fun search(query: String, limit: Int) =
                listOf(WebSearchResult("ddg", "https://ddg.example", ""))
        }
        val failing = object : WebSearchBackend {
            override suspend fun search(query: String, limit: Int): List<WebSearchResult> = error("down")
        }
        val empty = object : WebSearchBackend {
            override suspend fun search(query: String, limit: Int) = emptyList<WebSearchResult>()
        }
        assertEquals("ddg", FallbackWebSearchBackend(failing, ddg).search("q", 3).single().title)
        assertEquals("ddg", FallbackWebSearchBackend(empty, ddg).search("q", 3).single().title)
    }

    @Test
    fun unresponsiveEnginesParsedAndMalformedSkipped() {
        val body = """{"results":[],"unresponsive_engines":[["duckduckgo","CAPTCHA"],["google","access denied"],["x"],"bad",["","y"]]}"""
        assertEquals(
            listOf("duckduckgo" to "CAPTCHA", "google" to "access denied", "x" to ""),
            parseSearxngUnresponsive(body)
        )
        assertTrue(parseSearxngUnresponsive("""{"results":[]}""").isEmpty())
        assertTrue(parseSearxngUnresponsive("not json").isEmpty())
    }

    @Test
    fun failuresAreGroupedPerEngine() {
        assertEquals(
            listOf("google" to "access denied, timeout", "bing" to "?"),
            groupSearxngFailures(listOf("google" to "access denied", "bing" to "", "google" to "timeout", "google" to "timeout"))
        )
    }

    @Test
    fun timeoutsDependOnLocalOrRemote() {
        val local = searxngClient(local = true)
        val remote = searxngClient(local = false)
        assertEquals(25_000, local.callTimeoutMillis)
        assertEquals(25_000, local.readTimeoutMillis)
        assertEquals(10_000, remote.callTimeoutMillis)
        assertEquals(5_000, local.connectTimeoutMillis)
        assertEquals(5_000, remote.connectTimeoutMillis)
    }
}
