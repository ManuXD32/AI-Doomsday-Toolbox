package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import com.example.llamadroid.harness.harnessFriendlyModelLabel
import com.example.llamadroid.harness.HarnessLocalModelCapabilityStore
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.example.llamadroid.ui.components.AppSectionCard

/** Preserves unknown model-row properties while changing only capacity overrides. */
internal fun updateNativeHarnessModelRows(
    existingText: String,
    wireId: String,
    contextText: String,
    outputText: String,
): String? {
    val rows = runCatching { parseHarnessJsonValue(existingText)?.jsonArrayOrNull() }.getOrNull() ?: return null
    var found = false
    val updated = buildJsonArray {
        rows.forEach { element ->
            val row = element.jsonObjectOrNull()
            if (row == null || row.string("id") != wireId) {
                add(element)
                return@forEach
            }
            found = true
            add(buildJsonObject {
                row.forEach { (key, value) ->
                    if (key != "contextWindow" && key != "maxTokens") put(key, value)
                }
                contextText.trim().toLongOrNull()?.takeIf { it > 0L }?.let { put("contextWindow", it) }
                outputText.trim().toLongOrNull()?.takeIf { it > 0L }?.let { put("maxTokens", it) }
            })
        }
    }
    return updated.takeIf { found }?.toString()
}

private data class EditableHarnessModel(
    val providerId: String,
    val providerName: String,
    val fieldKey: String,
    val fieldValue: String,
    val wireId: String,
    val displayName: String,
    val contextWindow: Long?,
    val advertisedContextWindow: Long? = null,
    val maxTokens: Long?,
    val backendLimits: HarnessModelBackendLimitsUi? = null,
    val isAndroidManaged: Boolean = false,
) {
    val isLiteRt: Boolean
        get() = isAndroidManaged && wireId.startsWith("litert:")
}

