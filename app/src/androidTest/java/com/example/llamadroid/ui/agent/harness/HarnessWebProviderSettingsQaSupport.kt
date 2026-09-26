package com.example.llamadroid.ui.agent.harness

import android.annotation.SuppressLint
import android.app.Instrumentation
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import com.example.llamadroid.harness.HarnessOriginalWebView
import com.example.llamadroid.harness.HarnessWebViewBootDiagnostic
import com.example.llamadroid.harness.runtime.HarnessEndpoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Production WebView surface for the provider-settings QA. The view is the canonical
 * [HarnessOriginalWebView], while the small DOM helpers only press the public controls and
 * fill their labelled fields; settings mutations still travel through the authenticated page.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class HarnessWebProviderSettingsQaSurface(
    private val instrumentation: Instrumentation,
    private val endpoint: HarnessEndpoint,
) {
    private val ready = AtomicReference(CountDownLatch(1))
    private val failure = AtomicReference<String?>(null)
    private val scenarioRef = AtomicReference<ActivityScenario<ComponentActivity>?>(null)
    private val viewRef = AtomicReference<WebView?>(null)

    suspend fun attach(scenario: ActivityScenario<ComponentActivity>) {
        scenarioRef.set(scenario)
        scenario.onActivity { activity ->
            activity.setContent {
                HarnessOriginalWebView(
                    endpoint = endpoint,
                    modifier = Modifier.fillMaxSize(),
                    onDiagnostic = { outcome, code, _ ->
                        if (outcome == "failure") {
                            failure.compareAndSet(null, code ?: "HARNESS_WEBUI_FAILURE")
                            ready.get().countDown()
                        }
                    },
                    onBootDiagnostic = { diagnostic ->
                        when (diagnostic.outcome) {
                            "ready" -> ready.get().countDown()
                            "failure" -> {
                                failure.set(formatBootFailure(diagnostic))
                                ready.get().countDown()
                            }
                        }
                    },
                )
            }
            activity.window.decorView.post {
                viewRef.set(activity.window.decorView.findWebViewInTree())
            }
        }
        awaitReady()
    }

    /**
     * Keep the emulator failure actionable while retaining only reviewed metadata. In
     * particular, do not include WebView's free-form description, URL, response body, or
     * console message in the test result.
     */
    private fun formatBootFailure(diagnostic: HarnessWebViewBootDiagnostic): String = buildString {
        append(diagnostic.code ?: "HARNESS_WEBUI_FAILURE")
        diagnostic.resourceKind
            ?.takeIf { it in setOf("index", "plugin", "asset") }
            ?.let { append("|resource=").append(it) }
        diagnostic.httpStatus
            ?.takeIf { it in 100..599 }
            ?.let { append("|http=").append(it) }
        diagnostic.webResourceErrorCode
            ?.takeIf { it in -100..0 }
            ?.let { append("|webError=").append(it) }
        diagnostic.nativeStaticErrorKind
            ?.let { append("|native=").append(it) }
        diagnostic.nativeStaticElapsedMs
            ?.takeIf { it in 0..60_000 }
            ?.let { append("|elapsedMs=").append(it) }
    }

    suspend fun reload() {
        ready.set(CountDownLatch(1))
        failure.set(null)
        refreshWebViewRef()
        instrumentation.runOnMainSync {
            requireNotNull(findWebView()) { "HarnessOriginalWebView was not mounted" }.reload()
        }
        awaitReady()
    }

    suspend fun awaitText(text: String) = awaitBody(text, present = true)

    suspend fun awaitTextAbsent(text: String) = awaitBody(text, present = false)

    suspend fun clickTextIfPresent(text: String): Boolean {
        val script =
            """
            (function() {
              const needle = ${JSONObject.quote(text)};
              const nodes = Array.from(document.querySelectorAll('button,[role="button"],summary,a'));
              const visible = node => {
                const rect = node.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0 && getComputedStyle(node).visibility !== 'hidden';
              };
              const node = nodes.find(item => visible(item) && (item.textContent || '').includes(needle));
              if (!node) return 'missing';
              node.click();
              return 'clicked';
            })()
            """.trimIndent()
        val clicked = withTimeoutOrNull<Boolean>(5_000L) {
            var found = false
            while (!found) {
                found = evaluateString(script) == "clicked"
                if (!found) delay(100L)
            }
            found
        }
        return clicked == true
    }

    suspend fun clickSettingsNav(label: String) {
        awaitAction(
            "click settings navigation $label",
            """
            (function() {
              const needle = ${JSONObject.quote(label)};
              const nodes = Array.from(document.querySelectorAll('[role="dialog"] nav button'));
              const visible = node => {
                const rect = node.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0 && getComputedStyle(node).visibility !== 'hidden';
              };
              const node = nodes.find(item => visible(item) && (item.textContent || '').trim() === needle);
              if (!node) return 'missing';
              node.click();
              return 'clicked';
            })()
            """.trimIndent(),
        )
    }

    suspend fun clickText(text: String) {
        awaitAction(
            "click text $text",
            """
            (function() {
              const needle = ${JSONObject.quote(text)};
              const nodes = Array.from(document.querySelectorAll('button,[role="button"],summary,a'));
              const visible = node => {
                const rect = node.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0 && getComputedStyle(node).visibility !== 'hidden';
              };
              const node = nodes.find(item => visible(item) && (item.textContent || '').includes(needle));
              if (!node) return 'missing';
              node.click();
              return 'clicked';
            })()
            """.trimIndent(),
        )
    }

    suspend fun clickAriaLabel(label: String) {
        awaitAction(
            "click aria-label $label",
            """
            (function() {
              const needle = ${JSONObject.quote(label)};
              const nodes = Array.from(document.querySelectorAll('[aria-label]'));
              const visible = node => {
                const rect = node.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0 && getComputedStyle(node).visibility !== 'hidden';
              };
              const node = nodes.find(item => visible(item) && item.getAttribute('aria-label') === needle);
              if (!node) return 'missing';
              node.click();
              return 'clicked';
            })()
            """.trimIndent(),
        )
    }

    suspend fun clickAriaLabelContaining(text: String) {
        awaitAction(
            "click aria-label containing $text",
            """
            (function() {
              const needle = ${JSONObject.quote(text)};
              const nodes = Array.from(document.querySelectorAll('[aria-label]'));
              const visible = node => {
                const rect = node.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0 && getComputedStyle(node).visibility !== 'hidden';
              };
              const node = nodes.find(item => visible(item) && (item.getAttribute('aria-label') || '').includes(needle));
              if (!node) return 'missing';
              node.click();
              return 'clicked';
            })()
            """.trimIndent(),
        )
    }

    suspend fun fillInput(label: String, value: String) {
        awaitAction(
            "fill input $label",
            """
            (function() {
              const label = ${JSONObject.quote(label)};
              const value = ${JSONObject.quote(value)};
              const nodes = Array.from(document.querySelectorAll('input[aria-label],textarea[aria-label]'));
              const visible = node => {
                const rect = node.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0 && !node.disabled && getComputedStyle(node).visibility !== 'hidden';
              };
              const node = nodes.find(item => visible(item) && item.getAttribute('aria-label') === label);
              if (!node) return 'missing';
              const prototype = node instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
              const setter = Object.getOwnPropertyDescriptor(prototype, 'value').set;
              setter.call(node, value);
              node.dispatchEvent(new Event('input', {bubbles: true}));
              node.dispatchEvent(new Event('change', {bubbles: true}));
              node.blur();
              return 'filled';
            })()
            """.trimIndent(),
        )
    }

    /** Authenticated Remote RPC used to assert the persisted page state after UI actions. */
    suspend fun rpc(namespace: String, method: String, args: JsonObject = buildJsonObject {}): JsonElement {
        val rpcId = UUID.randomUUID().toString()
        val global = "__adtProviderQaRpc_${rpcId.replace("-", "")}"
        val envelope = buildJsonObject {
            put("type", "client-request")
            put("rpcId", rpcId)
            put("method", "$namespace/$method")
            putJsonObject("payload") { put("args", args) }
        }
        val key = JSONObject.quote(global)
        val path = JSONObject.quote("/api/$namespace/$method")
        val body = JSONObject.quote(envelope.toString())
        evaluate(
            """
            (function() {
              window[$key] = null;
              fetch($path, {
                method: 'POST', credentials: 'include',
                headers: {'Content-Type': 'application/json', 'Accept': 'application/json'},
                body: $body
              }).then(async function(response) {
                window[$key] = JSON.stringify({status: response.status, body: await response.text()});
              }).catch(function(error) {
                window[$key] = JSON.stringify({status: 0, body: String(error)});
              });
            })();
            """.trimIndent(),
        )
        val encoded = withTimeout(30_000L) {
            var value: String? = null
            while (value == null) {
                value = evaluateString("window[$key] === null ? null : window[$key]")
                if (value == null) delay(50L)
            }
            requireNotNull(value) { "WebView RPC $namespace/$method returned no response" }
        }
        evaluate("delete window[$key]")
        val transport = Json.parseToJsonElement(encoded).jsonObject
        check(transport["status"]?.jsonPrimitive?.content == "200") {
            "WebView RPC $namespace/$method returned HTTP ${transport["status"]}"
        }
        val response = Json.parseToJsonElement(transport["body"]?.jsonPrimitive?.content.orEmpty()).jsonObject
        check(response["type"]?.jsonPrimitive?.content == "server-response") {
            "WebView RPC $namespace/$method returned an invalid response envelope"
        }
        check(response["rpcId"]?.jsonPrimitive?.content == rpcId) {
            "WebView RPC $namespace/$method returned the wrong request ID"
        }
        val result = response["result"]?.jsonObject
            ?: error("WebView RPC $namespace/$method returned no result")
        check(result["ok"]?.jsonPrimitive?.content == "true") {
            "WebView RPC $namespace/$method failed: $result"
        }
        return result["value"] ?: JsonNull
    }

    private suspend fun awaitReady() {
        withTimeout(90_000L) {
            while (!ready.get().await(100L, TimeUnit.MILLISECONDS)) {
                failure.get()?.let { error("HarnessOriginalWebView failed during provider QA: $it") }
            }
        }
        failure.get()?.let { error("HarnessOriginalWebView failed during provider QA: $it") }
    }

    private suspend fun awaitBody(text: String, present: Boolean) {
        withTimeout(90_000L) {
            while (true) {
                val body = evaluateString("document.body ? document.body.innerText : ''").orEmpty()
                if (body.contains(text) == present) return@withTimeout
                failure.get()?.let { error("HarnessOriginalWebView failed during provider QA: $it") }
                delay(100L)
            }
        }
    }

    private suspend fun awaitAction(description: String, script: String) {
        withTimeout(45_000L) {
            while (true) {
                val outcome = evaluateString(script)
                if (outcome == "clicked" || outcome == "filled") return@withTimeout
                failure.get()?.let { error("$description failed because WebView boot failed: $it") }
                delay(100L)
            }
        }
    }

    private fun evaluate(script: String): String {
        refreshWebViewRef()
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        instrumentation.runOnMainSync {
            findWebView()?.evaluateJavascript(script) {
                result.set(it)
                latch.countDown()
            } ?: latch.countDown()
        }
        check(latch.await(15L, TimeUnit.SECONDS)) { "WebView JavaScript evaluation timed out" }
        return result.get() ?: "null"
    }

    private fun evaluateString(script: String): String? {
        val raw = evaluate(script)
        if (raw == "null") return null
        return runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrNull()
    }

    private fun findWebView(): WebView? {
        return viewRef.get()
    }

    private fun refreshWebViewRef() {
        scenarioRef.get()?.onActivity { activity ->
            viewRef.set(activity.window.decorView.findWebViewInTree())
        }
    }

    private fun View.findWebViewInTree(): WebView? {
        if (this is WebView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val nested = getChildAt(index).findWebViewInTree()
            if (nested != null) return nested
        }
        return null
    }
}

