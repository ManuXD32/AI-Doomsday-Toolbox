package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.harness.HarnessProviderPreset
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppAdvancedSection
import com.example.llamadroid.ui.components.AppSectionCard

/** Native equivalent of the official custom `llm-pi-ai` provider card. */
@Composable
fun NativeHarnessCustomProviderPanel(
    existingRoutes: Set<String>,
    enabled: Boolean,
    onCreate: (NativeHarnessCustomProviderRequest) -> Unit,
    onDiscover: (NativeHarnessCustomProviderRequest) -> Unit,
    onDismissDiscovery: () -> Unit,
    modifier: Modifier = Modifier,
    protocols: List<String> = NATIVE_CUSTOM_PROVIDER_PROTOCOLS,
    discovery: HarnessProviderDiscoveryUi = HarnessProviderDiscoveryUi(),
) {
    var routeOverride by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    // Keep the secret out of the saved-instance Bundle; credentials are written
    // only through the canonical Harness credential operation.
    var apiKey by remember { mutableStateOf("") }
    var selectedPresetId by rememberSaveable {
        mutableStateOf(NATIVE_CUSTOM_PROVIDER_PRESETS.first().id)
    }
    var selectedApi by rememberSaveable(protocols) { mutableStateOf(protocols.firstOrNull().orEmpty()) }
    var presetMenuOpen by rememberSaveable { mutableStateOf(false) }
    var protocolMenuOpen by rememberSaveable { mutableStateOf(false) }
    var models by rememberSaveable(stateSaver = nativeHarnessCustomProviderModelsSaver) {
        mutableStateOf(emptyList())
    }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var submittedRoute by remember { mutableStateOf<String?>(null) }

    val selectedPreset = NATIVE_CUSTOM_PROVIDER_PRESETS.firstOrNull { it.id == selectedPresetId }
        ?: NATIVE_CUSTOM_PROVIDER_PRESETS.first()
    LaunchedEffect(selectedPresetId, protocols) {
        selectedApi = nativeHarnessPresetProtocol(selectedPreset, protocols)
    }
    LaunchedEffect(existingRoutes, submittedRoute) {
        if (submittedRoute != null && submittedRoute in existingRoutes) {
            // The settings refresh is the success signal. Clear the transient
            // draft and secret so a second tap cannot create the same route.
            submittedRoute = null
            routeOverride = ""
            displayName = ""
            baseUrl = ""
            apiKey = ""
            models = emptyList()
            attempted = false
        }
    }
    val generatedRoute = nextNativeHarnessProviderRoute(selectedPreset, displayName, existingRoutes)
    val request = NativeHarnessCustomProviderRequest(
        route = routeOverride.trim().ifEmpty { generatedRoute },
        displayName = displayName,
        api = selectedApi,
        baseUrl = baseUrl,
        apiKey = apiKey,
        models = models.map { draft -> draft.toRequest() }
    )
    val validation = validateNativeCustomProviderRequest(request, existingRoutes)
    val discoveryValidation = validateNativeCustomProviderDiscoveryRequest(request)
    val hasInput = routeOverride.isNotBlank() || displayName.isNotBlank() || baseUrl.isNotBlank() ||
        apiKey.isNotBlank() || models.any { it.id.isNotBlank() || it.name.isNotBlank() }
    val shownError = if (attempted || hasInput) validation else null

    AppSectionCard(
        modifier = modifier,
        shape = AppChromeDefaults.CompactShape,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    ) {
        Text(
            stringResource(R.string.harness_custom_provider_title),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            stringResource(R.string.harness_custom_provider_description),
            style = MaterialTheme.typography.bodySmall
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // The Settings destination already owns a LazyColumn. Keeping a second
                // bounded scroll owner here clips advanced controls on compact screens and
                // prevents pointer clicks from reaching visible discovery actions.
                .testTag("harness_custom_provider_form"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                stringResource(R.string.harness_custom_provider_preset),
                style = MaterialTheme.typography.labelLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { presetMenuOpen = true },
                    enabled = enabled
                ) {
                    Text(stringResource(nativeHarnessPresetResource(selectedPreset)))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(
                    expanded = presetMenuOpen,
                    onDismissRequest = { presetMenuOpen = false }
                ) {
                    NATIVE_CUSTOM_PROVIDER_PRESETS.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(stringResource(nativeHarnessPresetResource(preset))) },
                            onClick = {
                                selectedPresetId = preset.id
                                routeOverride = ""
                                presetMenuOpen = false
                            }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it.take(160) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.harness_custom_provider_display_name)) },
                singleLine = true,
                enabled = enabled
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it.take(512) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.harness_custom_provider_base_url)) },
                placeholder = { Text(stringResource(R.string.harness_custom_provider_base_url_hint)) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it.take(16_384) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.harness_custom_provider_api_key)) },
                supportingText = { Text(stringResource(R.string.harness_custom_provider_api_key_hint)) },
                singleLine = true,
                enabled = enabled,
                visualTransformation = PasswordVisualTransformation()
            )
            Text(
                stringResource(R.string.harness_custom_provider_draft_discovery_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { onDiscover(request) },
                enabled = enabled && discoveryValidation == null && !discovery.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.harness_discover_models))
            }
            NativeHarnessCustomProviderDiscoveryPanel(
                discovery = discovery,
                enabled = enabled,
                onAdd = { selected ->
                    val existingIds = models.map { it.id.trim() }.toSet()
                    models = models + selected
                        .filter { it.id !in existingIds }
                        .map { candidate ->
                            NativeHarnessCustomProviderModelDraft(
                                id = candidate.id,
                                name = candidate.name.orEmpty(),
                                contextWindow = candidate.contextWindow?.toString().orEmpty(),
                                maxTokens = candidate.maxTokens?.toString().orEmpty(),
                            )
                        }
                    onDismissDiscovery()
                },
                onDismiss = onDismissDiscovery,
            )
            AppAdvancedSection(
                title = stringResource(R.string.harness_custom_provider_advanced),
                modifier = Modifier.testTag("harness_custom_provider_advanced"),
                initiallyExpanded = false
            ) {
                OutlinedTextField(
                    value = routeOverride,
                    onValueChange = { routeOverride = it.take(96) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.harness_custom_provider_route)) },
                    supportingText = {
                        Text(
                            if (routeOverride.isBlank()) {
                                stringResource(R.string.harness_custom_provider_route_generated_hint, generatedRoute)
                            } else stringResource(R.string.harness_custom_provider_route_hint)
                        )
                    },
                    singleLine = true,
                    enabled = enabled
                )
                Column {
                    Text(
                        stringResource(R.string.harness_custom_provider_api_protocol),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { protocolMenuOpen = true },
                            enabled = enabled && protocols.isNotEmpty()
                        ) {
                            Text(
                                selectedApi.takeIf(String::isNotBlank)?.let {
                                    stringResource(nativeCustomProviderProtocolResource(it))
                                } ?: stringResource(R.string.harness_custom_provider_api_unavailable)
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = protocolMenuOpen,
                            onDismissRequest = { protocolMenuOpen = false }
                        ) {
                            protocols.forEach { protocol ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(nativeCustomProviderProtocolResource(protocol))) },
                                    onClick = {
                                        selectedApi = protocol
                                        protocolMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.harness_custom_provider_models_optional),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    stringResource(R.string.harness_custom_provider_discover_before_save),
                    style = MaterialTheme.typography.bodySmall
                )
                if (models.isEmpty()) {
                    Text(
                        stringResource(R.string.harness_custom_provider_no_models),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                models.forEachIndexed { index, model ->
                    NativeHarnessCustomProviderModelFields(
                        index = index,
                        model = model,
                        enabled = enabled,
                        canRemove = true,
                        onChange = { next -> models = models.mapIndexed { at, old -> if (at == index) next else old } },
                        onRemove = { models = models.filterIndexed { at, _ -> at != index } }
                    )
                }
                OutlinedButton(
                    onClick = { models = models + NativeHarnessCustomProviderModelDraft() },
                    enabled = enabled && models.size < 32,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.harness_custom_provider_add_model))
                }
            }
            shownError?.let { error ->
                Text(
                    stringResource(nativeCustomProviderErrorResource(error.code)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Button(
                onClick = {
                    attempted = true
                    if (validation == null) {
                        submittedRoute = request.route
                        onCreate(request)
                    }
                },
                enabled = enabled && submittedRoute == null && validation == null && protocols.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.harness_custom_provider_create))
            }
        }
    }
}

