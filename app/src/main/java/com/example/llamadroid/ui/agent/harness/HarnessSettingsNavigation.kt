package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.components.AppStatePanel

private const val SETTINGS_ROOT = ""
private const val SETTINGS_PROVIDERS = "providers"
private const val SETTINGS_MODEL = "model"
private const val SETTINGS_GENERAL = "general"
private const val SETTINGS_ADVANCED = "advanced"
private const val SETTINGS_DOCUMENT = "document"
private const val SETTINGS_CAPABILITIES = "capabilities"
private const val SETTINGS_PERMISSIONS = "permissions"
private const val SETTINGS_PRESETS = "presets"
private const val SETTINGS_WORKSPACE = "workspace"
private const val SETTINGS_SCHEMA_PREFIX = "schema:"
private const val SETTINGS_CAPABILITY_PREFIX = "capability:"
private const val SETTINGS_PROVIDER_PREFIX = "provider:"
private const val SETTINGS_ADD_PROVIDER = "provider:add"

private data class HarnessSettingsEntry(
    val key: String,
    val title: String,
    val description: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val searchText: String = ""
)

private fun harnessStableDestinationPart(value: String): String = value
    .trim()
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')
    .ifBlank { "unnamed" }

private fun harnessSchemaDestination(section: HarnessSchemaSection): String =
    SETTINGS_SCHEMA_PREFIX + harnessStableDestinationPart(
        section.title + ":" + section.fields.map { it.key }.sorted().joinToString("|")
    )

private fun harnessCapabilityDestination(section: HarnessCapabilitySectionUi): String =
    SETTINGS_CAPABILITY_PREFIX + harnessStableDestinationPart(
        section.title + ":" + section.actions.map { it.id }.sorted().joinToString("|")
    )

@Composable
private fun HarnessSettingsLoadPanel(
    load: HarnessManagementLoadUi?,
    onAction: (NativeHarnessUiAction) -> Unit,
    tag: String
) {
    val errorCode = load?.errorCode
    if (load?.isLoading != true && errorCode == null) return
    AppStatePanel(
        kind = if (errorCode == null) AppStateKind.Running else AppStateKind.Error,
        title = stringResource(
            if (errorCode == null) {
                R.string.harness_settings_loading
            } else {
                R.string.harness_settings_load_error
            }
        ),
        message = errorCode?.let {
            stringResource(R.string.harness_settings_load_error_detail, it)
        } ?: stringResource(R.string.harness_settings_loading_description),
        actionLabel = if (errorCode != null) stringResource(R.string.harness_settings_retry) else null,
        onAction = if (errorCode != null) {
            {
                onAction(
                    NativeHarnessUiAction.LoadManagement(
                        HarnessManagementArea.SETTINGS,
                        force = true
                    )
                )
            }
        } else {
            null
        },
        modifier = Modifier.testTag(tag)
    )
}

/**
 * Searchable settings navigator. The menu is the only content composed until
 * the user chooses a destination; namespace and capability lists drill down to
 * one editor at a time so large Harness schemas do not block the first frame.
 */
