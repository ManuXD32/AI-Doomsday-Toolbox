package com.example.llamadroid.ui.agent.harness

import android.annotation.SuppressLint
import android.app.Instrumentation
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import com.example.llamadroid.harness.runtime.HarnessEndpoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * In-process loopback stand-in for one OpenAI Chat Completions route. It only
 * emits a synthetic `ask_user_question` call, records bounded request bodies,
 * and never contacts a provider or network outside the emulator process.
 */
internal class HarnessQaLoopbackQuestionProvider(
    private val questionMarker: String,
    private val optionLabel: String,
    private val preQuestionText: String,
    private val finalText: String
) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val workers: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "adt-harness-qa-provider").apply { isDaemon = true }
    }
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val requestBodies = CopyOnWriteArrayList<String>()
    private val requestCount = AtomicInteger(0)
    private val acceptThread = Thread({ acceptLoop() }, "adt-harness-qa-provider-accept")

    val baseUrl: String = "http://127.0.0.1:${server.localPort}"

    init {
        acceptThread.isDaemon = true
        acceptThread.start()
    }

    fun requestBodies(): List<String> = requestBodies.toList()

    fun requestCount(): Int = requestCount.get()

    suspend fun awaitRequestCount(expected: Int) = withTimeout(45_000L) {
        while (requestCount() < expected) delay(50L)
    }

    suspend fun awaitChatRequest(): String = withTimeout<String>(45_000L) {
        var request: String? = null
        while (request == null) {
            request = requestBodies.firstOrNull { it.contains("ask_user_question") }
            if (request == null) delay(50L)
        }
        request
    }

    override fun close() {
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        runCatching { workers.awaitTermination(2, TimeUnit.SECONDS) }
        runCatching { acceptThread.join(2_000L) }
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            try {
                val socket = server.accept()
                sockets += socket
                workers.execute { handle(socket) }
            } catch (_: SocketException) {
                return
            } catch (_: Throwable) {
                if (server.isClosed) return
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { owned ->
            val input = BufferedInputStream(owned.getInputStream())
            val requestLine = readHeaderLine(input).orEmpty()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readHeaderLine(input) ?: return
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).lowercase()] = line.substring(colon + 1).trim()
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength !in 0..MAX_REQUEST_BYTES) {
                writeJson(owned, 413, "{\"error\":\"QA_REQUEST_TOO_LARGE\"}")
                return
            }
            val bytes = ByteArray(contentLength)
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) return
                offset += count
            }
            val body = String(bytes, StandardCharsets.UTF_8)
            if (!requestLine.contains("/chat/completions")) {
                writeJson(owned, 404, "{\"error\":\"QA_ROUTE_NOT_FOUND\"}")
                return
            }
            requestBodies += body
            if (!body.contains("ask_user_question")) {
                writeJson(owned, 422, "{\"error\":\"QA_TOOL_SCHEMA_MISSING\"}")
                return
            }
            val sequence = requestCount.incrementAndGet()
            when (sequence) {
                2 -> writeFinalStream(owned)
                else -> writeQuestionStream(owned, sequence)
            }
        }
    }

    private fun writeJson(socket: Socket, status: Int, body: String) {
        val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        out.write("HTTP/1.1 $status QA\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n")
        out.write(body)
        out.flush()
    }

    private fun writeQuestionStream(socket: Socket, requestNumber: Int) {
        val callId = "adt-qa-call-${questionMarker.takeLast(12)}-$requestNumber"
        val arguments = buildJsonObject {
            putJsonArray("questions") {
                add(buildJsonObject {
                    put("id", "choice")
                    put("header", "ADT QA")
                    put("question", questionMarker)
                    putJsonArray("options") {
                        add(buildJsonObject {
                            put("label", optionLabel)
                            put("description", "Synthetic loopback choice")
                        })
                    }
                })
            }
        }.toString()
        val events = listOf(
            completionChunk(buildJsonObject {
                put("role", "assistant")
                put("content", preQuestionText)
            }),
            completionChunk(buildJsonObject {
                putJsonArray("tool_calls") {
                    add(buildJsonObject {
                        put("index", 0)
                        put("id", callId)
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", "ask_user_question")
                            put("arguments", "")
                        }
                    })
                }
            }),
            completionChunk(buildJsonObject {
                putJsonArray("tool_calls") {
                    add(buildJsonObject {
                        put("index", 0)
                        putJsonObject("function") { put("arguments", arguments) }
                    })
                }
            }),
            completionChunk(buildJsonObject {}, finishReason = "tool_calls"),
            "[DONE]"
        )
        val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n")
        out.flush()
        events.forEach { event ->
            out.write("data: $event\n\n")
            out.flush()
        }
    }

    private fun writeFinalStream(socket: Socket) {
        val events = listOf(
            completionChunk(buildJsonObject {
                put("role", "assistant")
                put("content", finalText)
            }),
            completionChunk(buildJsonObject {}, finishReason = "stop"),
            "[DONE]"
        )
        val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n")
        out.flush()
        events.forEach { event ->
            out.write("data: $event\n\n")
            out.flush()
        }
    }

    private fun readHeaderLine(input: BufferedInputStream): String? {
        val bytes = ByteArrayOutputStream(256)
        while (bytes.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) {
                return if (bytes.size() == 0) null else bytes.toString(StandardCharsets.ISO_8859_1.name())
            }
            if (value == '\n'.code) return bytes.toString(StandardCharsets.ISO_8859_1.name())
            if (value != '\r'.code) bytes.write(value)
        }
        return null
    }

    private fun completionChunk(delta: JsonObject, finishReason: String? = null): String = buildJsonObject {
        put("id", "adt-qa-completion")
        put("object", "chat.completion.chunk")
        putJsonArray("choices") {
            add(buildJsonObject {
                put("index", 0)
                put("delta", delta)
                if (finishReason == null) put("finish_reason", JsonNull) else put("finish_reason", finishReason)
            })
        }
    }.toString()

    private companion object {
        const val MAX_REQUEST_BYTES = 1_048_576
        const val MAX_HEADER_BYTES = 16_384
    }
}

