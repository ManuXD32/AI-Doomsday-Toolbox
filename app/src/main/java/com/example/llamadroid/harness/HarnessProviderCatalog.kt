package com.example.llamadroid.harness

import com.example.llamadroid.data.HttpEndpointUrlSupport
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** Canonical provider choices shared by provider CRUD and model discovery. */
enum class HarnessProviderPreset(
    val id: String,
    val displayName: String,
    val backend: String
) {
    ADT_MANAGED("adt-managed", "ADT managed", "adt"),
    REMOTE_LLAMA_CPP("remote-llama-cpp", "Remote llama.cpp", "llama-server"),
    LLAMA_SWAP("llama-swap", "llama-swap", "llama-swap"),
    GENERIC_OPENAI("generic-openai", "Generic OpenAI-compatible", "generic")
}

/**
 * Endpoint values are persisted as a root URL. Callers that need the OpenAI
 * route use [v1BaseUrl], so entering either `host` or `host/v1` is equivalent.
 */
data class HarnessProviderEndpoint(
    val rootUrl: String,
    val v1BaseUrl: String,
    val modelsUrl: String,
    val chatCompletionsUrl: String
)

internal fun normalizeHarnessProviderEndpoint(input: String?): HarnessProviderEndpoint? {
    val normalized = HttpEndpointUrlSupport.normalizeBaseUrl(input) ?: return null
    val parsed = runCatching { URI(normalized) }.getOrNull() ?: return null
    // A persisted URL must never be a second credential store. Secure keys are
    // resolved by the credential reference separately from the endpoint.
    if (!parsed.rawUserInfo.isNullOrBlank()) return null
    val path = parsed.rawPath.orEmpty().trimEnd('/').let { value ->
        if (value.substringAfterLast('/', value).equals("v1", ignoreCase = true)) {
            value.substringBeforeLast('/').ifBlank { "" }
        } else value
    }
    val root = buildString {
        append(parsed.scheme.lowercase())
        append("://")
        append(parsed.rawAuthority)
        append(path)
    }.trimEnd('/')
    val v1 = HttpEndpointUrlSupport.appendPath(root, "/v1") ?: return null
    val models = HttpEndpointUrlSupport.appendPath(v1, "/models") ?: return null
    val chat = HttpEndpointUrlSupport.appendPath(v1, "/chat/completions") ?: return null
    return HarnessProviderEndpoint(root, v1, models, chat)
}

/** Keep exact wire IDs while making path-backed IDs readable in the UI. */
internal fun harnessFriendlyModelLabel(
    wireId: String,
    advertisedName: String? = null
): String {
    val normalized = (advertisedName?.trim()?.takeIf { it.isNotEmpty() } ?: wireId.trim())
        .replace('\\', '/')
    return normalized.substringAfterLast('/').takeIf { it.isNotBlank() } ?: normalized
}

data class HarnessDiscoveredModel(
    val wireId: String,
    val displayName: String,
    val description: String? = null,
    val contextLength: Int? = null,
    /** Provider-advertised completion/output ceiling, when available. */
    val maxTokens: Int? = null,
    val inputModalities: List<String> = listOf("text"),
    val running: Boolean = false
)

internal data class HarnessLlamaCapabilities(
    val model: String? = null,
    val contextLength: Int? = null,
    val maxTokens: Int? = null,
    val slotCount: Int? = null,
    val supportsPromptCache: Boolean = true,
    val propsAvailable: Boolean = false,
    val slotsAvailable: Boolean = false,
    val slots: List<HarnessLlamaSlot> = emptyList()
)

internal data class HarnessLlamaSlot(
    val id: Int? = null,
    val state: String? = null,
    val model: String? = null
)

internal fun parseHarnessOpenAiModels(
    payload: JSONObject,
    limit: Int = 256
): List<HarnessDiscoveredModel> {
    val rows = payload.optJSONArray("data") ?: payload.optJSONArray("models") ?: return emptyList()
    val seen = mutableSetOf<String>()
    val models = rows.objects().asSequence().mapNotNull { row ->
        val id = firstText(row, "id", "model", "name") ?: return@mapNotNull null
        if (!seen.add(id)) return@mapNotNull null
        HarnessDiscoveredModel(
            wireId = id,
            displayName = harnessFriendlyModelLabel(id, firstText(row, "display_name", "name")),
            description = firstText(row, "description"),
            contextLength = positiveInt(row, "context_length", "context_window", "n_ctx"),
            maxTokens = positiveInt(row, "max_tokens", "max_output_tokens", "max_completion_tokens", "n_predict"),
            inputModalities = row.optJSONArray("input_modalities")?.stringsOrNull()
                ?.filter { it in SUPPORTED_MODALITIES }
                ?.ifEmpty { listOf("text") }
                ?: listOf("text")
        )
    }.take(limit.coerceAtLeast(0)).toList()
    val duplicateLabels = models.groupingBy { it.displayName.lowercase(Locale.ROOT) }.eachCount()
    return models.map { model ->
        if (duplicateLabels[model.displayName.lowercase(Locale.ROOT)] == 1) model
        else {
            val suffix = MessageDigest.getInstance("SHA-256")
                .digest(model.wireId.toByteArray(Charsets.UTF_8))
                .take(4).joinToString("") { "%02x".format(it) }
            model.copy(displayName = "${model.displayName} ($suffix)")
        }
    }
}

