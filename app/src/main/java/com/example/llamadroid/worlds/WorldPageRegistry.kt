package com.example.llamadroid.worlds

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

internal const val WORLDS_HOST = "worlds.adt.local"

/**
 * Holds the generated page for one WebView and serves it at an unguessable same-origin URL
 * (`https://worlds.adt.local/__page/<token>/index.html`) so the document, module scripts and the
 * import map all share one real https origin (the WebViewAssetLoader approach).
 */
class WorldPageHolder(private val random: SecureRandom = SecureRandom()) {
    @Volatile private var html: String = ""
    @Volatile private var token: String = newToken()

    /** Stores [pageHtml] under a fresh token and returns the URL to load. */
    @Synchronized
    fun publish(pageHtml: String): String {
        html = pageHtml
        token = newToken()
        return currentUrl()
    }

    fun currentUrl(): String = pageUrl(token)

    /** The page HTML when [url] is exactly the current page URL, else null. */
    fun htmlFor(url: String): String? = if (url == currentUrl()) html else null

    private fun newToken(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun pageUrl(token: String): String = "https://$WORLDS_HOST/__page/$token/index.html"
    }
}

/** Last-N rings plus counters shown by the diagnostics panel. Thread-safe (interceptor thread writes). */
class WorldDiagnostics(private val max: Int = MAX_ENTRIES) {
    private val lock = Any()
    private val console = ArrayDeque<String>()
    private val problems = ArrayDeque<String>()
    private val served = AtomicInteger(0)
    @Volatile var onChange: (() -> Unit)? = null

    fun recordServed() { served.incrementAndGet(); onChange?.invoke() }
    fun recordProblem(kind: String, url: String) = push(problems, "$kind ${url.take(300)}")

    fun recordConsole(level: String, message: String, source: String?, line: Int) {
        val where = if (source.isNullOrBlank()) "" else " (${source.substringAfterLast('/')}:$line)"
        push(console, "[$level] ${message.take(MAX_MESSAGE_CHARS)}$where")
    }

    val servedCount: Int get() = served.get()
    fun problemList(): List<String> = synchronized(lock) { problems.toList() }
    fun consoleList(): List<String> = synchronized(lock) { console.toList() }
    fun consoleErrorCount(): Int = consoleList().count { it.startsWith("[ERROR]") }

    private fun push(ring: ArrayDeque<String>, entry: String) {
        synchronized(lock) {
            ring.addLast(entry)
            while (ring.size > max) ring.removeFirst()
        }
        onChange?.invoke()
    }

    companion object {
        const val MAX_ENTRIES = 40
        const val MAX_MESSAGE_CHARS = 300
    }
}
