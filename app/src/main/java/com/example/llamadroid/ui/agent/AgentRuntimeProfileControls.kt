package com.example.llamadroid.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AgentRuntimeBackend
import com.example.llamadroid.data.db.AgentRuntimeEndpointConfig
import com.example.llamadroid.data.db.AgentRuntimeProfile
import com.example.llamadroid.data.db.normalizeAgentRuntimeBackend
import com.example.llamadroid.data.db.remoteEndpointBackends
import com.example.llamadroid.data.runtime.AgentRuntimeContinueAction
import com.example.llamadroid.data.runtime.AgentRuntimeEndpointModelDiscovery
import com.example.llamadroid.data.runtime.ManagedLlamaServerDescriptor
import com.example.llamadroid.data.runtime.ManagedLlamaServerState
import kotlinx.coroutines.launch

data class AgentLiteRtProfileOption(
    val id: Long,
    val displayName: String,
    val filename: String? = null
)

private data class AgentRuntimeEndpointEditorState(
    val config: AgentRuntimeEndpointConfig,
    val isNew: Boolean
)

private data class AgentRuntimeModelRefreshState(
    val key: String? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val empty: Boolean = false
)

private const val GLOBAL_CONNECTION_KEY = "__global_connection__"
private const val MISSING_CONNECTION_KEY = "__missing_connection__"

private fun agentRuntimeModelDiscoveryKey(
    backend: AgentRuntimeBackend,
    baseUrl: String
): String = "${backend.id}|${baseUrl.trim().trimEnd('/')}"

/**
 * Shared editor used by built-in and custom Agent cards. The bounded inner
 * scroll is deliberate: a long Spanish server/model label must not push the
 * dependent controls below an AlertDialog's visible area.
 */