@Composable
internal fun HarnessSettingsNavigator(
    state: NativeHarnessUiState,
    onAction: (NativeHarnessUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var destination by rememberSaveable { mutableStateOf(SETTINGS_ROOT) }
    var query by rememberSaveable { mutableStateOf("") }
    val stateHolder = rememberSaveableStateHolder()
    val providerSearchText = remember(state.provider.providers, state.provider.configs) {
        (state.provider.providers.flatMap { provider ->
            listOf(provider.id, provider.name) + provider.models + provider.modelNames.values
        } + state.provider.configs.map { it.id + " " + it.name }).joinToString(" ")
    }
    val modelSearchText = remember(state.provider.providers, state.provider.selectedModel) {
        (state.provider.selectedModel.orEmpty() + " " +
            state.provider.providers.flatMap { provider ->
                provider.models + provider.modelNames.values
            }.joinToString(" ")).trim()
    }

    val topEntries = listOf(
        HarnessSettingsEntry(
            SETTINGS_PROVIDERS,
            stringResource(R.string.harness_settings_category_providers),
            stringResource(R.string.harness_settings_category_providers_description),
            Icons.Default.Cloud,
            searchText = providerSearchText
        ),
        HarnessSettingsEntry(
            SETTINGS_MODEL,
            stringResource(R.string.harness_settings_category_model),
            stringResource(R.string.harness_settings_category_model_description),
            Icons.Default.Settings,
            searchText = modelSearchText
        ),
        HarnessSettingsEntry(
            SETTINGS_GENERAL,
            stringResource(R.string.harness_settings_category_general),
            stringResource(R.string.harness_settings_category_general_description),
            Icons.Default.Tune
        ),
        HarnessSettingsEntry(
            SETTINGS_ADVANCED,
            stringResource(R.string.harness_settings_category_advanced),
            stringResource(R.string.harness_settings_category_advanced_description),
            Icons.Default.Lock
        ),
        HarnessSettingsEntry(
            SETTINGS_DOCUMENT,
            stringResource(R.string.harness_settings_category_document),
            stringResource(R.string.harness_settings_category_document_description),
            Icons.Default.Description
        ),
        HarnessSettingsEntry(
            SETTINGS_CAPABILITIES,
            stringResource(R.string.harness_settings_category_capabilities),
            stringResource(R.string.harness_settings_category_capabilities_description),
            Icons.Default.Tune
        )
    )
    val advancedEntries = listOf(
        HarnessSettingsEntry(
            SETTINGS_PERMISSIONS,
            stringResource(R.string.harness_settings_advanced_permissions),
            stringResource(R.string.harness_settings_advanced_permissions_description),
            Icons.Default.Lock
        ),
        HarnessSettingsEntry(
            SETTINGS_PRESETS,
            stringResource(R.string.harness_settings_advanced_presets),
            stringResource(R.string.harness_settings_advanced_presets_description),
            Icons.Default.Settings
        ),
        HarnessSettingsEntry(
            SETTINGS_WORKSPACE,
            stringResource(R.string.harness_settings_advanced_workspace),
            stringResource(R.string.harness_settings_advanced_workspace_description),
            Icons.Default.Folder
        )
    )
    val namespaceFallback = stringResource(R.string.harness_settings_namespace_description)
    val schemaEntries = remember(state.schemaSections, namespaceFallback) {
        state.schemaSections.map { section ->
            HarnessSettingsEntry(
                key = harnessSchemaDestination(section),
                title = section.title,
                description = section.description ?: namespaceFallback,
                icon = Icons.Default.Tune
            )
        }
    }
    val capabilityFallback = stringResource(R.string.harness_settings_capability_description)
    val capabilityEntries = remember(state.capabilitySections, capabilityFallback) {
        state.capabilitySections.map { section ->
            HarnessSettingsEntry(
                key = harnessCapabilityDestination(section),
                title = section.title,
                description = section.description ?: capabilityFallback,
                icon = Icons.Default.Tune
            )
        }
    }

    if (destination == SETTINGS_ADD_PROVIDER) {
        HarnessAddProviderViewport(
            state = state,
            onAction = onAction,
            onBack = { destination = SETTINGS_PROVIDERS },
            stateHolder = stateHolder,
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("harness_settings_scroll"),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        state.notice?.let { notice ->
            item(key = "settings-notice") {
                HarnessNoticeBanner(notice = notice, onAction = onAction)
            }
        }
        if (destination == SETTINGS_ROOT) {
            val settingsLoad = state.managementLoads[HarnessManagementArea.SETTINGS]
            if (settingsLoad?.isLoading == true || settingsLoad?.errorCode != null) {
                item(key = "settings-load-state") {
                    HarnessSettingsLoadPanel(
                        load = settingsLoad,
                        onAction = onAction,
                        tag = "harness_settings_load_state"
                    )
                }
            }
            item(key = "settings-header") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.harness_settings_menu_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.harness_settings_menu_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("harness_settings_search"),
                        label = { Text(stringResource(R.string.harness_settings_search)) },
                        singleLine = true
                    )
                }
            }

            val searchEntries = if (query.isBlank()) {
                topEntries
            } else {
                buildList {
                    (topEntries + advancedEntries + schemaEntries + capabilityEntries).forEach { entry ->
                        if (entry.title.contains(query, ignoreCase = true) ||
                            entry.description.contains(query, ignoreCase = true) ||
                            entry.searchText.contains(query, ignoreCase = true)
                        ) add(entry)
                    }
                }
            }
            if (searchEntries.isEmpty()) {
                item(key = "settings-empty-search") {
                    AppStatePanel(
                        kind = AppStateKind.Empty,
                        title = stringResource(R.string.harness_settings_search_empty),
                        message = stringResource(R.string.harness_settings_search_empty_description)
                    )
                }
            } else {
                items(searchEntries, key = { "settings-entry-${it.key}" }) { entry ->
                    HarnessSettingsMenuRow(
                        entry = entry,
                        onClick = { destination = entry.key }
                    )
                }
            }
        } else {
            item(key = "settings-back") {
                TextButton(
                    onClick = {
                        destination = when {
                            destination.startsWith(SETTINGS_PROVIDER_PREFIX) -> SETTINGS_PROVIDERS
                            destination.startsWith(SETTINGS_SCHEMA_PREFIX) -> SETTINGS_GENERAL
                            destination.startsWith(SETTINGS_CAPABILITY_PREFIX) -> SETTINGS_CAPABILITIES
                            destination in setOf(SETTINGS_PERMISSIONS, SETTINGS_PRESETS, SETTINGS_WORKSPACE) -> SETTINGS_ADVANCED
                            else -> SETTINGS_ROOT
                        }
                    },
                    modifier = Modifier.testTag("harness_settings_back")
                ) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.harness_settings_back))
                }
            }
            item(key = "settings-load-destination") {
                HarnessSettingsLoadPanel(
                    load = state.managementLoads[HarnessManagementArea.SETTINGS],
                    onAction = onAction,
                    tag = "harness_settings_destination_load_state"
                )
            }
            when (destination) {
                SETTINGS_PROVIDERS -> item(key = SETTINGS_PROVIDERS) {
                    stateHolder.SaveableStateProvider(SETTINGS_PROVIDERS) {
                        HarnessProviderDirectory(
                            provider = state.provider,
                            onProviderSelected = { providerId ->
                                destination = "$SETTINGS_PROVIDER_PREFIX$providerId"
                            },
                            onAddProvider = { destination = SETTINGS_ADD_PROVIDER }
                        )
                    }
                }
                SETTINGS_MODEL -> item(key = SETTINGS_MODEL) {
                    stateHolder.SaveableStateProvider(SETTINGS_MODEL) {
                        HarnessModelEditor(
                            provider = state.provider,
                            onAction = onAction,
                        )
                    }
                }
                SETTINGS_GENERAL -> {
                    if (schemaEntries.isEmpty()) {
                        item(key = "settings-general-empty") {
                            HarnessSettingsEmptyDetail(
                                title = stringResource(R.string.harness_settings_general_empty),
                                message = stringResource(R.string.harness_settings_general_empty_description)
                            )
                        }
                    } else {
                        items(schemaEntries, key = { "settings-general-${it.key}" }) { entry ->
                            HarnessSettingsMenuRow(
                                entry = entry,
                                onClick = { destination = entry.key }
                            )
                        }
                    }
                }
                SETTINGS_ADVANCED -> {
                    items(advancedEntries, key = { "settings-advanced-${it.key}" }) { entry ->
                        HarnessSettingsMenuRow(
                            entry = entry,
                            onClick = { destination = entry.key }
                        )
                    }
                }
                SETTINGS_DOCUMENT -> item(key = SETTINGS_DOCUMENT) {
                    AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
                        Text(
                            stringResource(R.string.harness_settings_category_document),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(stringResource(R.string.harness_settings_category_document_detail))
                        HarnessSettingsDocumentAction(
                            hasDocument = state.settingsHasDocument,
                            onAction = onAction
                        )
                    }
                }
                SETTINGS_CAPABILITIES -> {
                    if (capabilityEntries.isEmpty()) {
                        item(key = "settings-capabilities-empty") {
                            HarnessSettingsEmptyDetail(
                                title = stringResource(R.string.harness_settings_capabilities_empty),
                                message = stringResource(R.string.harness_settings_capabilities_empty_description)
                            )
                        }
                    } else {
                        items(capabilityEntries, key = { "settings-capabilities-${it.key}" }) { entry ->
                            HarnessSettingsMenuRow(
                                entry = entry,
                                onClick = { destination = entry.key }
                            )
                        }
                    }
                }
                SETTINGS_PERMISSIONS -> item(key = SETTINGS_PERMISSIONS) {
                    HarnessPermissionCard(state = state.permission, onAction = onAction)
                }
                SETTINGS_PRESETS -> item(key = SETTINGS_PRESETS) {
                    HarnessAgentPresetCard(
                        state = state.agentPresets,
                        selectedSessionId = state.selectedSessionId,
                        onAction = onAction
                    )
                }
                SETTINGS_WORKSPACE -> item(key = SETTINGS_WORKSPACE) {
                    HarnessWorkspaceCard(state = state, onAction = onAction)
                }
                else -> {
                    val schemaSection = state.schemaSections.firstOrNull {
                        harnessSchemaDestination(it) == destination
                    }
                    val capabilitySection = state.capabilitySections.firstOrNull {
                        harnessCapabilityDestination(it) == destination
                    }
                    if (destination.startsWith(SETTINGS_PROVIDER_PREFIX)) {
                        val providerId = destination.removePrefix(SETTINGS_PROVIDER_PREFIX)
                        item(key = destination) {
                            stateHolder.SaveableStateProvider(destination) {
                                HarnessProviderEditor(
                                    provider = state.provider,
                                    providerId = providerId,
                                    onAction = onAction
                                )
                            }
                        }
                    } else if (schemaSection != null) {
                        item(key = destination) {
                            stateHolder.SaveableStateProvider(destination) {
                                HarnessSchemaSectionCard(section = schemaSection, onAction = onAction)
                            }
                        }
                    } else if (capabilitySection != null) {
                        item(key = destination) {
                            stateHolder.SaveableStateProvider(destination) {
                                HarnessCapabilitySectionCard(section = capabilitySection, onAction = onAction)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The provider editor is a long form rather than a list of independent settings rows. Keep it
 * on its own bounded viewport so the form's controls, including fields inside the advanced
 * section, share one scroll owner and can be positioned by Compose accessibility actions.
 */
@Composable
private fun HarnessAddProviderViewport(
    state: NativeHarnessUiState,
    onAction: (NativeHarnessUiAction) -> Unit,
    onBack: () -> Unit,
    stateHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("harness_settings_scroll")
            .padding(vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.notice?.let { notice ->
            HarnessNoticeBanner(notice = notice, onAction = onAction)
        }
        TextButton(
            onClick = onBack,
            modifier = Modifier.testTag("harness_settings_back"),
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.harness_settings_back))
        }
        HarnessSettingsLoadPanel(
            load = state.managementLoads[HarnessManagementArea.SETTINGS],
            onAction = onAction,
            tag = "harness_settings_destination_load_state",
        )
        stateHolder.SaveableStateProvider(SETTINGS_ADD_PROVIDER) {
            HarnessAddProviderEditor(
                provider = state.provider,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun HarnessSettingsMenuRow(
    entry: HarnessSettingsEntry,
    onClick: () -> Unit
) {
    AppSectionCard(
        modifier = Modifier.fillMaxWidth(),
        shape = AppChromeDefaults.InnerCardShape
    ) {
        TextButton(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("harness_settings_section_${entry.key.replace(':', '_')}")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(entry.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        entry.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun HarnessSettingsEmptyDetail(
    title: String,
    message: String
) {
    AppStatePanel(kind = AppStateKind.Empty, title = title, message = message)
}

@Composable
private fun HarnessProviderDirectory(
    provider: HarnessProviderUiState,
    onProviderSelected: (String) -> Unit,
    onAddProvider: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val options = remember(provider.providers, provider.configs) {
        val configuredIds = provider.providers.map { it.id }.toSet()
        val configOnly = provider.configs
            .filter { it.id !in configuredIds }
            .map { config ->
                HarnessProviderOption(
                    id = config.id,
                    name = config.name,
                    detail = config.credentialReference,
                    configured = config.credentialOptional || config.credentialConfigured || config.auth.configured,
                    canEdit = config.canSave,
                    canDelete = config.canDelete
                )
            }
        (provider.providers + configOnly)
            .distinctBy { it.id }
            .sortedWith(
                compareBy<HarnessProviderOption> { it.id != "deepseek-official" }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.id }
            )
    }
    val configById = remember(provider.configs) { provider.configs.associateBy { it.id } }
    val officialLabelTemplate = stringResource(R.string.harness_provider_official_label)
    val filteredOptions = remember(options, query) {
        if (query.isBlank()) {
            options
        } else {
            options.filter { option ->
                val searchText = buildString {
                    append(option.id)
                    append(' ')
                    append(option.name)
                    append(' ')
                    append(option.detail.orEmpty())
                    append(' ')
                    append(option.models.joinToString(" ") { model -> harnessModelOptionLabel(option, model) })
                }
                searchText.contains(query, ignoreCase = true)
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("harness_provider_directory"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            stringResource(R.string.harness_provider_directory_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            stringResource(R.string.harness_provider_directory_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = onAddProvider,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("harness_provider_add")
        ) {
            Text(stringResource(R.string.harness_provider_directory_add))
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("harness_provider_search"),
            label = { Text(stringResource(R.string.harness_provider_directory_search)) },
            singleLine = true
        )
        if (filteredOptions.isEmpty()) {
            AppStatePanel(
                kind = AppStateKind.Empty,
                title = stringResource(R.string.harness_settings_search_empty),
                message = stringResource(R.string.harness_settings_search_empty_description)
            )
        } else {
            filteredOptions.forEach { option ->
                val config = configById[option.id]
                val status = when {
                    config?.credentialConfigured == true || config?.auth?.configured == true ->
                        stringResource(R.string.harness_provider_status_configured)
                    config?.credentialOptional == true ->
                        stringResource(R.string.harness_provider_status_available_key_optional)
                    config != null -> stringResource(R.string.harness_provider_status_missing)
                    option.configured -> stringResource(R.string.harness_provider_status_available)
                    else -> stringResource(R.string.harness_provider_status_missing)
                }
                OutlinedButton(
                    onClick = { onProviderSelected(option.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("harness_provider_entry_${harnessStableDestinationPart(option.id)}")
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            harnessProviderOptionLabel(option, options, officialLabelTemplate),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HarnessProviderEditor(
    provider: HarnessProviderUiState,
    providerId: String,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    val config = provider.configs.firstOrNull { it.id == providerId }
    val option = provider.providers.firstOrNull { it.id == providerId }
    val officialLabelTemplate = stringResource(R.string.harness_provider_official_label)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("harness_provider_editor_${harnessStableDestinationPart(providerId)}"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (config != null) {
            HarnessProviderConfigCard(
                config = config,
                onAction = onAction,
                modifier = Modifier.testTag("harness_provider_config_${harnessStableDestinationPart(providerId)}")
            )
        } else {
            AppSectionCard(shape = AppChromeDefaults.InnerCardShape) {
                Text(
                    option?.let {
                        harnessProviderOptionLabel(it, provider.providers, officialLabelTemplate)
                    } ?: providerId,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(stringResource(R.string.harness_provider_editor_unavailable))
                option?.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                option?.models?.takeIf { it.isNotEmpty() }?.let { models ->
                    Text(
                        models.joinToString(", ") { model -> harnessModelOptionLabel(option, model) },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun HarnessAddProviderEditor(
    provider: HarnessProviderUiState,
    onAction: (NativeHarnessUiAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("harness_provider_add_editor"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            stringResource(R.string.harness_provider_directory_add),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        NativeHarnessCustomProviderPanel(
            existingRoutes = (provider.providers.map { it.id } + provider.configs.map { it.id }).toSet(),
            protocols = provider.customProviderProtocols.ifEmpty { NATIVE_CUSTOM_PROVIDER_PROTOCOLS },
            enabled = provider.canCreateProvider,
            onCreate = { onAction(NativeHarnessUiAction.CreateCustomProvider(it)) },
            discovery = provider.customProviderDiscovery,
            onDiscover = { onAction(NativeHarnessUiAction.DiscoverCustomProviderModels(it)) },
            onDismissDiscovery = { onAction(NativeHarnessUiAction.DismissCustomProviderModels) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