private fun editableHarnessModels(provider: HarnessProviderUiState): List<EditableHarnessModel> {
    val settingsModels = provider.configs.flatMap { config ->
        config.fields.firstOrNull(::isNativeHarnessProviderModelsField)?.let { field ->
            val rows = runCatching { parseHarnessJsonValue(field.value)?.jsonArrayOrNull() }.getOrNull()
                .orEmpty()
            rows.mapNotNull { element ->
                val row = element.jsonObjectOrNull() ?: return@mapNotNull null
                val id = row.string("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val providerOption = provider.providers.firstOrNull { it.id == config.id }
                val advertised = row.string("name")
                    ?: providerOption?.modelNames?.get(id)
                EditableHarnessModel(
                    providerId = config.id,
                    providerName = config.name,
                    fieldKey = field.key,
                    fieldValue = field.value,
                    wireId = id,
                    displayName = advertised?.takeIf(String::isNotBlank)
                        ?: harnessFriendlyModelLabel(id),
                    contextWindow = row.long("contextWindow")?.takeIf { it > 0L },
                    maxTokens = row.long("maxTokens")?.takeIf { it > 0L },
                )
            }
        }.orEmpty()
    }
    val configuredWireIds = settingsModels.mapTo(mutableSetOf()) { it.wireId }
    val androidModels = provider.providers
        .filter { it.canEdit && it.detail == "Android-managed provider" }
        .flatMap { option ->
            option.models.mapNotNull { wireId ->
                if (!configuredWireIds.add(wireId)) return@mapNotNull null
                EditableHarnessModel(
                    providerId = option.id,
                    providerName = option.name,
                    fieldKey = "",
                    fieldValue = "",
                    wireId = wireId,
                    displayName = option.modelNames[wireId]
                        ?.takeIf(String::isNotBlank)
                        ?: harnessFriendlyModelLabel(wireId),
                    contextWindow = option.modelContextWindows[wireId]
                        ?.takeIf { it > 0L }
                        ?.takeIf {
                            !wireId.startsWith("litert:") ||
                                option.modelCapabilitySources[wireId] == "explicit"
                        },
                    advertisedContextWindow = option.modelAdvertisedContextWindows[wireId]?.takeIf { it > 0L }
                        ?: option.modelContextWindows[wireId]?.takeIf { it > 0L },
                    maxTokens = option.modelMaxOutputTokens[wireId]?.takeIf { it > 0L },
                    backendLimits = option.modelBackendLimits[wireId],
                    isAndroidManaged = true,
                )
            }
        }
    return settingsModels + androidModels
}

@Composable
internal fun HarnessModelEditor(
    provider: HarnessProviderUiState,
    onAction: (NativeHarnessUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val models = remember(provider.configs, provider.providers) { editableHarnessModels(provider) }
    val groupedModels = remember(models) {
        models.groupBy { it.providerId to it.providerName }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            // This editor is embedded as one item in HarnessSettingsNavigation's LazyColumn.
            // The parent owns the page scroll; adding a verticalScroll here makes the lazy item
            // receive an infinite height constraint and crashes on the model settings route.
            .padding(vertical = 4.dp)
            .testTag("harness_model_editor"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.harness_model_editor_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.harness_model_editor_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (models.isEmpty()) {
            Text(
                stringResource(R.string.harness_model_editor_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            groupedModels.forEach { (providerKey, providerModels) ->
                AppSectionCard(shape = com.example.llamadroid.ui.components.AppChromeDefaults.InnerCardShape) {
                    Text(providerKey.second, style = MaterialTheme.typography.titleSmall)
                    Text(
                        providerKey.first,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    providerModels.forEach { model ->
                        HarnessEditableModelRow(model = model, onAction = onAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun HarnessEditableModelRow(
    model: EditableHarnessModel,
    onAction: (NativeHarnessUiAction) -> Unit,
) {
    val context = LocalContext.current
    val capabilityStore = remember(context) { HarnessLocalModelCapabilityStore(context) }
    val savedOverrides = remember(model.wireId) { capabilityStore.get(model.wireId) }
    var contextText by remember(model.providerId, model.wireId, model.contextWindow) {
        mutableStateOf(
            if (model.isLiteRt) savedOverrides?.contextTokens?.toString().orEmpty()
            else model.contextWindow?.toString().orEmpty()
        )
    }
    var outputText by remember(model.providerId, model.wireId, model.maxTokens) {
        mutableStateOf(
            if (model.isLiteRt) savedOverrides?.maxOutputTokens?.toString().orEmpty()
            else model.maxTokens?.toString().orEmpty()
        )
    }
    var mtpOverride by remember(model.wireId) { mutableStateOf(savedOverrides?.mtpEnabled) }
    var thinkingOverride by remember(model.wireId) { mutableStateOf(savedOverrides?.thinkingEnabled) }
    AppSectionCard(shape = com.example.llamadroid.ui.components.AppChromeDefaults.CompactShape) {
        Text(
            model.displayName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(R.string.harness_model_editor_wire_id, model.wireId),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = contextText,
                onValueChange = { contextText = it.filter(Char::isDigit).take(12) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.harness_model_editor_context)) },
                supportingText = {
                    if (model.isLiteRt) {
                        Text(
                            stringResource(
                                R.string.harness_app_tools_litert_context_hint,
                                minOf(model.advertisedContextWindow ?: 16_384L, 16_384L).toInt(),
                                model.advertisedContextWindow?.toInt() ?: 16_384,
                            )
                        )
                    } else if (model.contextWindow == null) {
                        Text(stringResource(R.string.harness_model_editor_unknown))
                    }
                },
                singleLine = true,
            )
            OutlinedTextField(
                value = outputText,
                onValueChange = { outputText = it.filter(Char::isDigit).take(12) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.harness_model_editor_output)) },
                supportingText = {
                    if (model.isLiteRt) {
                        Text(stringResource(
                            R.string.harness_app_tools_litert_output_hint,
                            model.maxTokens ?: 2_048L,
                        ))
                    }
                },
                singleLine = true,
            )
        }
        if (model.isLiteRt) {
            model.backendLimits?.let { limits ->
                val effectiveContext = limits.contextTokens ?: model.contextWindow
                val effectiveOutput = limits.outputTokens ?: model.maxTokens
                if (effectiveContext != null && effectiveOutput != null) {
                    Text(
                        stringResource(
                            R.string.harness_litert_0984_effective_limit,
                            limits.backend?.uppercase().orEmpty(),
                            effectiveContext.toInt(),
                            effectiveOutput.toInt(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (limits.backend == "cpu" && limits.autoChoseCpuForCapacity) {
                    Text(
                        stringResource(R.string.harness_litert_0984_auto_cpu_capacity),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (
                    limits.backend == "gpu" && limits.requestedBackend == "gpu" &&
                    limits.gpuSafetyLimitApplied && effectiveContext != null && effectiveOutput != null
                ) {
                    Text(
                        stringResource(
                            R.string.harness_litert_0984_forced_gpu_warning,
                            effectiveContext.toInt(),
                            effectiveOutput.toInt(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Text(
                stringResource(R.string.harness_litert_0984_model_mtp_setting),
                style = MaterialTheme.typography.labelMedium,
            )
            HarnessLiteRtOverrideChoices(value = mtpOverride, onChange = { mtpOverride = it })
            Text(
                stringResource(R.string.harness_litert_0984_model_thinking_setting),
                style = MaterialTheme.typography.labelMedium,
            )
            HarnessLiteRtOverrideChoices(value = thinkingOverride, onChange = { thinkingOverride = it })
        }
        OutlinedButton(
            onClick = {
                if (model.isAndroidManaged) {
                    onAction(
                        NativeHarnessUiAction.UpdateLocalModelCapability(
                            wireId = model.wireId,
                            contextTokens = contextText.trim().toLongOrNull()?.takeIf { it > 0L },
                            maxOutputTokens = outputText.trim().toLongOrNull()?.takeIf { it > 0L },
                            mtpEnabled = mtpOverride.takeIf { model.isLiteRt },
                            thinkingEnabled = thinkingOverride.takeIf { model.isLiteRt },
                        )
                    )
                } else {
                    updateNativeHarnessModelRows(
                        model.fieldValue,
                        model.wireId,
                        contextText,
                        outputText,
                    )?.let { updated ->
                        onAction(NativeHarnessUiAction.UpdateProviderField(model.providerId, model.fieldKey, updated))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Save, contentDescription = null)
            Text(stringResource(R.string.harness_model_editor_save))
        }
    }
}

@Composable
private fun HarnessLiteRtOverrideChoices(
    value: Boolean?,
    onChange: (Boolean?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            null to R.string.harness_litert_0984_use_global,
            true to R.string.harness_litert_0984_enabled,
            false to R.string.harness_litert_0984_disabled,
        ).forEach { (option, label) ->
            FilterChip(
                selected = value == option,
                onClick = { onChange(option) },
                label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
internal fun HarnessModelEditorDialog(
    provider: HarnessProviderUiState,
    onAction: (NativeHarnessUiAction) -> Unit,
) {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val dialogBodyMaxHeight = (windowHeight - 176.dp).coerceAtLeast(96.dp)
    Dialog(
        onDismissRequest = { onAction(NativeHarnessUiAction.CloseModelManager) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.harness_model_editor_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    IconButton(onClick = { onAction(NativeHarnessUiAction.CloseModelManager) }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.harness_model_editor_close))
                    }
                }
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = dialogBodyMaxHeight)
                        .imePadding(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = minOf(maxHeight, dialogBodyMaxHeight))
                            .verticalScroll(rememberScrollState()),
                    ) {
                        HarnessModelEditor(provider = provider, onAction = onAction)
                    }
                }
            }
        }
    }
}