/** Small WebView harness used only to prove the authenticated original UI sees the same event. */
@SuppressLint("SetJavaScriptEnabled")
internal class HarnessQaAuthenticatedWebSurface(
    private val instrumentation: Instrumentation,
    private val endpoint: HarnessEndpoint
) : AutoCloseable {
    private val viewRef = AtomicReference<WebView?>()
    private val pageFailure = AtomicReference<String?>()
    private val pageReady = CountDownLatch(1)

    fun attach(scenario: ActivityScenario<ComponentActivity>) {
        val cookieInstalled = CountDownLatch(1)
        val cookieOk = AtomicReference(false)
        scenario.onActivity { activity ->
            val view = WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { pageReady.countDown() }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError
                    ) {
                        if (request.isForMainFrame) {
                            pageFailure.set("${error.errorCode}:${error.description}")
                            pageReady.countDown()
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        response: WebResourceResponse
                    ) {
                        if (request.isForMainFrame && response.statusCode >= 400) {
                            pageFailure.set("HTTP ${response.statusCode}")
                            pageReady.countDown()
                        }
                    }
                }
            }
            viewRef.set(view)
            activity.setContentView(view)
            CookieManager.getInstance().setCookie(endpoint.origin, endpoint.cookieHeader + "; Path=/; HttpOnly; SameSite=Strict") { ok ->
                cookieOk.set(ok)
                cookieInstalled.countDown()
                if (ok) {
                    CookieManager.getInstance().flush()
                    view.loadUrl(endpoint.origin + "/")
                } else pageReady.countDown()
            }
        }
        check(cookieInstalled.await(5, TimeUnit.SECONDS)) { "QA WebView cookie installation timed out" }
        check(cookieOk.get()) { "QA WebView cookie installation failed" }
        check(pageReady.await(30, TimeUnit.SECONDS)) { "QA WebView did not finish loading" }
        check(pageFailure.get() == null) { "QA WebView failed: ${pageFailure.get()}" }
    }

    suspend fun clickText(text: String) {
        val quoted = JSONObject.quote(text)
        withTimeout(30_000L) {
            while (true) {
                val clicked = evaluateString(
                    """
                    (function() {
                      const needle = $quoted;
                      const nodes = Array.from(document.querySelectorAll('button,[role="button"],[role="treeitem"],a'));
                      const node = nodes.find(item => (item.textContent || '').includes(needle));
                      if (!node) return 'missing';
                      node.click();
                      return 'clicked';
                    })()
                    """.trimIndent()
                )
                if (clicked == "clicked") return@withTimeout
                delay(100L)
            }
        }
    }

    suspend fun awaitQuestion(marker: String, present: Boolean): String? = withTimeout<String?>(60_000L) {
        var answer: String? = null
        while (answer == null) {
            val text = evaluateString(
                """
                (function() {
                  const question = document.querySelector('[data-question-key]');
                  const approval = document.querySelector('[data-approval-key]');
                  return JSON.stringify({
                    question: question ? (question.textContent || '') : '',
                    approval: approval ? (approval.textContent || '') : ''
                  });
                })()
                """.trimIndent()
            ).orEmpty()
            if (text.contains(marker) == present) {
                answer = text.take(24_000)
            } else {
                delay(100L)
            }
        }
        answer
    }

    override fun close() {
        val view = viewRef.getAndSet(null) ?: return
        instrumentation.runOnMainSync {
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
        }
    }

    private fun evaluateString(script: String): String? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        instrumentation.runOnMainSync {
            viewRef.get()?.evaluateJavascript(script) { encoded ->
                result.set(encoded)
                latch.countDown()
            } ?: latch.countDown()
        }
        check(latch.await(15, TimeUnit.SECONDS)) { "QA WebView JavaScript evaluation timed out" }
        val raw = result.get() ?: return null
        if (raw == "null") return null
        return runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrNull()
    }
}
