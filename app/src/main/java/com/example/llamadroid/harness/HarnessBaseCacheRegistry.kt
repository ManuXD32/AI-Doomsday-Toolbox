package com.example.llamadroid.harness

import android.content.Context
import androidx.core.content.edit
import com.example.llamadroid.harness.runtime.HarnessRuntimeScope
import com.example.llamadroid.service.KvBaseHint
import com.example.llamadroid.service.KvCacheKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Metadata of the last DeepSeek Harness prefix (leading system message(s) + tool definitions)
 * that was sent with a base-cache hint. Never contains the prompt text.
 */
data class HarnessBaseInfo(
    val prefixHash: String,
    val cardId: Long,
    val model: String,
    /** Rough size of the prefix (characters / 4), used only for display. */
    val tokenEstimate: Int,
    val capturedAt: Long,
)

/**
 * Remembers, per runtime and managed llama card, which harness prefix a base cache was requested
 * for. A base restore lets every new thread or retry skip re-processing the harness system
 * prompt and tool definitions (about 10k tokens), because the coordinator loads the saved
 * prefix KV state before the first request of an owner that has no cache of its own.
 *
 * The cache file itself is owned by `NativeChatKvCacheCoordinator` (`KvCacheKey.Base`); this
 * registry only keeps the metadata so "Prepare caches" can show whether a DeepSeek base exists
 * and which prefix to rebuild from.
 */
object HarnessBaseCacheRegistry {
    private const val PREFERENCES = "harness_base_cache_registry"
    private const val SCOPE = "harness"

    /** Latest recorded harness base of [cardId], or null until one harness request has run. */
    fun latest(
        context: Context,
        cardId: Long,
        runtimeId: String = HarnessRuntimeScope.id(context),
    ): HarnessBaseInfo? {
        val raw = prefs(context).getString(prefKey(runtimeId, cardId), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            HarnessBaseInfo(
                prefixHash = json.getString("hash"),
                cardId = cardId,
                model = json.optString("model"),
                tokenEstimate = json.optInt("tokens"),
                capturedAt = json.optLong("capturedAt"),
            )
        }.getOrNull()
    }

    /** Records [info] unless the same prefix hash is already stored (keeps the first capture time). */
    fun record(context: Context, info: HarnessBaseInfo, runtimeId: String = HarnessRuntimeScope.id(context)) {
        val key = prefKey(runtimeId, info.cardId)
        val preferences = prefs(context)
        if (latest(context, info.cardId, runtimeId)?.prefixHash == info.prefixHash) return
        preferences.edit {
            putString(
                key,
                JSONObject().put("hash", info.prefixHash).put("model", info.model)
                    .put("tokens", info.tokenEstimate).put("capturedAt", info.capturedAt).toString(),
            )
        }
    }

    /** Forgets the base metadata of [cardId] (for example after its cache files were cleared). */
    fun clear(context: Context, cardId: Long, runtimeId: String = HarnessRuntimeScope.id(context)) {
        prefs(context).edit { remove(prefKey(runtimeId, cardId)) }
    }

    /** The cache key for a harness base: `base-harness-<sha16>`. */
    fun baseKey(cardId: Long, model: String, prefixHash: String): KvCacheKey.Base =
        KvCacheKey.Base(SCOPE, "$cardId|$model|$prefixHash")

    /** Hint for `KvSlotLease.request`; null when the request has no system prefix to cache. */
    fun hint(cardId: Long, model: String, prefixHash: String?): KvBaseHint? =
        prefixHash?.let { KvBaseHint(baseKey(cardId, model, it)) }

    /**
     * SHA-256 of the leading system/developer messages plus the tools JSON, or null when the
     * request has no leading system message. Keys are serialized in sorted order so the hash is
     * stable across equivalent requests. [messages] is the OpenAI `messages` array.
     */
    fun prefixHash(messages: JSONArray?, tools: JSONArray?): String? {
        val system = leadingSystemText(messages) ?: return null
        val canonicalTools = tools?.let { canonical(it) }.orEmpty()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$system\u0000$canonicalTools".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Characters of the prefix, used for [HarnessBaseInfo.tokenEstimate]. */
    fun prefixLength(messages: JSONArray?, tools: JSONArray?): Int =
        (leadingSystemText(messages)?.length ?: 0) + (tools?.let { canonical(it).length } ?: 0)

    private fun leadingSystemText(messages: JSONArray?): String? {
        if (messages == null) return null
        val parts = ArrayList<String>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: break
            if (message.optString("role") !in setOf("system", "developer")) break
            parts += messageText(message)
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private fun messageText(message: JSONObject): String = when (val value = message.opt("content")) {
        is String -> value
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
            .filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }
        else -> ""
    }

    /** Deterministic JSON text: object keys sorted, no whitespace. */
    internal fun canonical(value: Any?): String = StringBuilder().also { appendCanonical(it, value) }.toString()

    private fun appendCanonical(out: StringBuilder, value: Any?) {
        when (value) {
            is JSONObject -> {
                out.append('{')
                value.keys().asSequence().sorted().forEachIndexed { index, key ->
                    if (index > 0) out.append(',')
                    out.append(JSONObject.quote(key)).append(':')
                    appendCanonical(out, value.opt(key))
                }
                out.append('}')
            }
            is JSONArray -> {
                out.append('[')
                for (index in 0 until value.length()) {
                    if (index > 0) out.append(',')
                    appendCanonical(out, value.opt(index))
                }
                out.append(']')
            }
            null, JSONObject.NULL -> out.append("null")
            is String -> out.append(JSONObject.quote(value))
            else -> out.append(value.toString())
        }
    }

    private fun prefKey(runtimeId: String, cardId: Long) = "$runtimeId|$cardId"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
