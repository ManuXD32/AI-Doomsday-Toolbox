package com.example.llamadroid.harness

import android.content.Context
import androidx.core.content.edit
import com.example.llamadroid.harness.runtime.HarnessRuntimeScope
import com.example.llamadroid.service.AgentTool
import com.example.llamadroid.service.KvBaseHint
import com.example.llamadroid.service.KvCacheKey
import com.example.llamadroid.service.LlamaServerChatService
import com.example.llamadroid.service.LlamaServerRequestOptions
import com.example.llamadroid.service.OllamaService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Metadata of the last DeepSeek Harness prefix (leading system message(s) + tool definitions)
 * that was sent with a base-cache hint. Never contains the prompt text (that lives in [HarnessPrefix]).
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
 * The last NORMALIZED harness prefix (see [HarnessPromptNormalizer]) of one card: the leading
 * system message texts and the tools JSON array text, kept so "Prepare caches" can rebuild the
 * base without a live session. Stored in an app-private file, never in a journal or log.
 */
data class HarnessPrefix(
    val prefixHash: String,
    val model: String,
    val systemTexts: List<String>,
    val toolsJson: String,
    val thinking: Boolean,
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
        runCatching { prefixFile(context, runtimeId, cardId).delete() }
    }

    /**
     * Stores the normalized prefix of [cardId] (one bounded file per runtime and card, replaced
     * when the hash changes) so Prepare can rebuild it later. No-op when the same hash is stored.
     */
    fun savePrefix(
        context: Context,
        runtimeId: String,
        cardId: Long,
        model: String,
        messages: JSONArray?,
        tools: JSONArray?,
        thinking: Boolean,
    ) {
        val hash = prefixHash(messages, tools, thinking) ?: return
        val file = prefixFile(context, runtimeId, cardId)
        if (loadPrefix(context, cardId, runtimeId)?.takeIf { it.model == model }?.prefixHash == hash) return
        val texts = JSONArray().also { array -> leadingSystemTexts(messages).forEach { array.put(it) } }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(
                JSONObject().put("hash", hash).put("model", model).put("system", texts)
                    .put("tools", (tools ?: JSONArray()).toString()).put("thinking", thinking).toString()
            )
            if (!tmp.renameTo(file)) { tmp.copyTo(file, overwrite = true); tmp.delete() }
        }
    }

    /** The last stored normalized prefix of [cardId], or null until one harness request ran. */
    fun loadPrefix(
        context: Context,
        cardId: Long,
        runtimeId: String = HarnessRuntimeScope.id(context),
    ): HarnessPrefix? = runCatching {
        val file = prefixFile(context, runtimeId, cardId)
        if (!file.isFile || file.length() > MAX_PREFIX_FILE_BYTES) return@runCatching null
        val json = JSONObject(file.readText())
        val system = json.getJSONArray("system")
        HarnessPrefix(
            prefixHash = json.getString("hash"),
            model = json.optString("model"),
            systemTexts = (0 until system.length()).map { system.getString(it) },
            toolsJson = json.optString("tools", "[]"),
            thinking = json.optBoolean("thinking", true),
        )
    }.getOrNull()

    /**
     * Prefix-only 1-token request (the stored system messages, the tools and a minimal user turn)
     * with the same thinking setting as `streamLlamaServer`, so the base never contains a
     * session's own messages and its rendered prefix tokens equal a real request's.
     */
    fun prefixSender(baseUrl: String, model: String?, systemTexts: List<String>, tools: List<AgentTool>, thinking: Boolean): suspend (Int) -> Unit =
        { slot ->
            LlamaServerChatService().chatWithToolsStreaming(
                baseUrl = baseUrl,
                messages = systemTexts.map { OllamaService.ChatMessage(role = "system", content = it) } +
                    OllamaService.ChatMessage(role = "user", content = "Hi"),
                tools = tools,
                modelLabel = model,
                thinkingEnabled = thinking,
                maxTokens = 1,
                requestOptions = LlamaServerRequestOptions(slotId = slot, streamReadTimeoutMs = 0),
            ).getOrThrow()
            Unit
        }

    /** OpenAI `tools` array to the app's [AgentTool] list (shared with `HarnessLocalModels`). */
    internal fun agentTools(tools: JSONArray?): List<AgentTool> {
        if (tools == null) return emptyList()
        return (0 until tools.length()).mapNotNull { index ->
            val function = tools.optJSONObject(index)?.optJSONObject("function") ?: return@mapNotNull null
            val name = function.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val schema = function.optJSONObject("parameters") ?: JSONObject()
            val properties = schema.optJSONObject("properties") ?: JSONObject()
            val required = schema.optJSONArray("required")
            AgentTool(
                name = name,
                description = function.optString("description"),
                parameters = properties.keys().asSequence().associateWith { key ->
                    properties.optJSONObject(key)?.optString("description", key) ?: key
                },
                requiredParams = required?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }
                }.orEmpty(),
                schemaJson = schema.toString()
            )
        }
    }

    /** The cache key for a harness base: `base-harness-<sha16>`. */
    fun baseKey(cardId: Long, model: String, prefixHash: String): KvCacheKey.Base =
        KvCacheKey.Base(SCOPE, "$cardId|$model|$prefixHash")

    /**
     * Hint for `KvSlotLease.request`; null when the request has no system prefix to cache. With a
     * [builder] a missing base is rebuilt from the prefix only; without one the legacy capture applies.
     */
    fun hint(cardId: Long, model: String, prefixHash: String?, builder: (suspend (Int) -> Unit)? = null): KvBaseHint? =
        prefixHash?.let { KvBaseHint(baseKey(cardId, model, it), capture = true, builder = builder) }

    /**
     * SHA-256 of the leading system/developer messages plus the tools JSON, or null when the
     * request has no leading system message. Keys are serialized in sorted order so the hash is
     * stable across equivalent requests. [messages] is the OpenAI `messages` array. [thinking]
     * (when known) is part of the hash because it changes the chat-template kwargs.
     */
    fun prefixHash(messages: JSONArray?, tools: JSONArray?, thinking: Boolean? = null): String? {
        val system = leadingSystemText(messages) ?: return null
        val canonicalTools = tools?.let { canonical(it) }.orEmpty()
        val suffix = thinking?.let { "\u0000think=$it" }.orEmpty()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$system\u0000$canonicalTools$suffix".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Characters of the prefix, used for [HarnessBaseInfo.tokenEstimate]. */
    fun prefixLength(messages: JSONArray?, tools: JSONArray?): Int =
        (leadingSystemText(messages)?.length ?: 0) + (tools?.let { canonical(it).length } ?: 0)

    private fun leadingSystemText(messages: JSONArray?): String? =
        leadingSystemTexts(messages).takeIf { it.isNotEmpty() }?.joinToString("\n\n")

    internal fun leadingSystemTextList(messages: JSONArray?): List<String> = leadingSystemTexts(messages)

    private fun leadingSystemTexts(messages: JSONArray?): List<String> {
        if (messages == null) return emptyList()
        val parts = ArrayList<String>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: break
            if (message.optString("role") !in setOf("system", "developer")) break
            parts += messageText(message)
        }
        return parts
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

    private const val MAX_PREFIX_FILE_BYTES = 512 * 1024L

    private fun prefixFile(context: Context, runtimeId: String, cardId: Long): File {
        val name = MessageDigest.getInstance("SHA-256").digest("$runtimeId|$cardId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(24)
        return File(File(context.applicationContext.filesDir, "harness-base-prefix"), "$name.json")
    }

    private fun prefKey(runtimeId: String, cardId: Long) = "$runtimeId|$cardId"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
