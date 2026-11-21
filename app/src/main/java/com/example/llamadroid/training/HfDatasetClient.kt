package com.example.llamadroid.training

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One search hit from `/api/datasets?search=`. */
data class HfDatasetSummary(
    val id: String,
    val downloads: Long,
    val likes: Long,
    val gated: Boolean,
    val licence: String?
)

/** Metadata from `/api/datasets/{id}`. [licence] is null when neither cardData nor tags name one. */
data class HfDatasetInfo(
    val id: String,
    val licence: String?,
    val gated: Boolean,
    val tags: List<String>,
    val sha: String?
)

/** A file in the repository tree. */
data class HfFileEntry(val path: String, val sizeBytes: Long)

/** A converted parquet file from the datasets-server. */
data class HfParquetFile(
    val config: String,
    val split: String,
    val filename: String,
    val url: String,
    val sizeBytes: Long
)

/** Pure JSON parsers; every function returns an empty/null result on malformed input. */
object HfDatasetParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun parse(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    /** `gated` is false, "auto" or "manual" on the Hub. */
    private fun JsonObject.isGated(): Boolean {
        val p = this["gated"] as? JsonPrimitive ?: return false
        return p.booleanOrNull ?: (p.contentOrNull?.let { it.isNotBlank() && it != "false" } ?: false)
    }

    private fun licenceFrom(obj: JsonObject): String? {
        val card = obj["cardData"] as? JsonObject
        when (val lic = card?.get("license")) {
            is JsonPrimitive -> lic.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            is JsonArray -> lic.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { return it.joinToString(", ") }
            else -> Unit
        }
        val tags = (obj["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return tags.filter { it.startsWith("license:") }.map { it.removePrefix("license:") }
            .takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    fun parseSearch(text: String): List<HfDatasetSummary> {
        val array = parse(text) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.str("id") ?: return@mapNotNull null
            HfDatasetSummary(id, obj.long("downloads") ?: 0L, obj.long("likes") ?: 0L, obj.isGated(), licenceFrom(obj))
        }
    }

    fun parseInfo(text: String): HfDatasetInfo? {
        val obj = parse(text) as? JsonObject ?: return null
        val id = obj.str("id") ?: return null
        val tags = (obj["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return HfDatasetInfo(id, licenceFrom(obj), obj.isGated(), tags, obj.str("sha"))
    }

    /** Keeps only `type == "file"` entries. */
    fun parseTree(text: String): List<HfFileEntry> {
        val array = parse(text) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            if (obj.str("type") != "file") return@mapNotNull null
            val path = obj.str("path") ?: return@mapNotNull null
            HfFileEntry(path, obj.long("size") ?: 0L)
        }
    }

    fun parseParquet(text: String): List<HfParquetFile> {
        val files = ((parse(text) as? JsonObject)?.get("parquet_files") as? JsonArray) ?: return emptyList()
        return files.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val url = obj.str("url") ?: return@mapNotNull null
            HfParquetFile(
                config = obj.str("config") ?: "default",
                split = obj.str("split") ?: "train",
                filename = obj.str("filename") ?: url.substringAfterLast('/'),
                url = url,
                sizeBytes = obj.long("size") ?: 0L
            )
        }
    }
}

/**
 * Read-only Hugging Face dataset browsing. [tokenProvider] supplies the app's existing HF token
 * (see [HfToken]); it is sent as a Bearer header when non-blank.
 */
class HfDatasetClient(
    private val tokenProvider: () -> String = { "" },
    private val baseUrl: String = "https://huggingface.co",
    private val serverUrl: String = "https://datasets-server.huggingface.co",
    client: OkHttpClient? = null
) {
    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun get(url: String): String {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        val token = tokenProvider().trim()
        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private suspend fun fetch(url: String): Result<String> = withContext(Dispatchers.IO) { runCatching { get(url) } }

    suspend fun search(query: String, limit: Int = 30): Result<List<HfDatasetSummary>> {
        val url = "$baseUrl/api/datasets".toHttpUrl().newBuilder()
            .addQueryParameter("search", query.trim())
            .addQueryParameter("limit", limit.coerceIn(1, 100).toString())
            .addQueryParameter("full", "false")
            .build().toString()
        return fetch(url).map(HfDatasetParser::parseSearch)
    }

    suspend fun info(repo: String): Result<HfDatasetInfo> =
        fetch("$baseUrl/api/datasets/${repoPath(repo)}").mapCatching {
            HfDatasetParser.parseInfo(it) ?: throw IOException("Unreadable dataset info")
        }

    suspend fun tree(repo: String, revision: String = "main"): Result<List<HfFileEntry>> =
        fetch("$baseUrl/api/datasets/${repoPath(repo)}/tree/${segment(revision)}?recursive=1")
            .map(HfDatasetParser::parseTree)

    suspend fun parquetFiles(repo: String): Result<List<HfParquetFile>> {
        val url = "$serverUrl/parquet".toHttpUrl().newBuilder().addQueryParameter("dataset", repo).build().toString()
        return fetch(url).map(HfDatasetParser::parseParquet)
    }

    companion object {
        /** Direct download URL for one file of a dataset repository. */
        fun resolveUrl(repo: String, revision: String, path: String, baseUrl: String = "https://huggingface.co"): String =
            "$baseUrl/datasets/${repoPath(repo)}/resolve/${segment(revision)}/" +
                path.split('/').joinToString("/") { segment(it) }

        private fun repoPath(repo: String) = repo.split('/').joinToString("/") { segment(it) }

        private fun segment(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

/** Reads the app's existing Hugging Face token (shared with the model library). */
object HfToken {
    private const val PREFS_NAME = "litert_model_repository"
    private const val KEY_HF_TOKEN = "hugging_face_token"

    fun read(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HF_TOKEN, "").orEmpty().trim()
}
