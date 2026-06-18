package com.example.llamadroid.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.service.RemoteSummaryMetadata
import kotlinx.coroutines.launch

private const val LITERT_BACKEND_AUTO = "auto"
private const val LITERT_BACKEND_CPU = "cpu"
private const val LITERT_BACKEND_GPU = "gpu"

private fun normalizeLiteRtBackend(value: String?): String {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return when (normalized) {
        LITERT_BACKEND_CPU, "cpu-only" -> LITERT_BACKEND_CPU
        LITERT_BACKEND_GPU, "gpu-only", "opencl", "vulkan" -> LITERT_BACKEND_GPU
        else -> LITERT_BACKEND_AUTO
    }
}

private fun com.example.llamadroid.data.model.LiteRtModelEntity.advertisedLiteRtMaxContextTokens(): Int? =
    maxContextTokens?.takeIf { it > 0 }

@Composable
fun RemoteSummaryBackendEditor(
    title: String,
    backend: String,
    onBackendChange: (String) -> Unit,
    ollamaUrl: String,
    onOllamaUrlChange: (String) -> Unit,
    llamaServerUrl: String,
    onLlamaServerUrlChange: (String) -> Unit,
    llamaSwapUrl: String,
    onLlamaSwapUrlChange: (String) -> Unit,
    ollamaModel: String?,
    onOllamaModelSelected: (String) -> Unit,
    llamaSwapModel: String?,
    onLlamaSwapModelSelected: (String) -> Unit,
    llamaServerModelLabel: String?,
    llamaServerContextLabel: String?,
    llamaServerContextTokens: Int,
    requestedContextForWarning: Int?,
    liteRtModelId: Long? = null,
    onLiteRtModelSelected: (Long?) -> Unit = {},
    liteRtBackend: String = LITERT_BACKEND_AUTO,
    onLiteRtBackendChange: (String) -> Unit = {},
    liteRtMtpEnabled: Boolean = false,
    onLiteRtMtpEnabledChange: (Boolean) -> Unit = {},
    liteRtThinkingEnabled: Boolean = false,
    onLiteRtThinkingEnabledChange: ((Boolean) -> Unit)? = null,
    allowBlankUrlRefresh: Boolean = false,
    fetchMetadata: suspend () -> Result<RemoteSummaryMetadata>,
    onMetadataLoaded: (RemoteSummaryMetadata) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val liteRtModels by remember(context) {
        AppDatabase.getDatabase(context).liteRtModelDao().observeAll()
    }.collectAsState(initial = emptyList())
    val normalizedBackend = SettingsRepository.normalizeOllamaOrLlamaBackend(backend)
    val isLiteRt = SettingsRepository.isLiteRtBackend(normalizedBackend)
    val selectedRemoteModel = if (normalizedBackend == SettingsRepository.PDF_BACKEND_LLAMA_SWAP) {
        llamaSwapModel
    } else {
        ollamaModel
    }
    val requiresSelectableModel = SettingsRepository.requiresSelectedRemoteModel(normalizedBackend)

    var availableRemoteModels by remember(ollamaModel, llamaSwapModel, normalizedBackend) {
        mutableStateOf(selectedRemoteModel?.let(::listOf) ?: emptyList())
    }
    var showModelMenu by rememberSaveable { mutableStateOf(false) }
    var showLiteRtModelMenu by rememberSaveable { mutableStateOf(false) }
    var isRefreshingMetadata by rememberSaveable { mutableStateOf(false) }
    var metadataMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var ollamaUrlDraft by rememberSaveable { mutableStateOf(ollamaUrl) }
    var llamaServerUrlDraft by rememberSaveable { mutableStateOf(llamaServerUrl) }
    var llamaSwapUrlDraft by rememberSaveable { mutableStateOf(llamaSwapUrl) }
    var isEditingOllamaUrl by rememberSaveable { mutableStateOf(false) }
    var isEditingLlamaServerUrl by rememberSaveable { mutableStateOf(false) }
    var isEditingLlamaSwapUrl by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(ollamaUrl) {
        if (!isEditingOllamaUrl) {
            ollamaUrlDraft = ollamaUrl
        }
    }
    LaunchedEffect(llamaServerUrl) {
        if (!isEditingLlamaServerUrl) {
            llamaServerUrlDraft = llamaServerUrl
        }
    }
    LaunchedEffect(llamaSwapUrl) {
        if (!isEditingLlamaSwapUrl) {
            llamaSwapUrlDraft = llamaSwapUrl
        }
    }

    val currentUrl = when (normalizedBackend) {
        SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> llamaServerUrlDraft
        SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> llamaSwapUrlDraft
        SettingsRepository.PDF_BACKEND_LITERT -> "local"
        else -> ollamaUrlDraft
    }
    fun mergeSelectedModel(models: List<String>): List<String> {
        val selected = selectedRemoteModel?.takeIf { it.isNotBlank() }
        if (selected == null || models.contains(selected)) return models
        return listOf(selected) + models
    }

    fun applyMetadata(metadata: RemoteSummaryMetadata) {
        when (SettingsRepository.normalizeOllamaOrLlamaBackend(metadata.backend)) {
            SettingsRepository.PDF_BACKEND_LITERT -> {
                metadataMessage = context.getString(
                    R.string.pdf_metadata_litert_loaded,
                    metadata.serverModelLabel ?: context.getString(R.string.pdf_server_value_unavailable),
                    metadata.serverContextLabel ?: context.getString(R.string.pdf_server_value_unavailable)
                )
            }

            SettingsRepository.PDF_BACKEND_OLLAMA -> {
                availableRemoteModels = mergeSelectedModel(metadata.availableModels)
                metadataMessage = context.getString(
                    R.string.pdf_metadata_ollama_loaded,
                    metadata.availableModels.size
                )
            }

            SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> {
                availableRemoteModels = mergeSelectedModel(metadata.availableModels)
                metadataMessage = context.getString(
                    R.string.pdf_metadata_llama_swap_loaded,
                    metadata.availableModels.size
                )
            }

            else -> {
                metadataMessage = context.getString(
                    R.string.pdf_metadata_llama_loaded,
                    metadata.serverModelLabel ?: context.getString(R.string.pdf_server_value_unavailable),
                    metadata.serverContextLabel ?: context.getString(R.string.pdf_server_value_unavailable)
                )
            }
        }
        onMetadataLoaded(metadata)
    }

    fun refreshMetadata() {
        if (!isLiteRt && !allowBlankUrlRefresh && currentUrl.isBlank()) return
        scope.launch {
            isRefreshingMetadata = true
            metadataMessage = null
            fetchMetadata()
                .onSuccess(::applyMetadata)
                .onFailure {
                    metadataMessage = context.getString(
                        R.string.pdf_metadata_refresh_failed,
                        it.message ?: context.getString(R.string.error_generic)
                    )
                }
            isRefreshingMetadata = false
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onBackendChange(SettingsRepository.PDF_BACKEND_OLLAMA) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.pdf_backend_ollama))
                }
                OutlinedButton(
                    onClick = { onBackendChange(SettingsRepository.PDF_BACKEND_LLAMA_SERVER) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.pdf_backend_llama_server))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onBackendChange(SettingsRepository.PDF_BACKEND_LLAMA_SWAP) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.pdf_backend_llama_swap))
                }
                OutlinedButton(
                    onClick = { onBackendChange(SettingsRepository.PDF_BACKEND_LITERT) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.pdf_backend_litert))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!isLiteRt) {
                OutlinedTextField(
                    value = currentUrl,
                    onValueChange = {
                        when (normalizedBackend) {
                            SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> {
                                llamaServerUrlDraft = it
                                onLlamaServerUrlChange(it)
                            }
                            SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> {
                                llamaSwapUrlDraft = it
                                onLlamaSwapUrlChange(it)
                            }
                            else -> {
                                ollamaUrlDraft = it
                                onOllamaUrlChange(it)
                            }
                        }
                    },
                    label = {
                        Text(
                            when (normalizedBackend) {
                                SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> stringResource(R.string.pdf_llama_server_url_label)
                                SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> stringResource(R.string.pdf_llama_swap_url_label)
                                else -> stringResource(R.string.pdf_ollama_url_label)
                            }
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("remote_summary_url_field")
                        .onFocusChanged { focusState ->
                            when (normalizedBackend) {
                                SettingsRepository.PDF_BACKEND_LLAMA_SERVER -> {
                                    isEditingLlamaServerUrl = focusState.isFocused
                                }
                                SettingsRepository.PDF_BACKEND_LLAMA_SWAP -> {
                                    isEditingLlamaSwapUrl = focusState.isFocused
                                }
                                else -> {
                                    isEditingOllamaUrl = focusState.isFocused
                                }
                            }
                        },
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = ::refreshMetadata,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRefreshingMetadata && (isLiteRt || allowBlankUrlRefresh || currentUrl.isNotBlank())
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isRefreshingMetadata) {
                        stringResource(R.string.pdf_refreshing_metadata)
                    } else {
                        stringResource(R.string.pdf_refresh_backend_info)
                    }
                )
            }

            metadataMessage?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isLiteRt) {
                val selectedLiteRtModel = liteRtModels.firstOrNull { it.id == liteRtModelId }
                    ?: liteRtModels.firstOrNull()
                Text(
                    stringResource(R.string.litert_model_label),
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box {
                    OutlinedButton(
                        onClick = { showLiteRtModelMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = liteRtModels.isNotEmpty()
                    ) {
                        Text(selectedLiteRtModel?.displayName ?: stringResource(R.string.litert_error_model_missing))
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = showLiteRtModelMenu,
                        onDismissRequest = { showLiteRtModelMenu = false }
                    ) {
                        if (liteRtModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.litert_error_model_missing)) },
                                onClick = { showLiteRtModelMenu = false }
                            )
                        } else {
                            liteRtModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model.displayName) },
                                    onClick = {
                                        onLiteRtModelSelected(model.id)
                                        showLiteRtModelMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.litert_gallery_accelerator),
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        LITERT_BACKEND_AUTO to R.string.general_acceleration_mode_auto,
                        LITERT_BACKEND_CPU to R.string.general_acceleration_mode_cpu,
                        LITERT_BACKEND_GPU to R.string.litert_backend_gpu
                    ).forEach { (mode, label) ->
                        OutlinedButton(
                            onClick = { onLiteRtBackendChange(mode) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(label))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.litert_gallery_mtp_title), style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.litert_gallery_mtp_desc), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = liteRtMtpEnabled, onCheckedChange = onLiteRtMtpEnabledChange)
                }
                onLiteRtThinkingEnabledChange?.let { onThinkingChange ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.litert_thinking_title), style = MaterialTheme.typography.labelLarge)
                            Text(stringResource(R.string.litert_thinking_desc), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = liteRtThinkingEnabled, onCheckedChange = onThinkingChange)
                    }
                }
                val requested = requestedContextForWarning
                val advertised = selectedLiteRtModel?.advertisedLiteRtMaxContextTokens()
                if (requested != null && requested > 16_384 && normalizeLiteRtBackend(liteRtBackend) == LITERT_BACKEND_GPU) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.litert_gallery_high_gpu_context_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (requested != null && advertised != null && requested > advertised) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.pdf_context_warning, requested, advertised),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else if (requiresSelectableModel) {
                Text(
                    stringResource(
                        if (normalizedBackend == SettingsRepository.PDF_BACKEND_LLAMA_SWAP) {
                            R.string.pdf_llama_swap_model_label
                        } else {
                            R.string.pdf_ollama_model_label
                        }
                    ),
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box {
                    OutlinedButton(
                        onClick = { showModelMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = availableRemoteModels.isNotEmpty()
                    ) {
                        Text(
                            selectedRemoteModel ?: stringResource(
                                if (normalizedBackend == SettingsRepository.PDF_BACKEND_LLAMA_SWAP) {
                                    R.string.pdf_select_llama_swap_model
                                } else {
                                    R.string.pdf_select_ollama_model
                                }
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = showModelMenu,
                        onDismissRequest = { showModelMenu = false }
                    ) {
                        if (availableRemoteModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.pdf_no_remote_models_loaded)) },
                                onClick = { showModelMenu = false }
                            )
                        } else {
                            availableRemoteModels.forEach { model ->
                                DropdownMenuItem(
                                    text = { Text(model) },
                                    onClick = {
                                        if (normalizedBackend == SettingsRepository.PDF_BACKEND_LLAMA_SWAP) {
                                            onLlamaSwapModelSelected(model)
                                        } else {
                                            onOllamaModelSelected(model)
                                        }
                                        showModelMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    stringResource(R.string.pdf_llama_server_model_label),
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    llamaServerModelLabel ?: stringResource(R.string.pdf_server_value_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.pdf_llama_server_context_label),
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    llamaServerContextLabel ?: stringResource(R.string.pdf_server_value_unavailable),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (requestedContextForWarning != null &&
                    llamaServerContextTokens > 0 &&
                    requestedContextForWarning > llamaServerContextTokens
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(
                            R.string.pdf_context_warning,
                            requestedContextForWarning,
                            llamaServerContextTokens
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
