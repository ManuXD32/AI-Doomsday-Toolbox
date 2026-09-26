package com.example.llamadroid.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

private const val MAX_DOM_BODY_CHARS = 1_500
private const val MAX_DOM_CONTROLS = 16
private const val MAX_DOM_CONTROL_CHARS = 80
private const val MAX_DOM_TEXT_NODES = 200
private const val MAX_DOM_VISITED_NODES = 1_000
private const val MAX_DOM_SERIALIZED_CHARS = 3_000
private const val DOM_EVALUATION_TIMEOUT_MS = 5_000L
private const val MAX_PREVIEW_URL_CHARS = 128
private const val MAX_PREVIEW_TITLE_CHARS = 80
private const val MAX_SCREENSHOT_PATH_CHARS = 128
private const val MAX_SCREENSHOT_WIDTH = 1_440
private const val MAX_SCREENSHOT_HEIGHT = 2_560

data class AgentPreviewControl(
    val type: String,
    val label: String,
    val value: String,
    val x: Int,
    val y: Int,
    val enabled: Boolean
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.take(MAX_DOM_CONTROL_CHARS))
        put("label", label.take(MAX_DOM_CONTROL_CHARS))
        put("value", if (type.equals("password", ignoreCase = true)) "" else value.take(MAX_DOM_CONTROL_CHARS))
        put("x", x)
        put("y", y)
        put("enabled", enabled)
    }
}

data class AgentPreviewObservation(
    val url: String,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val progress: Int,
    val title: String?,
    val screenshotPath: String?,
    val screenshotBytes: Long,
    val bodyText: String = "",
    val controls: List<AgentPreviewControl> = emptyList(),
    val domTruncated: Boolean = false
) {
    fun toJson(): String {
        val boundedUrl = url.take(MAX_PREVIEW_URL_CHARS)
        val boundedTitle = title.orEmpty().take(MAX_PREVIEW_TITLE_CHARS)
        val boundedScreenshotPath = screenshotPath.orEmpty().take(MAX_SCREENSHOT_PATH_CHARS)
        val metadataTruncated = boundedUrl.length < url.length ||
            boundedTitle.length < title.orEmpty().length ||
            boundedScreenshotPath.length < screenshotPath.orEmpty().length
        val sourceControls = controls.take(MAX_DOM_CONTROLS)
        var controlTextLimit = MAX_DOM_CONTROL_CHARS
        var controlsForOutput = sourceControls

        fun rendered(
            body: String,
            controlsToRender: List<AgentPreviewControl>,
            textLimit: Int,
            truncated: Boolean
        ): String = JSONObject().apply {
            put("url", boundedUrl)
            put("viewport_width", viewportWidth)
            put("viewport_height", viewportHeight)
            put("load_progress", progress)
            put("title", boundedTitle)
            put("screenshot_path", boundedScreenshotPath)
            put("screenshot_bytes", screenshotBytes)
            // Controls come first so bounded result projection retains coordinates.
            put("controls", JSONArray().apply {
                controlsToRender.forEach { control ->
                    put(
                        control.copy(
                            type = control.type.take(textLimit),
                            label = control.label.take(textLimit),
                            value = if (control.type.equals("password", ignoreCase = true)) {
                                ""
                            } else {
                                control.value.take(textLimit)
                            }
                        ).toJson()
                    )
                }
            })
            put("body_text", body)
            put("dom_truncated", truncated || metadataTruncated)
            if (boundedScreenshotPath.isBlank()) {
                put("visual_context", "No screenshot captured; bounded DOM text and controls only.")
            } else {
                put("visual_context", "Screenshot captured for the vision-capable visual tester model. Full pixels are loaded only as an attachment.")
            }
        }.toString()

        fun fitBody(): Pair<Int, String> {
            val highLimit = minOf(MAX_DOM_BODY_CHARS, bodyText.length)
            var low = 0
            var high = highLimit
            var bestLength = 0
            var bestJson = rendered(
                body = "",
                controlsToRender = controlsForOutput,
                textLimit = controlTextLimit,
                truncated = domTruncated || bodyText.isNotEmpty() ||
                    controlsForOutput.size < controls.size ||
                    controlTextLimit < MAX_DOM_CONTROL_CHARS
            )
            while (low <= high) {
                val candidateLength = (low + high) / 2
                val candidate = rendered(
                    body = bodyText.take(candidateLength),
                    controlsToRender = controlsForOutput,
                    textLimit = controlTextLimit,
                    truncated = domTruncated || candidateLength < bodyText.length ||
                        controlsForOutput.size < controls.size ||
                        controlTextLimit < MAX_DOM_CONTROL_CHARS
                )
                if (candidate.length <= MAX_DOM_SERIALIZED_CHARS) {
                    bestLength = candidateLength
                    bestJson = candidate
                    low = candidateLength + 1
                } else {
                    high = candidateLength - 1
                }
            }
            return bestLength to bestJson
        }

        while (true) {
            val (_, json) = fitBody()
            if (json.length <= MAX_DOM_SERIALIZED_CHARS) return json
            if (controlTextLimit > 16) {
                controlTextLimit = (controlTextLimit / 2).coerceAtLeast(16)
            } else if (controlsForOutput.isNotEmpty()) {
                controlsForOutput = controlsForOutput.dropLast(1)
            } else {
                // Bounded metadata above leaves enough room for an empty DOM payload.
                return rendered(
                    body = "",
                    controlsToRender = emptyList(),
                    textLimit = 16,
                    truncated = true
                )
            }
        }
    }
}

