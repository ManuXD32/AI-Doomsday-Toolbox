package com.example.llamadroid.service.search

import android.content.Context
import androidx.core.content.edit
import com.example.llamadroid.service.DuckDuckGoSearchResult
import com.example.llamadroid.service.sanitizeWebSearchResultUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/** One search hit. Shares the DuckDuckGo result shape so every consumer keeps its citation format. */
internal typealias WebSearchResult = DuckDuckGoSearchResult

internal interface WebSearchBackend {
    suspend fun search(query: String, limit: Int): List<WebSearchResult>
}

internal enum class WebSearchBackendKind(val prefValue: String) {
    DUCKDUCKGO("duckduckgo"),
    SEARXNG("searxng");

    companion object {
        fun fromPref(value: String?): WebSearchBackendKind =
            entries.firstOrNull { it.prefValue == value } ?: DUCKDUCKGO
    }
}

/** Global web-search backend choice, kept in its own preferences file (SettingsRepository is over 2k lines). */
internal class WebSearchBackendSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var backend: WebSearchBackendKind
        get() = WebSearchBackendKind.fromPref(prefs.getString(KEY_BACKEND, null))
        set(value) = prefs.edit { putString(KEY_BACKEND, value.prefValue) }

    /** Raw user-entered SearXNG address; blank means "use the in-app instance when installed". */
    var searxngUrl: String
        get() = prefs.getString(KEY_URL, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_URL, value.trim()) }

    companion object {
        const val PREFS = "web_search_backend_settings"
        private const val KEY_BACKEND = "backend"
        private const val KEY_URL = "searxng_url"
    }
}

/** Parses a user-entered address into an http(s) base URL without trailing slash, or null. */
internal fun normalizeSearxngBaseUrl(raw: String): String? {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val url = trimmed.toHttpUrlOrNull() ?: return null
    if (url.query != null || url.fragment != null) return null
    return trimmed
}

/** Outcome of one SearXNG query: hits plus the diagnostics shown by the settings "Test" button. */
internal data class SearxngReport(
    val results: List<WebSearchResult>,
    /** Engines that did not answer, as (name, reason) pairs from `unresponsive_engines`. */
    val unresponsiveEngines: List<Pair<String, String>>,
    val elapsedMs: Long,
    val httpCode: Int,
)

internal class SearxngBackend(
    baseUrl: String,
    local: Boolean = false,
    private val client: OkHttpClient = searxngClient(local),
) : WebSearchBackend {
    private val searchEndpoint: HttpUrl = requireNotNull("${baseUrl.trimEnd('/')}/search".toHttpUrlOrNull()) {
        "SEARXNG_URL_INVALID"
    }

    override suspend fun search(query: String, limit: Int): List<WebSearchResult> =
        searchWithReport(query, limit).results

    /** Runs on [Dispatchers.IO]: the response body is read after the call resumes, which must never be the Main thread. */
    suspend fun searchWithReport(query: String, limit: Int): SearxngReport = withContext(Dispatchers.IO) {
        val url = searchEndpoint.newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "json")
            .build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val started = System.nanoTime()
        val (code, body) = client.newCall(request).awaitResponse().use { response ->
            check(response.isSuccessful) { "SEARXNG_HTTP_${response.code}" }
            val source = requireNotNull(response.body).source()
            source.request(MAX_BYTES + 1)
            check(source.buffer.size <= MAX_BYTES) { "SEARXNG_RESPONSE_TOO_LARGE" }
            response.code to source.buffer.readUtf8(minOf(source.buffer.size, MAX_BYTES))
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        SearxngReport(parseSearxngResults(body, limit), parseSearxngUnresponsive(body), elapsedMs, code)
    }

    private companion object {
        const val MAX_BYTES = 1_000_000L
    }
}

internal const val SEARXNG_LOCAL_TIMEOUT_SECONDS = 25L
internal const val SEARXNG_REMOTE_TIMEOUT_SECONDS = 10L
internal const val SEARXNG_CONNECT_TIMEOUT_SECONDS = 5L

/** Local in-app instance gets 25 s (it fans out to several engines); a user URL gets 10 s; connect is 5 s. */
internal fun searxngClient(local: Boolean): OkHttpClient {
    val total = if (local) SEARXNG_LOCAL_TIMEOUT_SECONDS else SEARXNG_REMOTE_TIMEOUT_SECONDS
    return OkHttpClient.Builder()
        .connectTimeout(SEARXNG_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(total, TimeUnit.SECONDS)
        .callTimeout(total, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

/** Parses `unresponsive_engines` (a list of `[name, reason]`); malformed entries are skipped. */
internal fun parseSearxngUnresponsive(json: String): List<Pair<String, String>> {
    val array = runCatching { JSONObject(json).optJSONArray("unresponsive_engines") }.getOrNull() ?: return emptyList()
    val out = ArrayList<Pair<String, String>>()
    for (i in 0 until array.length()) {
        val item = array.optJSONArray(i) ?: continue
        val name = item.optString(0).trim()
        if (name.isEmpty()) continue
        out += name to item.optString(1).trim().take(80)
    }
    return out
}

internal fun parseSearxngResults(json: String, limit: Int): List<WebSearchResult> {
    val array = JSONObject(json).optJSONArray("results") ?: return emptyList()
    val out = ArrayList<WebSearchResult>()
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val url = sanitizeWebSearchResultUrl(item.optString("url")) ?: continue
        val title = item.optString("title").replace(Regex("\\s+"), " ").trim().take(240)
        if (title.isBlank()) continue
        val snippet = item.optString("content").replace(Regex("\\s+"), " ").trim().take(500)
        if (out.none { it.url == url }) out += WebSearchResult(title, url, snippet)
        if (out.size >= limit) break
    }
    return out
}

/** Tries [primary]; falls back to [fallback] when it throws (not cancellation) or returns nothing. */
internal class FallbackWebSearchBackend(
    private val primary: WebSearchBackend,
    private val fallback: WebSearchBackend,
) : WebSearchBackend {
    /** IO dispatcher: the DuckDuckGo fallbacks read their response body after the call resumes (never on Main). */
    override suspend fun search(query: String, limit: Int): List<WebSearchResult> = withContext(Dispatchers.IO) {
        val first = try {
            primary.search(query, limit)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
        first.ifEmpty { fallback.search(query, limit) }
    }
}

internal object WebSearchBackends {
    /** W8 fills this with the in-app SearXNG base URL while that instance is running (or startable). */
    @Volatile
    var localInstanceBaseUrl: () -> String? = { null }

    /** Base URL of the SearXNG backend to try first, or null when DuckDuckGo is the only backend. */
    fun searxngBaseUrl(context: Context): String? {
        val settings = WebSearchBackendSettings(context)
        if (settings.backend != WebSearchBackendKind.SEARXNG) return null
        return normalizeSearxngBaseUrl(settings.searxngUrl)
            ?: localInstanceBaseUrl()?.let(::normalizeSearxngBaseUrl)
    }

    /** True when no user URL is set, so [searxngBaseUrl] resolves to the in-app instance. */
    fun isLocalInstance(context: Context): Boolean =
        normalizeSearxngBaseUrl(WebSearchBackendSettings(context).searxngUrl) == null

    /** SearXNG (when configured) with [duckDuckGo] as the fallback. */
    fun current(context: Context?, duckDuckGo: WebSearchBackend): WebSearchBackend {
        val base = context?.let(::searxngBaseUrl) ?: return duckDuckGo
        return FallbackWebSearchBackend(SearxngBackend(base, isLocalInstance(context)), duckDuckGo)
    }
}

private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, toClose, _ -> toClose.close() }
        }
    })
}
