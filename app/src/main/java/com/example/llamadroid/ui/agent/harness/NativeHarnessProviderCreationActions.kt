package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult

/** Serialized native custom-route transaction through the authenticated host route. */
internal class NativeHarnessProviderCreationActions(
    private val clientProvider: suspend () -> HarnessClient?,
    private val takenRoutes: () -> Set<String>,
    private val revisionProvider: () -> Int?,
    private val refresh: suspend () -> Unit,
    private val reportFailure: suspend (String, String) -> Unit,
    private val refreshModelCatalog: suspend () -> Unit
) {
    suspend fun create(rawRequest: NativeHarnessCustomProviderRequest): Boolean {
        val client = clientProvider() ?: return false
        val request = normalizeNativeCustomProviderRequest(rawRequest)
        validateNativeCustomProviderRequest(request, takenRoutes())?.let { error ->
            reportFailure(error.code, error.detail)
            return false
        }
        val revision = revisionProvider() ?: run {
            reportFailure(
                "CUSTOM_PROVIDER_NAMESPACE_UNAVAILABLE",
                "The custom provider settings namespace is unavailable"
            )
            return false
        }
        return when (val result = NativeHarnessProviderGateway.create(client, request, revision)) {
            is HarnessRpcResult.Failure -> {
                reportFailure(result.error.code, result.error.message)
                false
            }
            is HarnessRpcResult.Success -> {
                refresh()
                refreshModelCatalog()
                true
            }
        }
    }
}
