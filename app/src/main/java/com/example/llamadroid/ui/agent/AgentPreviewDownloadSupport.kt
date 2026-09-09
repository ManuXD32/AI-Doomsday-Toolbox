package com.example.llamadroid.ui.agent

import android.content.ContentResolver
import android.net.Uri
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal const val AGENT_PREVIEW_MAX_DOWNLOAD_BYTES = 16L * 1024L * 1024L
internal const val AGENT_PREVIEW_DOWNLOAD_CHUNK_BYTES = 32 * 1024

private const val AGENT_PREVIEW_DOWNLOAD_QUEUE_CAPACITY = 2
internal const val AGENT_PREVIEW_DOWNLOAD_BRIDGE_NAME = "AgentPreviewDownload"
private const val AGENT_PREVIEW_DOWNLOAD_MAX_FILENAME_CHARS = 96
private const val AGENT_PREVIEW_DOWNLOAD_MAX_MIME_CHARS = 128
private const val AGENT_PREVIEW_DOWNLOAD_MAX_ERROR_CHARS = 120
private const val AGENT_PREVIEW_DOWNLOAD_MAX_URL_CHARS = 2_048
private const val AGENT_PREVIEW_DOWNLOAD_MAX_PINNED_URLS = 8
private const val AGENT_PREVIEW_DOWNLOAD_PIN_STATE_NAME = "__adtPreviewDownloadPins"
private const val AGENT_PREVIEW_DOWNLOAD_PIN_EXPIRY_MS = 120_000L
private const val AGENT_PREVIEW_DOWNLOAD_CHUNK_ACK_TIMEOUT_MS = 5_000L
private const val AGENT_PREVIEW_DOWNLOAD_TIMEOUT_MS = 60_000L
private const val AGENT_PREVIEW_DOWNLOAD_MAX_BASE64_CHARS =
    ((AGENT_PREVIEW_DOWNLOAD_CHUNK_BYTES + 2) / 3) * 4

internal data class AgentPreviewDownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val contentLength: Long
)

internal enum class AgentPreviewDownloadKind {
    BLOB,
    SAME_ORIGIN_HTTP,
    UNSUPPORTED
}

/**
 * A bridge is installed once for the lifetime of one dedicated preview
 * WebView. Only the currently attached export session can receive calls, and
 * that session still validates its one-shot token and sequence.
 */
internal class AgentPreviewDownloadBridge {
    @Volatile
    private var session: AgentPreviewBlobExportSession? = null

    @Synchronized
    fun attach(next: AgentPreviewBlobExportSession): Boolean {
        if (session != null) return false
        session = next
        return true
    }

    @Synchronized
    fun detach(expected: AgentPreviewBlobExportSession) {
        if (session === expected) session = null
    }

    @Synchronized
    fun clear() {
        session = null
    }

    @JavascriptInterface
    fun chunk(receivedToken: String?, sequence: Int, encoded: String?): Boolean =
        session?.acceptChunk(receivedToken, sequence, encoded) ?: false

    @JavascriptInterface
    fun complete(receivedToken: String?) {
        session?.completeStream(receivedToken)
    }

    @JavascriptInterface
    fun fail(receivedToken: String?, reason: String?) {
        session?.failStream(receivedToken, reason)
    }
}

/** Keeps the bridge associated with its WebView without extending WebView lifetime. */
internal object AgentPreviewDownloadBridgeRegistry {
    private val lock = Any()
    private val bridges = WeakHashMap<WebView, AgentPreviewDownloadBridge>()

    fun register(webView: WebView, bridge: AgentPreviewDownloadBridge) {
        synchronized(lock) {
            bridges[webView] = bridge
        }
    }

    fun remove(webView: WebView): AgentPreviewDownloadBridge? = synchronized(lock) {
        bridges.remove(webView)
    }
}

private data class AgentPreviewDownloadOrigin(
    val scheme: String,
    val host: String,
    val port: Int
)

