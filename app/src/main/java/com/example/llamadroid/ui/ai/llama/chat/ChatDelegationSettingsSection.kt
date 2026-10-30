package com.example.llamadroid.ui.ai.llama.chat

import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AgentProotEnvironmentEntity
import com.example.llamadroid.data.db.AgentProotEnvironmentStatus
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.HarnessInstallationPurpose
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.harness.HarnessInstallationManager
import com.example.llamadroid.harness.HarnessKvCacheSettings
import com.example.llamadroid.service.delegation.DelegationAlarmScheduler
import com.example.llamadroid.service.delegation.DelegationApprovalPolicy
import com.example.llamadroid.service.delegation.DelegationMode
import com.example.llamadroid.service.delegation.NativeChatDelegation
import com.example.llamadroid.service.delegation.NativeChatDelegationConfig
import com.example.llamadroid.service.isNativeChatLoopbackHost
import com.example.llamadroid.ui.ai.llama.servers.ServerRegistryViewModel
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "DeepSeek runtime" group of the chat settings sheet. Every change writes
 * `settings.delegation` and calls [onSettingsChanged] so the screen auto-saves the apiParams.
 *
 * [defaultsMode] renders the same controls inside a server's "defaults for new chats" editor:
 * there is no chat, so per-task cache sizes and clear actions are hidden. A null [navController]
 * (the editor is a bottom sheet) hides the buttons that would navigate away from it.
 */
