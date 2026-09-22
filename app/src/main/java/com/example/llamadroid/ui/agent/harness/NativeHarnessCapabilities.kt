package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcError
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Wire-backed capability helpers for the alpha2 Harness release.
 *
 * The native screen owns presentation state, while this object keeps the
 * release-specific method names and argument envelopes in one place. Actions
 * that are not Remote methods (the approval/question waterfalls and host job
 * controls) stay outside this object and are supplied as explicit callbacks.
 */
internal object NativeHarnessCapabilities {
    const val LLM_NAMESPACE = "llm"
    const val SETTINGS_NAMESPACE = "settings"
    const val CREDENTIALS_NAMESPACE = "credentials"

    suspend fun listConfigurableProviders(client: HarnessClient): HarnessRpcResult =
        client.call(LLM_NAMESPACE, "listConfigurableProviders", policy = HarnessCallPolicy.SafeRead)

    suspend fun discoverModels(
        client: HarnessClient,
        settingsNamespace: String,
        request: JsonObject
    ): HarnessRpcResult {
        val response = client.call(
            "adt",
            "providerDiscovery",
            buildJsonObject {
                put("settingsNs", settingsNamespace)
                put("request", request)
            },
            HarnessCallPolicy.NoRetry,
        )
        return when (response) {
            is HarnessRpcResult.Failure -> HarnessRpcResult.Failure(
                HarnessRpcError(
                    response.error.code.take(128).ifBlank { "PROVIDER_DISCOVERY_FAILED" },
                    "Model discovery failed",
                )
            )
            is HarnessRpcResult.Success -> parseProviderDiscoveryResponse(response.value)
        }
    }

    /**
     * HarnessClient validates the authenticated server-response envelope before
     * this helper sees the route value. Older bridge builds returned a direct
     * route envelope, so accept both forms and reduce them to the model array
     * consumed by the native provider editor.
     */
    private fun parseProviderDiscoveryResponse(value: JsonElement): HarnessRpcResult {
        val payload = when (val root = value.jsonObjectOrNull()) {
            null -> value
            else -> {
                if (root.string("type") != "server-response") {
                    value
                } else {
                    val result = root.objectValue("result") ?: return invalidProviderDiscovery()
                    if (result.boolean("ok") != true) {
                        val errorCode = result.objectValue("error")?.string("code")
                            ?.takeIf { it.isNotBlank() && it.length <= 128 }
                        return HarnessRpcResult.Failure(
                            HarnessRpcError(errorCode ?: "PROVIDER_DISCOVERY_FAILED", "Model discovery failed")
                        )
                    }
                    result["value"] ?: return invalidProviderDiscovery()
                }
            }
        }
        val envelope = payload.jsonObjectOrNull()
        val models = payload.jsonArrayOrNull()
            ?: envelope?.get("models")?.jsonArrayOrNull()
        val status = envelope?.string("status")?.lowercase()
        return when {
            payload.jsonArrayOrNull() != null -> HarnessRpcResult.Success(models ?: return invalidProviderDiscovery())
            status in setOf("ok", "success", "discovered", "empty") && models != null ->
                HarnessRpcResult.Success(models)
            status == "error" || status in PROVIDER_DISCOVERY_FAILURE_STATUSES -> HarnessRpcResult.Failure(
                HarnessRpcError(
                    envelope?.string("errorCode")?.takeIf { it.isNotBlank() }
                        ?: providerDiscoveryFailureCode(status),
                    "Model discovery failed",
                )
            )
            else -> invalidProviderDiscovery()
        }
    }

    private fun invalidProviderDiscovery(): HarnessRpcResult = HarnessRpcResult.Failure(
        HarnessRpcError("PROVIDER_DISCOVERY_INVALID", "Model discovery returned an invalid response")
    )

    private fun providerDiscoveryFailureCode(status: String?): String = when (status) {
        "authentication_failure" -> "PROVIDER_DISCOVERY_UNAUTHORIZED"
        "connection_failure" -> "PROVIDER_DISCOVERY_NETWORK"
        "unsupported" -> "PROVIDER_DISCOVERY_UNSUPPORTED"
        "invalid_response" -> "PROVIDER_DISCOVERY_INVALID_RESPONSE"
        "oversized_response" -> "PROVIDER_DISCOVERY_TOO_LARGE"
        else -> "PROVIDER_DISCOVERY_FAILED"
    }

    suspend fun mutateSettings(
        client: HarnessClient,
        namespace: String,
        operations: JsonArray,
        expectedRevision: Int?
    ): HarnessRpcResult = client.call(
        SETTINGS_NAMESPACE,
        "mutate",
        buildJsonObject {
            put("ns", namespace)
            put("ops", operations)
            if (expectedRevision != null) put("expectedRevision", expectedRevision)
        },
        HarnessCallPolicy.NoRetry
    )

    suspend fun setCredential(
        client: HarnessClient,
        reference: String,
        value: String
    ): HarnessRpcResult = client.call(
        CREDENTIALS_NAMESPACE,
        "set",
        buildJsonObject {
            put("ref", reference)
            put("value", value)
        },
        HarnessCallPolicy.NoRetry
    )

    suspend fun describeCredentials(
        client: HarnessClient,
        references: JsonArray
    ): HarnessRpcResult = client.call(
        CREDENTIALS_NAMESPACE,
        "describe",
        buildJsonObject { put("refs", references) },
        HarnessCallPolicy.SafeRead
    )

    suspend fun unsetCredential(client: HarnessClient, reference: String): HarnessRpcResult =
        client.call(
            CREDENTIALS_NAMESPACE,
            "unset",
            buildJsonObject { put("ref", reference) },
            HarnessCallPolicy.NoRetry
        )

    /** Build the path operation used by the official provider editor. */
    fun setOperation(path: List<String>, value: kotlinx.serialization.json.JsonElement): JsonObject =
        buildJsonObject {
            put("op", "set")
            putJsonArray("path") { path.forEach { add(JsonPrimitive(it)) } }
            put("value", value)
        }

    /** Build the path operation used when an official provider is removed. */
    fun unsetOperation(path: List<String>): JsonObject = buildJsonObject {
        put("op", "unset")
        putJsonArray("path") { path.forEach { add(JsonPrimitive(it)) } }
    }
}

private val PROVIDER_DISCOVERY_FAILURE_STATUSES = setOf(
    "authentication_failure",
    "connection_failure",
    "unsupported",
    "invalid_response",
    "oversized_response",
)
