package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.normalizeHarnessProviderEndpoint
import com.example.llamadroid.harness.HarnessProviderPreset
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The only settings namespace in which the official custom provider card writes. */
internal const val NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE = "llm-pi-ai"

/** Protocols exported by the pinned pi-ai provider factory. */
internal val NATIVE_CUSTOM_PROVIDER_PROTOCOLS = listOf(
    "openai-completions",
    "openai-responses",
    "anthropic-messages"
)

/** Presets exposed by the custom-provider form; the built-in ADT route is managed separately. */
internal val NATIVE_CUSTOM_PROVIDER_PRESETS = listOf(
    HarnessProviderPreset.REMOTE_LLAMA_CPP,
    HarnessProviderPreset.LLAMA_SWAP,
    HarnessProviderPreset.GENERIC_OPENAI,
)

/** A model row in the official hand-declared provider profile. */
data class NativeHarnessCustomProviderModel(
    val id: String,
    val name: String? = null,
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val inputModalities: List<String> = emptyList(),
    val reasoningEfforts: Map<String, String?> = emptyMap()
)

/** Values collected by the native custom-provider card before it writes. */
data class NativeHarnessCustomProviderRequest(
    val route: String,
    val displayName: String = "",
    val api: String,
    val baseUrl: String,
    val models: List<NativeHarnessCustomProviderModel>,
    /** Transient secret; it is never included in UI state or settings JSON. */
    val apiKey: String = ""
)

internal data class NativeHarnessCustomProviderValidationError(
    val code: String,
    val detail: String
)

private val routePattern = Regex("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
private val legalApiKeyPattern = Regex("^[\\u0021-\\u007e]+$")
private val environmentLinePattern = Regex("^[A-Z][A-Z0-9_]*=[^=].*$")
private val supportedInputModalities = setOf("text", "image")

/** Pick the wire protocol for a provider preset without exposing backend jargon in the first form. */
internal fun nativeHarnessPresetProtocol(
    preset: HarnessProviderPreset,
    protocols: List<String>
): String = when (preset) {
    HarnessProviderPreset.ADT_MANAGED,
    HarnessProviderPreset.REMOTE_LLAMA_CPP,
    HarnessProviderPreset.LLAMA_SWAP,
    HarnessProviderPreset.GENERIC_OPENAI -> "openai-completions"
}.takeIf { it in protocols }
    ?: protocols.firstOrNull().orEmpty()

/** Convert a user-facing provider name into the safe route-key alphabet. */
internal fun nativeHarnessProviderRouteSlug(value: String): String {
    val compact = value.trim().lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
    val normalized = compact.takeIf { it.isNotEmpty() } ?: "provider"
    return if (normalized.firstOrNull()?.isLetter() == true) normalized.take(80).trimEnd('-')
    else "provider-${normalized.take(72).trimEnd('-')}"
}

/** Generate a collision-safe route for the hidden advanced route field. */
internal fun nextNativeHarnessProviderRoute(
    preset: HarnessProviderPreset,
    displayName: String,
    takenRoutes: Set<String>
): String {
    val seed = displayName.trim().takeIf { it.isNotEmpty() } ?: preset.id
    val base = nativeHarnessProviderRouteSlug(seed)
    val taken = takenRoutes.map(String::trim).toSet()
    if (base !in taken) return base
    var suffix = 2
    while (true) {
        val suffixText = "-$suffix"
        val candidate = base.take((80 - suffixText.length).coerceAtLeast(1)).trimEnd('-') + suffixText
        if (candidate !in taken) return candidate
        suffix += 1
    }
}

/** Apply the same whitespace handling as the official web card. */
internal fun normalizeNativeCustomProviderRequest(
    request: NativeHarnessCustomProviderRequest
): NativeHarnessCustomProviderRequest {
    val api = request.api.trim()
    val baseUrl = request.baseUrl.trim()
    return request.copy(
        route = request.route.trim(),
        displayName = request.displayName.trim(),
        api = api,
        // OpenAI-compatible transports use the canonical `/v1` base. Anthropic's
        // native client deliberately receives the server root and adds `/v1`
        // only for its own model listing route.
        baseUrl = canonicalNativeCustomProviderBaseUrl(api, baseUrl),
        apiKey = request.apiKey.trim(),
        models = request.models.map { model ->
            model.copy(
                id = model.id.trim(),
                name = model.name?.trim()?.takeIf(String::isNotEmpty),
                inputModalities = model.inputModalities.map(String::trim).filter(String::isNotEmpty),
                reasoningEfforts = model.reasoningEfforts.mapKeys { it.key.trim() }
            )
        }
    )
}

/** Persist one endpoint spelling so discovery and request routing share it. */
internal fun canonicalNativeCustomProviderBaseUrl(api: String, baseUrl: String): String =
    normalizeHarnessProviderEndpoint(baseUrl)?.let { endpoint ->
        if (api == "anthropic-messages") endpoint.rootUrl else endpoint.v1BaseUrl
    } ?: baseUrl

/** Validate constraints enforced by CustomProviderCard and llm-pi-ai Config. */
internal fun validateNativeCustomProviderRequest(
    rawRequest: NativeHarnessCustomProviderRequest,
    takenRoutes: Set<String> = emptySet()
): NativeHarnessCustomProviderValidationError? {
    val request = normalizeNativeCustomProviderRequest(rawRequest)
    if (!routePattern.matches(request.route)) {
        return NativeHarnessCustomProviderValidationError(
            "CUSTOM_PROVIDER_ROUTE_INVALID",
            "The provider ID must start with a lowercase letter and use lowercase letters, digits, or dashes"
        )
    }
    if (request.route in takenRoutes) {
        return NativeHarnessCustomProviderValidationError(
            "CUSTOM_PROVIDER_ROUTE_TAKEN",
            "A provider already uses this ID"
        )
    }
    if (request.api !in NATIVE_CUSTOM_PROVIDER_PROTOCOLS) {
        return NativeHarnessCustomProviderValidationError(
            "CUSTOM_PROVIDER_PROTOCOL_INVALID",
            "The selected API protocol is unavailable in this Harness release"
        )
    }
    if (normalizeHarnessProviderEndpoint(request.baseUrl) == null) {
        return NativeHarnessCustomProviderValidationError(
            "CUSTOM_PROVIDER_BASE_URL_INVALID",
            "The custom provider needs a valid HTTP or HTTPS URL"
        )
    }
    val ids = mutableSetOf<String>()
    request.models.forEach { model ->
        if (model.id.isBlank()) {
            return NativeHarnessCustomProviderValidationError(
                "CUSTOM_PROVIDER_MODEL_INVALID",
                "Every custom provider model needs an ID"
            )
        }
        if (!ids.add(model.id)) {
            return NativeHarnessCustomProviderValidationError(
                "CUSTOM_PROVIDER_MODEL_INVALID",
                "Custom provider model IDs must be unique"
            )
        }
        if (model.contextWindow != null && model.contextWindow < 1) {
            return NativeHarnessCustomProviderValidationError(
                "CUSTOM_PROVIDER_MODEL_INVALID",
                "A model context window must be positive"
            )
        }
        if (model.maxTokens != null && model.maxTokens < 1) {
            return NativeHarnessCustomProviderValidationError(
                "CUSTOM_PROVIDER_MODEL_INVALID",
                "A model output limit must be positive"
            )
        }
        if (model.inputModalities.any { it !in supportedInputModalities }) {
            return NativeHarnessCustomProviderValidationError(
                "CUSTOM_PROVIDER_MODEL_INVALID",
                "The model input type is not supported by this Harness release"
            )
        }
        if (model.reasoningEfforts.keys.any(String::isBlank)) {
            return NativeHarnessCustomProviderValidationError(
                "CUSTOM_PROVIDER_MODEL_INVALID",
                "Model reasoning levels must have names"
            )
        }
    }
    if (request.apiKey.isNotEmpty()) {
        if (environmentLinePattern.matches(request.apiKey) ||
            isQuotedNativeApiKey(request.apiKey) ||
            !legalApiKeyPattern.matches(request.apiKey)
        ) {
            return NativeHarnessCustomProviderValidationError(
                "CUSTOM_PROVIDER_KEY_INVALID",
                "The provider API key contains unsupported characters"
            )
        }
    }
    return null
}

/**
 * Draft discovery deliberately validates only what the probe needs. A route
 * is not persisted yet, model rows may be empty, and an API key is optional
 * for local or unauthenticated OpenAI-compatible servers.
 */
internal fun validateNativeCustomProviderDiscoveryRequest(
    rawRequest: NativeHarnessCustomProviderRequest
): NativeHarnessCustomProviderValidationError? {
    val request = normalizeNativeCustomProviderRequest(rawRequest)
    if (request.api !in NATIVE_CUSTOM_PROVIDER_PROTOCOLS) {
        return NativeHarnessCustomProviderValidationError(
            "CUSTOM_PROVIDER_PROTOCOL_INVALID",
            "The selected API protocol is unavailable in this Harness release"
        )
    }
    if (normalizeHarnessProviderEndpoint(request.baseUrl) == null) {
        return NativeHarnessCustomProviderValidationError(
            "CUSTOM_PROVIDER_BASE_URL_INVALID",
            "The custom provider needs a valid HTTP or HTTPS URL"
        )
    }
    return null
}

/** The credential reference derived by the official store.ts implementation. */
internal fun nativeCustomProviderCredentialReference(route: String): String =
    "${route.uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9]+"), "_")}_API_KEY"

