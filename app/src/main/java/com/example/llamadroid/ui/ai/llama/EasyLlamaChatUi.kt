package com.example.llamadroid.ui.ai.llama

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.repository.EasyLlamaChatCoordinator
import com.example.llamadroid.data.repository.isEasyLlamaChatModel
import com.example.llamadroid.service.ManagedLlamaServerException
import com.example.llamadroid.ui.components.AppSectionCard
import com.example.llamadroid.ui.walkthrough.WalkthroughAlertDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val EasyLlamaModelTypes = listOf(ModelType.LLM, ModelType.VISION)

/**
 * The one model contract used by Home, AI Hub, native-server setup, and managed cards.
 * Projectors, draft models, incomplete rows, and non-GGUF artifacts never enter the picker.
 */
internal fun isEasyLlamaModel(model: ModelEntity): Boolean =
    isEasyLlamaChatModel(model)

/** Keep backend details out of the UI while retaining the coordinator's localized recovery copy. */
internal fun localizedEasyLlamaFailure(throwable: Throwable, fallback: String): String =
    (throwable as? ManagedLlamaServerException)
        ?.message
        ?.takeIf { it.isNotBlank() }
        ?: fallback

internal fun localizedEasyLlamaFailure(
    context: android.content.Context,
    throwable: Throwable,
    fallbackRes: Int
): String = localizedEasyLlamaFailure(throwable, context.getString(fallbackRes))

@Composable
internal fun rememberInstalledEasyLlamaModels(): List<ModelEntity> {
    val context = LocalContext.current
    val database = remember(context) { AppDatabase.getDatabase(context) }
    val models by remember(database) {
        database.modelDao().getModelsByTypes(EasyLlamaModelTypes)
    }.collectAsState(initial = emptyList())
    val runnableModels by produceState(initialValue = emptyList<ModelEntity>(), models) {
        value = withContext(Dispatchers.IO) {
            models
                .filter(::isEasyLlamaModel)
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.filename })
        }
    }
    return runnableModels
}

