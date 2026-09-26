package com.example.llamadroid.harness

import com.example.llamadroid.service.DuckDuckGoSearchResult
import com.example.llamadroid.service.APP_WEB_SEARCH_USER_AGENT
import com.example.llamadroid.service.MAX_APP_WEB_SEARCH_QUERY_CHARS
import com.example.llamadroid.service.MAX_APP_WEB_SEARCH_RESULTS
import com.example.llamadroid.service.parseDuckDuckGoSearchResults
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal fun requireHarnessToolEnabled(enabled: Boolean, errorCode: String) {
    check(enabled) { errorCode }
}

/** Shared gate/response used by the authenticated broker and the app-owned web search adapter. */
internal suspend fun executeHarnessAppWebSearch(
    enabled: Boolean,
    query: String,
    maxResults: Int,
    search: suspend (String, Int) -> List<DuckDuckGoSearchResult>,
): JSONObject {
    requireHarnessToolEnabled(enabled, "WEB_SEARCH_DISABLED")
    val normalizedQuery = query.trim()
    require(normalizedQuery.isNotBlank()) { "APP_WEB_SEARCH_QUERY_REQUIRED" }
    require(normalizedQuery.length <= MAX_APP_WEB_SEARCH_QUERY_CHARS) { "APP_WEB_SEARCH_QUERY_TOO_LONG" }
    val boundedLimit = maxResults.coerceIn(1, MAX_APP_WEB_SEARCH_RESULTS)
    val results = search(normalizedQuery, boundedLimit).take(boundedLimit)
    return JSONObject()
        .put("source", "DuckDuckGo")
        .put("query", normalizedQuery)
        .put("resultCount", results.size)
        .put("results", JSONArray().apply {
            results.forEach { result ->
                put(JSONObject()
                    .put("title", result.title)
                    .put("url", result.url)
                    .put("snippet", result.snippet)
                    .put("citation", result.citation()))
            }
        })
}

/** Search returns bounded snippets and citations only. Result pages are never fetched. */
internal class HarnessAppWebSearch(
    private val endpoint: HttpUrl = "https://html.duckduckgo.com/html/".toHttpUrl(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    fun cancelOwnedRequests() = client.dispatcher.cancelAll()

    suspend fun search(query: String, maxResults: Int): List<DuckDuckGoSearchResult> {
        val url = endpoint.newBuilder().addQueryParameter("q", query).build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", APP_WEB_SEARCH_USER_AGENT)
            .header("Accept", "text/html")
            .build()
        val response = client.newCall(request).await()
        response.use {
            // DDG currently responds with an anomaly challenge on some networks. Do not
            // present that page as a successful search with zero results.
            check(it.code != 202) { "APP_WEB_SEARCH_UNAVAILABLE" }
            check(it.isSuccessful) { "APP_WEB_SEARCH_HTTP_${it.code}" }
            val source = requireNotNull(it.body).source()
            val byteLimit = 300_000L
            source.request(byteLimit + 1)
            check(source.buffer.size <= byteLimit) { "APP_WEB_SEARCH_RESPONSE_TOO_LARGE" }
            val html = source.buffer.readUtf8(minOf(source.buffer.size, byteLimit))
            check(!isDuckDuckGoChallenge(html)) { "APP_WEB_SEARCH_UNAVAILABLE" }
            return parseDuckDuckGoSearchResults(html, maxResults)
        }
    }

    private fun isDuckDuckGoChallenge(html: String): Boolean {
        val normalized = html.lowercase()
        return "anomaly.js" in normalized ||
            "anomaly-form" in normalized ||
            "challenge-form" in normalized
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, responseToClose, _ -> responseToClose.close() }
        }
    })
}
