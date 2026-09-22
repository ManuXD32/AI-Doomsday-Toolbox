package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/** Parsed model catalog data retained by the native settings surface. */
internal data class HarnessModelCatalogUiResult(
    val providers: List<HarnessProviderOption>,
    val failures: List<HarnessModelCatalogFailureUi>,
    val routableProviders: Set<String>,
    val defaultProvider: String?,
    val defaultModel: String?,
    val defaultReasoningEffort: String?
)

internal fun parseHarnessModelCatalog(value: JsonObject): HarnessModelCatalogUiResult {
    val routable = value.stringArray("routableProviders").toSet()
    val providers = value.objectArray("groups").mapNotNull group@{ group ->
        val id = group.string("id") ?: return@group null
        val models = group.objectArray("models")
        val modelIds = models.mapNotNull { it.string("id") ?: it.string("name") }
        val modelContextWindows = models.associate { model ->
            val modelId = model.string("id") ?: model.string("name").orEmpty()
            modelId to model.positiveLong(
                "contextWindow",
                "context_window",
                "context_length",
                "contextTokens",
                "context_tokens",
                "n_ctx"
            )?.takeIf { it > 0L }
        }
        val modelMaxOutputTokens = models.associate { model ->
            val modelId = model.string("id") ?: model.string("name").orEmpty()
            modelId to model.positiveLong(
                "maxOutputTokens",
                "max_output_tokens",
                "maxTokens",
                "max_tokens",
                "maxCompletionTokens",
                "max_completion_tokens",
                "n_predict"
            )?.takeIf { it > 0L }
        }
        val modelCapabilitySources = models.associate { model ->
            val modelId = model.string("id") ?: model.string("name").orEmpty()
            val context = modelContextWindows[modelId]
            modelId to (model.string("capabilitySource")
                ?: model.string("contextSource")
                ?: if (context != null) "detected" else "unknown")
        }
        val reasoning = models.mapNotNull model@{ model ->
            val modelId = model.string("id") ?: return@model null
            val metadata = model.objectValue("reasoning") ?: return@model null
            modelId to metadata.objectArray("efforts").mapNotNull effort@{ item ->
                val effortId = item.string("id") ?: return@effort null
                HarnessReasoningEffortUi(
                    id = effortId,
                    name = item.string("name") ?: effortId,
                    description = item.string("description")
                )
            }
        }.toMap()
        val defaults = models.mapNotNull model@{ model ->
            val modelId = model.string("id") ?: return@model null
            val effort = model.objectValue("reasoning")?.string("defaultEffort")
                ?: return@model null
            modelId to effort
        }.toMap()
        HarnessProviderOption(
            id = id,
            name = group.string("name") ?: id,
            models = modelIds,
            modelNames = models.mapNotNull { model ->
                val modelId = model.string("id") ?: model.string("name") ?: return@mapNotNull null
                modelId to (model.string("name") ?: modelId)
            }.toMap(),
            reasoningModels = reasoning.keys,
            reasoningDefaults = defaults,
            reasoningEfforts = reasoning,
            detail = group.string("description"),
            configured = id in routable,
            modelContextWindows = modelContextWindows,
            modelMaxOutputTokens = modelMaxOutputTokens,
            modelCapabilitySources = modelCapabilitySources
        )
    }
    val failures = value.objectArray("failures").mapNotNull failure@{ row ->
        val id = row.string("id") ?: return@failure null
        HarnessModelCatalogFailureUi(
            providerId = id,
            providerName = row.string("name") ?: id,
            message = row.string("message").orEmpty()
        )
    }
    val default = value.objectValue("default")
    return HarnessModelCatalogUiResult(
        providers = providers,
        failures = failures,
        routableProviders = routable,
        defaultProvider = default?.string("provider"),
        defaultModel = default?.string("model"),
        defaultReasoningEffort = default?.string("reasoningEffort")
    )
}

/**
 * Apply model limits stored in the provider profile after the runtime catalog
 * has been read. Profile values are explicit user overrides and therefore win
 * over endpoint metadata while retaining the model's exact wire ID.
 */
internal fun mergeHarnessSavedModelCapabilities(
    providers: List<HarnessProviderOption>,
    configs: List<HarnessProviderConfigUi>
): List<HarnessProviderOption> {
    val overridesByProvider = configs.associate { config ->
        config.id to config.fields
            .firstOrNull(::isNativeHarnessProviderModelsField)
            ?.value
            ?.let(::parseHarnessJsonValue)
            ?.jsonArrayOrNull()
            ?.mapNotNull { row ->
                val model = row.jsonObjectOrNull() ?: return@mapNotNull null
                val id = model.string("id") ?: return@mapNotNull null
                id to model
            }
            ?.toMap()
            .orEmpty()
    }
    return providers.map { provider ->
        val overrides = overridesByProvider[provider.id].orEmpty()
        if (overrides.isEmpty()) return@map provider
        val context = provider.modelContextWindows.toMutableMap()
        val output = provider.modelMaxOutputTokens.toMutableMap()
        val sources = provider.modelCapabilitySources.toMutableMap()
        val names = provider.modelNames.toMutableMap()
        provider.models.forEach { modelId ->
            val row = overrides[modelId] ?: return@forEach
            row.positiveLong(
                "contextWindow", "context_window", "context_length",
                "contextTokens", "context_tokens", "n_ctx"
            )?.takeIf { it > 0L }?.let {
                context[modelId] = it
                sources[modelId] = "explicit"
            }
            row.positiveLong(
                "maxOutputTokens", "max_output_tokens", "maxTokens",
                "max_tokens", "maxCompletionTokens", "max_completion_tokens",
                "n_predict"
            )?.takeIf { it > 0L }?.let { output[modelId] = it }
            row.string("name")?.takeIf { it.isNotBlank() }?.let { names[modelId] = it }
        }
        provider.copy(
            modelNames = names,
            modelContextWindows = context,
            modelMaxOutputTokens = output,
            modelCapabilitySources = sources
        )
    }
}