/** Shared searchable picker for every easy native chat entrypoint. */
@Composable
internal fun EasyLlamaModelPickerDialog(
    onDismiss: () -> Unit,
    onManageModels: () -> Unit = {},
    onSelected: (ModelEntity) -> Unit
) {
    val models = rememberInstalledEasyLlamaModels()
    var query by remember { mutableStateOf("") }
    val normalizedQuery = query.trim().lowercase()
    val filteredModels = remember(models, normalizedQuery) {
        if (normalizedQuery.isBlank()) models else models.filter { model ->
            model.filename.lowercase().contains(normalizedQuery) ||
                model.repoId.lowercase().contains(normalizedQuery)
        }
    }

    WalkthroughAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.easy_chat_picker_title),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.easy_chat_picker_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.easy_chat_picker_search)) },
                    singleLine = true
                )
                if (models.isEmpty()) {
                    Text(
                        text = stringResource(R.string.easy_chat_no_models),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(R.string.easy_chat_no_models_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            onDismiss()
                            onManageModels()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.easy_chat_manage_models),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else if (filteredModels.isEmpty()) {
                    Text(
                        text = stringResource(R.string.easy_chat_no_matching_models),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    filteredModels.forEach { model ->
                        TextButton(
                            onClick = { onSelected(model) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = model.filename,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = model.repoId,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    )
}

/**
 * Compact entry card used by Home, AI Hub, and the native server list. It deliberately owns no
 * registry or server state: selection and lifecycle go through [EasyLlamaChatCoordinator].
 */
@Composable
fun EasyLlamaChatEntryCard(
    modifier: Modifier = Modifier,
    onChatOpened: (chatId: Long, serverId: Long) -> Unit,
    onManageModels: () -> Unit = {}
) {
    val context = LocalContext.current
    val database = remember(context) { AppDatabase.getDatabase(context) }
    val coordinator = remember(context, database) {
        EasyLlamaChatCoordinator(context, database)
    }
    val scope = rememberCoroutineScope()
    val creatingServerText = stringResource(R.string.easy_chat_creating_server)
    val startingServerText = stringResource(R.string.easy_chat_starting_server)
    var showPicker by remember { mutableStateOf(false) }
    var isOpening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val downloadRequiredText = stringResource(R.string.easy_chat_download_required)
    var openingJob by remember { mutableStateOf<Job?>(null) }

    fun chooseModel() {
        if (isOpening) return
        error = null
        status = null
        isOpening = true
        openingJob = scope.launch {
            try {
                val hasModels = withContext(Dispatchers.IO) {
                    database.modelDao().getModelsByTypes(EasyLlamaModelTypes).first().any(::isEasyLlamaModel)
                }
                if (hasModels) showPicker = true else {
                    android.widget.Toast.makeText(context, downloadRequiredText,
                        android.widget.Toast.LENGTH_LONG).show()
                    onManageModels()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (throwable: Throwable) {
                error = localizedEasyLlamaFailure(context, throwable, R.string.easy_chat_open_failed)
            } finally { isOpening = false }
        }
    }

    AppSectionCard(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.58f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.easy_chat_entry_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.easy_chat_entry_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = ::chooseModel,
            enabled = !isOpening,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = if (isOpening) stringResource(R.string.easy_chat_starting)
                else stringResource(R.string.easy_chat_now),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isOpening) {
            TextButton(onClick = { openingJob?.cancel(); status = null }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_cancel))
            }
        }
        status?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        error?.let { message ->
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = ::chooseModel,
                        enabled = !isOpening
                    ) {
                        Text(stringResource(R.string.easy_chat_retry), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onManageModels, enabled = !isOpening) {
                        Text(stringResource(R.string.easy_chat_manage_models), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    if (showPicker) {
        EasyLlamaModelPickerDialog(
            onDismiss = { if (!isOpening) showPicker = false },
            onManageModels = onManageModels,
            onSelected = { model ->
                if (!isOpening) {
                    showPicker = false
                    isOpening = true
                    error = null
                    status = creatingServerText
                    openingJob = scope.launch {
                        val requestJob = currentCoroutineContext()[Job]
                        try {
                            val target = coordinator.createOrReuse(model.filename)
                            status = startingServerText
                            val session = coordinator.openChat(target.card.id) { message ->
                                scope.launch { if (requestJob?.isActive == true) status = message }
                            }
                            onChatOpened(session.chatId, session.serverId)
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (throwable: Throwable) {
                            if (currentCoroutineContext().isActive) {
                                error = localizedEasyLlamaFailure(
                                    context,
                                    throwable,
                                    R.string.easy_chat_open_failed
                                )
                            }
                        } finally {
                            isOpening = false
                            status = null
                        }
                    }
                }
            }
        )
    }
}

/** Easy server-first add dialog for the canonical managed-server manager. */
@Composable
internal fun EasyLlamaServerDialog(
    onDismiss: () -> Unit,
    onOpenAdvanced: () -> Unit,
    onSaved: () -> Unit,
    onChatOpened: (chatId: Long, serverId: Long) -> Unit,
    onManageModels: () -> Unit = {}
) {
    val context = LocalContext.current
    val database = remember(context) { AppDatabase.getDatabase(context) }
    val coordinator = remember(context, database) {
        EasyLlamaChatCoordinator(context, database)
    }
    val models = rememberInstalledEasyLlamaModels()
    val scope = rememberCoroutineScope()
    val creatingServerText = stringResource(R.string.easy_chat_creating_server)
    val startingServerText = stringResource(R.string.easy_chat_starting_server)
    var selectedModel by remember { mutableStateOf<ModelEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var lastSubmitStartChat by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var submitJob by remember { mutableStateOf<Job?>(null) }

    fun submit(startChat: Boolean) {
        val model = selectedModel ?: return
        if (working) return
        working = true
        lastSubmitStartChat = startChat
        error = null
        status = creatingServerText
        submitJob = scope.launch {
            val requestJob = currentCoroutineContext()[Job]
            try {
                val target = coordinator.createOrReuse(model.filename, name.trim().ifBlank { null })
                if (startChat) {
                    status = startingServerText
                    val session = coordinator.openChat(target.card.id) { message ->
                        scope.launch { if (requestJob?.isActive == true) status = message }
                    }
                    onChatOpened(session.chatId, session.serverId)
                } else {
                    onSaved()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (throwable: Throwable) {
                if (currentCoroutineContext().isActive) {
                    error = localizedEasyLlamaFailure(
                        context,
                        throwable,
                        if (startChat) R.string.easy_chat_open_failed else R.string.easy_chat_save_failed
                    )
                }
            } finally {
                working = false
                status = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.easy_server_add_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.easy_server_add_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.easy_server_name_optional)) },
                    singleLine = true,
                    enabled = !working
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = selectedModel?.filename
                                ?: stringResource(R.string.easy_server_no_model),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        selectedModel?.repoId?.let { repo ->
                            Text(
                                text = repo,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Button(
                    onClick = { showPicker = true },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Storage, contentDescription = null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.easy_server_choose_model), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (models.isEmpty()) {
                    Text(
                        text = stringResource(R.string.easy_chat_no_models_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(
                        onClick = onManageModels,
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.easy_chat_manage_models),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                status?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                error?.let { message ->
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(
                                onClick = { lastSubmitStartChat?.let(::submit) },
                                enabled = !working && lastSubmitStartChat != null
                            ) {
                                Text(stringResource(R.string.easy_chat_retry), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = onManageModels, enabled = !working) {
                                Text(stringResource(R.string.easy_chat_manage_models), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                TextButton(
                    onClick = onOpenAdvanced,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.easy_server_advanced_setup), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { submit(startChat = true) },
                    enabled = selectedModel != null && !working,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(stringResource(R.string.easy_server_start_chat), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(
                    onClick = { submit(startChat = false) },
                    enabled = selectedModel != null && !working,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.easy_server_save), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { submitJob?.cancel(); onDismiss() }) {
                Text(stringResource(R.string.action_cancel), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    )

    if (showPicker) {
        EasyLlamaModelPickerDialog(
            onDismiss = { if (!working) showPicker = false },
            onManageModels = onManageModels,
            onSelected = { model ->
                if (!working) {
                    selectedModel = model
                    showPicker = false
                }
            }
        )
    }
}
