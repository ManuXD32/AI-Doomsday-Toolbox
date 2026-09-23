package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonObject

/** One settings/provider snapshot shared by the categorized native editors. */
internal class NativeHarnessSettingsReader(
    private val clientOrNull: suspend () -> HarnessClient?,
    private val state: kotlinx.coroutines.flow.StateFlow<NativeHarnessUiState>,
    private val mutate: suspend ((NativeHarnessUiState) -> NativeHarnessUiState) -> Unit,
    private val describeAuth: suspend (List<String>) -> Map<String, HarnessProviderAuthUi>,
    private val isCurrent: (HarnessClient) -> Boolean,
    private val reportFailure: suspend (String, String) -> Unit,
    /** Optional Android bridge projection (LiteRT/managed servers). */
    private val localModelCatalog: suspend () -> JsonObject? = { null },
) {
    private val settingsLock = Mutex()
    private val catalogLock = Mutex()
    var settingsRevisions: Map<String, Int> = emptyMap()
        private set
    var settingsWritable = false
        private set
    var providerBindings: Map<String, NativeHarnessProviderBinding> = emptyMap()
        private set

    suspend fun refreshSettings(reportFailure: Boolean) = settingsLock.withLock { readSettings(reportFailure) }

    private suspend fun readSettings(reportFailure: Boolean) {
        val client = clientOrNull() ?: return
        when (val result = client.describeSettings()) {
            is HarnessRpcResult.Failure -> if (reportFailure) reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                if (!isCurrent(client)) return
                val namespaces = result.value.objectArray("namespaces")
                settingsWritable = result.value.boolean("writable").orDefault(false)
                val settingsHasDocument = result.value.boolean("hasDocument").orDefault(false)
                settingsRevisions = namespaces.mapNotNull { item ->
                    item.string("ns")?.let { it to (item.int("revision") ?: 0) }
                }.toMap()
                val sections = namespaces.mapNotNull { item ->
                    val namespace = item.string("ns") ?: return@mapNotNull null
                    HarnessSchemaSection(
                        title = namespace,
                        description = item.string("applies"),
                        fields = parseHarnessSchemaFields(
                            namespace = namespace,
                            schema = item.objectValue("schema"),
                            value = item["value"],
                            user = item["user"],
                            writable = settingsWritable,
                            hiddenPaths = harnessNamespaceSecretPaths(item)
                        )
                    )
                }
                val directory = when (val directoryResult = NativeHarnessCapabilities.listConfigurableProviders(client)) {
                    is HarnessRpcResult.Success -> directoryResult.value
                        .let { value -> if (value is JsonArray) value.mapNotNull { it.jsonObjectOrNull() } else emptyList() }
                    is HarnessRpcResult.Failure -> {
                        if (reportFailure) reportFailure(directoryResult.error.code, directoryResult.error.message)
                        emptyList()
                    }
                }
                providerBindings = buildHarnessProviderBindings(directory, namespaces, settingsWritable)
                val credentialReferences = providerBindings.values
                    .mapNotNull { it.apiKeyReference }
                    .distinct()
                val credentialInfo = if (credentialReferences.isEmpty()) {
                    emptyMap()
                } else {
                    when (val credentialResult = NativeHarnessCapabilities.describeCredentials(
                        client,
                        buildJsonArray { credentialReferences.forEach { add(JsonPrimitive(it)) } }
                    )) {
                        is HarnessRpcResult.Success -> credentialReferences.associateWith { reference ->
                            credentialResult.value.objectValue(reference)
                        }
                        is HarnessRpcResult.Failure -> {
                            if (reportFailure) reportFailure(
                                credentialResult.error.code,
                                credentialResult.error.message
                            )
                            emptyMap()
                        }
                    }
                }
                val authStates = try {
                    describeAuth(providerBindings.keys.toList())
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    emptyMap()
                }
                if (!isCurrent(client)) return
                val configs = providerBindings.values.map { binding ->
                    val credentialReference = binding.apiKeyReference
                    val credential = credentialReference?.let(credentialInfo::get)
                    HarnessProviderConfigUi(
                        id = binding.providerId,
                        name = binding.displayName,
                        fields = harnessProviderFields(binding, namespaces, settingsWritable),
                        auth = authStates[binding.providerId] ?: HarnessProviderAuthUi(),
                        credentialReference = credentialReference,
                        credentialConfigured = credential?.boolean("configured").orDefault(false),
                        credentialOptional = nativeHarnessProviderCredentialOptional(binding),
                        credentialSource = credential?.string("source"),
                        credentialWritable = nativeHarnessProviderCredentialWritable(binding, credential),
                        canSave = settingsWritable,
                        canDelete = binding.canDelete
                    )
                }
                mutate { current ->
                    current.copy(
                        settingsWritable = settingsWritable,
                        settingsHasDocument = settingsHasDocument,
                        schemaSections = sections,
                        provider = current.provider.copy(
                            configs = configs,
                            canCreateProvider = settingsWritable &&
                                settingsRevisions.containsKey(NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE),
                            customProviderProtocols = NATIVE_CUSTOM_PROVIDER_PROTOCOLS
                        )
                    )
                }
            }
        }
    }

    suspend fun refreshModelCatalog(reportFailure: Boolean) = catalogLock.withLock { readModelCatalog(reportFailure) }

    private suspend fun readModelCatalog(reportFailure: Boolean) {
        val client = clientOrNull() ?: return
        mutate { it.copy(provider = it.provider.copy(isCatalogLoading = true)) }
        when (val result = client.modelCatalog()) {
            is HarnessRpcResult.Failure -> {
                mutate { it.copy(provider = it.provider.copy(isCatalogLoading = false)) }
                if (reportFailure) reportFailure(result.error.code, result.error.message)
            }
            is HarnessRpcResult.Success -> {
                if (!isCurrent(client)) return
                val catalog = result.value.jsonObjectOrNull()
                if (catalog == null) {
                    mutate { it.copy(provider = it.provider.copy(isCatalogLoading = false)) }
                    reportFailure("MODEL_CATALOG_INVALID", "Harness returned an invalid model catalog")
                    return
                }
                val parsed = parseHarnessModelCatalog(catalog)
                val savedConfigs = state.value.provider.configs
                val localProviders = runCatching {
                    localModelCatalog()?.let(::parseHarnessLocalModelCatalog).orEmpty()
                }.getOrDefault(emptyList())
                val providers = mergeHarnessSavedModelCapabilities(
                    mergeHarnessLocalModelProviders(parsed.providers, localProviders),
                    savedConfigs
                )
                val selectedProvider = state.value.provider.selectedProviderId
                    ?.takeIf { id -> providers.any { it.id == id } }
                    ?: parsed.defaultProvider
                    ?: providers.firstOrNull()?.id
                val provider = providers.firstOrNull { it.id == selectedProvider }
                val selectedModel = state.value.provider.selectedModel
                    ?.takeIf { model ->
                        provider?.models?.contains(model) == true &&
                            provider.let { it != null && harnessModelContextKnown(it, model) }
                    }
                    ?: parsed.defaultModel
                        ?.takeIf { parsed.defaultProvider == selectedProvider }
                        ?.takeIf { model -> provider?.let { harnessModelContextKnown(it, model) } == true }
                    ?: provider?.models?.firstOrNull { harnessModelContextKnown(provider, it) }
                val selectedEffort = state.value.provider.selectedReasoningEffort
                    ?.takeIf { effort -> provider?.reasoningEfforts?.get(selectedModel).orEmpty().any { it.id == effort } }
                    ?: if (state.value.provider.thinkingEnabled) {
                        provider?.reasoningDefaults?.get(selectedModel)
                            ?: parsed.defaultReasoningEffort?.takeIf { selectedModel == parsed.defaultModel }
                    } else null
                mutate { current ->
                    current.copy(provider = current.provider.copy(
                        selectedProviderId = selectedProvider,
                        selectedModel = selectedModel,
                        selectedReasoningEffort = selectedEffort,
                        providers = providers,
                        catalogFailures = parsed.failures,
                        supportsThinking = selectedModel != null && provider?.reasoningModels?.contains(selectedModel) == true,
                        isCatalogLoading = false
                    ))
                }
            }
        }
    }

}