internal fun classifyAgentPreviewDownload(
    downloadUrl: String,
    configuredPreviewUrl: String
): AgentPreviewDownloadKind {
    if (downloadUrl.length > AGENT_PREVIEW_DOWNLOAD_MAX_URL_CHARS ||
        configuredPreviewUrl.length > AGENT_PREVIEW_DOWNLOAD_MAX_URL_CHARS
    ) return AgentPreviewDownloadKind.UNSUPPORTED
    val configuredOrigin = parseAgentPreviewDownloadOrigin(configuredPreviewUrl) ?: return AgentPreviewDownloadKind.UNSUPPORTED
    val uri = runCatching { Uri.parse(downloadUrl) }.getOrNull() ?: return AgentPreviewDownloadKind.UNSUPPORTED
    return when (uri.scheme?.lowercase(Locale.US)) {
        "blob" -> {
            val nestedUrl = uri.schemeSpecificPart.takeIf { it.isNotBlank() }
                ?: return AgentPreviewDownloadKind.UNSUPPORTED
            val nested = runCatching { Uri.parse(nestedUrl) }.getOrNull()
                ?: return AgentPreviewDownloadKind.UNSUPPORTED
            if (samePreviewDownloadOrigin(nested, configuredOrigin) && nested.path.orEmpty().isNotBlank()) {
                AgentPreviewDownloadKind.BLOB
            } else {
                AgentPreviewDownloadKind.UNSUPPORTED
            }
        }
        "http", "https" -> if (samePreviewDownloadOrigin(uri, configuredOrigin)) {
            AgentPreviewDownloadKind.SAME_ORIGIN_HTTP
        } else {
            AgentPreviewDownloadKind.UNSUPPORTED
        }
        else -> AgentPreviewDownloadKind.UNSUPPORTED
    }
}

internal fun isAgentPreviewDownloadSizeAllowed(contentLength: Long): Boolean =
    contentLength < 0L || contentLength <= AGENT_PREVIEW_MAX_DOWNLOAD_BYTES

internal fun sanitizeAgentPreviewDownloadFilename(
    contentDisposition: String?,
    mimeType: String?
): String {
    val headerName = contentDisposition?.take(512)
        ?.let(::filenameFromContentDisposition)
        ?.let(::safePreviewDownloadBasename)
        ?.takeIf { it.isNotBlank() }
    val extension = previewDownloadExtension(mimeType)
    val baseName = headerName ?: "preview_download"
    return if (baseName.contains('.')) {
        baseName.take(AGENT_PREVIEW_DOWNLOAD_MAX_FILENAME_CHARS)
    } else {
        "$baseName.$extension".take(AGENT_PREVIEW_DOWNLOAD_MAX_FILENAME_CHARS)
    }
}

internal fun normalizedAgentPreviewDownloadMimeType(mimeType: String?): String {
    val normalized = mimeType
        ?.take(AGENT_PREVIEW_DOWNLOAD_MAX_MIME_CHARS + 1)
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf { it.matches(Regex("[a-z0-9!#${'$'}%&'*+.^_`{|}~-]+/[a-z0-9!#${'$'}%&'*+.^_`{|}~-]+")) }
        ?.take(AGENT_PREVIEW_DOWNLOAD_MAX_MIME_CHARS)
    return normalized ?: "application/octet-stream"
}

internal fun buildAgentPreviewBlobExportScript(
    token: String,
    blobUrl: String,
    maxBytes: Long = AGENT_PREVIEW_MAX_DOWNLOAD_BYTES,
    chunkBytes: Int = AGENT_PREVIEW_DOWNLOAD_CHUNK_BYTES
): String {
    require(token.isNotBlank())
    require(maxBytes in 1L..AGENT_PREVIEW_MAX_DOWNLOAD_BYTES)
    require(chunkBytes in 1..AGENT_PREVIEW_DOWNLOAD_CHUNK_BYTES)
    val quotedToken = JSONObject.quote(token)
    val quotedUrl = JSONObject.quote(blobUrl)
    return """
        (function(){
          "use strict";
          var token = $quotedToken;
          var source = $quotedUrl;
          var maxBytes = $maxBytes;
          var chunkBytes = $chunkBytes;
          var bridge = window.$AGENT_PREVIEW_DOWNLOAD_BRIDGE_NAME;
          function fail(reason) {
            try {
              if (bridge) bridge.fail(token, String(reason || "blob_export_failed").slice(0, $AGENT_PREVIEW_DOWNLOAD_MAX_ERROR_CHARS));
            } catch (ignored) {}
          }
          function encode(bytes) {
            var binary = "";
            for (var index = 0; index < bytes.length; index++) {
              binary += String.fromCharCode(bytes[index]);
            }
            return btoa(binary);
          }
          try {
            if (!bridge || source.toLowerCase().indexOf("blob:") !== 0) throw new Error("unsupported_source");
            if (new URL(source).origin !== window.location.origin) throw new Error("origin_mismatch");
            fetch(source).then(function(response) {
              if (!response.ok) throw new Error("blob_fetch_failed");
              if (!response.body || !response.body.getReader) throw new Error("stream_unavailable");
              var reader = response.body.getReader();
                  var sequence = 0;
                  var total = 0;
                  function sendParts(bytes, offset) {
                    if (offset >= bytes.length) return Promise.resolve();
                    var end = Math.min(offset + chunkBytes, bytes.length);
                    var part = bytes.slice(offset, end);
                    total += part.length;
                    if (total > maxBytes) return Promise.reject(new Error("size_limit"));
                    return Promise.resolve(bridge.chunk(token, sequence, encode(part))).then(function(accepted) {
                      if (!accepted) throw new Error("backpressure");
                      sequence += 1;
                      return sendParts(bytes, end);
                    });
                  }
                  function pump() {
                    return reader.read().then(function(result) {
                      if (result.done) {
                    bridge.complete(token);
                    return;
                      }
                      var bytes = result.value;
                      return sendParts(bytes, 0).then(pump);
                    });
                  }
              return pump();
            }).catch(fail);
          } catch (error) {
            fail(error && error.message ? error.message : "blob_export_failed");
          }
        })();
    """.trimIndent()
}