@Composable
internal fun ChatDelegationSettingsSection(
    settings: ChatSettingsState,
    chat: LlamaChatEntity?,
    chatServer: LlamaServerEntity?,
    registryViewModel: ServerRegistryViewModel,
    navController: NavController?,
    onSettingsChanged: () -> Unit,
    defaultsMode: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember(context) { HarnessInstallationManager.get(context) }
    val installations by manager.installations.collectAsState()
    val operation by manager.operation.collectAsState()
    val repository = remember {
        val db = AppDatabase.getDatabase(context)
        LlamaRepository(db.llamaServerDao(), db.llamaChatDao(), db.llamaChatFolderDao(), db.llamaMessageDao())
    }
    val config = settings.delegation
    val runtimes = installations.filter { it.purpose == HarnessInstallationPurpose.HARNESS }
    val readyRuntimes = runtimes.filter { it.status == AgentProotEnvironmentStatus.READY }
    val block = delegationEnableBlock(
        readyRuntimes.isNotEmpty(), settings.activeServerIsLiteRt, config.subagentServer
    )
    var showNewRuntime by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var cacheNote by remember { mutableStateOf(false) }

    fun update(next: NativeChatDelegationConfig) {
        settings.delegation = next
        onSettingsChanged()
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Enable switch and its reason.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.native_chat_delegation_enable),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            Switch(
                checked = config.enabled,
                enabled = config.enabled || block == DelegationEnableBlock.NONE,
                onCheckedChange = { on ->
                    if (!on) {
                        update(config.copy(enabled = false))
                        return@Switch
                    }
                    val runtimeId = config.runtimeId?.takeIf { id -> readyRuntimes.any { it.id == id } }
                        ?: readyRuntimes.firstOrNull()?.id
                    // The deepseek_* tools only reach the model when the chat's tool master switch is on.
                    settings.toolsEnabled = true
                    update(config.copy(enabled = true, runtimeId = runtimeId))
                    val persist = chat != null && !chat.persistKvCache && chatServer != null &&
                        chatServer.usesOpenAiCompatibleEngine() && isNativeChatLoopbackHost(chatServer.host)
                    if (persist && chat != null) {
                        cacheNote = true
                        scope.launch { repository.setPersistKvCache(chat.id, true) }
                    }
                }
            )
        }
        Text(
            stringResource(R.string.native_chat_delegation_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (block) {
            DelegationEnableBlock.NO_RUNTIME -> Text(
                stringResource(R.string.native_chat_delegation_reason_no_runtime),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
            )
            DelegationEnableBlock.LITERT_SAME_AS_CHAT -> Text(
                stringResource(R.string.native_chat_delegation_reason_litert),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error
            )
            DelegationEnableBlock.NONE -> Unit
        }
        if (cacheNote) {
            Text(
                stringResource(R.string.native_chat_delegation_cache_enabled_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()
        // Runtime picker.
        Text(
            stringResource(R.string.native_chat_delegation_runtime_title),
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            stringResource(R.string.native_chat_delegation_runtime_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (runtimes.isEmpty()) {
            Text(
                stringResource(R.string.native_chat_delegation_runtime_none),
                style = MaterialTheme.typography.bodySmall
            )
        }
        runtimes.forEach { runtime ->
            RuntimeRow(runtime, selected = runtime.id == config.runtimeId) {
                update(config.copy(runtimeId = runtime.id))
            }
        }
        if (operation.busy) {
            val progress = if (operation.totalBytes > 0) operation.completedBytes.toFloat() / operation.totalBytes else null
            Text(
                stringResource(R.string.native_chat_delegation_runtime_working, operation.phase),
                style = MaterialTheme.typography.bodySmall
            )
            if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            val error = createError ?: operation.errorCode?.takeIf { operation.phase == "FAILED" }
            if (error != null) {
                Text(
                    stringResource(R.string.native_chat_delegation_runtime_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        OutlinedButton(
            onClick = { createError = null; showNewRuntime = true },
            enabled = !operation.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.native_chat_delegation_runtime_new), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (navController != null) {
            OutlinedButton(onClick = { navController.navigate(Screen.Agent.route) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.native_chat_delegation_runtime_manage), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        HorizontalDivider()
        DelegationServerChoiceBlock(
            title = stringResource(R.string.native_chat_delegation_subagent_title),
            hint = stringResource(R.string.native_chat_delegation_subagent_hint),
            forSubagent = true,
            choice = config.subagentServer,
            onChoice = { update(config.copy(subagentServer = it)) },
            registryViewModel = registryViewModel,
            navController = navController,
            sameAsChatReason = if (settings.activeServerIsLiteRt) {
                stringResource(R.string.native_chat_delegation_reason_litert)
            } else null
        )
        HorizontalDivider()
        DelegationServerChoiceBlock(
            title = stringResource(R.string.native_chat_delegation_harness_title),
            hint = stringResource(R.string.native_chat_delegation_harness_hint),
            forSubagent = false,
            choice = config.harnessServer,
            onChoice = { update(config.copy(harnessServer = it)) },
            registryViewModel = registryViewModel,
            navController = navController
        )
        HorizontalDivider()
        DelegationParallelBlock(settings, chatServer, registryViewModel, navController, onSettingsChanged)

        HorizontalDivider()
        ModeBlock(config.mode) { update(config.copy(mode = it)) }

        HorizontalDivider()
        ApprovalBlock(config.approvalPolicy) { update(config.copy(approvalPolicy = it)) }

        HorizontalDivider()
        CachesBlock(
            chatId = chat?.id,
            runtimeId = config.runtimeId,
            subagentCacheEnabled = config.subagentKvCacheEnabled,
            onSubagentCacheEnabled = { update(config.copy(subagentKvCacheEnabled = it)) },
            defaultsMode = defaultsMode
        )

        if (!DelegationAlarmScheduler.canScheduleExact(context)) {
            HorizontalDivider()
            Text(
                stringResource(R.string.native_chat_delegation_alarm_title),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.native_chat_delegation_alarm_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.native_chat_delegation_alarm_button), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    if (showNewRuntime) {
        NewRuntimeDialog(
            onDismiss = { showNewRuntime = false },
            onCreate = { name ->
                showNewRuntime = false
                scope.launch {
                    runCatching { manager.create(name) }
                        .onSuccess { id ->
                            createError = null
                            update(settings.delegation.copy(runtimeId = id))
                        }
                        .onFailure { createError = it.message ?: it.javaClass.simpleName }
                }
            }
        )
    }
}

@Composable
private fun RuntimeRow(runtime: AgentProotEnvironmentEntity, selected: Boolean, onSelect: () -> Unit) {
    val ready = runtime.status == AgentProotEnvironmentStatus.READY
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect, enabled = ready)
        Column(modifier = Modifier.weight(1f)) {
            Text(runtime.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(runtimeStatusRes(runtime.status)),
                style = MaterialTheme.typography.labelSmall,
                color = if (ready) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun runtimeStatusRes(status: String): Int = when (status) {
    AgentProotEnvironmentStatus.READY -> R.string.native_chat_delegation_runtime_status_ready
    AgentProotEnvironmentStatus.INSTALLING, AgentProotEnvironmentStatus.UPDATING ->
        R.string.native_chat_delegation_runtime_status_installing
    AgentProotEnvironmentStatus.DELETING -> R.string.native_chat_delegation_runtime_status_deleting
    AgentProotEnvironmentStatus.BROKEN -> R.string.native_chat_delegation_runtime_status_broken
    else -> R.string.native_chat_delegation_runtime_status_not_installed
}

@Composable
private fun NewRuntimeDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.native_chat_delegation_runtime_new_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.native_chat_delegation_runtime_new_desc), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(120) },
                    label = { Text(stringResource(R.string.native_chat_delegation_runtime_name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()) }) {
                Text(stringResource(R.string.native_chat_delegation_runtime_create))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

@Composable
private fun ModeBlock(mode: DelegationMode, onMode: (DelegationMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.native_chat_delegation_mode_title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.native_chat_delegation_mode_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DelegationMode.entries.forEach { option ->
            val label = when (option) {
                DelegationMode.AUTO -> R.string.native_chat_delegation_mode_auto
                DelegationMode.PREFER -> R.string.native_chat_delegation_mode_prefer
                DelegationMode.ONLY_WHEN_ASKED -> R.string.native_chat_delegation_mode_ask
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == option, onClick = { onMode(option) })
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ApprovalBlock(policy: DelegationApprovalPolicy, onPolicy: (DelegationApprovalPolicy) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.native_chat_delegation_approval_title), style = MaterialTheme.typography.titleSmall)
        DelegationApprovalPolicy.entries.forEach { option ->
            val (label, desc) = when (option) {
                DelegationApprovalPolicy.SUBAGENT_DECIDES ->
                    R.string.native_chat_delegation_approval_subagent to R.string.native_chat_delegation_approval_subagent_desc
                DelegationApprovalPolicy.FULL_ACCESS ->
                    R.string.native_chat_delegation_approval_full to R.string.native_chat_delegation_approval_full_desc
                DelegationApprovalPolicy.ASK_ME ->
                    R.string.native_chat_delegation_approval_ask to R.string.native_chat_delegation_approval_ask_desc
            }
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                RadioButton(selected = policy == option, onClick = { onPolicy(option) })
                Column(modifier = Modifier.weight(1f).padding(top = 12.dp)) {
                    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private enum class PendingClear { NONE, SUBAGENT, HARNESS }

@Composable
private fun CachesBlock(
    chatId: Long?,
    runtimeId: String?,
    subagentCacheEnabled: Boolean,
    onSubagentCacheEnabled: (Boolean) -> Unit,
    defaultsMode: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember(context) { NativeChatDelegation.get(context) }
    val kvSettings = remember(context) { HarnessKvCacheSettings.get(context) }
    val harnessEnabled by kvSettings.enabled.collectAsState()
    val task by remember(chatId) {
        if (chatId != null) api.observeLatestTask(chatId) else kotlinx.coroutines.flow.flowOf(null)
    }.collectAsState(initial = null)
    var tick by remember { mutableIntStateOf(0) }
    var subagentBytes by remember { mutableLongStateOf(0L) }
    var harnessBytes by remember { mutableLongStateOf(0L) }
    var pending by remember { mutableStateOf(PendingClear.NONE) }
    val taskId = task?.id

    LaunchedEffect(taskId, runtimeId, tick) {
        withContext(Dispatchers.IO) {
            subagentBytes = taskId?.let { api.subagentCacheBytes(it) } ?: 0L
            harnessBytes = runtimeId?.let { kvSettings.sizeForRuntime(it) } ?: 0L
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.native_chat_delegation_caches_title), style = MaterialTheme.typography.titleSmall)
        SwitchRow(stringResource(R.string.native_chat_delegation_subagent_cache), subagentCacheEnabled, onSubagentCacheEnabled)
        // Sizes, clearing and the app-wide harness cache switch belong to a real chat/runtime.
        if (!defaultsMode) CacheSizeRow(
            label = stringResource(R.string.native_chat_delegation_subagent_cache_size, Formatter.formatShortFileSize(context, subagentBytes)),
            canClear = subagentBytes > 0 && taskId != null
        ) { pending = PendingClear.SUBAGENT }
        if (!defaultsMode) {
            SwitchRow(
                stringResource(R.string.native_chat_delegation_harness_cache),
                harnessEnabled
            ) { kvSettings.setEnabled(it) }
            Text(
                stringResource(R.string.native_chat_delegation_harness_cache_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CacheSizeRow(
                label = stringResource(R.string.native_chat_delegation_harness_cache_size, Formatter.formatShortFileSize(context, harnessBytes)),
                canClear = harnessBytes > 0 && runtimeId != null
            ) { pending = PendingClear.HARNESS }
        }
    }

    if (pending != PendingClear.NONE) {
        val isHarness = pending == PendingClear.HARNESS
        AlertDialog(
            onDismissRequest = { pending = PendingClear.NONE },
            title = { Text(stringResource(R.string.native_chat_delegation_clear_title)) },
            text = {
                Text(
                    stringResource(
                        if (isHarness) R.string.native_chat_delegation_clear_harness_message
                        else R.string.native_chat_delegation_clear_subagent_message
                    ),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = pending
                    pending = PendingClear.NONE
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            when (target) {
                                PendingClear.SUBAGENT -> taskId?.let { api.clearSubagentCache(it) }
                                PendingClear.HARNESS -> runtimeId?.let { kvSettings.clearRuntime(it) }
                                PendingClear.NONE -> Unit
                            }
                        }
                        tick++
                    }
                }) { Text(stringResource(R.string.native_chat_delegation_clear)) }
            },
            dismissButton = { TextButton(onClick = { pending = PendingClear.NONE }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun CacheSizeRow(label: String, canClear: Boolean, onClear: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp))
        OutlinedButton(enabled = canClear, onClick = onClear) {
            Text(stringResource(R.string.native_chat_delegation_clear), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
