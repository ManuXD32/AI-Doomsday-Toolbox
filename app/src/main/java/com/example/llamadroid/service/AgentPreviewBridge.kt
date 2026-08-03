package com.example.llamadroid.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

data class AgentPreviewObservation(
    val url: String,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val progress: Int,
    val title: String?,
    val screenshotPath: String?,
    val screenshotBytes: Long
) {
    fun toJson(): String = JSONObject().apply {
        put("url", url)
        put("viewport_width", viewportWidth)
        put("viewport_height", viewportHeight)
        put("load_progress", progress)
        put("title", title.orEmpty())
        put("screenshot_path", screenshotPath.orEmpty())
        put("screenshot_bytes", screenshotBytes)
        put("visual_context", "Screenshot captured for the vision-capable visual tester model. Full pixels are loaded only as an attachment.")
    }.toString(2)
}

object AgentPreviewBridge {
    private val lock = Any()
    private var webViewRef: WeakReference<WebView>? = null
    private var activeConversationId: Long? = null
    private var activeUrl: String? = null

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

    suspend fun observe(context: Context, conversationId: Long?): Result<AgentPreviewObservation> = runCatching {
        withContext(Dispatchers.Main) {
            val webView = synchronized(lock) { webViewRef?.get() }
                ?: throw IllegalStateException("No active local WebUI preview is open.")
            val registeredConversationId = synchronized(lock) { activeConversationId }
            if (conversationId != null && registeredConversationId != null && conversationId != registeredConversationId) {
                throw IllegalStateException("The active preview belongs to a different conversation.")
            }
            val width = webView.width.coerceAtLeast(1)
            val height = webView.height.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            webView.draw(Canvas(bitmap))
            val dir = File(context.cacheDir, "agent_preview")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "preview_${System.currentTimeMillis()}.png")
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 92, out)
            }
            bitmap.recycle()
            AgentPreviewObservation(
                url = webView.url ?: synchronized(lock) { activeUrl }.orEmpty(),
                viewportWidth = width,
                viewportHeight = height,
                progress = webView.progress,
                title = webView.title,
                screenshotPath = file.absolutePath,
                screenshotBytes = file.length()
            )
        }
    }

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
                "type" -> {
                    val safeText = JSONObject.quote(text.orEmpty())
                    webView.evaluateJavascript(
                        """
                        (function(){
                          const el = document.activeElement;
                          if (!el) return false;
                          const value = $safeText;
                          if ('value' in el) {
                            el.value = (el.value || '') + value;
                            el.dispatchEvent(new Event('input', {bubbles:true}));
                            el.dispatchEvent(new Event('change', {bubbles:true}));
                            return true;
                          }
                          if (el.isContentEditable) {
                            el.textContent = (el.textContent || '') + value;
                            el.dispatchEvent(new Event('input', {bubbles:true}));
                            return true;
                          }
                          return false;
                        })();
                        """.trimIndent(),
                        null
                    )
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
    }
}