/** Candidate picker used while a provider draft is still unsaved. */
@Composable
private fun NativeHarnessCustomProviderDiscoveryPanel(
    discovery: HarnessProviderDiscoveryUi,
    enabled: Boolean,
    onAdd: (List<HarnessDiscoveredModelUi>) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!discovery.isLoading && !discovery.hasRun) return
    var query by remember(discovery.candidates) { mutableStateOf("") }
    val selectedIds = remember(discovery.candidates, discovery.selectedIds) {
        mutableStateOf(discovery.selectedIds)
    }
    val visible = discovery.candidates.filter { candidate ->
        val filter = query.trim()
        filter.isEmpty() || candidate.id.contains(filter, ignoreCase = true) ||
            candidate.name?.contains(filter, ignoreCase = true) == true
    }
    val discoveryErrorResource = discovery.errorCode?.let(::localizedHarnessNoticeMessage)
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.harness_provider_discovery_title), style = MaterialTheme.typography.titleSmall)
        when {
            discovery.isLoading -> Text(stringResource(R.string.harness_provider_discovery_loading))
            discovery.errorCode != null -> Text(
                stringResource(
                    discoveryErrorResource ?: R.string.harness_provider_discovery_error,
                ),
                color = MaterialTheme.colorScheme.error,
            )
            discovery.candidates.isEmpty() -> Text(stringResource(R.string.harness_provider_discovery_empty))
            else -> {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(128) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.harness_provider_discovery_search)) },
                    singleLine = true,
                    enabled = enabled,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(visible, key = { it.id }) { candidate ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Checkbox(
                                checked = candidate.id in selectedIds.value,
                                onCheckedChange = { checked ->
                                    selectedIds.value = selectedIds.value.toMutableSet().apply {
                                        if (checked) add(candidate.id) else remove(candidate.id)
                                    }
                                },
                                enabled = enabled,
                            )
                            Column(modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                                Text(candidate.name?.takeIf(String::isNotBlank) ?: candidate.id)
                                Text(
                                    stringResource(
                                        R.string.harness_provider_discovery_metadata,
                                        candidate.contextWindow?.toString()
                                            ?: stringResource(R.string.harness_provider_discovery_unknown),
                                        candidate.maxTokens?.toString()
                                            ?: stringResource(R.string.harness_provider_discovery_unknown),
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                Button(
                    onClick = {
                        onAdd(discovery.candidates.filter { it.id in selectedIds.value })
                    },
                    enabled = enabled && selectedIds.value.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.harness_provider_discovery_add))
                }
            }
        }
        if (!discovery.isLoading) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.harness_provider_discovery_close))
            }
        }
    }
}

