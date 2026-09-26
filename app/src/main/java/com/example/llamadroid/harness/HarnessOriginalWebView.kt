package com.example.llamadroid.harness

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.example.llamadroid.R
import com.example.llamadroid.harness.runtime.HarnessEndpoint
import com.example.llamadroid.ui.agent.AGENT_PREVIEW_DOWNLOAD_BRIDGE_NAME
import com.example.llamadroid.ui.agent.AgentPreviewBlobExportSession
import com.example.llamadroid.ui.agent.AgentPreviewDownloadBridge
import com.example.llamadroid.ui.agent.AgentPreviewDownloadKind
import com.example.llamadroid.ui.agent.buildAgentPreviewDownloadPinScript
import com.example.llamadroid.ui.agent.buildAgentPreviewDownloadReleaseScript
import com.example.llamadroid.ui.agent.classifyAgentPreviewDownload
import com.example.llamadroid.ui.agent.isAgentPreviewDownloadSizeAllowed
import com.example.llamadroid.ui.agent.sanitizeAgentPreviewDownloadFilename
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

private const val WEB_BOOT_PROBE_INITIAL_DELAY_MS = 250L
private const val WEB_BOOT_PROBE_INTERVAL_MS = 250L
// Cold PRoot boot includes parsing the signed plugin graph; keep a bounded mount budget.
private const val WEB_BOOT_PROBE_DEADLINE_MS = 60_000L
private const val WEB_STATIC_CONNECT_TIMEOUT_MS = 5_000
private const val WEB_STATIC_READ_TIMEOUT_MS = 15_000
private const val WEB_INDEX_MAX_BYTES = 512 * 1024
private const val WEB_STATIC_CACHE_MAX_BYTES = 12 * 1024 * 1024
private const val WEB_STATIC_CACHE_MAX_ENTRY_BYTES = 2 * 1024 * 1024

private data class HarnessWebViewStaticCacheEntry(
    val mimeType: String,
    val charset: String,
    val status: Int,
    val message: String,
    val body: ByteArray,
)

/**
 * Static WebView resources are immutable for one authenticated runtime
 * generation. Keep a small process-local cache because intercepted responses
 * otherwise bypass WebView's normal HTTP cache on every boot/history revisit.
 */
private object HarnessWebViewStaticCache {
    private val entries = object : LinkedHashMap<String, HarnessWebViewStaticCacheEntry>(32, 0.75f, true) {}
    private var sizeBytes = 0

    fun get(key: String): HarnessWebViewStaticCacheEntry? = synchronized(entries) { entries[key] }

    fun put(key: String, value: HarnessWebViewStaticCacheEntry) = synchronized(entries) {
        entries.remove(key)?.let { sizeBytes -= it.body.size }
        entries[key] = value
        sizeBytes += value.body.size
        while (sizeBytes > WEB_STATIC_CACHE_MAX_BYTES && entries.isNotEmpty()) {
            val iterator = entries.entries.iterator()
            val eldest = iterator.next()
            sizeBytes -= eldest.value.body.size
            iterator.remove()
        }
    }
}

private fun bootResourceKind(uri: Uri): String? {
    val path = uri.encodedPath.orEmpty()
    if (path.substringAfterLast('/').startsWith("favicon", ignoreCase = true)) return null
    return when {
        path.startsWith("/assets/") -> "asset"
        path.startsWith("/plugins/") -> "plugin"
        else -> null
    }
}

private fun bootResourceMimeInvalid(uri: Uri, mimeType: String?): Boolean {
    val mime = mimeType?.lowercase().orEmpty()
    if (mime.isBlank()) return false
    val path = uri.encodedPath.orEmpty().lowercase()
    return when {
        path.endsWith(".css") -> !mime.contains("text/css")
        path.endsWith(".js") || path.endsWith(".mjs") ->
            !mime.contains("javascript") && !mime.contains("ecmascript")
        else -> false
    }
}