/**
 * Installs a narrow page-local compatibility shim for user-triggered Blob
 * downloads. The caller should inject it at document start when possible;
 * the no-dependency fallback may inject it at the earliest safe page callback.
 * Only same-origin anchors carrying a download attribute are pinned.
 */
internal fun buildAgentPreviewDownloadPinScript(configuredPreviewUrl: String): String {
    require(configuredPreviewUrl.isNotBlank())
    require(configuredPreviewUrl.length <= AGENT_PREVIEW_DOWNLOAD_MAX_URL_CHARS)
    val quotedConfiguredUrl = JSONObject.quote(configuredPreviewUrl)
    return """
        (function(){
          "use strict";
          var stateName = "$AGENT_PREVIEW_DOWNLOAD_PIN_STATE_NAME";
          if (window[stateName]) return;
          var NativeURL = window.URL;
          if (!NativeURL || typeof NativeURL.revokeObjectURL !== "function") return;
          var configuredUrl = $quotedConfiguredUrl;
          var configuredOrigin;
          try {
            configuredOrigin = new NativeURL(configuredUrl).origin;
          } catch (ignored) {
            return;
          }
          if (!configuredOrigin || window.location.origin !== configuredOrigin) return;
          var nativeRevoke = NativeURL.revokeObjectURL;
          var pins = Object.create(null);
          var pinOrder = [];
          var maxPins = $AGENT_PREVIEW_DOWNLOAD_MAX_PINNED_URLS;
          if (typeof HTMLAnchorElement === "undefined" ||
              !HTMLAnchorElement.prototype ||
              typeof HTMLAnchorElement.prototype.click !== "function") return;
          function sameOriginBlob(value) {
            if (typeof value !== "string" || value.toLowerCase().indexOf("blob:") !== 0) return false;
            try {
              return new NativeURL(value).origin === configuredOrigin;
            } catch (ignored) {
              return false;
            }
          }
          function pinAnchor(anchor) {
            if (!anchor || !anchor.hasAttribute || !anchor.hasAttribute("download")) return;
            var value = anchor.href || anchor.getAttribute("href") || "";
            if (!sameOriginBlob(value) || pins[value]) return;
            if (pinOrder.length >= maxPins) return;
            var record = { deferredRevocation: false, timer: null };
            pins[value] = record;
            pinOrder.push(value);
            record.timer = setTimeout(function() { release(value); }, $AGENT_PREVIEW_DOWNLOAD_PIN_EXPIRY_MS);
          }
          function anchorFromTarget(target) {
            while (target && target !== document) {
              if (target instanceof HTMLAnchorElement) return target;
              target = target.parentNode;
            }
            return null;
          }
          function release(value) {
            var key = String(value || "");
            var record = pins[key];
            if (!record) return;
            if (record.timer !== null) clearTimeout(record.timer);
            delete pins[key];
            for (var index = pinOrder.length - 1; index >= 0; index--) {
              if (pinOrder[index] === key) pinOrder.splice(index, 1);
            }
            if (record.deferredRevocation) {
              try { nativeRevoke.call(NativeURL, key); } catch (ignored) {}
            }
          }
          document.addEventListener("click", function(event) {
            pinAnchor(anchorFromTarget(event.target));
          }, true);
          try {
            var nativeClick = HTMLAnchorElement.prototype.click;
            HTMLAnchorElement.prototype.click = function() {
              pinAnchor(this);
              return nativeClick.apply(this, arguments);
            };
            NativeURL.revokeObjectURL = function(value) {
              var key = String(value || "");
              var record = pins[key];
              if (record) {
                record.deferredRevocation = true;
                return;
              }
              return nativeRevoke.call(NativeURL, value);
            };
          } catch (ignored) {
            try { HTMLAnchorElement.prototype.click = nativeClick; } catch (ignoredClick) {}
            try { NativeURL.revokeObjectURL = nativeRevoke; } catch (ignoredRevoke) {}
            return;
          }
          window[stateName] = { release: release };
        })();
    """.trimIndent()
}

