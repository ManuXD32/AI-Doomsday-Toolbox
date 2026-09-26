package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppTaskActionFooter

/** A reference supplied by the canonical Harness file/session resolver. */
data class HarnessComposerReferenceUi(
    val key: String,
    val title: String,
    val mention: String,
    val kind: String = "file",
    val detail: String? = null,
)

internal enum class HarnessComposerTrigger { SLASH, AT }

private data class HarnessComposerSuggestion(
    val key: String,
    val label: String,
    val insertion: String,
    val description: String?,
    val trigger: HarnessComposerTrigger,
    val clientCommand: String? = null,
)

/** Native equivalents of client pickers/panels; executable commands come from commands/list. */
private val HARNESS_CLIENT_COMMANDS = listOf(
    "model",
    "goal",
    "permission",
    "plan",
)

internal fun insertHarnessComposerTrigger(value: TextFieldValue, trigger: HarnessComposerTrigger): TextFieldValue {
    val cursor = value.selection.end.coerceIn(0, value.text.length)
    val separator = if (cursor > 0 && !value.text[cursor - 1].isWhitespace()) " " else ""
    val prefix = separator + if (trigger == HarnessComposerTrigger.SLASH) "/" else "@"
    return TextFieldValue(
        value.text.substring(0, cursor) + prefix + value.text.substring(cursor),
        TextRange(cursor + prefix.length),
    )
}

/** Pure cursor-aware replacement used by the inline command and reference picker. */
internal fun replaceHarnessComposerToken(
    value: TextFieldValue,
    insertion: String,
    trigger: HarnessComposerTrigger,
): TextFieldValue {
    val cursor = value.selection.end.coerceIn(0, value.text.length)
    val beforeCursor = value.text.substring(0, cursor)
    val tokenStart = when (trigger) {
        HarnessComposerTrigger.AT -> harnessComposerAtStart(value.text, cursor)
            ?: beforeCursor.indexOfLast { it.isWhitespace() }.let { index -> if (index < 0) 0 else index + 1 }
        HarnessComposerTrigger.SLASH -> beforeCursor.indexOfLast { it.isWhitespace() }.let { index ->
            if (index < 0) 0 else index + 1
        }
    }
    val token = beforeCursor.substring(tokenStart)
    if (trigger == HarnessComposerTrigger.SLASH && !token.startsWith("/")) return value
    if (trigger == HarnessComposerTrigger.AT && !token.startsWith("@")) return value
    val afterCursor = value.text.substring(cursor)
    // A menu may provide a trailing separator; reuse it instead of doubling
    // the existing space immediately after the replaced token.
    val remainingText = if (insertion.endsWith(" ") && afterCursor.startsWith(" ")) {
        afterCursor.drop(1)
    } else afterCursor
    val suffix = if (insertion.endsWith(" ") || insertion.endsWith("/") ||
        afterCursor.firstOrNull()?.isWhitespace() == true
    ) "" else " "
    val replacement = insertion + suffix
    val updated = value.text.substring(0, tokenStart) + replacement + remainingText
    val newCursor = tokenStart + replacement.length
    return TextFieldValue(updated, TextRange(newCursor))
}

/** Returns the active @ token start, including quoted file/session names. */
private fun harnessComposerAtStart(text: String, cursor: Int): Int? {
    val boundedCursor = cursor.coerceIn(0, text.length)
    var quoted = false
    var candidate: Int? = null
    for (index in 0 until boundedCursor) {
        val character = text[index]
        if (character == '"' && (index == 0 || text[index - 1] != '\\')) {
            quoted = !quoted
            continue
        }
        if (!quoted && character == '@' && (index == 0 || text[index - 1].isWhitespace())) {
            candidate = index
        } else if (!quoted && character.isWhitespace()) {
            candidate = null
        }
    }
    return candidate
}

private fun harnessComposerTrigger(value: TextFieldValue): HarnessComposerTrigger? {
    val cursor = value.selection.end.coerceIn(0, value.text.length)
    val token = harnessComposerToken(value)
    return when {
        token.startsWith("/") -> HarnessComposerTrigger.SLASH
        harnessComposerAtStart(value.text, cursor) != null -> HarnessComposerTrigger.AT
        else -> null
    }
}

private fun harnessComposerToken(value: TextFieldValue): String {
    val cursor = value.selection.end.coerceIn(0, value.text.length)
    val before = value.text.substring(0, cursor)
    val atStart = harnessComposerAtStart(value.text, cursor)
    if (atStart != null) return before.substring(atStart)
    return before.substring(before.indexOfLast { it.isWhitespace() } + 1)
}