/** Loopback OpenAI-compatible endpoint returning an empty catalog without requiring a key. */
internal class HarnessQaEmptyModelProvider : AutoCloseable {
    data class Request(val path: String, val authorization: String?)

    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val workers: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "adt-harness-provider-settings-qa").apply { isDaemon = true }
    }
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val captured = CopyOnWriteArrayList<Request>()
    private val acceptThread = Thread({ acceptLoop() }, "adt-harness-provider-settings-qa-accept")

    val baseUrl: String = "http://127.0.0.1:${server.localPort}/v1"

    init {
        acceptThread.isDaemon = true
        acceptThread.start()
    }

    fun requests(): List<Request> = captured.toList()

    suspend fun awaitRequestCount(expected: Int) = withTimeout(45_000L) {
        while (captured.size < expected) delay(50L)
    }

    override fun close() {
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        runCatching { workers.awaitTermination(2L, TimeUnit.SECONDS) }
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
            val reader = BufferedReader(InputStreamReader(owned.getInputStream(), StandardCharsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
            }
            val target = requestLine.split(' ').getOrNull(1).orEmpty()
            val path = target.substringBefore('?')
            captured += Request(path, headers["authorization"])
            val (status, body) = when {
                path.endsWith("/models") -> 200 to "{\"data\":[]}"
                path.endsWith("/props") -> 200 to "{\"model\":\"\"}"
                else -> 404 to "{}"
            }
            writeResponse(owned.getOutputStream(), status, body)
        }
    }

    private fun writeResponse(output: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = if (status == 200) "OK" else "Not Found"
        output.write(
            "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\n".toByteArray(StandardCharsets.ISO_8859_1)
        )
        output.write("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }
}