/** Releases exactly one URL previously pinned by [buildAgentPreviewDownloadPinScript]. */
internal fun buildAgentPreviewDownloadReleaseScript(blobUrl: String): String {
    require(blobUrl.isNotBlank())
    require(blobUrl.length <= AGENT_PREVIEW_DOWNLOAD_MAX_URL_CHARS)
    val quotedUrl = JSONObject.quote(blobUrl)
    return """
        (function(){
          "use strict";
          var state = window.$AGENT_PREVIEW_DOWNLOAD_PIN_STATE_NAME;
          if (state && typeof state.release === "function") state.release($quotedUrl);
        })();
    """.trimIndent()
}

/**
 * Streams one user-selected preview Blob into one SAF destination. The bridge
 * only accepts a one-shot token and bounded base64 chunks; it has no file or
 * command access. Calls from JavaScript are acknowledged only while the
 * bounded queue has capacity, so the page cannot enqueue the entire Blob.
 */
internal class AgentPreviewBlobExportSession(
    private val webView: WebView,
    private val downloadBridge: AgentPreviewDownloadBridge,
    private val contentResolver: ContentResolver,
    private val destination: Uri,
    private val blobUrl: String,
    private val scope: CoroutineScope,
    private val onResult: (Result<Unit>) -> Unit
) {
    private val token = UUID.randomUUID().toString()
    private val chunks = Channel<ByteArray>(AGENT_PREVIEW_DOWNLOAD_QUEUE_CAPACITY)
    private val cancelled = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val notified = AtomicBoolean(true)
    private val failure = AtomicReference<Throwable?>(null)
    private var writerJob: Job? = null
    private var expectedSequence = 0
    private var acceptedBytes = 0L
    @Volatile
    private var streamCompleted = false

    fun start() {
        check(writerJob == null) { "Preview Blob export already started." }
        check(downloadBridge.attach(this)) { "Another preview Blob export is already active." }
        writerJob = scope.launch(Dispatchers.IO) {
            try {
                val output = contentResolver.openOutputStream(destination)
                    ?: throw IllegalStateException("Unable to open the selected preview destination.")
                output.use { stream ->
                    kotlinx.coroutines.withTimeout(AGENT_PREVIEW_DOWNLOAD_TIMEOUT_MS) {
                        for (chunk in chunks) {
                            stream.write(chunk)
                        }
                        stream.flush()
                    }
                }
                failure.get()?.let { throw it }
                if (!streamCompleted) {
                    throw IllegalStateException("Preview Blob export ended before completion.")
                }
                finish(Result.success(Unit))
            } catch (error: CancellationException) {
                if (!cancelled.get()) finish(Result.failure(error))
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                finish(Result.failure(failure.get() ?: error))
            }
        }
        runCatching {
            webView.evaluateJavascript(
                buildAgentPreviewBlobExportScript(token, blobUrl),
                null
            )
        }.onFailure { error ->
            failStream(token, error.message ?: "script_failed")
        }
    }

    fun cancel(notifyResult: Boolean = false) {
        if (!cancelled.compareAndSet(false, true)) return
        notified.set(notifyResult)
        failure.compareAndSet(null, CancellationException("Preview download cancelled."))
        chunks.close()
        writerJob?.cancel()
        downloadBridge.detach(this)
        finish(Result.failure(failure.get()!!))
    }

    @Synchronized
    internal fun acceptChunk(receivedToken: String?, sequence: Int, encoded: String?): Boolean {
        if (cancelled.get() || finished.get() || streamCompleted || receivedToken != token || sequence != expectedSequence) {
            return false
        }
        // Android invokes JavaScript-interface methods off the UI thread. Fail
        // closed if a WebView implementation ever calls this method on main;
        // never block the UI while the SAF provider drains the queue.
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        val payload = encoded ?: return false
        if (payload.length > AGENT_PREVIEW_DOWNLOAD_MAX_BASE64_CHARS) return false
        val decoded = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull() ?: return false
        if (decoded.isEmpty() || decoded.size > AGENT_PREVIEW_DOWNLOAD_CHUNK_BYTES) return false
        if (acceptedBytes + decoded.size > AGENT_PREVIEW_MAX_DOWNLOAD_BYTES) return false
        val accepted = runCatching {
            runBlocking {
                withTimeout(AGENT_PREVIEW_DOWNLOAD_CHUNK_ACK_TIMEOUT_MS) {
                    chunks.send(decoded)
                    true
                }
            }
        }.getOrDefault(false)
        if (!accepted) return false
        acceptedBytes += decoded.size
        expectedSequence += 1
        return true
    }

    @Synchronized
    internal fun completeStream(receivedToken: String?) {
        if (receivedToken != token || cancelled.get() || finished.get() || streamCompleted) return
        streamCompleted = true
        chunks.close()
    }

    @Synchronized
    internal fun failStream(receivedToken: String?, reason: String?) {
        if (receivedToken != token || cancelled.get() || finished.get() || streamCompleted) return
        failure.compareAndSet(
            null,
            IllegalStateException(
                "Preview Blob export failed: ${reason.orEmpty().take(AGENT_PREVIEW_DOWNLOAD_MAX_ERROR_CHARS)}"
            )
        )
        chunks.close(failure.get())
    }

    private fun finish(result: Result<Unit>) {
        if (!finished.compareAndSet(false, true)) return
        downloadBridge.detach(this)
        chunks.close()
        scope.launch(Dispatchers.Main.immediate) {
            runCatching {
                webView.evaluateJavascript(
                    buildAgentPreviewDownloadReleaseScript(blobUrl),
                    null
                )
            }
            if (notified.get()) onResult(result)
        }
    }
}

