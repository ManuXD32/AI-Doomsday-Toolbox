package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessCallPolicy
import com.example.llamadroid.harness.client.HarnessRpcError
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Authenticated host routes for custom-provider transactions. */
internal object NativeHarnessProviderGateway {
    private const val CREATE_ROUTE = "/api/adt/providerCreate"
    private const val UPDATE_ROUTE = "/api/adt/providerUpdate"
    private const val DELETE_ROUTE = "/api/adt/providerDelete"

    suspend fun create(
        client: HarnessClient,
        request: NativeHarnessCustomProviderRequest,
        expectedRevision: Int,
    ): HarnessRpcResult = post(
        client,
        CREATE_ROUTE,
        buildJsonObject {
            put("route", request.route)
            put("profile", buildNativeCustomProviderProfile(request))
            put("expectedRevision", expectedRevision)
            // Keep the transient key out of the persisted profile. The host
            // transaction stores it and rolls it back if the CAS write fails.
            request.apiKey.trim().takeIf(String::isNotEmpty)?.let { put("apiKey", it) }
        }
    )

    suspend fun updateCredential(
        client: HarnessClient,
        binding: NativeHarnessProviderBinding,
        reference: String,
        value: String?,
    ): HarnessRpcResult {
        val operation = if (value == null) {
            NativeHarnessCapabilities.unsetOperation(binding.settingsPath + "apiKeyEnv")
        } else {
            NativeHarnessCapabilities.setOperation(
                binding.settingsPath + "apiKeyEnv",
                JsonPrimitive(reference),
            )
        }
        return post(
            client,
            UPDATE_ROUTE,
            buildJsonObject {
                put("ns", binding.settingsNamespace)
                putJsonArray("ops") { add(operation) }
                put("expectedRevision", binding.revision)
                putJsonObject("credential") {
                    put("ref", reference)
                    if (value != null) put("value", value)
                }
            }
        )
    }

    suspend fun delete(
        client: HarnessClient,
        binding: NativeHarnessProviderBinding,
    ): HarnessRpcResult = post(
        client,
        DELETE_ROUTE,
        buildJsonObject {
            put("expectedRevision", binding.revision)
            putJsonObject("target") {
                put("settingsNs", binding.settingsNamespace)
                putJsonArray("settingsPath") {
                    binding.settingsPath.forEach { add(JsonPrimitive(it)) }
                }
                binding.apiKeyReference?.let { put("credentialRef", it) }
            }
        }
    )

    private suspend fun post(
        client: HarnessClient,
        path: String,
        args: kotlinx.serialization.json.JsonObject,
    ): HarnessRpcResult {
        val method = path.substringAfterLast('/')
        val response = client.call("adt", method, args, HarnessCallPolicy.NoRetry)
        return when (response) {
            is HarnessRpcResult.Failure -> safeFailure(response.error)
            is HarnessRpcResult.Success -> normalize(response.value)
        }
    }

    private fun safeFailure(error: HarnessRpcError): HarnessRpcResult.Failure =
        HarnessRpcResult.Failure(
            HarnessRpcError(
                error.code.take(128).ifBlank { "PROVIDER_ROUTE_FAILED" },
                "Provider settings operation failed",
            )
        )

    /** Accept the client-unwrapped value and older direct route envelopes. */
    private fun normalize(value: kotlinx.serialization.json.JsonElement): HarnessRpcResult {
        val root = value.jsonObjectOrNull() ?: return invalidResult()
        if (root.string("type") == "server-response") {
            return normalizeResult(root.objectValue("result") ?: return invalidResult())
        }
        if (root.containsKey("ok")) return normalizeResult(root)
        return HarnessRpcResult.Success(value)
    }

    /** Reduce a route result without exposing its backend message. */
    private fun normalizeResult(result: kotlinx.serialization.json.JsonObject): HarnessRpcResult {
        if (result.boolean("ok") != true) {
            val code = result.objectValue("error")?.string("code")
                ?.takeIf { it.isNotBlank() && it.length <= 128 }
                ?: "PROVIDER_ROUTE_FAILED"
            return HarnessRpcResult.Failure(HarnessRpcError(code, "Provider settings operation failed"))
        }
        return HarnessRpcResult.Success(result["value"] ?: JsonNull)
    }

    private fun invalidResult(): HarnessRpcResult = HarnessRpcResult.Failure(
        HarnessRpcError("PROVIDER_ROUTE_INVALID", "Provider settings returned an invalid response")
    )
}