@Composable
fun AgentRuntimeProfileControls(
    profile: AgentRuntimeProfile,
    ollamaModels: List<String>,
    llamaSwapModels: List<String> = emptyList(),
    managedLlamaServers: List<ManagedLlamaServerDescriptor>,
    liteRtModels: List<AgentLiteRtProfileOption>,
    endpointConfigs: List<AgentRuntimeEndpointConfig> = emptyList(),
    onSaveEndpointConfig: (AgentRuntimeEndpointConfig) -> Unit = {},
    onDeleteEndpointConfig: (Long) -> Unit = {},
    onProfileChange: (AgentRuntimeProfile) -> Unit,
    onContinue: ((AgentRuntimeContinueAction) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val normalizedProfile = profile.normalized()
    val selectedEndpoint = endpointConfigs.firstOrNull { it.id == normalizedProfile.endpointConfigId }
    val backend = selectedEndpoint?.normalizedBackend ?: normalizedProfile.normalizedBackend
    val serverOptions = managedLlamaServers
        .filter { server ->
            val serverBackend = normalizeAgentRuntimeBackend(server.backend)
            serverBackend == backend.id ||
                (backend == AgentRuntimeBackend.LLAMA_SERVER &&
                    serverBackend == AgentRuntimeBackend.LLAMA_SERVER.id)
        }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    val selectedServer = serverOptions.firstOrNull { it.id == normalizedProfile.managedLlamaServerId }
    val selectedLiteRt = liteRtModels.firstOrNull { it.id == normalizedProfile.liteRtModelId }
    val endpointOptions = endpointConfigs
        .filter { it.normalizedBackend == backend }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    var endpointEditor by remember { mutableStateOf<AgentRuntimeEndpointEditorState?>(null) }
    var endpointToDelete by remember { mutableStateOf<AgentRuntimeEndpointConfig?>(null) }
    var discoveredModels by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var modelRefreshState by remember { mutableStateOf(AgentRuntimeModelRefreshState()) }
    val endpointModelScope = rememberCoroutineScope()
    val endpointContext = LocalContext.current
    val settingsRepository = remember(endpointContext) { SettingsRepository(endpointContext) }
    val globalOllamaUrl by settingsRepository.ollamaUrl.collectAsState()
    val globalLlamaServerUrl by settingsRepository.llamaServerUrl.collectAsState()
    val globalLlamaSwapUrl by settingsRepository.agentLlamaSwapUrl.collectAsState()
    val missingEndpoint = normalizedProfile.endpointConfigId != null && selectedEndpoint == null
    val globalConnectionUrl = when (backend) {
        AgentRuntimeBackend.OLLAMA -> globalOllamaUrl
        AgentRuntimeBackend.LLAMA_SERVER -> globalLlamaServerUrl
        AgentRuntimeBackend.LLAMA_SWAP -> globalLlamaSwapUrl
        AgentRuntimeBackend.LITERT -> ""
    }.trim().trimEnd('/')
    val activeConnectionLabel = when {
        selectedEndpoint != null -> "${selectedEndpoint.name} · ${selectedEndpoint.baseUrl}"
        missingEndpoint -> stringResource(R.string.agent_runtime_connection_missing)
        selectedServer != null -> "${selectedServer.displayName} · ${selectedServer.host}:${selectedServer.port}"
        globalConnectionUrl.isNotBlank() -> "${stringResource(R.string.agent_runtime_connection_global)} · $globalConnectionUrl"
        else -> stringResource(R.string.agent_runtime_connection_global)
    }
    val connectionSelection = when {
        selectedEndpoint != null -> selectedEndpoint.id.toString()
        missingEndpoint -> MISSING_CONNECTION_KEY
        else -> GLOBAL_CONNECTION_KEY
    }
    val refreshBackend = selectedEndpoint?.normalizedBackend ?: backend
    val refreshBaseUrl = when {
        selectedEndpoint != null -> selectedEndpoint.baseUrl
        !missingEndpoint && selectedServer == null && backend in AgentRuntimeBackend.remoteEndpointBackends -> {
            globalConnectionUrl
        }
        else -> ""
    }.trim().trimEnd('/')
    val refreshKey = refreshBaseUrl
        .takeIf { it.isNotBlank() }
        ?.let { agentRuntimeModelDiscoveryKey(refreshBackend, it) }
    val remoteDiscoveredModels = refreshKey?.let { discoveredModels[it] }.orEmpty()

    fun clearNamedEndpointSelection() {
        onProfileChange(
            normalizedProfile.copy(
                endpointConfigId = null,
                model = normalizedProfile.model?.takeIf { it.isNotBlank() }
                    ?: selectedEndpoint?.defaultModel,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.agent_runtime_profile_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.agent_runtime_profile_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            AgentRuntimeDropdown(
                label = stringResource(R.string.agent_runtime_engine_label),
                selected = backend.id,
                values = AgentRuntimeBackend.entries.map { it.id },
                labelFor = { runtimeBackendLabel(it) },
                onSelected = { selected ->
                    val nextBackend = AgentRuntimeBackend.from(selected)
                    val nextServer = managedLlamaServers.firstOrNull {
                        normalizeAgentRuntimeBackend(it.backend) == nextBackend.id
                    }
                    onProfileChange(
                        normalizedProfile.copy(
                            backend = nextBackend.id,
                            endpointConfigId = normalizedProfile.endpointConfigId?.takeIf { endpointId ->
                                endpointConfigs.any {
                                    it.id == endpointId && it.normalizedBackend == nextBackend
                                }
                            },
                            managedLlamaServerId = if (nextBackend == AgentRuntimeBackend.LLAMA_SERVER || nextBackend == AgentRuntimeBackend.LLAMA_SWAP) {
                                normalizedProfile.managedLlamaServerId?.takeIf { id ->
                                    managedLlamaServers.any { server ->
                                        server.id == id && normalizeAgentRuntimeBackend(server.backend) == nextBackend.id
                                    }
                                } ?: nextServer?.id
                            } else {
                                null
                            },
                            liteRtModelId = if (nextBackend == AgentRuntimeBackend.LITERT) {
                                normalizedProfile.liteRtModelId?.takeIf { id -> liteRtModels.any { it.id == id } }
                                    ?: liteRtModels.firstOrNull()?.id
                            } else {
                                null
                            },
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            )

            if (backend in AgentRuntimeBackend.remoteEndpointBackends) {
                AgentRuntimeDropdown(
                    label = stringResource(R.string.agent_runtime_connection_label),
                    selected = connectionSelection,
                    values = endpointOptions.map { it.id.toString() },
                    labelFor = { connectionId ->
                        when (connectionId) {
                            GLOBAL_CONNECTION_KEY -> activeConnectionLabel
                            MISSING_CONNECTION_KEY -> stringResource(R.string.agent_runtime_connection_missing)
                            else -> endpointConfigs.firstOrNull { it.id.toString() == connectionId }?.let { endpoint ->
                                "${endpoint.name} · ${endpoint.baseUrl}"
                            } ?: connectionId
                        }
                    },
                    onSelected = { endpointId ->
                        val endpoint = endpointConfigs.firstOrNull { it.id.toString() == endpointId }
                        endpoint?.let {
                            onProfileChange(
                                normalizedProfile.copy(
                                    backend = it.normalizedBackend.id,
                                    endpointConfigId = it.id,
                                    model = normalizedProfile.model?.takeIf { model -> model.isNotBlank() }
                                        ?: it.defaultModel,
                                    managedLlamaServerId = null,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                )
                if (refreshKey != null) {
                    TextButton(
                        enabled = modelRefreshState.key != refreshKey || !modelRefreshState.loading,
                        onClick = {
                            endpointModelScope.launch {
                                modelRefreshState = AgentRuntimeModelRefreshState(
                                    key = refreshKey,
                                    loading = true
                                )
                                val result = AgentRuntimeEndpointModelDiscovery.fetch(
                                    backend = refreshBackend,
                                    baseUrl = refreshBaseUrl
                                )
                                if (result.isSuccess) {
                                    discoveredModels = discoveredModels + (
                                        refreshKey to result.getOrDefault(emptyList())
                                    )
                                }
                                modelRefreshState = AgentRuntimeModelRefreshState(
                                    key = refreshKey,
                                    failed = result.isFailure,
                                    empty = result.getOrNull()?.isEmpty() == true
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.agent_runtime_connection_refresh_models),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (modelRefreshState.key == refreshKey) {
                        val feedback = when {
                            modelRefreshState.loading -> R.string.agent_runtime_connection_loading_models
                            modelRefreshState.failed -> R.string.agent_runtime_connection_models_error
                            modelRefreshState.empty -> R.string.agent_runtime_connection_models_empty
                            else -> null
                        }
                        feedback?.let { feedbackResId ->
                            Text(
                                stringResource(feedbackResId),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (modelRefreshState.failed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            endpointEditor = AgentRuntimeEndpointEditorState(
                                config = AgentRuntimeEndpointConfig(
                                    name = "",
                                    backend = backend.id,
                                    baseUrl = "",
                                    defaultModel = normalizedProfile.model
                                ),
                                isNew = true
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.agent_runtime_endpoint_config_save))
                    }
                    if (selectedEndpoint != null) {
                        OutlinedButton(
                            onClick = {
                                endpointEditor = AgentRuntimeEndpointEditorState(selectedEndpoint, isNew = false)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.agent_runtime_endpoint_config_edit))
                        }
                        TextButton(
                            onClick = { endpointToDelete = selectedEndpoint },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(R.string.agent_runtime_endpoint_config_delete),
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1
                            )
                        }
                    }
                    if (selectedEndpoint != null || missingEndpoint) {
                        TextButton(
                            onClick = ::clearNamedEndpointSelection,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(R.string.agent_runtime_connection_clear),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                if (missingEndpoint) {
                    AgentRuntimeNeedsDirectionNotice(
                        text = stringResource(R.string.agent_runtime_endpoint_config_missing),
                        continueAction = AgentRuntimeContinueAction.openProfile(normalizedProfile.agentKey),
                        onContinue = onContinue
                    )
                }
            }

            when (backend) {
                AgentRuntimeBackend.OLLAMA -> {
                    val endpointSpecificModels = if (selectedEndpoint != null) {
                        remoteDiscoveredModels
                    } else {
                        remoteDiscoveredModels.ifEmpty { ollamaModels }
                    }
                    AgentRuntimeDropdown(
                        label = stringResource(R.string.agent_runtime_model_label),
                        selected = normalizedProfile.model.orEmpty(),
                        values = endpointSpecificModels,
                        onSelected = { model ->
                            onProfileChange(normalizedProfile.copy(model = model, updatedAt = System.currentTimeMillis()))
                        },
                        allowTextEntry = true
                    )
                    if (normalizedProfile.model.isNullOrBlank()) {
                        AgentRuntimeNeedsDirectionNotice(
                            text = stringResource(R.string.agent_runtime_model_missing),
                            continueAction = AgentRuntimeContinueAction.openProfile(normalizedProfile.agentKey),
                            onContinue = onContinue
                        )
                    }
                }

                AgentRuntimeBackend.LLAMA_SERVER -> {
                    if (selectedEndpoint != null) {
                        AgentRuntimeDropdown(
                            label = stringResource(R.string.agent_runtime_model_label),
                            selected = normalizedProfile.model.orEmpty(),
                            values = remoteDiscoveredModels,
                            onSelected = { model ->
                                onProfileChange(
                                    normalizedProfile.copy(
                                        model = model,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                            },
                            allowTextEntry = true
                        )
                    } else {
                        AgentRuntimeServerDropdown(
                            label = stringResource(R.string.agent_runtime_managed_server_label),
                            selectedServer = selectedServer,
                            servers = serverOptions,
                            onSelected = { server ->
                                onProfileChange(
                                    normalizedProfile.copy(
                                        managedLlamaServerId = server.id,
                                        model = server.modelName ?: normalizedProfile.model,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                            }
                        )
                    }
                    if (selectedEndpoint == null && (normalizedProfile.managedLlamaServerId == null || selectedServer == null)) {
                        AgentRuntimeNeedsDirectionNotice(
                            text = stringResource(
                                if (serverOptions.isEmpty()) {
                                    R.string.agent_runtime_server_missing
                                } else {
                                    R.string.agent_runtime_server_unassigned
                                }
                            ),
                            continueAction = AgentRuntimeContinueAction.openServer(
                                normalizedProfile.agentKey,
                                normalizedProfile.managedLlamaServerId
                            ),
                            onContinue = onContinue
                        )
                    } else if (selectedEndpoint == null && selectedServer != null && !selectedServer.isReady) {
                        AgentRuntimeNeedsDirectionNotice(
                            text = stringResource(
                                if (selectedServer.state == ManagedLlamaServerState.STOPPED) {
                                    R.string.agent_runtime_server_stopped
                                } else {
                                    R.string.agent_runtime_server_not_ready
                                }
                            ),
                            continueAction = AgentRuntimeContinueAction.openServer(
                                normalizedProfile.agentKey,
                                selectedServer.id
                            ),
                            onContinue = onContinue
                        )
                    }
                }

                AgentRuntimeBackend.LLAMA_SWAP -> {
                    if (selectedEndpoint == null && serverOptions.isNotEmpty()) {
                        AgentRuntimeServerDropdown(
                            label = stringResource(R.string.agent_runtime_managed_server_label),
                            selectedServer = selectedServer,
                            servers = serverOptions,
                            onSelected = { server ->
                                onProfileChange(
                                    normalizedProfile.copy(
                                        managedLlamaServerId = server.id,
                                        updatedAt = System.currentTimeMillis()
                                    )
                                )
                            }
                        )
                    }
                    AgentRuntimeDropdown(
                        label = stringResource(R.string.agent_runtime_model_label),
                        selected = normalizedProfile.model.orEmpty(),
                        values = if (selectedEndpoint != null) {
                            remoteDiscoveredModels
                        } else if (refreshBackend == AgentRuntimeBackend.LLAMA_SWAP) {
                            remoteDiscoveredModels.ifEmpty { llamaSwapModels }
                        } else {
                            llamaSwapModels
                        },
                        onSelected = { model ->
                            onProfileChange(normalizedProfile.copy(model = model, updatedAt = System.currentTimeMillis()))
                        },
                        allowTextEntry = true
                    )
                    if (normalizedProfile.model.isNullOrBlank()) {
                        AgentRuntimeNeedsDirectionNotice(
                            text = stringResource(R.string.agent_runtime_model_missing),
                            continueAction = AgentRuntimeContinueAction.openProfile(normalizedProfile.agentKey),
                            onContinue = onContinue
                        )
                    }
                    if (selectedEndpoint == null && normalizedProfile.managedLlamaServerId != null && selectedServer == null) {
                        AgentRuntimeNeedsDirectionNotice(
                            text = stringResource(R.string.agent_runtime_server_missing),
                            continueAction = AgentRuntimeContinueAction.openServer(
                                normalizedProfile.agentKey,
                                normalizedProfile.managedLlamaServerId
                            ),
                            onContinue = onContinue
                        )
                    } else if (selectedEndpoint == null && selectedServer != null && !selectedServer.isReady) {
                        AgentRuntimeNeedsDirectionNotice(
                            text = stringResource(
                                if (selectedServer.state == ManagedLlamaServerState.STOPPED) {
                                    R.string.agent_runtime_server_stopped
                                } else {
                                    R.string.agent_runtime_server_not_ready
                                }
                            ),
                            continueAction = AgentRuntimeContinueAction.openServer(
                                normalizedProfile.agentKey,
                                selectedServer.id
                            ),
                            onContinue = onContinue
                        )
                    }
                }

                AgentRuntimeBackend.LITERT -> {
                    AgentRuntimeDropdown(
                        label = stringResource(R.string.agent_runtime_litert_model_label),
                        selected = selectedLiteRt?.displayName
                            ?: stringResource(R.string.agent_runtime_litert_model_missing),
                        values = liteRtModels.map { it.id.toString() },
                        labelFor = { id ->
                            liteRtModels.firstOrNull { it.id.toString() == id }?.displayName
                                ?: id
                        },
                        onSelected = { id ->
                            onProfileChange(
                                normalizedProfile.copy(
                                    liteRtModelId = id.toLongOrNull(),
                                    model = null,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        },
                        readOnly = true
                    )
                    selectedLiteRt?.filename?.let { filename ->
                        Text(
                            text = filename,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (selectedLiteRt == null) {
                        AgentRuntimeNeedsDirectionNotice(
                            text = stringResource(R.string.agent_runtime_litert_model_missing),
                            continueAction = AgentRuntimeContinueAction.openProfile(normalizedProfile.agentKey),
                            onContinue = onContinue
                        )
                    }
                }
            }
        }
    }

    endpointEditor?.let { editor ->
        AgentRuntimeEndpointConfigDialog(
            initial = editor.config,
            isNew = editor.isNew,
            existingConfigs = endpointConfigs,
            onDismiss = { endpointEditor = null },
            onSave = { config ->
                onSaveEndpointConfig(config)
                endpointEditor = null
            }
        )
    }
    endpointToDelete?.let { endpoint ->
        AlertDialog(
            onDismissRequest = { endpointToDelete = null },
            title = { Text(stringResource(R.string.agent_runtime_endpoint_config_delete_title)) },
            text = {
                Text(
                    stringResource(R.string.agent_runtime_endpoint_config_delete_message, endpoint.name),
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteEndpointConfig(endpoint.id)
                        if (normalizedProfile.endpointConfigId == endpoint.id) {
                            onProfileChange(
                                normalizedProfile.copy(
                                    endpointConfigId = null,
                                    model = null,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                        endpointToDelete = null
                    }
                ) {
                    Text(stringResource(R.string.agent_runtime_endpoint_config_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { endpointToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun AgentRuntimeEndpointConfigDialog(
    initial: AgentRuntimeEndpointConfig,
    isNew: Boolean,
    existingConfigs: List<AgentRuntimeEndpointConfig>,
    onDismiss: () -> Unit,
    onSave: (AgentRuntimeEndpointConfig) -> Unit
) {
    var name by remember(initial.id, initial.name) { mutableStateOf(initial.name) }
    var backend by remember(initial.id, initial.backend) { mutableStateOf(initial.backend) }
    var baseUrl by remember(initial.id, initial.baseUrl) { mutableStateOf(initial.baseUrl) }
    var defaultModel by remember(initial.id, initial.defaultModel) { mutableStateOf(initial.defaultModel.orEmpty()) }
    var error by remember(initial.id) { mutableStateOf<String?>(null) }
    val duplicateNameMessage = stringResource(R.string.agent_runtime_endpoint_config_duplicate_name)
    val invalidConfigMessage = stringResource(R.string.agent_runtime_endpoint_config_invalid)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isNew) {
                        R.string.agent_runtime_endpoint_config_new_title
                    } else {
                        R.string.agent_runtime_endpoint_config_edit_title
                    }
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AgentRuntimeDropdown(
                    label = stringResource(R.string.agent_runtime_endpoint_config_backend_label),
                    selected = backend,
                    values = AgentRuntimeBackend.remoteEndpointBackends.map { it.id },
                    labelFor = { runtimeBackendLabel(it) },
                    onSelected = { backend = it }
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.agent_runtime_endpoint_config_name_label)) },
                    placeholder = { Text(stringResource(R.string.agent_runtime_endpoint_config_name_hint)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.agent_runtime_endpoint_config_url_label)) },
                    placeholder = { Text(stringResource(R.string.agent_runtime_endpoint_config_url_hint)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = defaultModel,
                    onValueChange = { defaultModel = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.agent_runtime_endpoint_config_default_model_label)) },
                    placeholder = { Text(stringResource(R.string.agent_runtime_endpoint_config_default_model_hint)) },
                    singleLine = true
                )
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val candidate = AgentRuntimeEndpointConfig(
                        id = initial.id,
                        name = name,
                        backend = backend,
                        baseUrl = baseUrl,
                        defaultModel = defaultModel,
                        createdAt = initial.createdAt,
                        updatedAt = System.currentTimeMillis()
                    )
                    val normalized = runCatching { candidate.validate() }
                    normalized.fold(
                        onSuccess = { valid ->
                            if (existingConfigs.any {
                                    it.id != valid.id && it.name.equals(valid.name, ignoreCase = true)
                                }) {
                                error = duplicateNameMessage
                            } else {
                                onSave(valid)
                            }
                        },
                        onFailure = {
                            // Validation messages are intentionally not user-facing: the
                            // data layer keeps them stable for diagnostics, while this
                            // editor stays localized in both supported languages.
                            error = invalidConfigMessage
                        }
                    )
                }
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun AgentRuntimeProfileSummary(
    profile: AgentRuntimeProfile?,
    managedLlamaServers: List<ManagedLlamaServerDescriptor> = emptyList(),
    liteRtModels: List<AgentLiteRtProfileOption> = emptyList(),
    endpointConfigs: List<AgentRuntimeEndpointConfig> = emptyList(),
    modifier: Modifier = Modifier
) {
    val resolved = profile?.normalized() ?: return
    val server = managedLlamaServers.firstOrNull { it.id == resolved.managedLlamaServerId }
    val liteRt = liteRtModels.firstOrNull { it.id == resolved.liteRtModelId }
    val endpoint = endpointConfigs.firstOrNull { it.id == resolved.endpointConfigId }
    val effectiveBackend = endpoint?.normalizedBackend ?: resolved.normalizedBackend
    val value = when (effectiveBackend) {
        AgentRuntimeBackend.OLLAMA,
        AgentRuntimeBackend.LLAMA_SWAP -> buildString {
            endpoint?.name?.let { append(it) }
            if (isNotEmpty() && !resolved.model.isNullOrBlank()) append(" · ")
            if (effectiveBackend == AgentRuntimeBackend.LLAMA_SWAP && endpoint == null) {
                server?.compactLabel()?.let { append(it) }
                if (isNotEmpty() && !resolved.model.isNullOrBlank()) append(" · ")
            }
            append(resolved.model.orEmpty())
        }
        AgentRuntimeBackend.LLAMA_SERVER -> buildString {
            append(endpoint?.name ?: server?.compactLabel()
                ?: stringResource(R.string.agent_runtime_server_unassigned))
            resolved.model?.takeIf { it.isNotBlank() }?.let {
                append(" · ").append(it)
            }
        }
        AgentRuntimeBackend.LITERT -> liteRt?.displayName
            ?: stringResource(R.string.agent_runtime_litert_model_missing)
    }
    Text(
        text = stringResource(
            R.string.agent_runtime_profile_summary,
            runtimeBackendLabel(effectiveBackend.id),
            value.ifBlank { stringResource(R.string.agent_runtime_model_missing) }
        ),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AgentRuntimeDropdown(
    label: String,
    selected: String,
    values: List<String>,
    onSelected: (String) -> Unit,
    labelFor: @Composable (String) -> String = { it },
    allowTextEntry: Boolean = false,
    readOnly: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = if (allowTextEntry) selected else labelFor(selected),
            onValueChange = { if (allowTextEntry) onSelected(it) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            readOnly = readOnly,
            enabled = allowTextEntry || values.isNotEmpty(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
        ) {
            values.forEach { value ->
                DropdownMenuItem(
                    text = {
                        Text(
                            labelFor(value),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AgentRuntimeServerDropdown(
    label: String,
    selectedServer: ManagedLlamaServerDescriptor?,
    servers: List<ManagedLlamaServerDescriptor>,
    onSelected: (ManagedLlamaServerDescriptor) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedServer?.compactLabel()
                ?: stringResource(R.string.agent_runtime_server_unassigned),
            onValueChange = {},
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
            readOnly = true,
            enabled = servers.isNotEmpty(),
            label = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState())
        ) {
            servers.forEach { server ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(server.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                server.compactLabel(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    onClick = {
                        onSelected(server)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AgentRuntimeNeedsDirectionNotice(
    text: String,
    continueAction: AgentRuntimeContinueAction? = null,
    onContinue: ((AgentRuntimeContinueAction) -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.agent_runtime_needs_direction),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (onContinue != null && continueAction != null) {
                TextButton(
                    onClick = { onContinue(continueAction) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.agent_runtime_continue), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun runtimeBackendLabel(backend: String): String = when (AgentRuntimeBackend.from(backend)) {
    AgentRuntimeBackend.OLLAMA -> stringResource(R.string.agent_runtime_engine_ollama)
    AgentRuntimeBackend.LLAMA_SERVER -> stringResource(R.string.agent_runtime_engine_llama_server)
    AgentRuntimeBackend.LLAMA_SWAP -> stringResource(R.string.agent_runtime_engine_llama_swap)
    AgentRuntimeBackend.LITERT -> stringResource(R.string.agent_runtime_engine_litert)
}