private fun parseAgentPreviewDownloadOrigin(url: String): AgentPreviewDownloadOrigin? = runCatching {
    val uri = Uri.parse(url)
    val scheme = uri.scheme?.lowercase(Locale.US) ?: return@runCatching null
    if (scheme != "http" && scheme != "https") return@runCatching null
    val authority = uri.encodedAuthority ?: return@runCatching null
    if (authority.contains('@') || Uri.decode(authority).contains('@')) return@runCatching null
    val host = uri.host?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
        ?: return@runCatching null
    val port = when {
        uri.port > 0 -> uri.port
        scheme == "https" -> 443
        else -> 80
    }
    AgentPreviewDownloadOrigin(scheme, host, port)
}.getOrNull()

private fun samePreviewDownloadOrigin(
    uri: Uri,
    expected: AgentPreviewDownloadOrigin
): Boolean {
    val actual = parseAgentPreviewDownloadOrigin(uri.toString()) ?: return false
    return actual == expected
}

private fun filenameFromContentDisposition(value: String): String? {
    val extended = Regex(
        "filename\\*\\s*=\\s*(?:UTF-8''|\")?([^;\"]+)",
        RegexOption.IGNORE_CASE
    ).find(value)?.groupValues?.getOrNull(1)
    val basic = Regex(
        "filename\\s*=\\s*\"?([^;\"]+)",
        RegexOption.IGNORE_CASE
    ).find(value)?.groupValues?.getOrNull(1)
    return (extended ?: basic)?.trim()?.takeIf { it.isNotBlank() }
}

private fun safePreviewDownloadBasename(value: String): String =
    value
        .replace('\\', '/')
        .substringAfterLast('/')
        .filter { it >= ' ' && it != '\u007f' }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.', '_', ' ')
        .take(AGENT_PREVIEW_DOWNLOAD_MAX_FILENAME_CHARS)
        .takeIf { it != "." && it != ".." }
        .orEmpty()

private fun previewDownloadExtension(mimeType: String?): String = when (
    normalizedAgentPreviewDownloadMimeType(mimeType)
) {
    "text/csv" -> "csv"
    "text/plain" -> "txt"
    "application/json" -> "json"
    "text/html" -> "html"
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    else -> "bin"
}
