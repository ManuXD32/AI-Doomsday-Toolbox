package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * Native settings mutations kept separate from the session controller. The
 * field editor and reset action both use the official path-addressed settings
 * contract, so clearing an override never writes the composed default back as
 * a user value.
 */
internal class NativeHarnessSettingsActions(
    private val clientProvider: suspend () -> HarnessClient?,
    private val stateProvider: () -> NativeHarnessUiState,
    private val revisionProvider: (String) -> Int?,
    private val takenProviderRoutes: () -> Set<String> = { emptySet() },
    private val mutate: suspend ((NativeHarnessUiState) -> NativeHarnessUiState) -> Unit,
    private val refresh: suspend () -> Unit,
    private val reportFailure: suspend (String, String) -> Unit,
    private val refreshModelCatalog: suspend () -> Unit,
    private val draftDiscovery: suspend (NativeHarnessCustomProviderRequest) -> List<HarnessDiscoveredModelUi> =
        { request -> NativeHarnessDraftProviderDiscovery.discover(request) }
) {
    private var documentSnapshot: NativeHarnessSettingsDocumentSnapshot? = null
    private val providerCreation = NativeHarnessProviderCreationActions(
        clientProvider = clientProvider,
        takenRoutes = takenProviderRoutes,
        revisionProvider = { revisionProvider(NATIVE_CUSTOM_PROVIDER_SETTINGS_NAMESPACE) },
        refresh = refresh,
        refreshModelCatalog = refreshModelCatalog,
        reportFailure = reportFailure
    )

    suspend fun createCustomProvider(request: NativeHarnessCustomProviderRequest) {
        if (providerCreation.create(request)) dismissCustomProviderModels()
    }

    /**
     * Discover a draft before it is persisted. The API key is sent only in
     * this authenticated RPC request and never copied into UI state or the
     * settings document.
     */
    suspend fun discoverCustomProviderModels(rawRequest: NativeHarnessCustomProviderRequest) {
        val request = normalizeNativeCustomProviderRequest(rawRequest)
        validateNativeCustomProviderDiscoveryRequest(request)?.let { error ->
            mutate { current ->
                current.copy(provider = current.provider.copy(
                    customProviderDiscovery = HarnessProviderDiscoveryUi(
                        isLoading = false,
                        errorCode = error.code,
                        hasRun = true,
                    )
                ))
            }
            reportFailure(error.code, error.detail)
            return
        }
        mutate { current ->
            current.copy(provider = current.provider.copy(
                customProviderDiscovery = HarnessProviderDiscoveryUi(
                    isLoading = true,
                    hasRun = true,
                )
            ))
        }
        val candidates = try {
            draftDiscovery(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: NativeHarnessDraftDiscoveryException) {
            return finishDraftDiscovery(error.code, error.message ?: "Model discovery failed")
        } catch (_: Throwable) {
            return finishDraftDiscovery("PROVIDER_DISCOVERY_FAILED")
        }
        when {
            candidates.isEmpty() -> finishDraftDiscovery("PROVIDER_DISCOVERY_EMPTY")
            else -> mutate { current ->
                current.copy(provider = current.provider.copy(
                    customProviderDiscovery = HarnessProviderDiscoveryUi(
                        candidates = candidates,
                        selectedIds = candidates.map { it.id }.toSet(),
                        hasRun = true,
                    )
                ))
            }
        }
    }

    suspend fun dismissCustomProviderModels() {
        mutate { current ->
            current.copy(provider = current.provider.copy(customProviderDiscovery = HarnessProviderDiscoveryUi()))
        }
    }

    private suspend fun finishDraftDiscovery(code: String, message: String = "Model discovery failed") {
        mutate { current ->
            current.copy(provider = current.provider.copy(
                customProviderDiscovery = current.provider.customProviderDiscovery.copy(
                    isLoading = false,
                    errorCode = code,
                    hasRun = true,
                )
            ))
        }
        reportFailure(code, message)
    }

    suspend fun update(key: String, text: String) {
        val target = target(key) ?: return
        val value = harnessSettingsValue(target.field, text) ?: run {
            reportFailure("SETTINGS_JSON_INVALID", "Enter a valid value for this Harness setting")
            return
        }
        write(target.namespace, NativeHarnessCapabilities.setOperation(target.path, value))
    }

    suspend fun reset(key: String) {
        val target = target(key) ?: return
        if (!target.field.isOverridden) {
            reportFailure("SETTINGS_FIELD_NOT_OVERRIDDEN", "This Harness setting is already using its default")
            return
        }
        write(target.namespace, NativeHarnessCapabilities.unsetOperation(target.path))
    }

    suspend fun openDocument() {
        if (!stateProvider().settingsHasDocument) {
            reportFailure("SETTINGS_DOCUMENT_UNAVAILABLE", "The Harness settings document is unavailable")
            return
        }
        val client = clientProvider() ?: return
        when (val result = client.describeSettings()) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> {
                val describe = result.value.jsonObjectOrNull()
                if (describe == null || !describe.boolean("hasDocument").orDefault(false)) {
                    reportFailure("SETTINGS_DOCUMENT_UNAVAILABLE", "The Harness settings document is unavailable")
                    return
                }
                val snapshot = try {
                    parseHarnessSettingsDocumentSnapshot(describe)
                } catch (_: NativeHarnessSettingsDocumentMalformedException) {
                    documentSnapshot = null
                    mutate { it.copy(settingsDocument = null) }
                    reportFailure(
                        "SETTINGS_DOCUMENT_INVALID",
                        "The Harness settings document contains an invalid user section"
                    )
                    return
                }
                if (snapshot.text.length > HARNESS_MAX_SETTINGS_DOCUMENT_CHARS) {
                    reportFailure("SETTINGS_DOCUMENT_TOO_LARGE", "The Harness settings document is too large to edit here")
                    return
                }
                documentSnapshot = snapshot
                val writable = describe.boolean("writable").orDefault(stateProvider().settingsWritable)
                mutate { current ->
                    current.copy(
                        settingsDocument = HarnessSettingsDocumentUi(
                            text = snapshot.text,
                            canEdit = writable
                        )
                    )
                }
            }
        }
    }

    suspend fun saveDocument(text: String) {
        val current = stateProvider()
        val document = current.settingsDocument
        if (document == null) {
            reportFailure("SETTINGS_DOCUMENT_UNAVAILABLE", "The Harness settings document is unavailable")
            return
        }
        if (!document.canEdit) {
            reportFailure("SETTINGS_READ_ONLY", "Harness settings are read-only in this environment")
            return
        }
        val client = clientProvider() ?: return
        val snapshot = documentSnapshot ?: run {
            reportFailure("SETTINGS_DOCUMENT_UNAVAILABLE", "The Harness settings document is unavailable")
            return
        }
        when (val edit = diffHarnessSettingsDocument(snapshot, text)) {
            is NativeHarnessSettingsDocumentEdit.Failure -> {
                reportFailure(edit.code, "The Harness settings document could not be parsed")
                return
            }
            is NativeHarnessSettingsDocumentEdit.Success -> writeDocument(edit.operations, snapshot, client)
        }
    }

    suspend fun closeDocument() {
        documentSnapshot = null
        mutate { it.copy(settingsDocument = null) }
    }

    private suspend fun writeDocument(
        operations: Map<String, List<kotlinx.serialization.json.JsonObject>>,
        snapshot: NativeHarnessSettingsDocumentSnapshot,
        client: HarnessClient
    ) {
        if (operations.isEmpty()) {
            closeDocument()
            return
        }
        mutate { current -> current.copy(settingsDocument = current.settingsDocument?.copy(isSaving = true)) }
        var currentSnapshot = snapshot
        operations.forEach { (namespace, namespaceOperations) ->
            when (val result = NativeHarnessCapabilities.mutateSettings(
                client = client,
                namespace = namespace,
                operations = kotlinx.serialization.json.buildJsonArray {
                    namespaceOperations.forEach { add(it) }
                },
                expectedRevision = snapshot.revisions[namespace]
            )) {
                is HarnessRpcResult.Failure -> {
                    mutate { current -> current.copy(settingsDocument = current.settingsDocument?.copy(isSaving = false)) }
                    reportFailure(result.error.code, result.error.message)
                    return
                }
                is HarnessRpcResult.Success -> {
                    val view = result.value.jsonObjectOrNull()
                    val revision = view?.int("revision")
                    if (view == null || revision == null) {
                        mutate { current -> current.copy(settingsDocument = current.settingsDocument?.copy(isSaving = false)) }
                        reportFailure(
                            "SETTINGS_DOCUMENT_INVALID_RESULT",
                            "Harness returned an invalid settings revision"
                        )
                        return
                    }
                    val user = try {
                        harnessSettingsUserSection(view)
                    } catch (_: NativeHarnessSettingsDocumentMalformedException) {
                        mutate { current -> current.copy(settingsDocument = current.settingsDocument?.copy(isSaving = false)) }
                        reportFailure(
                            "SETTINGS_DOCUMENT_INVALID",
                            "Harness returned an invalid user settings section"
                        )
                        return
                    }
                    currentSnapshot = currentSnapshot.copy(
                        baseline = currentSnapshot.baseline + (
                            namespace to user
                        ),
                        revisions = currentSnapshot.revisions + (namespace to revision)
                    )
                    documentSnapshot = currentSnapshot
                }
            }
        }
        refresh()
        closeDocument()
    }

    private suspend fun write(namespace: String, operation: JsonElement) {
        val client = clientProvider() ?: return
        val result = NativeHarnessCapabilities.mutateSettings(
            client = client,
            namespace = namespace,
            operations = buildJsonArray { add(operation) },
            expectedRevision = revisionProvider(namespace)
        )
        when (result) {
            is HarnessRpcResult.Failure -> reportFailure(result.error.code, result.error.message)
            is HarnessRpcResult.Success -> refresh()
        }
    }

    private suspend fun target(key: String): Target? {
        if (key.isBlank()) {
            reportFailure("SETTINGS_FIELD_INVALID", "The Harness setting key is invalid")
            return null
        }
        val section = stateProvider().schemaSections
            .asSequence()
            .firstOrNull { section -> section.fields.any { it.key == key } }
        if (section == null) {
            reportFailure("SETTINGS_FIELD_UNAVAILABLE", "This Harness setting is not declared by the current schema")
            return null
        }
        val field = section.fields.first { it.key == key }
        val prefix = "${harnessSchemaNamespaceKey(section.title)}."
        if (!key.startsWith(prefix)) {
            reportFailure("SETTINGS_FIELD_INVALID", "The Harness setting key is invalid")
            return null
        }
        val path = when {
            field.path.isNotEmpty() -> field.path
            key == "${prefix}value" -> emptyList()
            else -> harnessSchemaPathFromKey(key.removePrefix(prefix)) ?: run {
                reportFailure("SETTINGS_FIELD_INVALID", "The Harness setting key is invalid")
                return null
            }
        }
        if (!stateProvider().settingsWritable) {
            reportFailure("SETTINGS_READ_ONLY", "Harness settings are read-only in this environment")
            return null
        }
        if (!field.enabled) {
            reportFailure("SETTINGS_FIELD_READ_ONLY", "This Harness setting cannot be edited")
            return null
        }
        return Target(section.title, path, field)
    }

    private data class Target(
        val namespace: String,
        val path: List<String>,
        val field: HarnessSchemaField
    )

    private fun harnessSettingsValue(field: HarnessSchemaField, text: String): JsonElement? = when (field.type) {
        HarnessSchemaFieldType.TOGGLE -> JsonPrimitive(text.equals("true", ignoreCase = true))
        HarnessSchemaFieldType.INTEGER -> parseHarnessInteger(text)
        HarnessSchemaFieldType.DECIMAL -> parseHarnessDecimal(text)
        HarnessSchemaFieldType.JSON -> parseHarnessJsonValue(text)
        else -> JsonPrimitive(text)
    }
}