object AgentPreviewBridge {
    private val lock = Any()
    private var webViewRef: WeakReference<WebView>? = null
    private var activeConversationId: Long? = null
    private var activeUrl: String? = null

    private data class DomSnapshot(
        val bodyText: String,
        val controls: List<AgentPreviewControl>,
        val truncated: Boolean
    )

    private data class PreviewCapture(
        val url: String,
        val viewportWidth: Int,
        val viewportHeight: Int,
        val progress: Int,
        val title: String?,
        val rawDomResult: String
    )

    fun register(webView: WebView, conversationId: Long?, url: String?) {
        synchronized(lock) {
            webViewRef = WeakReference(webView)
            activeConversationId = conversationId
            activeUrl = url
        }
    }

    fun unregister(webView: WebView) {
        synchronized(lock) {
            if (webViewRef?.get() === webView) {
                webViewRef = null
                activeConversationId = null
                activeUrl = null
            }
        }
    }

    fun hasActivePreview(conversationId: Long?): Boolean {
        val webView = synchronized(lock) { webViewRef?.get() }
        return webView != null && (conversationId == null || synchronized(lock) { activeConversationId } == conversationId)
    }

    suspend fun observe(
        context: Context,
        conversationId: Long?,
        captureScreenshot: Boolean = true
    ): Result<AgentPreviewObservation> = runCatching {
        var bitmap: Bitmap? = null
        try {
            val capture = withContext(Dispatchers.Main) {
                val webView = activeWebView(conversationId)
                val width = webView.width.coerceAtLeast(1)
                val height = webView.height.coerceAtLeast(1)
                if (captureScreenshot) {
                    bitmap = capturePreviewBitmap(webView, width, height)
                }
                val rawDomResult = evaluateJavascriptBounded(
                    webView,
                    buildDomObservationScript(width, height)
                )
                PreviewCapture(
                    url = webView.url ?: synchronized(lock) { activeUrl }.orEmpty(),
                    viewportWidth = width,
                    viewportHeight = height,
                    progress = webView.progress,
                    title = webView.title,
                    rawDomResult = rawDomResult
                )
            }
            val dom = withContext(Dispatchers.Default) {
                parseDomSnapshot(
                    rawResult = capture.rawDomResult,
                    viewportWidth = capture.viewportWidth,
                    viewportHeight = capture.viewportHeight
                )
            }
            val screenshot = bitmap?.let { persistScreenshot(context, it) }
            AgentPreviewObservation(
                url = capture.url,
                viewportWidth = capture.viewportWidth,
                viewportHeight = capture.viewportHeight,
                progress = capture.progress,
                title = capture.title,
                screenshotPath = screenshot?.first,
                screenshotBytes = screenshot?.second ?: 0L,
                bodyText = dom.bodyText,
                controls = dom.controls,
                domTruncated = dom.truncated
            )
        } finally {
            bitmap?.let { if (!it.isRecycled) it.recycle() }
        }
    }.onFailure { if (it is CancellationException) throw it }