@Composable
private fun NativeHarnessCustomProviderModelFields(
    index: Int,
    model: NativeHarnessCustomProviderModelDraft,
    enabled: Boolean,
    canRemove: Boolean,
    onChange: (NativeHarnessCustomProviderModelDraft) -> Unit,
    onRemove: () -> Unit
) {
    AppSectionCard(shape = AppChromeDefaults.CompactShape) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.harness_custom_provider_model_number, index + 1),
                style = MaterialTheme.typography.labelLarge
            )
            IconButton(onClick = onRemove, enabled = enabled && canRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.harness_custom_provider_remove_model)
                )
            }
        }
        OutlinedTextField(
            value = model.id,
            onValueChange = { onChange(model.copy(id = it.take(160))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.harness_custom_provider_model_id)) },
            singleLine = true,
            enabled = enabled
        )
        OutlinedTextField(
            value = model.name,
            onValueChange = { onChange(model.copy(name = it.take(160))) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.harness_custom_provider_model_name)) },
            singleLine = true,
            enabled = enabled
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = model.contextWindow,
                onValueChange = { onChange(model.copy(contextWindow = it.take(12))) },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.harness_custom_provider_context_window)) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            OutlinedTextField(
                value = model.maxTokens,
                onValueChange = { onChange(model.copy(maxTokens = it.take(12))) },
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.harness_custom_provider_max_tokens)) },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    }
}

