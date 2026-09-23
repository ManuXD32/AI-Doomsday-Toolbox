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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llamadroid.R
import com.example.llamadroid.harness.harnessFriendlyModelLabel
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
    val maxTokens: Long?,
    val isAndroidManaged: Boolean = false,
)

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
                    contextWindow = option.modelContextWindows[wireId]?.takeIf { it > 0L },
                    maxTokens = option.modelMaxOutputTokens[wireId]?.takeIf { it > 0L },
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
    var contextText by remember(model.providerId, model.wireId, model.contextWindow) {
        mutableStateOf(model.contextWindow?.toString().orEmpty())
    }
    var outputText by remember(model.providerId, model.wireId, model.maxTokens) {
        mutableStateOf(model.maxTokens?.toString().orEmpty())
    }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = contextText,
                onValueChange = { contextText = it.filter(Char::isDigit).take(12) },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.harness_model_editor_context)) },
                supportingText = {
                    if (model.contextWindow == null) Text(stringResource(R.string.harness_model_editor_unknown))
                },
                singleLine = true,
            )
            OutlinedTextField(
                value = outputText,
                onValueChange = { outputText = it.filter(Char::isDigit).take(12) },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.harness_model_editor_output)) },
                singleLine = true,
            )
        }
        OutlinedButton(
            onClick = {
                if (model.isAndroidManaged) {
                    onAction(
                        NativeHarnessUiAction.UpdateLocalModelCapability(
                            wireId = model.wireId,
                            contextTokens = contextText.trim().toLongOrNull()?.takeIf { it > 0L },
                            maxOutputTokens = outputText.trim().toLongOrNull()?.takeIf { it > 0L },
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