    suspend fun interact(
        conversationId: Long?,
        action: String,
        x: Float? = null,
        y: Float? = null,
        text: String? = null,
        key: String? = null,
        scrollDx: Int? = null,
        scrollDy: Int? = null,
        waitMs: Long? = null
    ): Result<String> = runCatching {
        val normalized = action.trim().lowercase()
        if (normalized == "wait") {
            delay((waitMs ?: 500L).coerceIn(0L, 5_000L))
            return@runCatching JSONObject().put("status", "ok").put("action", "wait").toString(2)
        }
        withContext(Dispatchers.Main) {
            val webView = synchronized(lock) { webViewRef?.get() }
                ?: throw IllegalStateException("No active local WebUI preview is open.")
            val registeredConversationId = synchronized(lock) { activeConversationId }
            if (conversationId != null && registeredConversationId != null && conversationId != registeredConversationId) {
                throw IllegalStateException("The active preview belongs to a different conversation.")
            }
            when (normalized) {
                "tap", "click" -> {
                    val px = x ?: throw IllegalArgumentException("tap requires x")
                    val py = y ?: throw IllegalArgumentException("tap requires y")
                    val downAt = System.currentTimeMillis()
                    webView.dispatchTouchEvent(MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, px, py, 0))
                    webView.dispatchTouchEvent(MotionEvent.obtain(downAt, downAt + 80, MotionEvent.ACTION_UP, px, py, 0))
                }
                "type", "fill" -> {
                    val safeText = JSONObject.quote(text.orEmpty())
                    val result = evaluateJavascriptBounded(
                        webView,
                        buildEditableScript(
                            valueExpression = safeText,
                            replacing = normalized == "fill"
                        )
                    )
                    if (!javascriptBoolean(result)) {
                        throw IllegalStateException("$normalized requires a focused editable element.")
                    }
                }
                "key" -> {
                    val code = when (key?.trim()?.lowercase()) {
                        "enter" -> KeyEvent.KEYCODE_ENTER
                        "tab" -> KeyEvent.KEYCODE_TAB
                        "backspace" -> KeyEvent.KEYCODE_DEL
                        "escape", "esc" -> KeyEvent.KEYCODE_ESCAPE
                        else -> throw IllegalArgumentException("Unsupported key: ${key.orEmpty()}")
                    }
                    webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
                    webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
                }
                "scroll" -> {
                    val dx = scrollDx ?: 0
                    val dy = scrollDy ?: 0
                    webView.scrollBy(dx, dy)
                    webView.evaluateJavascript("window.scrollBy(${dx}, ${dy});", null)
                }
                "reload" -> webView.reload()
                else -> throw IllegalArgumentException("Unsupported preview action: $action")
            }
            JSONObject()
                .put("status", "ok")
                .put("action", normalized)
                .put("url", webView.url.orEmpty())
                .put("load_progress", webView.progress)
                .toString(2)
        }
    }.onFailure { if (it is CancellationException) throw it }

    private fun activeWebView(conversationId: Long?): WebView {
        val webView = synchronized(lock) { webViewRef?.get() }
            ?: throw IllegalStateException("No active local WebUI preview is open.")
        val registeredConversationId = synchronized(lock) { activeConversationId }
        if (conversationId != null && registeredConversationId != null && conversationId != registeredConversationId) {
            throw IllegalStateException("The active preview belongs to a different conversation.")
        }
        return webView
    }

    private fun capturePreviewBitmap(webView: WebView, width: Int, height: Int): Bitmap {
        val scale = minOf(
            1f,
            MAX_SCREENSHOT_WIDTH.toFloat() / width,
            MAX_SCREENSHOT_HEIGHT.toFloat() / height
        )
        val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                if (scale != 1f) scale(scale, scale)
                webView.draw(this)
            }
        }
    }

    private suspend fun persistScreenshot(context: Context, bitmap: Bitmap): Pair<String, Long> =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "agent_preview")
            if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory) {
                throw IllegalStateException("Unable to create preview cache directory.")
            }
            val file = File(dir, "preview_${System.currentTimeMillis()}_${System.nanoTime()}.png")
            file.outputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 92, out)) {
                    throw IllegalStateException("Unable to encode preview screenshot.")
                }
            }
            file.absolutePath to file.length()
        }

    private suspend fun evaluateJavascriptBounded(webView: WebView, script: String): String =
        try {
            withTimeout(DOM_EVALUATION_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    try {
                        webView.evaluateJavascript(script) { value ->
                            if (continuation.isActive) continuation.resume(value.orEmpty())
                        }
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException("Preview JavaScript did not respond within five seconds.", error)
        }

    private fun parseDomSnapshot(
        rawResult: String,
        viewportWidth: Int,
        viewportHeight: Int
    ): DomSnapshot {
        val decoded = JSONTokener(rawResult).nextValue()
        val json = when (decoded) {
            is String -> JSONObject(decoded)
            is JSONObject -> decoded
            else -> throw IllegalStateException("Preview DOM observation returned no JSON object.")
        }
        val bodyText = compactText(json.optString("body_text", ""), MAX_DOM_BODY_CHARS)
        val controlsJson = json.optJSONArray("controls")
        val controls = buildList {
            if (controlsJson != null) {
                for (index in 0 until minOf(controlsJson.length(), MAX_DOM_CONTROLS)) {
                    val item = controlsJson.optJSONObject(index) ?: continue
                    val type = compactText(item.optString("type", "control"), MAX_DOM_CONTROL_CHARS)
                    val value = if (type.equals("password", ignoreCase = true)) {
                        ""
                    } else {
                        compactText(item.optString("value", ""), MAX_DOM_CONTROL_CHARS)
                    }
                    add(
                        AgentPreviewControl(
                            type = type,
                            label = compactText(item.optString("label", ""), MAX_DOM_CONTROL_CHARS),
                            value = value,
                            x = item.optInt("x", 0).coerceIn(0, (viewportWidth - 1).coerceAtLeast(0)),
                            y = item.optInt("y", 0).coerceIn(0, (viewportHeight - 1).coerceAtLeast(0)),
                            enabled = item.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }
        return DomSnapshot(
            bodyText = bodyText,
            controls = controls,
            truncated = json.optBoolean("body_text_truncated", false) ||
                json.optBoolean("controls_truncated", false) ||
                bodyText.length >= MAX_DOM_BODY_CHARS ||
                controls.size >= MAX_DOM_CONTROLS
        )
    }

    private fun compactText(value: String, maxChars: Int): String =
        value.replace(Regex("\\s+"), " ").trim().take(maxChars)

    private fun javascriptBoolean(rawResult: String): Boolean =
        rawResult.trim().trim('"') == "true"

    private fun buildEditableScript(valueExpression: String, replacing: Boolean): String {
        val valueAssignment = if (replacing) "value" else "(el.value || '') + value"
        val contentAssignment = if (replacing) "value" else "(el.textContent || '') + value"
        return """
            (function(){
              var el = document.activeElement;
              if (!el || el.disabled || el.readOnly) return false;
              var value = $valueExpression;
              if ('value' in el) {
                el.value = $valueAssignment;
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return true;
              }
              if (el.isContentEditable) {
                el.textContent = $contentAssignment;
                el.dispatchEvent(new Event('input', {bubbles:true}));
                return true;
              }
              return false;
            })();
        """.trimIndent()
    }

    private fun buildDomObservationScript(physicalWidth: Int, physicalHeight: Int): String = """
        (function(){
          var MAX_TEXT = $MAX_DOM_BODY_CHARS;
          var MAX_CONTROLS = $MAX_DOM_CONTROLS;
          var MAX_LABEL = $MAX_DOM_CONTROL_CHARS;
          var MAX_TEXT_NODES = $MAX_DOM_TEXT_NODES;
          var MAX_VISITED_NODES = $MAX_DOM_VISITED_NODES;
          var physicalWidth = $physicalWidth;
          var physicalHeight = $physicalHeight;
          var root = document.body || document.documentElement;
          function compact(value) {
            return String(value == null ? '' : value).slice(0, MAX_TEXT).replace(/\s+/g, ' ').trim();
          }
          function visible(element) {
            if (!element) return false;
            var style = window.getComputedStyle(element);
            var rect = element.getBoundingClientRect();
            return rect.width > 0 && rect.height > 0 && style.display !== 'none' &&
              style.visibility !== 'hidden' && style.opacity !== '0';
          }
          function textSnapshot() {
            if (!root || !document.createTreeWalker) return {text:'', truncated:false};
            var walker = document.createTreeWalker(root, 4, null);
            var parts = [];
            var chars = 0;
            var visited = 0;
            var textNodes = 0;
            var node;
            while (visited < MAX_VISITED_NODES && textNodes < MAX_TEXT_NODES &&
                (node = walker.nextNode())) {
              visited++;
              var parent = node.parentElement;
              if (!parent || /^(SCRIPT|STYLE|NOSCRIPT)$/i.test(parent.tagName) || !visible(parent)) continue;
              var part = compact(node.nodeValue);
              if (!part) continue;
              var remaining = MAX_TEXT - chars;
              if (remaining <= 0) break;
              if (part.length > remaining) part = part.slice(0, remaining);
              parts.push(part);
              chars += part.length;
              textNodes++;
            }
            return {
              text: parts.join(' ').slice(0, MAX_TEXT),
              truncated: chars >= MAX_TEXT || visited >= MAX_VISITED_NODES || textNodes >= MAX_TEXT_NODES
            };
          }
          function boundedNodeText(element, limit) {
            if (!element) return '';
            var walker = document.createTreeWalker(element, 4, null);
            var result = '', node, visited = 0;
            while (visited++ < 50 && result.length < limit && (node = walker.nextNode())) {
              result += compact(node.nodeValue).slice(0, limit - result.length) + ' ';
            }
            return result.slice(0, limit).trim();
          }
          function labelFor(element) {
            var label = compact(element.getAttribute('aria-label') || element.getAttribute('title'));
            if (!label && element.labels) {
              for (var i = 0; i < Math.min(element.labels.length, 4); i++) {
                label = boundedNodeText(element.labels[i], MAX_LABEL);
                if (label) break;
              }
            }
            if (!label) label = boundedNodeText(element, MAX_LABEL) || compact(element.placeholder);
            return label.slice(0, MAX_LABEL);
          }
          function isControl(element) {
            var tag = (element.tagName || '').toLowerCase();
            return tag === 'button' || tag === 'input' || tag === 'textarea' || tag === 'select' ||
              tag === 'a' || element.getAttribute('role') === 'button' || element.isContentEditable;
          }
          function controlSnapshot() {
            var output = [];
            if (!root || !document.createTreeWalker) return {items:output, truncated:false};
            var walker = document.createTreeWalker(root, 1, null);
            var visited = 0;
            var element;
            var cssWidth = (document.documentElement && document.documentElement.clientWidth) || window.innerWidth || 1;
            var cssHeight = (document.documentElement && document.documentElement.clientHeight) || window.innerHeight || 1;
            var scaleX = physicalWidth / Math.max(cssWidth, 1);
            var scaleY = physicalHeight / Math.max(cssHeight, 1);
            while (visited < MAX_VISITED_NODES && output.length < MAX_CONTROLS &&
                (element = walker.nextNode())) {
              visited++;
              if (!isControl(element) || !visible(element)) continue;
              var rect = element.getBoundingClientRect();
              if (rect.right <= 0 || rect.bottom <= 0 || rect.left >= cssWidth || rect.top >= cssHeight) continue;
              var centerX = (Math.max(0, rect.left) + Math.min(cssWidth, rect.right)) / 2;
              var centerY = (Math.max(0, rect.top) + Math.min(cssHeight, rect.bottom)) / 2;
              var tag = (element.tagName || 'control').toLowerCase();
              var type = compact(element.getAttribute('type') || tag).toLowerCase().slice(0, MAX_LABEL);
              var autocomplete = compact(element.getAttribute('autocomplete')).toLowerCase();
              var sensitive = type === 'password' || type === 'file' || autocomplete.indexOf('password') >= 0;
              var value = '';
              if (!sensitive) {
                if ('value' in element) value = compact(element.value);
                else if (element.isContentEditable) value = boundedNodeText(element, MAX_LABEL);
              }
              output.push({
                type: type || 'control',
                label: labelFor(element),
                value: value.slice(0, MAX_LABEL),
                x: Math.max(0, Math.min(physicalWidth - 1, Math.round(centerX * scaleX))),
                y: Math.max(0, Math.min(physicalHeight - 1, Math.round(centerY * scaleY))),
                enabled: !element.disabled && element.getAttribute('aria-disabled') !== 'true'
              });
            }
            return {
              items: output,
              truncated: output.length >= MAX_CONTROLS || visited >= MAX_VISITED_NODES
            };
          }
          var textResult = textSnapshot();
          var controlResult = controlSnapshot();
          return JSON.stringify({
            body_text: textResult.text,
            body_text_truncated: textResult.truncated,
            controls: controlResult.items,
            controls_truncated: controlResult.truncated
          });
        })();
    """.trimIndent()
}