internal fun harnessModelContextKnown(provider: HarnessProviderOption, modelId: String): Boolean =
    provider.modelContextWindows[modelId]?.let { it > 0L } == true &&
        provider.modelCapabilitySources[modelId] != "unknown"

private fun JsonObject.positiveLong(vararg keys: String): Long? = keys
    .asSequence()
    .mapNotNull { key -> long(key)?.takeIf { it > 0L } }
    .firstOrNull()

/** Displays the provider's canonical name while keeping the submitted value as its ID. */
internal fun harnessModelOptionLabel(provider: HarnessProviderOption, modelId: String): String {
    val label = provider.modelNames[modelId].orEmpty().ifBlank { modelId }
    val duplicateLabel = provider.modelNames.values.count { it == label } > 1
    val disambiguator = Integer.toUnsignedString(modelId.hashCode(), 16)
        .padStart(8, '0')
        .takeLast(6)
    return if (duplicateLabel && label != modelId) "$label · $disambiguator" else label
}

/** The official plugin manager returns a ChangeResult envelope on RPC success. */
internal data class HarnessPluginInstallOutcome(
    val application: String?,
    val errorCode: String?,
    val pendingBuilds: List<String>,
    val approvedBuilds: List<String>,
    val bundle: String?,
    val packageOutput: String?,
    val packageKind: String?,
    val restartRequired: Boolean
)

internal fun parseHarnessPluginInstallOutcome(value: JsonObject): HarnessPluginInstallOutcome {
    val application = value.string("application")
    val error = value.objectValue("error")
    val packageResult = value.objectValue("packageResult")
    return HarnessPluginInstallOutcome(
        application = application,
        errorCode = error?.string("code") ?: packageResult?.string("kind"),
        pendingBuilds = value.stringArray("pendingBuilds"),
        approvedBuilds = value.stringArray("approvedBuilds"),
        bundle = value.string("bundle"),
        packageOutput = packageResult?.string("output")?.takeLast(4000),
        packageKind = packageResult?.string("kind"),
        restartRequired = application == "restart-required"
    )
}

private const val UNADDRESSABLE_ENTRY_ID = ""
internal const val HARNESS_PLUGIN_UNADDRESSABLE_REASON = "unaddressable"
internal const val HARNESS_PLUGIN_PRESET_REASON = "preset-composition"

/**
 * Parse both the live Loader entries and the optional preset composition rows
 * returned by the pinned `pluginInventory/list` Remote. Preset rows are a
 * read-only projection even when a live mount happens to expose an entry id;
 * keeping an empty id prevents the native mutation path from treating that
 * projection as a profile patch target.
 */
internal fun parseHarnessPluginInventory(value: kotlinx.serialization.json.JsonElement): List<HarnessPluginInventoryUi> {
    val rows = value.objectArrayOrSelf().mapNotNull { row ->
        parseHarnessPluginInventoryRow(row)
    }.toMutableList()
    val snapshot = value.jsonObjectOrNull()
    snapshot?.objectArray("agentPresets")?.forEach { preset ->
        rows += preset.objectArray("rows").mapNotNull { row ->
            parseHarnessPluginInventoryRow(
                row = row,
                forceReadOnly = true,
                readOnlyReason = HARNESS_PLUGIN_PRESET_REASON
            )
        }
    }
    return rows
}

private fun parseHarnessPluginInventoryRow(
    row: JsonObject,
    forceReadOnly: Boolean = false,
    readOnlyReason: String? = null
): HarnessPluginInventoryUi? {
    val liveEntryId = row.string("entryId")?.takeIf { it.isNotBlank() }
    val patchId = row.string("patchId") ?: row.string("id")
    val moduleName = row.string("moduleName")
        ?: row.string("name")
        ?: patchId
        ?: liveEntryId
        ?: return null
    val enabledValue = row["enabled"]?.jsonPrimitiveOrNull()?.contentOrNull
    val conditional = enabledValue == "conditional"
    return HarnessPluginInventoryUi(
        entryId = if (forceReadOnly) UNADDRESSABLE_ENTRY_ID else liveEntryId ?: UNADDRESSABLE_ENTRY_ID,
        moduleName = moduleName,
        enabled = row.boolean("enabled").orDefault(false),
        phase = row.string("fiberPhase") ?: row.string("phase")
            ?: if (conditional) "conditional" else null,
        patchId = patchId ?: liveEntryId,
        readOnlyReason = row.string("readOnlyReason")
            ?: readOnlyReason
            ?: if (liveEntryId == null || forceReadOnly) HARNESS_PLUGIN_UNADDRESSABLE_REASON else null
    )
}

/** Enrich live rows with manager metadata without dropping read-only projections. */
internal fun mergeHarnessPluginInventory(
    snapshot: List<HarnessPluginInventoryUi>,
    managed: List<HarnessPluginInventoryUi>
): List<HarnessPluginInventoryUi> {
    val managedByEntry = managed
        .filter { it.entryId.isNotBlank() }
        .associateBy { it.entryId }
    val result = snapshot.map { row ->
        if (row.entryId.isBlank()) row else managedByEntry[row.entryId] ?: row
    }.toMutableList()
    managed.forEach { row ->
        val alreadyPresent = if (row.entryId.isBlank()) {
            result.any {
                it.entryId.isBlank() && it.moduleName == row.moduleName && it.patchId == row.patchId
            }
        } else {
            result.any { it.entryId == row.entryId }
        }
        if (!alreadyPresent) result += row
    }
    return result
}