private fun sameHarnessOrigin(uri: Uri, endpoint: HarnessEndpoint): Boolean {
    val configured = endpoint.origin.toUri()
    return uri.scheme == configured.scheme && uri.host == configured.host && uri.port == configured.port
}

private fun staticMimeType(path: String, responseType: String?): String {
    responseType?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    return when {
        path.endsWith(".css", ignoreCase = true) -> "text/css"
        path.endsWith(".js", ignoreCase = true) || path.endsWith(".mjs", ignoreCase = true) -> "text/javascript"
        path.endsWith(".json", ignoreCase = true) -> "application/json"
        path.endsWith(".svg", ignoreCase = true) -> "image/svg+xml"
        path.endsWith(".webmanifest", ignoreCase = true) -> "application/manifest+json"
        path.startsWith("/plugins/") -> "text/javascript"
        else -> "text/html"
    }
}

private fun responseCharset(responseType: String?): String? = responseType
    ?.substringAfter("charset=", "")
    ?.substringBefore(';')
    ?.trim()
    ?.trim('"')
    ?.takeIf { it.isNotBlank() }

private fun readBounded(input: InputStream, maxBytes: Int): ByteArray? {
    val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    input.use { stream ->
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) return null
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}

private class DisconnectingInputStream(
    input: InputStream,
    private val connection: HttpURLConnection,
    private val onReadFailure: (Throwable) -> Unit,
) : FilterInputStream(input) {
    override fun read(): Int = readSafely { super.read() }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        readSafely { super.read(buffer, offset, length) }

    private inline fun readSafely(block: () -> Int): Int = try {
        block()
    } catch (error: java.io.IOException) {
        onReadFailure(error)
        throw error
    }

    override fun close() {
        try {
            super.close()
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * WebView does not consistently attach an app-installed HttpOnly cookie to its first request
 * across Android System WebView providers. Fetch only the immutable boot/static surface with the
 * authenticated native client; API calls and the remote WebSocket remain browser-owned so the
 * page keeps its normal same-origin behavior.
 */
private fun authenticatedHarnessStaticResponse(
    request: WebResourceRequest,
    endpoint: HarnessEndpoint,
    onFailure: (HarnessWebViewNativeStaticFailure) -> Unit = {},
): WebResourceResponse? {
    val uri = request.url
    val path = uri.encodedPath.orEmpty().ifBlank { "/" }
    if (!request.method.equals("GET", ignoreCase = true) || !sameHarnessOrigin(uri, endpoint) ||
        !isHarnessWebViewStaticPath(path)) return null

    val isIndex = path == "/" || path == "/index.html"
    val cacheKey = if (isIndex) null else buildString {
        // The cookie hash separates runtime generations without retaining the
        // credential itself in the cache key or diagnostics.
        append(endpoint.origin).append('|').append(endpoint.cookieHeader.hashCode()).append('|')
            .append(uri.encodedPath.orEmpty()).append('?').append(uri.encodedQuery.orEmpty())
    }
    cacheKey?.let { key ->
        HarnessWebViewStaticCache.get(key)?.let { cached ->
            return WebResourceResponse(
                cached.mimeType,
                cached.charset,
                cached.status,
                cached.message,
                mapOf("Cache-Control" to "public, max-age=31536000, immutable"),
                ByteArrayInputStream(cached.body),
            )
        }
    }

    val startedAt = SystemClock.elapsedRealtime()
    var responseStatus: Int? = null
    fun failure(kind: String, status: Int? = responseStatus): HarnessWebViewNativeStaticFailure =
        HarnessWebViewNativeStaticFailure(
            errorKind = boundedHarnessNativeStaticErrorKind(kind) ?: "unknown",
            elapsedMs = boundedHarnessNativeStaticElapsedMs(SystemClock.elapsedRealtime() - startedAt) ?: 0,
            httpStatus = status?.takeIf { it in 100..599 },
        )
    val connection = try {
        URL(uri.toString()).openConnection() as? HttpURLConnection
    } catch (error: Throwable) {
        onFailure(failure(classifyHarnessNativeStaticError(error)))
        return null
    } ?: run {
        onFailure(failure("unsupported"))
        return null
    }
    return runCatching {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = WEB_STATIC_CONNECT_TIMEOUT_MS
        connection.readTimeout = WEB_STATIC_READ_TIMEOUT_MS
        connection.useCaches = true
        connection.setRequestProperty("Accept-Encoding", "identity")
        connection.setRequestProperty("Cache-Control", "no-cache")
        connection.setRequestProperty("Cookie", endpoint.cookieHeader)
        val status = connection.responseCode
        responseStatus = status
        val responseType = connection.contentType
        val responseMessage = connection.responseMessage ?: "HTTP $status"
        val stream = if (status in 200..399) connection.inputStream else connection.errorStream
        val body = stream ?: ByteArrayInputStream(ByteArray(0))
        if (isIndex) {
            val bytes = readBounded(body, WEB_INDEX_MAX_BYTES)
            if (bytes == null) {
                connection.disconnect()
                onFailure(failure("body_too_large"))
                return@runCatching null
            }
            connection.disconnect()
            val html = String(bytes, Charsets.UTF_8)
            val patched = if (status in 200..299) patchHarnessWebViewIndexForAndroid(html) else html
            WebResourceResponse(
                staticMimeType(path, responseType),
                responseCharset(responseType) ?: "UTF-8",
                status.coerceIn(100, 599),
                responseMessage,
                mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(patched.toByteArray(Charsets.UTF_8)),
            )
        } else {
            val mimeType = staticMimeType(path, responseType)
            val charset = responseCharset(responseType) ?: "UTF-8"
            // Unknown or large bodies must stay streaming. A failed bounded read
            // consumes/closes the stream and cannot be reused as a fallback.
            if (status in 200..299 && cacheKey != null &&
                connection.contentLengthLong in 0..WEB_STATIC_CACHE_MAX_ENTRY_BYTES.toLong()) {
                val bytes = readBounded(body, WEB_STATIC_CACHE_MAX_ENTRY_BYTES)
                if (bytes != null) {
                    connection.disconnect()
                    HarnessWebViewStaticCache.put(
                        cacheKey,
                        HarnessWebViewStaticCacheEntry(
                            mimeType = mimeType,
                            charset = charset,
                            status = status.coerceIn(100, 599),
                            message = responseMessage,
                            body = bytes,
                        )
                    )
                    WebResourceResponse(
                        mimeType,
                        charset,
                        status.coerceIn(100, 599),
                        responseMessage,
                        mapOf("Cache-Control" to "public, max-age=31536000, immutable"),
                        ByteArrayInputStream(bytes),
                    )
                } else {
                    connection.disconnect()
                    onFailure(failure("body_too_large"))
                    null
                }
            } else WebResourceResponse(
                mimeType,
                charset,
                status.coerceIn(100, 599),
                responseMessage,
                mapOf("Cache-Control" to "no-store"),
                DisconnectingInputStream(body, connection) { error ->
                    onFailure(failure(classifyHarnessNativeStaticError(error)))
                },
            )
        }
    }.getOrElse { error ->
        connection.disconnect()
        onFailure(failure(classifyHarnessNativeStaticError(error)))
        null
    }
}

private fun scheduleHarnessWebViewBootProbe(
    view: WebView,
    firstResourceFailure: AtomicReference<HarnessWebViewBootDiagnostic?>,
    probeStarted: AtomicBoolean,
    probeFinished: AtomicBoolean,
    navigationStartedAt: AtomicLong,
    onDiagnostic: (String, String?, Int?) -> Unit,
    onBootDiagnostic: (HarnessWebViewBootDiagnostic) -> Unit,
    onTerminal: (HarnessWebViewBootDiagnostic) -> Unit,
) {
    if (!probeStarted.compareAndSet(false, true)) return
    val startedAt = navigationStartedAt.get().takeIf { it > 0 } ?: SystemClock.elapsedRealtime()

    fun probe() {
        if (probeFinished.get() || navigationStartedAt.get() != startedAt) return
        val resourceFailure = firstResourceFailure.get()
        if (resourceFailure != null) {
            if (probeFinished.compareAndSet(false, true)) onTerminal(resourceFailure)
            return
        }
        val deadlineReached = SystemClock.elapsedRealtime() - startedAt >= WEB_BOOT_PROBE_DEADLINE_MS
        runCatching {
            view.evaluateJavascript(buildHarnessWebViewBootProbeScript()) { raw ->
                if (probeFinished.get() || navigationStartedAt.get() != startedAt) return@evaluateJavascript
                val atDeadline = deadlineReached ||
                    SystemClock.elapsedRealtime() - startedAt >= WEB_BOOT_PROBE_DEADLINE_MS
                val diagnostic = parseHarnessWebViewBootProbe(raw)?.toDiagnostic(atDeadline)
                    ?: HarnessWebViewBootDiagnostic(
                        outcome = if (atDeadline) "failure" else "pending",
                        code = if (atDeadline) "HARNESS_WEBUI_BOOT_GRAPH_MISSING" else "HARNESS_WEBUI_BOOT_PENDING",
                    )
                if (diagnostic.outcome == "ready" || diagnostic.outcome == "failure" || atDeadline) {
                    if (probeFinished.compareAndSet(false, true)) onTerminal(diagnostic)
                } else {
                    onBootDiagnostic(diagnostic)
                    view.postDelayed({ probe() }, WEB_BOOT_PROBE_INTERVAL_MS)
                }
            }
        }.onFailure {
            val atDeadline = SystemClock.elapsedRealtime() - startedAt >= WEB_BOOT_PROBE_DEADLINE_MS
            if (atDeadline) {
                val diagnostic = HarnessWebViewBootDiagnostic(
                    outcome = "failure",
                    code = "HARNESS_WEBUI_BOOT_GRAPH_MISSING",
                )
                if (probeFinished.compareAndSet(false, true)) onTerminal(diagnostic)
            } else {
                view.postDelayed({ probe() }, WEB_BOOT_PROBE_INTERVAL_MS)
            }
        }
    }

    onDiagnostic("opening", "HARNESS_WEBUI_BOOT_PENDING", null)
    view.postDelayed({ probe() }, WEB_BOOT_PROBE_INITIAL_DELAY_MS)
}

private fun scheduleHarnessWebViewNavigationWatchdog(
    view: WebView,
    navigationStartedAt: AtomicLong,
    pageFinished: AtomicBoolean,
    probeFinished: AtomicBoolean,
    onTimeout: (HarnessWebViewBootDiagnostic) -> Unit,
) {
    val startedAt = navigationStartedAt.get()
    if (startedAt <= 0) return
    view.postDelayed({
        if (!pageFinished.get() && !probeFinished.get() && navigationStartedAt.get() == startedAt) {
            val diagnostic = HarnessWebViewBootDiagnostic(
                outcome = "failure",
                code = HARNESS_WEBUI_NAVIGATION_TIMEOUT,
            )
            if (probeFinished.compareAndSet(false, true)) onTimeout(diagnostic)
        }
    }, WEB_BOOT_PROBE_DEADLINE_MS)
}

/** Authenticated original interface. This WebView never registers as the project preview. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HarnessOriginalWebView(
    endpoint: HarnessEndpoint, modifier: Modifier = Modifier, onDownload: (String) -> Unit = {},
    onDiagnostic: (outcome: String, code: String?, httpStatus: Int?) -> Unit = { _, _, _ -> },
    onBootDiagnostic: (HarnessWebViewBootDiagnostic) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentDownload by rememberUpdatedState(onDownload)
    val currentDiagnostic by rememberUpdatedState(onDiagnostic)
    val currentBootDiagnostic by rememberUpdatedState(onBootDiagnostic)
    var failed by remember(endpoint.origin, endpoint.cookieHeader) { mutableStateOf(false) }
    var bootLoading by remember(endpoint.origin, endpoint.cookieHeader) { mutableStateOf(true) }
    var failureCode by remember(endpoint.origin, endpoint.cookieHeader) { mutableStateOf<String?>(null) }
    var attempt by remember(endpoint.origin, endpoint.cookieHeader) { mutableStateOf(0) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val exportBridge = remember(endpoint.origin, endpoint.cookieHeader, attempt) { AgentPreviewDownloadBridge() }
    val firstResourceFailure = remember(endpoint.origin, endpoint.cookieHeader, attempt) {
        AtomicReference<HarnessWebViewBootDiagnostic?>(null)
    }
    val bootProbeStarted = remember(endpoint.origin, endpoint.cookieHeader, attempt) { AtomicBoolean(false) }
    val bootProbeFinished = remember(endpoint.origin, endpoint.cookieHeader, attempt) { AtomicBoolean(false) }
    val pageFinished = remember(endpoint.origin, endpoint.cookieHeader, attempt) { AtomicBoolean(false) }
    val navigationStartedAt = remember(endpoint.origin, endpoint.cookieHeader, attempt) { AtomicLong(0L) }
    var exportTarget by remember { mutableStateOf<Pair<WebView, String>?>(null) }
    var exportSession by remember { mutableStateOf<AgentPreviewBlobExportSession?>(null) }
    val blobPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val target = exportTarget
        exportTarget = null
        if (target != null) {
            if (uri == null) target.first.evaluateJavascript(buildAgentPreviewDownloadReleaseScript(target.second), null)
            else if (classifyAgentPreviewDownload(target.first.url.orEmpty(), endpoint.origin) == AgentPreviewDownloadKind.SAME_ORIGIN_HTTP) {
                val session = AgentPreviewBlobExportSession(target.first, exportBridge, context.contentResolver, uri, target.second, scope) { result ->
                    exportSession = null
                    target.first.evaluateJavascript(buildAgentPreviewDownloadReleaseScript(target.second), null)
                    Toast.makeText(context, if (result.isSuccess) R.string.status_complete else R.string.harness_attachment_failed, Toast.LENGTH_SHORT).show()
                }
                exportSession = session
                runCatching { session.start() }.onFailure { session.cancel(); exportSession = null }
            }
        }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }
    val view = remember(endpoint.origin, endpoint.cookieHeader, attempt) {
        WebView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setNetworkAvailable(true)
            onResume()
            resumeTimers()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            // This export-only interface accepts chunks for one user-selected document and nonce.
            // It exposes no filesystem lookup, credential, tool or execution operations.
            installHarnessPreviewDownloadBridge(exportBridge)
            setDownloadListener { url, _, disposition, mime, length ->
                if (classifyAgentPreviewDownload(url, endpoint.origin) == AgentPreviewDownloadKind.BLOB) {
                    if (exportSession == null && exportTarget == null && isAgentPreviewDownloadSizeAllowed(length)) {
                        exportTarget = this to url
                        blobPicker.launch(sanitizeAgentPreviewDownloadFilename(disposition, mime))
                    } else Toast.makeText(context, R.string.harness_attachment_failed, Toast.LENGTH_SHORT).show()
                } else currentDownload(url)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val isStaticRequest = request.method.equals("GET", ignoreCase = true) &&
                        sameHarnessOrigin(request.url, endpoint) &&
                        isHarnessWebViewStaticPath(request.url.encodedPath.orEmpty().ifBlank { "/" })
                    fun publishStaticFailure(diagnostic: HarnessWebViewBootDiagnostic) {
                        if (!firstResourceFailure.compareAndSet(null, diagnostic)) return
                        view.post {
                            bootProbeFinished.set(true)
                            bootLoading = false
                            failureCode = diagnostic.code
                            failed = true
                            currentDiagnostic("failure", diagnostic.code, diagnostic.httpStatus)
                            currentBootDiagnostic(diagnostic)
                        }
                    }
                    val response = authenticatedHarnessStaticResponse(request, endpoint) { failure ->
                        publishStaticFailure(
                            HarnessWebViewBootDiagnostic(
                                outcome = "failure",
                                code = HARNESS_WEBVIEW_STATIC_INTERCEPT_FAILED,
                                httpStatus = failure.httpStatus,
                                resourceKind = if (request.isForMainFrame) "index" else bootResourceKind(request.url),
                                nativeStaticErrorKind = failure.errorKind,
                                nativeStaticElapsedMs = failure.elapsedMs,
                            ),
                        )
                    }
                    if (isStaticRequest && response == null) {
                        publishStaticFailure(
                            HarnessWebViewBootDiagnostic(
                                outcome = "failure",
                                code = HARNESS_WEBVIEW_STATIC_INTERCEPT_FAILED,
                                resourceKind = if (request.isForMainFrame) "index" else bootResourceKind(request.url),
                                nativeStaticErrorKind = "unknown",
                                nativeStaticElapsedMs = 0,
                            ),
                        )
                    }
                    return response
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (sameHarnessOrigin(url.toUri(), endpoint)) {
                        navigationStartedAt.set(SystemClock.elapsedRealtime())
                        pageFinished.set(false)
                        bootProbeStarted.set(false)
                        bootProbeFinished.set(false)
                        firstResourceFailure.set(null)
                        bootLoading = true
                        failureCode = null
                        currentDiagnostic("opening", "HARNESS_WEBUI_BOOT_PENDING", null)
                        scheduleHarnessWebViewNavigationWatchdog(
                            view = view,
                            navigationStartedAt = navigationStartedAt,
                            pageFinished = pageFinished,
                            probeFinished = bootProbeFinished,
                        ) { diagnostic ->
                            if (diagnostic.outcome == "failure") {
                                firstResourceFailure.compareAndSet(null, diagnostic)
                            }
                            bootLoading = false
                            failureCode = diagnostic.code
                            failed = true
                            currentBootDiagnostic(diagnostic)
                            currentDiagnostic("failure", diagnostic.code, diagnostic.httpStatus)
                        }
                    }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (sameHarnessOrigin(url.toUri(), endpoint)) {
                        pageFinished.set(true)
                        view.evaluateJavascript(buildAgentPreviewDownloadPinScript(endpoint.origin), null)
                        // Page load only proves that index.html reached WebView. The pinned client
                        // removes [data-dsh-boot] only after all plugin activation and the real
                        // uiRenderer mount; poll until that terminal DOM state or a bounded timeout.
                        scheduleHarnessWebViewBootProbe(
                            view = view,
                            firstResourceFailure = firstResourceFailure,
                            probeStarted = bootProbeStarted,
                            probeFinished = bootProbeFinished,
                            navigationStartedAt = navigationStartedAt,
                            onDiagnostic = currentDiagnostic,
                            onBootDiagnostic = currentBootDiagnostic,
                        ) { diagnostic ->
                            if (diagnostic.outcome == "failure") {
                                firstResourceFailure.compareAndSet(null, diagnostic)
                            }
                            bootLoading = false
                            failureCode = diagnostic.code
                            currentBootDiagnostic(diagnostic)
                            if (diagnostic.outcome == "failure") {
                                failed = true
                                currentDiagnostic("failure", diagnostic.code, diagnostic.httpStatus)
                            } else {
                                currentDiagnostic("ready", diagnostic.code, diagnostic.httpStatus)
                            }
                        }
                    }
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    bootProbeFinished.set(true)
                    bootLoading = false
                    failureCode = "HARNESS_WEBVIEW_PROCESS_LOST"
                    currentBootDiagnostic(
                        HarnessWebViewBootDiagnostic(
                            outcome = "failure",
                            code = "HARNESS_WEBVIEW_PROCESS_LOST",
                        ),
                    )
                    failed = true
                    currentDiagnostic("failure", "HARNESS_WEBVIEW_PROCESS_LOST", null)
                    return true
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    val resourceKind = if (!request.isForMainFrame && sameHarnessOrigin(request.url, endpoint)) {
                        bootResourceKind(request.url)
                    } else null
                    val essential = request.isForMainFrame || resourceKind != null
                    if (response.statusCode >= 400 && essential) {
                        val code = when {
                            request.isForMainFrame -> "HARNESS_WEBVIEW_HTTP_FAILED"
                            resourceKind != null && response.statusCode in setOf(401, 403) -> HARNESS_WEBUI_BOOT_ASSET_AUTH_FAILED
                            resourceKind != null && bootResourceMimeInvalid(request.url, response.mimeType) -> HARNESS_WEBUI_BOOT_ASSET_MIME_INVALID
                            resourceKind == "plugin" -> "HARNESS_WEBUI_PLUGIN_HTTP_FAILED"
                            else -> HARNESS_WEBUI_BOOT_ASSET_HTTP_FAILED
                        }
                        val diagnostic = HarnessWebViewBootDiagnostic(
                            outcome = "failure",
                            code = code,
                            httpStatus = response.statusCode,
                            resourceKind = resourceKind,
                            mimeType = response.mimeType,
                        )
                        if (firstResourceFailure.compareAndSet(null, diagnostic)) {
                            bootProbeFinished.set(true)
                            bootLoading = false
                            failed = true
                            failureCode = code
                            currentDiagnostic("failure", code, response.statusCode)
                            currentBootDiagnostic(diagnostic)
                        }
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    val resourceKind = if (!request.isForMainFrame && sameHarnessOrigin(request.url, endpoint)) {
                        bootResourceKind(request.url)
                    } else null
                    if (request.isForMainFrame || resourceKind != null) {
                        val code = when {
                            request.isForMainFrame -> "HARNESS_WEBVIEW_LOAD_FAILED"
                            resourceKind == "plugin" -> "HARNESS_WEBUI_PLUGIN_LOAD_FAILED"
                            else -> HARNESS_WEBUI_BOOT_ASSET_HTTP_FAILED
                        }
                        val diagnostic = HarnessWebViewBootDiagnostic(
                            outcome = "failure",
                            code = code,
                            resourceKind = resourceKind,
                            webResourceErrorCode = boundedHarnessWebResourceErrorCode(error.errorCode),
                        )
                        if (firstResourceFailure.compareAndSet(null, diagnostic)) {
                            bootProbeFinished.set(true)
                            bootLoading = false
                            failed = true
                            failureCode = code
                            currentDiagnostic("failure", code, null)
                            currentBootDiagnostic(diagnostic)
                        }
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    val configuredOrigin = endpoint.origin.toUri()
                    val sameOrigin = uri.scheme == "http" && uri.host == configuredOrigin.host && uri.port == configuredOrigin.port
                    if (sameOrigin || !request.isForMainFrame) return false
                    if (uri.scheme in setOf("https", "http")) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(webView: WebView?, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return runCatching { files.launch(params.createIntent()); true }.getOrElse {
                        callback.onReceiveValue(null); fileCallback = null; false
                    }
                }

                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    // The browser reports two important bootstrap failures only through the
                    // console: a module served with the wrong MIME and the client module
                    // loader rejecting an imported plugin. Keep the message itself out of
                    // diagnostics and retain only the bounded failure class and resource kind.
                    if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                        val text = message.message().lowercase()
                        val source = message.sourceId().orEmpty()
                        val sourceUri = source.toUri()
                        val sourceIsFavicon = sourceUri.encodedPath.orEmpty()
                            .substringAfterLast('/').startsWith("favicon", ignoreCase = true)
                        if (!sourceIsFavicon) {
                            val mimeFailure = text.contains("mime type")
                            val importFailure = mimeFailure || text.contains("failed to load module") ||
                                text.contains("import failed") || text.contains("web boot:")
                            val scriptFailure = bootLoading && (
                                text.contains("uncaught") || text.contains("syntaxerror") ||
                                    text.contains("referenceerror") || text.contains("typeerror") ||
                                    text.contains("securityerror")
                                )
                            if (importFailure || scriptFailure) {
                                val resourceKind = if (sameHarnessOrigin(sourceUri, endpoint)) {
                                    bootResourceKind(sourceUri)
                                } else null
                                val code = when {
                                    mimeFailure -> HARNESS_WEBUI_BOOT_ASSET_MIME_INVALID
                                    importFailure -> HARNESS_WEBUI_BOOT_IMPORT_FAILED
                                    else -> HARNESS_WEBUI_SCRIPT_ERROR
                                }
                                val diagnostic = HarnessWebViewBootDiagnostic(
                                    outcome = "failure",
                                    code = code,
                                    resourceKind = resourceKind,
                                )
                                if (firstResourceFailure.compareAndSet(null, diagnostic)) {
                                    bootProbeFinished.set(true)
                                    bootLoading = false
                                    failureCode = code
                                    failed = true
                                    currentDiagnostic("failure", code, null)
                                    currentBootDiagnostic(diagnostic)
                                }
                            }
                        }
                    }
                    return super.onConsoleMessage(message)
                }
            }
        }
    }
    LaunchedEffect(view, endpoint.cookieHeader) {
        bootLoading = true
        failureCode = null
        currentDiagnostic("opening", null, null)
        val installed = CompletableDeferred<Boolean>()
        val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }
        val cookiePair = endpoint.cookieHeader.substringBefore(';').trim()
        cookies.setCookie(endpoint.origin, cookiePair + "; Path=/; HttpOnly; SameSite=Lax") {
            installed.complete(it)
        }
        val cookieWritten = withTimeoutOrNull(5_000) { installed.await() } == true
        cookies.flush()
        val cookieVisible = cookies.getCookie(endpoint.origin).orEmpty()
            .split(';')
            .asSequence()
            .map { it.trim() }
            .any { it == cookiePair }
        if (cookieWritten && cookieVisible) {
            // Keep the explicit header for the first navigation as a fallback for WebView
            // providers that acknowledge setCookie() before their cookie store is attached.
            view.loadUrl(
                endpoint.origin + "/",
                mapOf("Cookie" to endpoint.cookieHeader, "Cache-Control" to "no-cache"),
            )
        } else {
            bootLoading = false
            failureCode = HARNESS_WEBVIEW_COOKIE_UNAVAILABLE
            failed = true
            currentDiagnostic("failure", HARNESS_WEBVIEW_COOKIE_UNAVAILABLE, null)
            currentBootDiagnostic(
                HarnessWebViewBootDiagnostic(
                    outcome = "failure",
                    code = HARNESS_WEBVIEW_COOKIE_UNAVAILABLE,
                ),
            )
        }
    }
    DisposableEffect(view) {
        onDispose {
            bootProbeFinished.set(true)
            exportSession?.cancel(); exportSession = null; exportTarget = null; exportBridge.clear()
            fileCallback?.onReceiveValue(null)
            fileCallback = null
            view.onPause()
            view.pauseTimers()
            view.stopLoading()
            view.destroy()
        }
    }
    if (failed) {
        Column(modifier.fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.harness_webui_boot_unavailable), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.harness_webui_boot_unavailable_detail))
            failureCode?.let { Text(stringResource(R.string.harness_webui_boot_failure_detail, it)) }
            TextButton(onClick = { failed = false; attempt += 1 }) { Text(stringResource(R.string.harness_runtime_retry)) }
        }
    } else Box(modifier.fillMaxSize()) {
        // A new runtime credential or retry owns a new browser instance. Re-key the
        // AndroidView too, otherwise its factory can retain the disposed old view.
        key(view) { AndroidView(factory = { view }, modifier = Modifier.fillMaxSize()) }
        if (bootLoading) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.94f))
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.harness_webui_boot_loading))
                Text(stringResource(R.string.harness_webui_boot_loading_detail))
            }
        }
    }
}

/**
 * The remembered bridge is concrete and exposes only its three annotated
 * chunk/complete/fail methods. Lint cannot infer that type through the
 * generic Compose `remember` value at the call site.
 */
@SuppressLint("JavascriptInterface")
private fun WebView.installHarnessPreviewDownloadBridge(bridge: AgentPreviewDownloadBridge) {
    addJavascriptInterface(bridge, AGENT_PREVIEW_DOWNLOAD_BRIDGE_NAME)
}
