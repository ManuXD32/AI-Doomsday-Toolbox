package com.example.llamadroid.service

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Bounded, metadata-only control probes shared by preparation and the idle watchdog. */
internal object ManagedLlamaServerHttp {
    private val client = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS).build()

    fun healthy(baseUrl: String): Boolean = runCatching {
        client.newCall(Request.Builder().url("$baseUrl/health").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    fun read(baseUrl: String, path: String): String? = runCatching {
        client.newCall(Request.Builder().url(baseUrl.trimEnd('/') + path).build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val source = response.body?.source() ?: return@use null
            val buffer = okio.Buffer()
            while (!source.exhausted()) {
                val remaining = MAX_METADATA_BYTES - buffer.size
                if (remaining <= 0) return@use null
                if (source.read(buffer, minOf(8192L, remaining)) < 0) break
            }
            buffer.readUtf8()
        }
    }.getOrNull()

    fun contextTokens(baseUrl: String, configured: Int?): Int? {
        val props = read(baseUrl, "/props")?.let { runCatching { JSONObject(it) }.getOrNull() }
        val slots = read(baseUrl, "/slots")?.let(::slotRows).orEmpty()
        return managedLlamaContextTokens(props, slots, configured)
    }

    fun idleObservation(baseUrl: String): LlamaServerIdleObservation? {
        val raw = read(baseUrl, "/slots") ?: return null
        val slots = slotRows(raw) ?: return null
        if (slots.isEmpty()) return null
        if (slots.any { it.opt("is_processing") !is Boolean && it.optString("state").lowercase() !in
                setOf("idle", "processing", "generating", "busy", "prompt") }) return null
        val busy = slots.any { row ->
            row.optBoolean("is_processing", false) ||
                row.optString("state").lowercase() in setOf("processing", "generating", "busy", "prompt")
        }
        val counters = read(baseUrl, "/metrics")?.lineSequence()?.filter { line ->
            line.startsWith("llamacpp:prompt_tokens_total ") ||
                line.startsWith("llamacpp:tokens_predicted_total ") ||
                line.startsWith("llamacpp:predicted_tokens_total ")
        }?.take(8)?.joinToString("|").orEmpty()
        val slotFingerprint = slots.joinToString("|") { slot ->
            listOf("id", "id_task", "n_past", "n_decoded").joinToString(":") {
                slot.optLong(it, -1).toString()
            }
        }
        return LlamaServerIdleObservation(busy, "$counters|$slotFingerprint")
    }

    private fun slotRows(raw: String): List<JSONObject>? = runCatching {
        val list = if (raw.trimStart().startsWith("[")) JSONArray(raw)
        else JSONObject(raw).optJSONArray("slots") ?: return null
        // A truncated or partly malformed list cannot prove that every other client is idle.
        if (list.length() !in 1..256) return null
        (0 until list.length()).map { list.optJSONObject(it) ?: return null }
    }.getOrNull()

    private const val MAX_METADATA_BYTES = 1_048_576L
}

/** Runtime per-slot values beat training context and the pre-start conservative profile limit. */
internal fun managedLlamaContextTokens(props: JSONObject?, slots: List<JSONObject>, configured: Int?): Int? {
    fun JSONObject?.positive(vararg names: String): Int? = names.asSequence()
        .mapNotNull { name -> this?.optLong(name)?.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt() }
        .firstOrNull()
    return slots.mapNotNull { it.positive("n_ctx", "n_ctx_slot", "context_length") }.minOrNull()
        ?: props?.optJSONObject("default_generation_settings").positive("n_ctx", "context_length")
        ?: props.positive("n_ctx_per_seq", "n_ctx_slot", "context_length")
        ?: props.positive("n_ctx")?.let { it / (props.positive("n_slots") ?: 1) }?.takeIf { it > 0 }
        ?: configured?.takeIf { it > 0 }
}