/** Build the exact profile value written at `providers.<route>`. */
internal fun buildNativeCustomProviderProfile(
    rawRequest: NativeHarnessCustomProviderRequest
): JsonObject {
    val request = normalizeNativeCustomProviderRequest(rawRequest)
    return buildJsonObject {
        if (request.displayName.isNotEmpty()) put("displayName", request.displayName)
        if (request.apiKey.isNotEmpty()) {
            put("apiKeyEnv", nativeCustomProviderCredentialReference(request.route))
        }
        put("api", request.api)
        put("baseURL", request.baseUrl)
        putJsonArray("models") {
            request.models.forEach { model ->
                add(buildJsonObject {
                    put("id", model.id)
                    model.name?.let { put("name", it) }
                    model.contextWindow?.let { put("contextWindow", it) }
                    model.maxTokens?.let { put("maxTokens", it) }
                    if (model.inputModalities.isNotEmpty()) {
                        putJsonArray("input") {
                            model.inputModalities.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                    if (model.reasoningEfforts.isNotEmpty()) {
                        putJsonObject("reasoningEfforts") {
                            model.reasoningEfforts.forEach { (level, wireValue) ->
                                if (wireValue == null) put(level, kotlinx.serialization.json.JsonNull)
                                else put(level, wireValue)
                            }
                        }
                    }
                })
            }
        }
    }
}

/** Build the official `settings/mutate` set operation for a new route. */
internal fun buildNativeCustomProviderOperation(
    rawRequest: NativeHarnessCustomProviderRequest
): JsonObject {
    val request = normalizeNativeCustomProviderRequest(rawRequest)
    return NativeHarnessCapabilities.setOperation(
        listOf("providers", request.route),
        buildNativeCustomProviderProfile(request)
    )
}

private fun isQuotedNativeApiKey(value: String): Boolean {
    if (value.length <= 1) return false
    val first = value.first()
    return first in listOf('"', '\'', '`') && value.last() == first
}