/** Parses metadata-only llama.cpp `/props` and `/slots` responses. */
internal fun parseHarnessLlamaCapabilities(
    props: JSONObject?,
    slotsPayload: JSONObject?,
    limit: Int = 256
): HarnessLlamaCapabilities {
    val slots = (slotsPayload?.optJSONArray("slots") ?: slotsPayload?.optJSONArray("data"))
        ?.objects()
        ?.take(limit.coerceAtLeast(0))
        ?.map { row ->
            HarnessLlamaSlot(
                id = nonNegativeInt(row, "id", "slot_id"),
                state = firstText(row, "state", "status"),
                model = firstText(row, "model", "model_alias")
            )
        }
        .orEmpty()
    return HarnessLlamaCapabilities(
        model = props?.let { firstText(it, "model_alias", "model", "model_path") },
        contextLength = props?.let { positiveInt(it, "n_ctx", "context_length", "n_ctx_train") },
        maxTokens = props?.let {
            positiveInt(it, "n_predict", "max_tokens", "max_output_tokens", "max_completion_tokens")
        },
        slotCount = props?.let { positiveInt(it, "n_slots") } ?: slots.size.takeIf { it > 0 },
        supportsPromptCache = props?.optBoolean("cache_prompt", true) ?: true,
        propsAvailable = props != null,
        slotsAvailable = slotsPayload != null,
        slots = slots
    )
}

/**
 * Parses llama-swap's authoritative `/v1/models` catalog plus optional
 * `/running` metadata. The catalog includes unloaded configured models;
 * `/upstream` is a direct proxy route and is never enumerated here.
 */
internal fun parseHarnessLlamaSwapModels(
    modelsPayload: JSONObject,
    runningPayload: JSONObject?,
    limit: Int = 256
): List<HarnessDiscoveredModel> {
    val runningIds = runningPayload?.let(::modelRows).orEmpty().mapTo(mutableSetOf()) { it.wireId }
    return modelRows(modelsPayload).asSequence()
        .map { model -> model.copy(running = model.wireId in runningIds) }
        .take(limit.coerceAtLeast(0))
        .toList()
}

/** Metadata-only activity payload used by the native bridge and adapter. */
internal fun harnessPromptProgressActivity(
    phase: String,
    total: Int? = null,
    cached: Int? = null,
    processed: Int? = null,
    timeMs: Long? = null,
    known: Boolean = total != null && total > 0,
    requestId: String? = null,
    sessionId: String? = null,
    attemptId: String? = null,
): JSONObject = JSONObject().put("type", "prompt_progress").put("phase", phase)
    .put("known", known)
    .apply {
        requestId?.takeIf { it.isNotBlank() }?.let { put("requestId", it) }
        sessionId?.takeIf { it.isNotBlank() }?.let { put("sessionId", it) }
        attemptId?.takeIf { it.isNotBlank() }?.let { put("attemptId", it) }
        total?.takeIf { it >= 0 }?.let { put("total", it) }
        cached?.takeIf { it >= 0 }?.let { put("cached", it) }
        processed?.takeIf { it >= 0 }?.let { put("processed", it) }
        timeMs?.takeIf { it >= 0L }?.let { put("time_ms", it) }
    }

private fun modelRows(payload: JSONObject): List<HarnessDiscoveredModel> {
    val array = payload.optJSONArray("data") ?: payload.optJSONArray("models")
        ?: payload.optJSONArray("running")
    if (array != null) {
        return array.objects().mapNotNull { row ->
            val id = firstText(row, "id", "model", "name") ?: return@mapNotNull null
            HarnessDiscoveredModel(
                wireId = id,
                displayName = harnessFriendlyModelLabel(id, firstText(row, "display_name", "name")),
                description = firstText(row, "description"),
                contextLength = positiveInt(row, "context_length", "context_window", "n_ctx"),
                maxTokens = positiveInt(row, "max_tokens", "max_output_tokens", "max_completion_tokens", "n_predict")
            )
        }
    }
    // Some llama-swap versions expose model metadata as an object keyed by model.
    return payload.keys().asSequence().mapNotNull { key ->
        // `/upstream` is a direct proxy route in llama-swap, never a registry.
        // Do not interpret a similarly named response field as a catalog.
        if (key in setOf("object", "status", "success", "data", "models", "running", "upstream")) {
            return@mapNotNull null
        }
        val value = payload.opt(key)
        val id = when (value) {
            is JSONObject -> firstText(value, "id", "model", "name") ?: key
            is String -> value
            else -> key
        }.trim()
        id.takeIf { it.isNotEmpty() }?.let { HarnessDiscoveredModel(it, harnessFriendlyModelLabel(it)) }
    }.toList()
}

private fun firstText(value: JSONObject, vararg keys: String): String? = keys.asSequence()
    .mapNotNull { key -> value.optString(key).trim().takeIf { it.isNotEmpty() } }
    .firstOrNull()

private fun positiveInt(value: JSONObject, vararg keys: String): Int? = keys.asSequence()
    .mapNotNull { key -> value.optInt(key, -1).takeIf { it > 0 } }
    .firstOrNull()

private fun nonNegativeInt(value: JSONObject, vararg keys: String): Int? = keys.asSequence()
    .mapNotNull { key -> value.optInt(key, -1).takeIf { it >= 0 } }
    .firstOrNull()

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)

private fun JSONArray.stringsOrNull(): List<String> = (0 until length()).mapNotNull { index ->
    optString(index).trim().takeIf { it.isNotEmpty() }
}

private const val MODALITY_TEXT = "text"
private val SUPPORTED_MODALITIES = setOf(MODALITY_TEXT, "image", "audio")
