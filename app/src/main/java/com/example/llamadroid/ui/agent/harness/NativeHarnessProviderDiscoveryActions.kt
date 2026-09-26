package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Owns the discover/select/adopt workflow so the main controller stays small. */
internal class NativeHarnessProviderDiscoveryActions(
    private val clientProvider: suspend () -> HarnessClient?,
    private val configProvider: (String) -> HarnessProviderConfigUi?,
    private val settingsNamespaceProvider: (String) -> String?,
    private val updateConfig: suspend (String, (HarnessProviderConfigUi) -> HarnessProviderConfigUi) -> Unit,
    private val reportFailure: suspend (String, String) -> Unit,
    private val reportSuccess: suspend (Int) -> Unit,
) {
    suspend fun discover(providerId: String) {
        val config = configProvider(providerId) ?: return reportFailure(
            "PROVIDER_NOT_DECLARED",
            "The selected provider is not declared by Harness",
        )
        updateConfig(providerId) {
            it.copy(discovery = it.discovery.copy(isLoading = true, errorCode = null, hasRun = true))
        }
        try {
            val fields = config.fields
            val api = fields.firstOrNull { it.key.substringAfterLast('.') == "api" }
                ?.value.orEmpty()
            val baseUrl = fields.firstOrNull { it.key.substringAfterLast('.') == "baseURL" }
                ?.value.orEmpty()
            val request = buildJsonObject {
                put("provider", providerId)
                if (api.isNotBlank()) put("api", api)
                if (baseUrl.isNotBlank()) {
                    put("baseURL", canonicalNativeCustomProviderBaseUrl(api, baseUrl))
                }
            }
            val client = clientProvider() ?: run {
                finishWithError(providerId, "HARNESS_UNAVAILABLE")
                return
            }
            val settingsNamespace = settingsNamespaceProvider(providerId) ?: run {
                finishWithError(providerId, "PROVIDER_NOT_DECLARED")
                return
            }
            when (val result = NativeHarnessCapabilities.discoverModels(
                client,
                settingsNamespace,
                request,
            )) {
                is HarnessRpcResult.Failure -> finishWithError(providerId, result.error.code, result.error.message)
                is HarnessRpcResult.Success -> {
                    val candidates = parseNativeHarnessDiscoveredModels(result.value)
                    if (candidates == null) {
                        finishWithError(providerId, "PROVIDER_DISCOVERY_INVALID")
                    } else if (candidates.isEmpty()) {
                        finishWithError(providerId, "PROVIDER_DISCOVERY_EMPTY")
                    } else {
                        val known = config.fields
                            .firstOrNull(::isNativeHarnessProviderModelsField)
                            ?.value
                            ?.let(::parseHarnessJsonValue)
                            ?.jsonArrayOrNull()
                            ?.mapNotNull { it.jsonObjectOrNull()?.string("id") }
                            .orEmpty()
                            .toSet()
                        updateConfig(providerId) {
                            it.copy(
                                discovery = HarnessProviderDiscoveryUi(
                                    candidates = candidates,
                                    selectedIds = candidates.map { model -> model.id }
                                        .filterNot(known::contains)
                                        .toSet(),
                                    hasRun = true,
                                ),
                            )
                        }
                        reportSuccess(candidates.size)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            updateConfig(providerId) { it.copy(discovery = it.discovery.copy(isLoading = false)) }
            throw cancelled
        } catch (_: Throwable) {
            finishWithError(providerId, "PROVIDER_DISCOVERY_FAILED")
        }
    }

    suspend fun toggle(providerId: String, modelId: String) {
        val config = configProvider(providerId) ?: return
        val selected = modelId !in config.discovery.selectedIds
        setSelection(providerId, listOf(modelId), selected)
    }

    suspend fun setSelection(providerId: String, modelIds: List<String>, selected: Boolean) {
        if (configProvider(providerId) == null) return
        updateConfig(providerId) {
            it.copy(
                discovery = updateNativeHarnessProviderDiscoverySelection(
                    it.discovery,
                    modelIds,
                    selected,
                )
            )
        }
    }

    suspend fun adopt(providerId: String): Boolean {
        val config = configProvider(providerId) ?: return false
        val field = config.fields.firstOrNull(::isNativeHarnessProviderModelsField)
        if (field == null) {
            reportFailure("PROVIDER_MODELS_UNAVAILABLE", "This provider has no editable model list")
            return false
        }
        val merged = mergeNativeHarnessDiscoveredModels(
            field.value,
            config.discovery.candidates,
            config.discovery.selectedIds,
        )
        if (merged == null) {
            reportFailure("PROVIDER_MODELS_INVALID", "The provider model list is not valid JSON")
            return false
        }
        updateConfig(providerId) { current ->
            current.copy(
                fields = current.fields.map { candidate ->
                    if (candidate.key == field.key && candidate.path == field.path) {
                        candidate.copy(value = merged)
                    } else candidate
                },
                discovery = clearNativeHarnessProviderDiscovery(),
            )
        }
        return true
    }

    suspend fun dismiss(providerId: String) {
        if (configProvider(providerId) != null) {
            updateConfig(providerId) { it.copy(discovery = clearNativeHarnessProviderDiscovery()) }
        }
    }

    private suspend fun finishWithError(providerId: String, code: String, message: String = "Model discovery failed") {
        updateConfig(providerId) {
            it.copy(discovery = it.discovery.copy(isLoading = false, errorCode = code, hasRun = true))
        }
        reportFailure(code, message)
    }
}
