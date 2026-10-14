package com.example.llamadroid.ui.ai.llama.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.normalizeLlamaServerEngine
import com.example.llamadroid.data.repository.UnifiedServerKind
import com.example.llamadroid.service.delegation.DelegationServerChoice
import com.example.llamadroid.service.delegation.NativeChatDelegationConfig
import com.example.llamadroid.service.LlamaKvUnifiedMode
import com.example.llamadroid.service.isNativeChatLoopbackHost
import com.example.llamadroid.ui.ai.llama.servers.ServerPickerSheet
import com.example.llamadroid.ui.ai.llama.servers.ServerRegistryViewModel
import com.example.llamadroid.ui.ai.llama.servers.serverProfileEditorRoute

/** What the delegation section needs to know about one server row. */
internal data class DelegationServerInfo(
    val rowId: Long,
    val name: String,
    val engine: String,
    val isRemote: Boolean,
    val cardId: Long?,
    /** Total context of a phone-local card (null for remote or legacy rows). */
    val contextTokens: Int?,
    /** `--parallel` of a phone-local card (null when unknown). */
    val parallel: Int?,
    /** `--kv-unified` is forced on, so every slot can use the full context. */
    val kvUnified: Boolean = false
)

/** Resolves [rowId] through the shared server registry; [fallback] covers the chat's own row. */
@Composable
internal fun rememberDelegationServerInfo(
    registryViewModel: ServerRegistryViewModel,
    rowId: Long?,
    fallback: LlamaServerEntity? = null
): DelegationServerInfo? {
    val servers by registryViewModel.servers.collectAsState()
    val unified = rowId?.let { id -> servers.firstOrNull { it.rowId == id } }
    val info by produceState<DelegationServerInfo?>(
        initialValue = null,
        rowId, unified?.cardId, unified?.name, unified?.kind, fallback?.id
    ) {
        value = when {
            rowId == null -> null
            unified != null -> {
                val seed = unified.cardId
                    ?.takeIf { unified.kind == UnifiedServerKind.LOCAL_MANAGED }
                    ?.let { runCatching { registryViewModel.loadManagedSeed(it) }.getOrNull() }
                DelegationServerInfo(
                    rowId = rowId,
                    name = unified.name,
                    engine = normalizeLlamaServerEngine(unified.engine),
                    isRemote = unified.kind == UnifiedServerKind.REMOTE,
                    cardId = unified.cardId,
                    contextTokens = seed?.profile?.contextSize,
                    parallel = seed?.profile?.parallel ?: seed?.let { 1 },
                    kvUnified = LlamaKvUnifiedMode.fromValue(seed?.profile?.kvUnifiedMode) == LlamaKvUnifiedMode.ENABLED
                )
            }
            fallback != null && fallback.id == rowId -> DelegationServerInfo(
                rowId = rowId,
                name = fallback.name,
                engine = fallback.normalizedEngine(),
                isRemote = !isNativeChatLoopbackHost(fallback.host),
                cardId = null,
                contextTokens = null,
                parallel = null
            )
            else -> null
        }
    }
    return info
}

@Composable
private fun ChoiceRadioRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
    }
}

