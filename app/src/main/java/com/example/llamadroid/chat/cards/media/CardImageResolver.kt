package com.example.llamadroid.chat.cards.media

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Resolves a photo search query to an https image URL (never returns a model-supplied URL). */
interface CardImageResolver {
    suspend fun resolve(query: String): String?
}

/**
 * Wikipedia page image first, then Openverse. Successful lookups are cached in memory and in a small JSON file
 * ([cacheFile], at most [MAX_ENTRIES] entries); failures and misses are not cached.
 */
class HttpCardImageResolver(
    private val client: OkHttpClient,
    private val userAgent: String,
    private val cacheFile: File? = null,
    private val wikipediaBase: String = WIKIPEDIA_BASE,
    private val openverseBase: String = OPENVERSE_BASE,
) : CardImageResolver {
    private val memory = ConcurrentHashMap<String, String>()
    private val diskLock = Any()
    @Volatile private var diskLoaded = false

    override suspend fun resolve(query: String): String? {
        val key = query.trim().lowercase().take(120)
        if (key.isEmpty()) return null
        loadDisk()
        memory[key]?.let { return it }
        val url = withContext(Dispatchers.IO) {
            fetch(wikipediaBase, "generator=search&gsrsearch=%s&gsrlimit=1&prop=pageimages&piprop=thumbnail&pithumbsize=640&format=json&action=query", key, ::parseWikipedia)
                ?: fetch(openverseBase, "q=%s&page_size=1", key, ::parseOpenverse)
        } ?: return null
        memory[key] = url
        saveDisk()
        return url
    }

    private fun fetch(base: String, template: String, query: String, parse: (String) -> String?): String? = runCatching {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = (base + (if (base.contains('?')) "&" else "?") + template.replace("%s", encoded)).toHttpUrlOrNull()
            ?: return null
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        val body = client.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
        body?.let(parse)
    }.getOrNull()

    private fun loadDisk() {
        if (diskLoaded) return
        synchronized(diskLock) {
            if (diskLoaded) return
            runCatching {
                val f = cacheFile?.takeIf { it.isFile } ?: return@runCatching
                val o = JSONObject(f.readText())
                for (k in o.keys()) {
                    val v = o.optString(k)
                    if (isHttps(v)) memory.putIfAbsent(k, v)
                }
            }
            diskLoaded = true
        }
    }

    private fun saveDisk() {
        val f = cacheFile ?: return
        synchronized(diskLock) {
            runCatching {
                while (memory.size > MAX_ENTRIES) memory.remove(memory.keys.first())
                val o = JSONObject()
                for ((k, v) in memory) o.put(k, v)
                f.parentFile?.mkdirs()
                f.writeText(o.toString())
            }
        }
    }

    companion object {
        const val WIKIPEDIA_BASE = "https://en.wikipedia.org/w/api.php"
        const val OPENVERSE_BASE = "https://api.openverse.org/v1/images/"
        const val MAX_ENTRIES = 500

        fun isHttps(url: String?): Boolean = url != null && url.startsWith("https://", ignoreCase = true)

        /** First page thumbnail of a Wikipedia `generator=search` answer; null when absent or not https. */
        fun parseWikipedia(json: String): String? = runCatching {
            val pages = JSONObject(json).getJSONObject("query").getJSONObject("pages")
            var best: String? = null
            var bestIndex = Int.MAX_VALUE
            for (k in pages.keys()) {
                val p = pages.getJSONObject(k)
                val src = p.optJSONObject("thumbnail")?.optString("source")?.takeIf { isHttps(it) }
                val idx = p.optInt("index", 0)
                if (src != null && idx < bestIndex) { best = src; bestIndex = idx }
            }
            best
        }.getOrNull()

        /** `results[0].thumbnail` or `.url` of an Openverse answer; null when absent or not https. */
        fun parseOpenverse(json: String): String? = runCatching {
            val first = JSONObject(json).getJSONArray("results").getJSONObject(0)
            listOf("thumbnail", "url").map { first.optString(it) }.firstOrNull { isHttps(it) }
        }.getOrNull()
    }
}