private data class NativeHarnessCustomProviderModelDraft(
    val id: String = "",
    val name: String = "",
    val contextWindow: String = "",
    val maxTokens: String = ""
) {
    fun toRequest(): NativeHarnessCustomProviderModel = NativeHarnessCustomProviderModel(
        id = id,
        name = name,
        contextWindow = parseCapacityInput(contextWindow),
        maxTokens = parseCapacityInput(maxTokens)
    )

    private fun parseCapacityInput(value: String): Int? = when {
        value.isBlank() -> null
        else -> value.toIntOrNull() ?: -1
    }
}

private val nativeHarnessCustomProviderModelsSaver = listSaver<
    List<NativeHarnessCustomProviderModelDraft>,
    String
>(
    save = { models -> models.flatMap { listOf(it.id, it.name, it.contextWindow, it.maxTokens) } },
    restore = { values ->
        values.chunked(4).map { fields ->
            NativeHarnessCustomProviderModelDraft(
                id = fields.getOrElse(0) { "" },
                name = fields.getOrElse(1) { "" },
                contextWindow = fields.getOrElse(2) { "" },
                maxTokens = fields.getOrElse(3) { "" },
            )
        }
    },
)

private fun nativeHarnessPresetResource(preset: HarnessProviderPreset): Int = when (preset) {
    HarnessProviderPreset.REMOTE_LLAMA_CPP -> R.string.harness_custom_provider_preset_remote_llama_cpp
    HarnessProviderPreset.LLAMA_SWAP -> R.string.harness_custom_provider_preset_llama_swap
    HarnessProviderPreset.GENERIC_OPENAI -> R.string.harness_custom_provider_preset_generic_openai
    HarnessProviderPreset.ADT_MANAGED -> error("ADT managed is not a custom-provider preset")
}

private fun nativeCustomProviderErrorResource(code: String): Int = when (code) {
    "CUSTOM_PROVIDER_ROUTE_INVALID" -> R.string.harness_custom_provider_route_invalid
    "CUSTOM_PROVIDER_ROUTE_TAKEN" -> R.string.harness_custom_provider_route_taken
    "CUSTOM_PROVIDER_PROTOCOL_INVALID" -> R.string.harness_custom_provider_api_invalid
    "CUSTOM_PROVIDER_BASE_URL_INVALID" -> R.string.harness_custom_provider_base_url_invalid
    "CUSTOM_PROVIDER_MODELS_REQUIRED" -> R.string.harness_custom_provider_models_required
    "CUSTOM_PROVIDER_KEY_INVALID" -> R.string.harness_custom_provider_api_key_invalid
    else -> R.string.harness_custom_provider_model_invalid
}

private fun nativeCustomProviderProtocolResource(protocol: String): Int = when (protocol) {
    "openai-completions" -> R.string.harness_custom_provider_api_openai_completions
    "openai-responses" -> R.string.harness_custom_provider_api_openai_responses
    "anthropic-messages" -> R.string.harness_custom_provider_api_anthropic_messages
    else -> R.string.harness_custom_provider_api_unavailable
}