/** Number field that only reports values accepted by [validate]; shows an inline error otherwise. */
@Composable
private fun DelegationLimitField(
    label: String,
    value: Int?,
    resetKey: Any?,
    min: Int,
    max: Int,
    validate: (String) -> Int?,
    onValid: (Int) -> Unit
) {
    var text by remember(resetKey) { mutableStateOf(value?.toString().orEmpty()) }
    val invalid = validate(text) == null
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            val filtered = next.filter { it.isDigit() }.take(8)
            text = filtered
            validate(filtered)?.let(onValid)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        isError = invalid,
        supportingText = if (invalid) {
            { Text(stringResource(R.string.native_chat_delegation_limit_error, min, max)) }
        } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

/**
 * Server choice of one participant: "Same as this chat" or "Another server" (picker filtered by
 * engine). Remote/LiteRT servers expose context and output fields; phone-local cards show their
 * own values read-only.
 */
@Composable
internal fun DelegationServerChoiceBlock(
    title: String,
    hint: String,
    forSubagent: Boolean,
    choice: DelegationServerChoice,
    onChoice: (DelegationServerChoice) -> Unit,
    registryViewModel: ServerRegistryViewModel,
    navController: NavController?,
    sameAsChatReason: String? = null
) {
    var showPicker by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceRadioRow(
            selected = choice is DelegationServerChoice.SameAsChat,
            label = stringResource(R.string.native_chat_delegation_server_same)
        ) { onChoice(DelegationServerChoice.SameAsChat) }
        if (sameAsChatReason != null && choice is DelegationServerChoice.SameAsChat) {
            Text(sameAsChatReason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        ChoiceRadioRow(
            selected = choice is DelegationServerChoice.Server,
            label = stringResource(R.string.native_chat_delegation_server_other)
        ) { showPicker = true }
        if (choice is DelegationServerChoice.Server) {
            val info = rememberDelegationServerInfo(registryViewModel, choice.rowId)
            Column(
                modifier = Modifier.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = info?.name ?: stringResource(R.string.native_chat_delegation_server_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (info == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = { showPicker = true }) {
                        Text(stringResource(R.string.native_chat_delegation_server_change), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (info != null && delegationNeedsLimitFields(info.engine, info.isRemote)) {
                    DelegationLimitField(
                        label = stringResource(R.string.native_chat_delegation_max_context),
                        value = choice.contextTokens,
                        resetKey = choice.rowId,
                        min = NativeChatDelegationConfig.MIN_CONTEXT_TOKENS,
                        max = NativeChatDelegationConfig.MAX_CONTEXT_TOKENS,
                        validate = ::validateDelegationContext,
                        onValid = { onChoice(choice.copy(contextTokens = it)) }
                    )
                    DelegationLimitField(
                        label = stringResource(R.string.native_chat_delegation_max_output),
                        value = choice.maxOutputTokens,
                        resetKey = choice.rowId,
                        min = NativeChatDelegationConfig.MIN_OUTPUT_TOKENS,
                        max = NativeChatDelegationConfig.MAX_OUTPUT_TOKENS,
                        validate = ::validateDelegationMaxOutput,
                        onValid = { onChoice(choice.copy(maxOutputTokens = it)) }
                    )
                } else if (info?.contextTokens != null) {
                    Text(
                        stringResource(
                            R.string.native_chat_delegation_card_values,
                            info.contextTokens,
                            info.parallel ?: 1
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    info.cardId?.takeIf { navController != null }?.let { cardId ->
                        OutlinedButton(onClick = { navController?.navigate(serverProfileEditorRoute(cardId, null)) }) {
                            Text(stringResource(R.string.native_chat_delegation_edit_server), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
    if (showPicker) {
        val currentRow = (choice as? DelegationServerChoice.Server)?.rowId
        ServerPickerSheet(
            viewModel = registryViewModel,
            selectedRowId = currentRow,
            onDismiss = { showPicker = false },
            onPick = {},
            title = title,
            onPickServer = { server ->
                showPicker = false
                val rowId = server.rowId ?: return@ServerPickerSheet
                val previous = choice as? DelegationServerChoice.Server
                val needsLimits = delegationNeedsLimitFields(server.engine, server.kind == UnifiedServerKind.REMOTE)
                onChoice(
                    if (previous != null && previous.rowId == rowId) previous
                    else DelegationServerChoice.Server(
                        rowId = rowId,
                        contextTokens = if (needsLimits) DELEGATION_DEFAULT_REMOTE_CONTEXT else null,
                        maxOutputTokens = if (needsLimits) DELEGATION_DEFAULT_REMOTE_MAX_OUTPUT else null
                    )
                )
            },
            filter = { isDelegationServerEligible(it.engine, forSubagent) }
        )
    }
}

@Composable
private fun participantName(who: DelegationParticipant): String = stringResource(
    when (who) {
        DelegationParticipant.MAIN_CHAT -> R.string.native_chat_delegation_who_main
        DelegationParticipant.SUBAGENT -> R.string.native_chat_delegation_who_subagent
        DelegationParticipant.HARNESS_AGENT -> R.string.native_chat_delegation_who_harness
    }
)

/** One "Server X: N slots ..." line plus a slot-shortage warning for a server shared by [who]. */
@Composable
private fun DelegationServerUsageLine(
    info: DelegationServerInfo?,
    who: Set<DelegationParticipant>,
    parallelRequests: Boolean,
    navController: NavController?
) {
    val names = who.map { participantName(it) }.joinToString(", ")
    val name = info?.name ?: stringResource(R.string.native_chat_delegation_server_missing)
    val slots = info?.takeIf { it.cardId != null }?.parallel
    val context = info?.contextTokens
    val line = when {
        slots != null && context != null -> stringResource(
            R.string.native_chat_delegation_parallel_line,
            name, slots, delegationContextPerSlot(context, slots, info.kvUnified), names
        )
        info?.isRemote == true -> stringResource(R.string.native_chat_delegation_parallel_line_remote, name, names)
        else -> stringResource(R.string.native_chat_delegation_parallel_line_unknown, name, names)
    }
    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val shortfall = if (DelegationParticipant.HARNESS_AGENT in who && info?.cardId != null) {
        delegationHarnessSlotShortfall(context, slots, info.kvUnified)
    } else null
    if (shortfall != null && info?.cardId != null) {
        Text(
            if ((slots ?: 1) > 1) stringResource(R.string.native_chat_delegation_harness_slot_warning, slots ?: 1, shortfall)
            else stringResource(R.string.native_chat_delegation_harness_context_warning, shortfall),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        if (navController != null) {
            OutlinedButton(onClick = { navController.navigate(serverProfileEditorRoute(info.cardId, null)) }) {
                Text(stringResource(R.string.native_chat_delegation_change_slots), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (info?.cardId != null && delegationNeedsMoreSlots(who.size, slots, parallelRequests)) {
        Text(
            stringResource(R.string.native_chat_delegation_parallel_warning, delegationRecommendedSlots(who.size)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        if (navController != null) {
            OutlinedButton(onClick = { navController.navigate(serverProfileEditorRoute(info.cardId, null)) }) {
                Text(stringResource(R.string.native_chat_delegation_change_slots), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Parallel-requests switch with per-server slot advice for the participants that share a server. */
@Composable
internal fun DelegationParallelBlock(
    settings: ChatSettingsState,
    chatServer: LlamaServerEntity?,
    registryViewModel: ServerRegistryViewModel,
    navController: NavController?,
    onSettingsChanged: () -> Unit
) {
    val config = settings.delegation
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.native_chat_delegation_parallel_title),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            Switch(
                checked = config.parallelRequests,
                onCheckedChange = {
                    settings.delegation = config.copy(parallelRequests = it)
                    onSettingsChanged()
                }
            )
        }
        Text(
            stringResource(
                if (config.parallelRequests) R.string.native_chat_delegation_parallel_on_desc
                else R.string.native_chat_delegation_parallel_off_desc
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val groups = delegationParticipantsByServer(chatServer?.id, config)
        groups.forEach { (rowId, who) ->
            val info = rememberDelegationServerInfo(registryViewModel, rowId, chatServer)
            DelegationServerUsageLine(info, who, config.parallelRequests, navController)
        }
    }
}
