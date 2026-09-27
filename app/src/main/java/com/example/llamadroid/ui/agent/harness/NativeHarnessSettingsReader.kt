package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    /** Monotonic guard for reads that outlive an endpoint replacement. */
    private var catalogRequestGeneration = 0L
    /** Last complete provider snapshot used for provider-level catalog failures. */
    private var lastGoodCatalogProviders: List<HarnessProviderOption> = emptyList()
    private var lastCatalogClient: HarnessClient? = null
    var settingsRevisions: Map<String, Int> = emptyMap()
        private set
    var settingsWritable = false
        private set
    var providerBindings: Map<String, NativeHarnessProviderBinding> = emptyMap()
        private set

    suspend fun refreshSettings(reportFailure: Boolean) = settingsLock.withLock { readSettings(reportFailure) }

    /**
     * Provider settings are the capability source for the catalog. Keep this
     * ordering in one entry point so attach, reconnect, WebUI return, and
     * manual refreshes cannot publish a catalog against an old provider view.
     */
    suspend fun refreshSettingsThenCatalog(reportFailure: Boolean) {
        refreshSettings(reportFailure)
        refreshModelCatalog(reportFailure)
    }

    private suspend fun readSettings(reportFailure: Boolean) {
        val client = clientOrNull() ?: return
        when (val result = client.describeSettings()) {
            is HarnessRpcResult.Failure -> if (reportFailure && isCurrent(client)) {
                reportFailure(result.error.code, result.error.message)
            }
            is HarnessRpcResult.Success -> {
                if (!isCurrent(client)) return
                val namespaces = result.value.objectArray("namespaces")
                val writable = result.value.boolean("writable").orDefault(false)
                val settingsHasDocument = result.value.boolean("hasDocument").orDefault(false)
                val revisions = namespaces.mapNotNull { item ->
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
                            writable = writable,
                            hiddenPaths = harnessNamespaceSecretPaths(item)
                        )
                    )
                }
                val directory = when (val directoryResult = NativeHarnessCapabilities.listConfigurableProviders(client)) {
                    is HarnessRpcResult.Success -> directoryResult.value
                        .let { value -> if (value is JsonArray) value.mapNotNull { it.jsonObjectOrNull() } else emptyList() }
                    is HarnessRpcResult.Failure -> {
                        if (reportFailure && isCurrent(client)) {
                            reportFailure(directoryResult.error.code, directoryResult.error.message)
                        }
                        emptyList()
                    }
                }
                val bindings = buildHarnessProviderBindings(directory, namespaces, writable)
                val credentialReferences = bindings.values
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
                            if (reportFailure && isCurrent(client)) reportFailure(
                                credentialResult.error.code,
                                credentialResult.error.message
                            )
                            emptyMap()
                        }
                    }
                }
                val authStates = try {
                    describeAuth(bindings.keys.toList())
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    emptyMap()
                }
                if (!isCurrent(client)) return
                settingsWritable = writable
                settingsRevisions = revisions
                providerBindings = bindings
                val configs = bindings.values.map { binding ->
                    val credentialReference = binding.apiKeyReference
                    val credential = credentialReference?.let(credentialInfo::get)
                    HarnessProviderConfigUi(
                        id = binding.providerId,
                        name = binding.displayName,
                        fields = harnessProviderFields(binding, namespaces, writable),
                        auth = authStates[binding.providerId] ?: HarnessProviderAuthUi(),
                        credentialReference = credentialReference,
                        credentialConfigured = credential?.boolean("configured").orDefault(false),
                        credentialOptional = nativeHarnessProviderCredentialOptional(binding),
                        credentialSource = credential?.string("source"),
                        credentialWritable = nativeHarnessProviderCredentialWritable(binding, credential),
                        canSave = writable,
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
                            canCreateProvider = writable &&
                                revisions.containsKey(NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE),
                            customProviderProtocols = NATIVE_CUSTOM_PROVIDER_PROTOCOLS
                        )
                    )
                }
            }
        }
    }

    suspend fun refreshModelCatalog(reportFailure: Boolean) = catalogLock.withLock {
        val generation = ++catalogRequestGeneration
        readModelCatalog(reportFailure, generation)
    }

    private suspend fun readModelCatalog(reportFailure: Boolean, generation: Long) {
        val client = clientOrNull() ?: run {
            mutate { it.copy(provider = it.provider.copy(isCatalogLoading = false, catalogRefreshFailed = true)) }
            return
        }
        if (!isCatalogRequestCurrent(client, generation)) return
        if (lastCatalogClient !== client) {
            lastCatalogClient = client
            lastGoodCatalogProviders = emptyList()
        }
        mutate { it.copy(provider = it.provider.copy(isCatalogLoading = true, catalogRefreshFailed = false)) }
        try {
        when (val result = client.modelCatalog()) {
            is HarnessRpcResult.Failure -> {
                if (!isCatalogRequestCurrent(client, generation)) return
                mutate { it.copy(provider = it.provider.copy(isCatalogLoading = false, catalogRefreshFailed = true)) }
                if (reportFailure) reportFailure(result.error.code, result.error.message)
            }
            is HarnessRpcResult.Success -> {
                if (!isCatalogRequestCurrent(client, generation)) return
                val catalog = result.value.jsonObjectOrNull()
                if (catalog == null) {
                    if (!isCatalogRequestCurrent(client, generation)) return
                    mutate { it.copy(provider = it.provider.copy(isCatalogLoading = false, catalogRefreshFailed = true)) }
                    if (reportFailure) {
                        reportFailure("MODEL_CATALOG_INVALID", "Harness returned an invalid model catalog")
                    }
                    return
                }
                val parsed = parseHarnessModelCatalog(catalog)
                val stateSnapshot = state.value
                val savedConfigs = stateSnapshot.provider.configs
                var localCatalogRead = false
                val localProviders = try {
                    localModelCatalog()?.let { value ->
                        localCatalogRead = true
                        parseHarnessLocalModelCatalog(value)
                    }.orEmpty()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    emptyList()
                }
                val catalogProviders = mergeHarnessSavedModelCapabilities(
                    mergeHarnessLocalModelProviders(parsed.providers, localProviders),
                    savedConfigs
                )
                // A transient provider failure can arrive as an empty group.
                // Overlay cached rows by ID so the empty group cannot win a
                // distinctBy merge and erase the last known model limits.
                val cachedProviders = stateSnapshot.provider.providers + lastGoodCatalogProviders
                val failedProviderIds = parsed.failures.map { it.providerId }.toSet()
                var providers = overlayHarnessProviderRows(
                    fresh = catalogProviders,
                    replacements = cachedProviders
                        .filter { it.id in failedProviderIds }
                        .associateBy { it.id },
                )
                if (!localCatalogRead) {
                    // A failed Android bridge projection must not make all
                    // previously discovered local rows disappear. A valid
                    // empty JSON catalog is still authoritative and sets
                    // localCatalogRead=true above.
                    providers = overlayHarnessProviderRows(
                        fresh = providers,
                        replacements = cachedProviders
                            .filter(::isAndroidManagedHarnessProvider)
                            .associateBy { it.id },
                    )
                }
                if (parsed.failures.isEmpty()) {
                    lastGoodCatalogProviders = providers
                }
                if (!isCatalogRequestCurrent(client, generation)) return
                mutate { current ->
                    // Resolve the selection from the state held at commit
                    // time. A session/model action may have completed while
                    // the catalog RPC was in flight; using the earlier
                    // snapshot here would overwrite that live selection.
                    val selectedProviderId = current.provider.selectedProviderId
                        ?.let { currentProviderId ->
                            current.provider.selectedModel?.let { modelId ->
                                canonicalHarnessProviderId(providers, currentProviderId, modelId)
                            } ?: currentProviderId
                        }
                        ?: parsed.defaultProvider
                            ?.takeIf { id -> providers.any { it.id == id } }
                        ?: providers.firstOrNull()?.id
                    val provider = providers.firstOrNull { it.id == selectedProviderId }
                    val selectedModel = current.provider.selectedModel
                        ?: parsed.defaultModel
                            ?.takeIf { parsed.defaultProvider == selectedProviderId }
                            ?.takeIf { model ->
                                provider?.models?.contains(model) == true &&
                                    (model.startsWith("llama:") || provider.let { harnessModelContextKnown(it, model) })
                            }
                        ?: provider?.let(::harnessSelectableModelIds)?.firstOrNull()
                    val selectedEffort = current.provider.selectedReasoningEffort
                        ?.takeIf { effort -> provider?.reasoningEfforts?.get(selectedModel).orEmpty().any { it.id == effort } }
                        ?: if (current.provider.thinkingEnabled) {
                            provider?.reasoningDefaults?.get(selectedModel)
                                ?: parsed.defaultReasoningEffort?.takeIf { selectedModel == parsed.defaultModel }
                        } else null
                    current.copy(provider = current.provider.copy(
                        selectedProviderId = selectedProviderId,
                        selectedModel = selectedModel,
                        selectedReasoningEffort = selectedEffort,
                        providers = providers,
                        catalogFailures = parsed.failures,
                        supportsThinking = selectedModel != null && provider?.reasoningModels?.contains(selectedModel) == true,
                        isCatalogLoading = false,
                        catalogRefreshFailed = false,
                    ))
                }
            }
        }
        } catch (error: Throwable) {
            if (error !is CancellationException && isCatalogRequestCurrent(client, generation)) {
                mutate { it.copy(provider = it.provider.copy(catalogRefreshFailed = true)) }
            }
            throw error
        } finally {
            // A replaced client or cancelled refresh must not leave the picker spinning.
            // The catalog mutex prevents this cleanup from racing a newer refresh.
            withContext(NonCancellable) {
                if (isCatalogRequestCurrent(client, generation)) {
                    mutate { it.copy(provider = it.provider.copy(isCatalogLoading = false)) }
                }
            }
        }
    }

    private fun isCatalogRequestCurrent(client: HarnessClient, generation: Long): Boolean =
        catalogRequestGeneration == generation && isCurrent(client)

    private fun overlayHarnessProviderRows(
        fresh: List<HarnessProviderOption>,
        replacements: Map<String, HarnessProviderOption>,
    ): List<HarnessProviderOption> {
        val result = fresh.map { replacements[it.id] ?: it }.toMutableList()
        replacements.values.forEach { replacement ->
            if (result.none { it.id == replacement.id }) result += replacement
        }
        return result
    }

    private fun isAndroidManagedHarnessProvider(provider: HarnessProviderOption): Boolean =
        provider.detail == "Android-managed provider" || provider.id in setOf(
            "adt-managed",
            "adt-llama-server",
            "adt-ollama",
        )

}