private fun clearHarnessComposerToken(
    value: TextFieldValue,
    trigger: HarnessComposerTrigger,
): TextFieldValue {
    val cursor = value.selection.end.coerceIn(0, value.text.length)
    val before = value.text.substring(0, cursor)
    val tokenStart = when (trigger) {
        HarnessComposerTrigger.AT -> harnessComposerAtStart(value.text, cursor)
            ?: before.indexOfLast { it.isWhitespace() }.let { if (it < 0) 0 else it + 1 }
        HarnessComposerTrigger.SLASH -> before.indexOfLast { it.isWhitespace() }.let { if (it < 0) 0 else it + 1 }
    }
    val token = before.substring(tokenStart)
    val expected = if (trigger == HarnessComposerTrigger.AT) "@" else "/"
    if (!token.startsWith(expected)) return value
    val updated = value.text.removeRange(tokenStart, cursor)
    return TextFieldValue(updated, TextRange(tokenStart))
}

@Composable
internal fun HarnessComposerSlot(
    state: NativeHarnessUiState,
    onAction: (NativeHarnessUiAction) -> Unit,
    references: List<HarnessComposerReferenceUi> = emptyList(),
    onReferenceQuery: (String?) -> Unit = {},
    onClientCommand: (String) -> Unit = {},
) {
    val enabled = state.runtime.status != HarnessRuntimeStatus.STOPPING &&
        state.selectedSessionId != null &&
        state.selectedSessionId !in state.deletingSessionIds
    val modelPickerEnabled = harnessModelPickerEnabled(state)
    Column(modifier = Modifier.fillMaxWidth()) {
        HarnessUsageSummaryStrip(state)
        HarnessGenerationStatus(
            activity = state.generationActivity,
            canCancelTurn = state.canCancelTurn,
            isStoppingTurn = state.isStoppingTurn,
        )
        HarnessComposer(
            state = state,
            enabled = enabled,
            modelPickerEnabled = modelPickerEnabled,
            references = references,
            onReferenceQuery = onReferenceQuery,
            onClientCommand = onClientCommand,
            onTextChange = { onAction(NativeHarnessUiAction.UpdateComposer(it)) },
            onModeChange = { onAction(NativeHarnessUiAction.SetComposerMode(it)) },
            onOpenAttachmentPicker = { onAction(NativeHarnessUiAction.OpenAttachmentPicker) },
            onOpenReferencePicker = { onAction(NativeHarnessUiAction.OpenReferencePicker) },
            onOpenAttachment = { onAction(NativeHarnessUiAction.OpenAttachment(it)) },
            onRemoveAttachment = { onAction(NativeHarnessUiAction.RemoveAttachment(it)) },
            onCancelTurn = { onAction(NativeHarnessUiAction.CancelTurn) },
            onSubmit = { onAction(NativeHarnessUiAction.SubmitComposer) },
            onSelectModel = { providerId, modelId ->
                onAction(NativeHarnessUiAction.SelectSessionModel(providerId, modelId))
            },
            onRefreshModelCatalog = { onAction(NativeHarnessUiAction.RefreshModelCatalog) },
            onSelectReasoning = { onAction(NativeHarnessUiAction.SelectReasoningEffort(it)) },
            onSelectPermission = { onAction(NativeHarnessUiAction.SelectPermissionPreset(it)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HarnessComposer(
    state: NativeHarnessUiState,
    enabled: Boolean,
    modelPickerEnabled: Boolean,
    references: List<HarnessComposerReferenceUi>,
    onReferenceQuery: (String?) -> Unit,
    onClientCommand: (String) -> Unit,
    onTextChange: (String) -> Unit,
    onModeChange: (HarnessPromptMode) -> Unit,
    onOpenAttachmentPicker: () -> Unit,
    onOpenReferencePicker: () -> Unit,
    onOpenAttachment: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onCancelTurn: () -> Unit,
    onSubmit: () -> Unit,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onRefreshModelCatalog: () -> Unit,
    onSelectReasoning: (String?) -> Unit,
    onSelectPermission: (String) -> Unit,
) {
    val sessionKey = state.selectedSessionId ?: "no-session"
    var draft by rememberSaveable(sessionKey, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.composerText))
    }
    // Controller updates are authoritative after submit, session changes, and external inserts.
    LaunchedEffect(state.composerText, state.selectedSessionId) {
        if (state.composerText != draft.text) {
            draft = TextFieldValue(state.composerText)
        }
    }
    var explicitTrigger by rememberSaveable(sessionKey) { mutableStateOf<HarnessComposerTrigger?>(null) }
    var dismissedToken by rememberSaveable(sessionKey) { mutableStateOf<String?>(null) }
    var modelMenu by remember { mutableStateOf(false) }
    var reasoningMenu by remember { mutableStateOf(false) }
    var permissionMenu by remember { mutableStateOf(false) }
    var deliveryMenu by remember { mutableStateOf(false) }
    val typedTrigger = harnessComposerTrigger(draft)
    val rawToken = harnessComposerToken(draft)
    val trigger = explicitTrigger ?: typedTrigger?.takeUnless { rawToken == dismissedToken }
    val token = rawToken.removePrefix("/").removePrefix("@").removePrefix("\"").removeSuffix("\"")
    LaunchedEffect(state.selectedSessionId, trigger, token) {
        onReferenceQuery(
            if (trigger == HarnessComposerTrigger.AT && state.selectedSessionId != null) token else null,
        )
    }
    val selectedProvider = state.provider.providers.firstOrNull { it.id == state.provider.selectedProviderId }
    val slashSuggestions = remember(state.commands, state.extensions.skills) {
        buildList {
            HARNESS_CLIENT_COMMANDS.forEach { name ->
                add(HarnessComposerSuggestion("client:$name", "/$name", "/$name", null, HarnessComposerTrigger.SLASH, name))
            }
            state.commands.forEach { command ->
                add(HarnessComposerSuggestion("command:${command.name}", command.name, command.name, command.description, HarnessComposerTrigger.SLASH))
            }
            state.extensions.skills.forEach { skill ->
                val label = skill.name.let { if (it.startsWith("/")) it else "/$it" }
                add(HarnessComposerSuggestion("skill:${skill.id}", label, label, skill.summary, HarnessComposerTrigger.SLASH))
            }
        }.distinctBy { it.insertion }
    }
    val referenceSuggestions = remember(references) {
        references.map { reference ->
            HarnessComposerSuggestion(
                key = "ref:${reference.key}",
                label = reference.title,
                insertion = reference.mention,
                description = reference.detail,
                trigger = HarnessComposerTrigger.AT,
            )
        }.distinctBy { it.key }
    }
    val matchingSuggestions = when (trigger) {
        HarnessComposerTrigger.SLASH -> slashSuggestions
            .filter { token.isBlank() || it.label.removePrefix("/").contains(token, ignoreCase = true) }
        HarnessComposerTrigger.AT -> referenceSuggestions
            .filter { token.isBlank() || it.label.contains(token, ignoreCase = true) }
        null -> emptyList()
    }
    var suggestionPage by remember(trigger, token) { mutableStateOf(0) }
    val page = suggestionPage.coerceIn(0, (matchingSuggestions.size - 1).coerceAtLeast(0) / 80)
    val suggestions = matchingSuggestions.drop(page * 80).take(80)

    fun chooseSuggestion(suggestion: HarnessComposerSuggestion) {
        suggestion.clientCommand?.let { command ->
            val next = clearHarnessComposerToken(draft, suggestion.trigger)
            draft = next
            onTextChange(next.text)
            explicitTrigger = null
            dismissedToken = null
            when (command) {
                "model" -> modelMenu = true
                "permission" -> permissionMenu = true
                else -> onClientCommand(command)
            }
            return
        }
        val prepared = if (harnessComposerTrigger(draft) == null && harnessComposerToken(draft).isBlank()) {
            val cursor = draft.selection.end.coerceIn(0, draft.text.length)
            val prefix = if (suggestion.trigger == HarnessComposerTrigger.SLASH) "/" else "@"
            val withTrigger = draft.text.substring(0, cursor) + prefix + draft.text.substring(cursor)
            TextFieldValue(withTrigger, TextRange(cursor + 1))
        } else {
            draft
        }
        val next = replaceHarnessComposerToken(prepared, suggestion.insertion, suggestion.trigger)
        draft = next
        onTextChange(next.text)
        explicitTrigger = null
        dismissedToken = null
    }

    fun activateTrigger(nextTrigger: HarnessComposerTrigger) {
        val currentTrigger = harnessComposerTrigger(draft)
        if (currentTrigger == nextTrigger) {
            explicitTrigger = nextTrigger
            return
        }
        val next = insertHarnessComposerTrigger(draft, nextTrigger)
        draft = next
        onTextChange(next.text)
        explicitTrigger = nextTrigger
        dismissedToken = null
    }

    fun submit() {
        val command = draft.text.trim().removePrefix("/")
        if (draft.text.trim().startsWith('/') && command in HARNESS_CLIENT_COMMANDS) {
            chooseSuggestion(HarnessComposerSuggestion("client:$command", "/$command", "/$command", null,
                HarnessComposerTrigger.SLASH, command))
        } else onSubmit()
    }

    AppTaskActionFooter {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp),
                ) {
                    items(state.attachments, key = { it.id }) { attachment ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = false,
                                onClick = { onOpenAttachment(attachment.id) },
                                label = { Text(attachment.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                                enabled = !attachment.isUploading,
                            )
                            if (attachment.canRemove) {
                                IconButton(
                                    onClick = { onRemoveAttachment(attachment.id) },
                                    enabled = !attachment.isUploading,
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(Icons.Default.Close, stringResource(R.string.harness_remove_attachment))
                                }
                            }
                        }
                    }
                }
            }
            HarnessComposerSessionControls(
                state = state,
                enabled = enabled,
                modelPickerEnabled = modelPickerEnabled,
                modelExpanded = modelMenu,
                onModelExpandedChange = { modelMenu = it },
                reasoningExpanded = reasoningMenu,
                onReasoningExpandedChange = { reasoningMenu = it },
                permissionExpanded = permissionMenu,
                onPermissionExpandedChange = { permissionMenu = it },
                onSelectModel = onSelectModel,
                onRefreshModelCatalog = onRefreshModelCatalog,
                onSelectReasoning = onSelectReasoning,
                selectedProvider = selectedProvider,
            )
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { next ->
                                val changed = next.text != draft.text
                                draft = next
                                onTextChange(next.text)
                                if (changed) dismissedToken = null
                                if (explicitTrigger != null && harnessComposerTrigger(next) == null) explicitTrigger = null
                            },
                            modifier = Modifier.fillMaxWidth().testTag("harness_composer"),
                            enabled = enabled,
                            label = { Text(stringResource(R.string.harness_composer_label)) },
                            placeholder = { Text(stringResource(R.string.harness_composer_hint)) },
                            minLines = 1,
                            maxLines = 3,
                        )
                        DropdownMenu(
                            expanded = trigger != null && (suggestions.isNotEmpty() || trigger == HarnessComposerTrigger.AT),
                            onDismissRequest = {
                                dismissedToken = rawToken
                                explicitTrigger = null
                            },
                            modifier = Modifier.widthIn(min = 240.dp, max = 360.dp),
                            properties = PopupProperties(focusable = false),
                        ) {
                            suggestions.forEach { suggestion ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(suggestion.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            suggestion.description?.takeIf { it.isNotBlank() }?.let {
                                                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    },
                                    onClick = { chooseSuggestion(suggestion) },
                                )
                            }
                            if (page > 0) DropdownMenuItem(
                                text = { Text(stringResource(R.string.harness_history_previous_page)) },
                                onClick = { suggestionPage = page - 1 },
                            )
                            if ((page + 1) * 80 < matchingSuggestions.size) DropdownMenuItem(
                                text = { Text(stringResource(R.string.harness_history_next_page)) },
                                onClick = { suggestionPage = page + 1 },
                            )
                            if (trigger == HarnessComposerTrigger.AT) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.harness_reference_browse_all)) },
                                    onClick = {
                                        dismissedToken = rawToken
                                        explicitTrigger = null
                                        onOpenReferencePicker()
                                    },
                                )
                            }
                        }
                    }
                    val showStopControl = state.canCancelTurn || state.isStoppingTurn
                    Column(
                        modifier = Modifier.width(48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (showStopControl) {
                            IconButton(
                                onClick = onCancelTurn,
                                enabled = state.canCancelTurn && !state.isStoppingTurn,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("harness_stop_turn"),
                            ) {
                                Icon(
                                    Icons.Default.Stop,
                                    contentDescription = stringResource(
                                        if (state.isStoppingTurn) R.string.harness_stopping_turn
                                        else R.string.harness_stop
                                    ),
                                )
                            }
                        }
                        IconButton(
                            onClick = ::submit,
                            enabled = enabled && draft.text.isNotBlank(),
                            modifier = Modifier.size(48.dp).testTag("harness_submit"),
                        ) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.harness_submit)) }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = { activateTrigger(HarnessComposerTrigger.SLASH) },
                    enabled = enabled,
                    modifier = Modifier.testTag("harness_composer_commands"),
                ) { Text("/") }
                TextButton(
                    onClick = { activateTrigger(HarnessComposerTrigger.AT) },
                    enabled = enabled,
                    modifier = Modifier.testTag("harness_composer_references"),
                ) { Text("@") }
                IconButton(
                    onClick = onOpenAttachmentPicker,
                    enabled = enabled,
                    modifier = Modifier.size(48.dp),
                ) { Icon(Icons.Default.AttachFile, stringResource(R.string.harness_add_attachment)) }
                Box {
                    FilterChip(
                        selected = deliveryMenu,
                        onClick = { deliveryMenu = true },
                        enabled = enabled,
                        label = {
                            Text(
                                stringResource(
                                    if (state.composerMode == HarnessPromptMode.QUEUE) {
                                        R.string.harness_queue_mode
                                    } else {
                                        R.string.harness_steer_mode
                                    },
                                ),
                                maxLines = 1,
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = deliveryMenu,
                        onDismissRequest = { deliveryMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.harness_queue_mode)) },
                            onClick = {
                                deliveryMenu = false
                                onModeChange(HarnessPromptMode.QUEUE)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.harness_steer_mode)) },
                            enabled = state.canCancelTurn,
                            onClick = {
                                deliveryMenu = false
                                onModeChange(HarnessPromptMode.STEER)
                            },
                        )
                    }
                }
            }
        }
    }

    if (permissionMenu) {
        AlertDialog(
            onDismissRequest = { permissionMenu = false },
            title = { Text(stringResource(R.string.harness_permission_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.harness_permission_description),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.permission.options.isEmpty()) {
                        Text(
                            stringResource(R.string.harness_permission_empty),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    state.permission.options.forEach { option ->
                        val selected = option.value == state.permission.currentValue
                        OutlinedButton(
                            onClick = {
                                onSelectPermission(option.value)
                                permissionMenu = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (selected) {
                                Icon(Icons.Default.Check, contentDescription = null)
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.Start,
                            ) {
                                Text(option.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                option.description?.takeIf(String::isNotBlank)?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { permissionMenu = false }) {
                    Text(stringResource(R.string.harness_close))
                }
            },
        )
    }
}

/** The model catalog can be refreshed and a default selected before a session exists. */
internal fun harnessModelPickerEnabled(state: NativeHarnessUiState): Boolean {
    val sessionId = state.selectedSessionId
    return state.runtime.status == HarnessRuntimeStatus.RUNNING &&
        (sessionId == null || sessionId !in state.deletingSessionIds)
}

@Composable
private fun HarnessComposerSessionControls(
    state: NativeHarnessUiState,
    enabled: Boolean,
    modelPickerEnabled: Boolean,
    selectedProvider: HarnessProviderOption?,
    modelExpanded: Boolean,
    onModelExpandedChange: (Boolean) -> Unit,
    reasoningExpanded: Boolean,
    onReasoningExpandedChange: (Boolean) -> Unit,
    permissionExpanded: Boolean,
    onPermissionExpandedChange: (Boolean) -> Unit,
    onSelectModel: (providerId: String, modelId: String) -> Unit,
    onRefreshModelCatalog: () -> Unit,
    onSelectReasoning: (String?) -> Unit,
) {
    val modelId = state.provider.selectedModel
    val modelLabel = selectedProvider?.let { modelId?.let { id -> harnessModelOptionLabel(it, id) } }
        ?: stringResource(R.string.harness_choose_model)
    val efforts = selectedProvider?.let { modelId?.let(it.reasoningEfforts::get) }.orEmpty()
    val effortLabel = efforts.firstOrNull { it.id == state.provider.selectedReasoningEffort }?.name
        ?: state.provider.selectedReasoningEffort
        ?: stringResource(R.string.harness_reasoning_provider_default)
    val permissionLabel = state.permission.options.firstOrNull { it.value == state.permission.currentValue }?.name
        ?: state.permission.currentValue
        ?: stringResource(R.string.harness_choose_value)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            FilterChip(
                selected = modelExpanded,
                onClick = { onModelExpandedChange(true) },
                enabled = modelPickerEnabled,
                label = { Text(modelLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
            DropdownMenu(expanded = modelExpanded, onDismissRequest = { onModelExpandedChange(false) }) {
                DropdownMenuItem(
                    text = {
                        Text(stringResource(
                            if (state.provider.isCatalogLoading) R.string.harness_refreshing_models
                            else R.string.harness_refresh_models
                        ))
                    },
                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    onClick = onRefreshModelCatalog,
                    enabled = !state.provider.isCatalogLoading,
                )
                if (state.provider.isCatalogLoading) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.harness_refreshing_models)) },
                        onClick = {},
                        enabled = false,
                    )
                }
                if (state.provider.catalogRefreshFailed) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.harness_model_refresh_failed),
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                }
                state.provider.catalogFailures.take(3).forEach { failure ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    R.string.harness_model_catalog_failure,
                                    failure.providerName,
                                    failure.message,
                                ),
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                }
                state.provider.providers.mapNotNull { provider ->
                    val selectableModels = provider.models.filter { harnessModelContextKnown(provider, it) }
                    if (selectableModels.isEmpty()) null else provider to selectableModels
                }.forEach { (provider, selectableModels) ->
                    DropdownMenuItem(
                        text = { Text(provider.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {},
                        enabled = false,
                    )
                    selectableModels.forEach { id ->
                        DropdownMenuItem(
                            text = { Text(harnessModelOptionLabel(provider, id), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            onClick = { onSelectModel(provider.id, id); onModelExpandedChange(false) },
                        )
                    }
                }
            }
        }
        Box {
            FilterChip(
                selected = reasoningExpanded,
                onClick = { onReasoningExpandedChange(true) },
                enabled = enabled && efforts.isNotEmpty(),
                label = { Text(effortLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
            DropdownMenu(expanded = reasoningExpanded, onDismissRequest = { onReasoningExpandedChange(false) }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.harness_reasoning_provider_default)) },
                    onClick = { onSelectReasoning(null); onReasoningExpandedChange(false) },
                )
                efforts.forEach { effort ->
                    DropdownMenuItem(
                        text = { Text(effort.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        onClick = { onSelectReasoning(effort.id); onReasoningExpandedChange(false) },
                    )
                }
            }
        }
        Box {
            FilterChip(
                selected = permissionExpanded,
                onClick = { onPermissionExpandedChange(true) },
                enabled = enabled && state.permission.options.isNotEmpty(),
                label = { Text(permissionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** Compact Pocket-style session selector shared by conversation and work tabs. */
@Composable
internal fun HarnessSessionStrip(
    sessions: List<HarnessSessionUiState>,
    onAction: (NativeHarnessUiAction) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val selected = sessions.firstOrNull { it.isSelected }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = sessions.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag("harness_session_selector"),
            ) {
                Text(
                    selected?.title ?: stringResource(R.string.harness_choose_session),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("⌄")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                sessions.forEach { session ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(session.projectFolder, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        leadingIcon = if (session.isSelected) { { Icon(Icons.Default.Check, null) } } else null,
                        onClick = { expanded = false; onAction(NativeHarnessUiAction.SelectSession(session.id)) },
                    )
                }
            }
        }
        IconButton(
            onClick = { onAction(NativeHarnessUiAction.CreateSession) },
            modifier = Modifier.testTag("harness_new_session"),
        ) { Icon(Icons.Default.Add, stringResource(R.string.harness_new_session)) }
    }
}

@Composable
internal fun HarnessSessionOverflowCardActions(
    session: HarnessSessionUiState,
    deleting: Boolean,
    deletionFailed: Boolean = false,
    onAction: (NativeHarnessUiAction) -> Unit,
) {
    var menuOpen by rememberSaveable(session.id) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(session.id) { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { menuOpen = true },
            enabled = !deleting || deletionFailed,
            modifier = Modifier.testTag("harness_session_actions_${session.id}"),
        ) { Icon(Icons.Default.MoreVert, stringResource(R.string.harness_session_actions)) }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = {
                    Text(stringResource(if (deletionFailed) R.string.harness_session_delete_retry_action else R.string.harness_session_delete))
                },
                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; confirmDelete = true },
            )
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.harness_session_delete_confirm_title)) },
            text = { Text(stringResource(R.string.harness_session_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onAction(NativeHarnessUiAction.DeleteSession(session.id))
                    },
                    enabled = !deleting || deletionFailed,
                ) {
                    Text(
                        stringResource(if (deletionFailed) R.string.harness_session_delete_retry_action else R.string.harness_session_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.harness_cancel)) } },
        )
    }
}
