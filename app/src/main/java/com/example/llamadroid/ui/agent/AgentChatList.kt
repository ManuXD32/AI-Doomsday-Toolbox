package com.example.llamadroid.ui.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.isBackgroundCommandReminder
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import androidx.compose.ui.text.input.ImeAction
import com.example.llamadroid.ui.ai.llama.MarkdownText
import coil.compose.AsyncImage
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
private fun agentRoleLabel(roleName: String): String {
    return when (roleName.uppercase()) {
        "ORCHESTRATOR" -> stringResource(R.string.agent_role_orchestrator)
        "CODER" -> stringResource(R.string.agent_role_coder)
        "REVIEWER" -> stringResource(R.string.agent_role_reviewer)
        "EXECUTOR" -> stringResource(R.string.agent_role_executor)
        "SUMMARIZER" -> stringResource(R.string.agent_role_summarizer)
        else -> roleName
    }
}

private fun formatAgentMessageTimestamp(timestamp: Long, locale: Locale = Locale.getDefault()): String {
    val nowCalendar = Calendar.getInstance()
    val messageCalendar = Calendar.getInstance().apply { timeInMillis = timestamp }
    val isSameDay = nowCalendar.get(Calendar.YEAR) == messageCalendar.get(Calendar.YEAR) &&
        nowCalendar.get(Calendar.DAY_OF_YEAR) == messageCalendar.get(Calendar.DAY_OF_YEAR)
    val pattern = if (isSameDay) "HH:mm" else "MMM dd HH:mm"
    return SimpleDateFormat(pattern, locale).format(Date(timestamp))
}

private fun formatToolDuration(durationMs: Long): String {
    val safeDurationMs = durationMs.coerceAtLeast(0L)
    return when {
        safeDurationMs < 1_000L -> "${safeDurationMs}ms"
        safeDurationMs < 60_000L -> "%.1fs".format(Locale.US, safeDurationMs / 1_000.0)
        else -> {
            val minutes = safeDurationMs / 60_000L
            val seconds = (safeDurationMs % 60_000L) / 1_000L
            "${minutes}m ${seconds}s"
        }
    }
}

private sealed class AgentChatListItem {
    abstract val key: String

    data class Message(
        val message: AgentService.Companion.ChatMessage
    ) : AgentChatListItem() {
        override val key: String = message.id
    }

    data class ToolGroup(
        val messages: List<AgentService.Companion.ChatMessage>,
        val resultsByToolCallId: Map<String, AgentService.Companion.ChatMessage>
    ) : AgentChatListItem() {
        override val key: String = "tool-group-${messages.firstOrNull()?.id.orEmpty()}-${messages.lastOrNull()?.id.orEmpty()}"
    }

    /** A delegation is a first-class timeline event, never an incidental tool row. */
    data class Delegation(
        val message: AgentService.Companion.ChatMessage?,
        val result: AgentService.Companion.ChatMessage?,
        val info: AgentDelegationInfo?
    ) : AgentChatListItem() {
        override val key: String = "delegation-${info?.invocationId ?: message?.id.orEmpty()}"
    }
}

/** Durable invocation metadata used by the parent timeline's delegation card. */
data class AgentDelegationInfo(
    val invocationId: String,
    val parentToolCallId: String,
    val displayName: String,
    val status: String,
    val task: String,
    val returnSummary: String? = null,
    val startedAt: Long = 0L
)

private fun resolvedToolName(message: AgentService.Companion.ChatMessage): String? =
    message.toolName ?: message.pendingToolCall?.name

private fun isGroupableToolCall(message: AgentService.Companion.ChatMessage): Boolean {
    return message.role == "assistant" &&
        resolvedToolName(message) != null &&
        !message.needsApproval &&
        !message.isPlan
}

private fun toolCallAgentKey(message: AgentService.Companion.ChatMessage): String {
    return message.customAgentName
        ?.takeIf { it.isNotBlank() }
        ?.let { "custom:$it" }
        ?: message.agentRole
            ?.takeIf { it.isNotBlank() }
            ?.let { "role:$it" }
        ?: "role:unknown"
}

private fun buildAgentChatListItems(
    visibleMessages: List<AgentService.Companion.ChatMessage>,
    allMessages: List<AgentService.Companion.ChatMessage>,
    delegationsByParentToolCallId: Map<String, AgentDelegationInfo> = emptyMap()
): List<AgentChatListItem> {
    val resultsByToolCallId = allMessages
        .asSequence()
        .filter { it.role == "tool" && it.toolCallId != null }
        .associateBy { it.toolCallId.orEmpty() }

    val items = mutableListOf<AgentChatListItem>()
    val visibleDelegationToolCallIds = visibleMessages
        .asSequence()
        .filter { it.role == "assistant" && resolvedToolName(it) == "call_agent" }
        .mapNotNull { it.toolCallId ?: it.pendingToolCall?.id }
        .toSet()
    val unmatchedDelegations = delegationsByParentToolCallId.values
        .filter { it.parentToolCallId !in visibleDelegationToolCallIds }
        .sortedBy { it.startedAt }
    var unmatchedIndex = 0

    fun appendUnmatchedDelegations(upToTimestamp: Long = Long.MAX_VALUE) {
        while (
            unmatchedIndex < unmatchedDelegations.size &&
            unmatchedDelegations[unmatchedIndex].startedAt <= upToTimestamp
        ) {
            val info = unmatchedDelegations[unmatchedIndex++]
            items += AgentChatListItem.Delegation(
                message = null,
                result = resultsByToolCallId[info.parentToolCallId],
                info = info
            )
        }
    }

    var index = 0
    while (index < visibleMessages.size) {
        val message = visibleMessages[index]
        appendUnmatchedDelegations(message.timestamp)
        // Keep delegation separate even when it is next to other tool calls. The
        // parent handoff is meaningful workflow, not merely tool activity.
        if (message.role == "assistant" && resolvedToolName(message) == "call_agent") {
            val toolCallId = message.toolCallId ?: message.pendingToolCall?.id
            items += AgentChatListItem.Delegation(
                message = message,
                result = toolCallId?.let(resultsByToolCallId::get),
                info = toolCallId?.let(delegationsByParentToolCallId::get)
            )
            index += 1
            continue
        }
        if (!isGroupableToolCall(message)) {
            items += AgentChatListItem.Message(message)
            index += 1
            continue
        }

        val group = mutableListOf(message)
        index += 1
        val groupAgentKey = toolCallAgentKey(message)
        while (
            index < visibleMessages.size &&
            isGroupableToolCall(visibleMessages[index]) &&
            toolCallAgentKey(visibleMessages[index]) == groupAgentKey
        ) {
            group += visibleMessages[index]
            index += 1
        }

        // A single call is still a tool sequence. Keeping it in the same compact
        // component makes the uncluttered presentation consistent.
        items += AgentChatListItem.ToolGroup(
            messages = group,
            resultsByToolCallId = resultsByToolCallId
        )
    }
    appendUnmatchedDelegations()
    return items
}

internal fun buildAgentChatRenderProjection(
    messages: List<AgentService.Companion.ChatMessage>,
    maxMessages: Int = AGENT_CHAT_RENDER_MESSAGE_LIMIT
): List<AgentService.Companion.ChatMessage> {
    val bounded = if (messages.size <= maxMessages) messages else messages.takeLast(maxMessages)
    return bounded.map { message ->
        if (message.role == "user" && AgentService.isQueuedGuidanceEnvelope(message.content)) {
            message.copy(
                content = AgentService.visibleQueuedGuidanceContent(message.content),
                guidanceDeliveryState = message.guidanceDeliveryState ?: "DELIVERED"
            )
        } else {
            message
        }
    }
}

/** Shared, persistence-safe timeline filtering for the root and invocation chats. */
internal fun buildVisibleAgentTimelineMessages(
    renderMessages: List<AgentService.Companion.ChatMessage>,
    showAllOutput: Boolean
): List<AgentService.Companion.ChatMessage> {
    val assistantTrackedToolCalls = renderMessages
        .asSequence()
        .filter { it.role == "assistant" && it.toolCallId != null && it.toolName != null }
        .mapNotNull { it.toolCallId }
        .toSet()

    return renderMessages.filter { msg ->
        when {
            // Tool results are presented inside their assistant tool row. Showing
            // the database transport message as a second chat bubble breaks
            // consecutive grouping and duplicates large output.
            msg.role == "tool" && msg.toolCallId != null &&
                assistantTrackedToolCalls.contains(msg.toolCallId) -> false
            // A request placeholder is persisted before a model produces text.
            // It is useful only while the live stream owns it; old placeholders
            // otherwise become empty ASSISTANT cards in restored timelines.
            msg.role == "assistant" && msg.content.isBlank() && msg.toolName == null &&
                msg.thinking.isNullOrBlank() && !msg.isStreaming -> false
            showAllOutput -> true
            msg.role == "system" ->
                msg.content.contains("ready") || AgentService.isTransientCompactionStatusMessageForUi(msg)
            isBackgroundCommandReminder(msg.toolName, msg.content, msg.toolOutput) -> false
            else -> true
        }
    }
}

/**
 * Reconciles durable invocation history with the coordinator's live projection.
 * A live row with the same stable message id replaces its Room counterpart so a
 * streaming placeholder is never duplicated when an incremental checkpoint lands.
 */
internal fun mergeInvocationTimelineMessages(
    persistedMessages: List<AgentService.Companion.ChatMessage>,
    liveMessages: List<AgentService.Companion.ChatMessage>,
    invocationId: String
): List<AgentService.Companion.ChatMessage> {
    val mergedById = LinkedHashMap<String, AgentService.Companion.ChatMessage>()
    persistedMessages
        .asSequence()
        .filter { it.invocationId == invocationId }
        .forEach { mergedById[it.id] = it }
    liveMessages
        .asSequence()
        .filter { it.invocationId == invocationId }
        .forEach { mergedById[it.id] = it }
    return mergedById.values.sortedWith(
        compareBy<AgentService.Companion.ChatMessage> { it.sequenceNumber }
            .thenBy { it.timestamp }
            .thenBy { it.id }
    )
}

private fun boundedPreview(raw: String, maxChars: Int): String =
    if (raw.length <= maxChars) raw else raw.take(maxChars) + "\n…"

@Composable
fun AgentChatList(
    messages: List<AgentService.Companion.ChatMessage>,
    listState: LazyListState,
    showAllOutput: Boolean,
    onApprove: (AgentService.Companion.ChatMessage) -> Unit,
    onDeny: (AgentService.Companion.ChatMessage) -> Unit,
    onDelete: (String) -> Unit,
    onRegenerate: (String) -> Unit,
    onEdit: (String, String) -> Unit,
    editingMessageId: String?,
    editingText: String,
    onEditingTextChange: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    resolvingPlanMessageId: String? = null,
    onToggleOutput: (String) -> Unit, // New callback
    onKnowledgeLinkClick: (String) -> Boolean = { false },
    delegationsByParentToolCallId: Map<String, AgentDelegationInfo> = emptyMap(),
    onOpenDelegation: (AgentDelegationInfo) -> Unit = {},
    readOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    val renderMessages = remember(messages) {
        buildAgentChatRenderProjection(messages)
    }
    val visibleMessages = remember(renderMessages, showAllOutput) {
        buildVisibleAgentTimelineMessages(renderMessages, showAllOutput)
    }
    val visibleItems = remember(visibleMessages, renderMessages, delegationsByParentToolCallId) {
        buildAgentChatListItems(visibleMessages, renderMessages, delegationsByParentToolCallId)
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(16.dp)
    ) {
        items(visibleItems, key = { it.key }) { item ->
            when (item) {
                is AgentChatListItem.Message -> {
                    val message = item.message
                    ChatMessageBubble(
                        message = message,
                        onApprove = { onApprove(message) },
                        onDeny = { onDeny(message) },
                        onDelete = { onDelete(message.id) },
                        onRegenerate = { onRegenerate(message.id) },
                        onEdit = { onEdit(message.id, message.content) },
                        isEditing = editingMessageId == message.id,
                        editingText = editingText,
                        onEditingTextChange = onEditingTextChange,
                        onSaveEdit = onSaveEdit,
                        onCancelEdit = onCancelEdit,
                        isPlanResolving = resolvingPlanMessageId == message.id,
                        onToggleOutput = { onToggleOutput(message.id) },
                        onKnowledgeLinkClick = onKnowledgeLinkClick,
                        showMessageActions = !readOnly
                    )
                }
                is AgentChatListItem.ToolGroup -> {
                    ToolCallGroupBubble(
                        messages = item.messages,
                        resultsByToolCallId = item.resultsByToolCallId,
                        onToggleOutput = onToggleOutput,
                        onKnowledgeLinkClick = onKnowledgeLinkClick,
                        useLocalOutputExpansion = readOnly
                    )
                }
                is AgentChatListItem.Delegation -> {
                    DelegationCard(
                        message = item.message,
                        result = item.result,
                        info = item.info,
                        onOpen = { item.info?.let(onOpenDelegation) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DelegationCard(
    message: AgentService.Companion.ChatMessage?,
    result: AgentService.Companion.ChatMessage?,
    info: AgentDelegationInfo?,
    onOpen: () -> Unit
) {
    val fallbackName = listOfNotNull(message?.toolArgs?.get("agent"), message?.toolArgs?.get("name"))
        .joinToString(" - ")
        .ifBlank { message?.toolArgs?.get("agent") ?: "Agent" }
    val isTerminal = info?.status?.let { it != "RUNNING" } ?: (result != null)
    val startedAt = info?.startedAt?.takeIf { it > 0L } ?: message?.timestamp ?: System.currentTimeMillis()
    val timestamp = remember(startedAt) { formatAgentMessageTimestamp(startedAt) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp)
            .clickable(enabled = info != null, onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFF9800).copy(alpha = 0.24f)),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Groups, null, tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(8.dp))
                Text(
                    info?.displayName ?: fallbackName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    delegationStatusLabel(info?.status, isTerminal),
                    style = MaterialTheme.typography.labelMedium
                )
            }
            (info?.task ?: message?.toolArgs?.get("task"))?.takeIf { it.isNotBlank() }?.let { task ->
                Text(task, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            (info?.returnSummary ?: result?.toolOutput ?: result?.content)
                ?.takeIf { isTerminal && it.isNotBlank() }
                ?.let { summary ->
                    Text(boundedPreview(summary, TOOL_OUTPUT_PREVIEW_CHARS), maxLines = 5, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            Text(
                "$timestamp · ${stringResource(R.string.agent_role_label, agentRoleLabel("ORCHESTRATOR"))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun delegationStatusLabel(status: String?, isTerminal: Boolean): String = when (status?.uppercase()) {
    "RUNNING" -> stringResource(R.string.agent_invocation_working)
    "COMPLETED" -> stringResource(R.string.agent_invocation_status_completed)
    "FAILED" -> stringResource(R.string.agent_invocation_status_failed)
    "CANCELLED" -> stringResource(R.string.agent_invocation_status_cancelled)
    "INTERRUPTED" -> stringResource(R.string.agent_invocation_status_interrupted)
    else -> stringResource(if (isTerminal) R.string.agent_invocation_finished else R.string.agent_invocation_working)
}

@Composable
private fun ToolCallGroupBubble(
    messages: List<AgentService.Companion.ChatMessage>,
    resultsByToolCallId: Map<String, AgentService.Companion.ChatMessage>,
    onToggleOutput: (String) -> Unit,
    onKnowledgeLinkClick: (String) -> Boolean,
    useLocalOutputExpansion: Boolean
) {
    var groupExpanded by remember(messages.firstOrNull()?.id, messages.size) { mutableStateOf(false) }
    val startedAt = messages.firstOrNull()?.timestamp ?: System.currentTimeMillis()
    val formattedTimestamp = remember(startedAt) { formatAgentMessageTimestamp(startedAt) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 380.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = 4.dp,
                bottomEnd = 20.dp
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { groupExpanded = !groupExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (groupExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.agent_tool_group_title),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.agent_tool_group_count, messages.size),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                AnimatedVisibility(
                    visible = groupExpanded,
                    enter = expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)),
                    exit = shrinkVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy))
                ) {
                    Column(
                        modifier = Modifier.padding(top = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        messages.forEach { toolMessage ->
                            ToolCallGroupRow(
                                message = toolMessage,
                                resultMessage = toolMessage.toolCallId?.let(resultsByToolCallId::get),
                                onToggleOutput = { onToggleOutput(toolMessage.id) },
                                onKnowledgeLinkClick = onKnowledgeLinkClick,
                                useLocalOutputExpansion = useLocalOutputExpansion
                            )
                        }
                    }
                }
            }
        }
        Text(
            text = "$formattedTimestamp · ${stringResource(R.string.agent_role_label, agentRoleLabel(messages.firstOrNull()?.agentRole ?: "ORCHESTRATOR"))}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
        )
    }
}

@Composable
private fun ToolCallGroupRow(
    message: AgentService.Companion.ChatMessage,
    resultMessage: AgentService.Companion.ChatMessage?,
    onToggleOutput: () -> Unit,
    onKnowledgeLinkClick: (String) -> Boolean,
    useLocalOutputExpansion: Boolean
) {
    val toolName = resolvedToolName(message).orEmpty()
    var locallyExpanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val isExpanded = message.isOutputExpanded || (useLocalOutputExpansion && locallyExpanded)
    val output = message.toolOutput ?: resultMessage?.toolOutput ?: resultMessage?.content
    val durationText = resultMessage?.let { formatToolDuration(it.timestamp - message.timestamp) }
    val startedText = stringResource(R.string.agent_tool_started_at, formatAgentMessageTimestamp(message.timestamp))
    val durationLabel = durationText?.let { stringResource(R.string.agent_tool_duration, it) }
    val cleanOutput = remember(output) {
        output
            ?.replaceFirst(Regex("(?m)^PREVIEW_IMAGE_PATH:.*$"), "")
            ?.trim()
    }
    val outputPreview = remember(cleanOutput) {
        cleanOutput?.let {
            if (it.length <= TOOL_OUTPUT_PREVIEW_CHARS) it
            else it.take(TOOL_OUTPUT_PREVIEW_CHARS) + "\n…"
        }
    }
    var showFullOutput by remember(message.id) { mutableStateOf(false) }
    val context = LocalContext.current

    if (showFullOutput && !cleanOutput.isNullOrBlank()) {
        val visibleFullOutput = remember(cleanOutput) {
            boundedPreview(cleanOutput, TOOL_OUTPUT_VIEWER_CHARS)
        }
        Dialog(onDismissRequest = { showFullOutput = false }) {
            Card(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.agent_tool_call_title, toolName),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            visibleFullOutput,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    }
                    Row(modifier = Modifier.align(Alignment.End)) {
                        TextButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(toolName, cleanOutput.orEmpty()))
                        }) { Text(stringResource(R.string.action_copy)) }
                        TextButton(onClick = { showFullOutput = false }) {
                            Text(stringResource(R.string.action_close))
                        }
                    }
                }
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (useLocalOutputExpansion) {
                            locallyExpanded = !locallyExpanded
                        } else {
                            onToggleOutput()
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.agent_tool_call_title, toolName),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                text = listOfNotNull(startedText, durationLabel).joinToString(" · "),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(start = 22.dp, top = 2.dp)
            )
            if (isExpanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    if (message.content.isNotBlank()) {
                        ChatMessageContent(
                            message = message,
                            isEditing = false,
                            editingText = "",
                            onEditingTextChange = {},
                            onCancelEdit = {},
                            onSaveEdit = {},
                            textColor = MaterialTheme.colorScheme.onSurface,
                            onKnowledgeLinkClick = onKnowledgeLinkClick
                        )
                    }
                    val argsString = message.toolArgs
                        ?.entries
                        ?.joinToString("\n") { "${it.key}: ${it.value}" }
                        ?.let { boundedPreview(it, TOOL_ARGS_PREVIEW_CHARS) }
                        .orEmpty()
                    if (argsString.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        SelectionContainer {
                            Text(
                                text = argsString,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.04f), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            )
                        }
                    }
                    if (!outputPreview.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        SelectionContainer {
                            Text(
                                text = outputPreview,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                maxLines = TOOL_OUTPUT_PREVIEW_LINES,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            )
                        }
                        if ((cleanOutput?.length ?: 0) > TOOL_OUTPUT_PREVIEW_CHARS) {
                            TextButton(onClick = { showFullOutput = true }) {
                                Text(stringResource(R.string.agent_tool_view_full_output))
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val TOOL_OUTPUT_PREVIEW_CHARS = 4_000
private const val TOOL_OUTPUT_PREVIEW_LINES = 18
private const val TOOL_OUTPUT_VIEWER_CHARS = 60_000
private const val TOOL_ARGS_PREVIEW_CHARS = 2_000
private const val AGENT_CHAT_RENDER_MESSAGE_LIMIT = 600

@Composable
fun ChatMessageBubble(
    message: AgentService.Companion.ChatMessage,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onDelete: () -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    isEditing: Boolean = false,
    editingText: String = "",
    onEditingTextChange: (String) -> Unit = {},
    onSaveEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
    isPlanResolving: Boolean = false,
    onToggleOutput: () -> Unit = {},
    onKnowledgeLinkClick: (String) -> Boolean = { false },
    showMessageActions: Boolean = true
) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipboardLabelMessage = stringResource(R.string.clipboard_label_message)
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val isSystem = message.role == "system"
    val isAssistant = message.role == "assistant"
    val isDelegation = message.isDelegation
    val isCompactionStatus = AgentService.isTransientCompactionStatusMessageForUi(message)
    val formattedTimestamp = remember(message.timestamp) { formatAgentMessageTimestamp(message.timestamp) }
    val imageFile = remember(message.imagePath) { message.imagePath?.let(::File)?.takeIf { it.exists() } }
    var showImagePreview by remember(message.imagePath) { mutableStateOf(false) }
    val toolPreviewImagePath = remember(message.toolOutput) {
        message.toolOutput
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("PREVIEW_IMAGE_PATH:") }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
    val toolPreviewImageFile = remember(toolPreviewImagePath) {
        toolPreviewImagePath?.let(::File)?.takeIf { it.exists() }
    }
    
    var delegationExpanded by remember { mutableStateOf(false) }
    
    val textColor = when {
        isUser -> MaterialTheme.colorScheme.onPrimary
        isCompactionStatus -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    
    val elevation = if (isUser) 1.dp else 2.dp
    val borderStroke = if (!isUser) BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)) else null
    
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        
        Card(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .then(if (message.isStreaming) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(20.dp)) else Modifier)
                .then(if (isDelegation) Modifier.clickable { delegationExpanded = !delegationExpanded } else Modifier),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    isUser -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f)
                    isCompactionStatus -> Color(0xFF2E7D32).copy(alpha = 0.92f)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                }
            ),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 20.dp
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = elevation),
            border = borderStroke
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                if (isDelegation) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.agent_delegation_title), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Icon(if (delegationExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(20.dp))
                    }
                    if (delegationExpanded) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                if (isDelegation && !delegationExpanded) {
                    // Hidden
                } else if (message.needsApproval) {
                    val title = when (message.toolName) {
                        "write_file" -> stringResource(R.string.agent_approve_file_title)
                        "run_command" -> stringResource(R.string.agent_approve_cmd_title)
                        "edit_lines" -> stringResource(R.string.agent_approve_edit_title)
                        else -> stringResource(R.string.agent_approve_generic_title, message.toolName ?: "Tool")
                    }
                    Text(text = title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    if (message.content.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = message.content,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = Color.Black.copy(alpha = 0.05f), 
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    ) {
                        SelectionContainer {
                            Column(modifier = Modifier.padding(10.dp)) {
                                when (message.toolName) {
                                    "write_file", "edit_lines" -> {
                                        Text(stringResource(R.string.agent_path_label, message.toolArgs?.get("path") ?: ""), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        
                                        val content = if (message.toolName == "write_file") {
                                            message.toolArgs?.get("content")
                                        } else {
                                            "Lines: ${message.toolArgs?.get("start_line")} - ${message.toolArgs?.get("end_line")}\n\n${message.toolArgs?.get("new_content")}"
                                        }
                                        Text(
                                            text = boundedPreview(content.orEmpty(), TOOL_ARGS_PREVIEW_CHARS),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            maxLines = TOOL_OUTPUT_PREVIEW_LINES,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    "run_command" -> {
                                        Text(
                                            text = boundedPreview(message.toolArgs?.get("command").orEmpty(), TOOL_ARGS_PREVIEW_CHARS),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            maxLines = TOOL_OUTPUT_PREVIEW_LINES,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    else -> {
                                        // Generic tool args preview
                                        val argsString = message.toolArgs?.entries?.joinToString("\n") { "${it.key}: ${it.value}" } ?: ""
                                        Text(
                                            text = boundedPreview(argsString.ifEmpty { stringResource(R.string.agent_no_args) }, TOOL_ARGS_PREVIEW_CHARS),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            maxLines = TOOL_OUTPUT_PREVIEW_LINES,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onDeny, 
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer), 
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.action_deny), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = onApprove, 
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)), 
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.action_allow), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        }
                    }
                } else {
                    // Thought process
                    ThinkingBlock(message = message)

                    if (imageFile != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clickable { showImagePreview = true }
                        ) {
                            AsyncImage(
                                model = imageFile,
                                contentDescription = stringResource(R.string.agent_image_attachment_title),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 140.dp, max = 260.dp)
                            )
                        }
                    }

                    // Tool Call block
                    message.toolName?.let { tool ->
                        val isExpanded = message.isOutputExpanded || (message.isPlan && message.isPlanApproved == null)
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(onClick = onToggleOutput)
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (message.isPlan) stringResource(R.string.agent_plan_title) else stringResource(R.string.agent_tool_call_title, tool),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                    
                                }
                                
                                AnimatedVisibility(
                                    visible = isExpanded,
                                    enter = expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)),
                                    exit = shrinkVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy))
                                ) {
                                    Column {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        ChatMessageContent(
                                            message = if (message.isPlan && !message.planModifiedContent.isNullOrBlank()) {
                                                message.copy(content = message.planModifiedContent)
                                            } else {
                                                message
                                            },
                                            isEditing = isEditing,
                                            editingText = editingText,
                                            onEditingTextChange = onEditingTextChange,
                                            onCancelEdit = onCancelEdit,
                                            onSaveEdit = onSaveEdit,
                                            textColor = MaterialTheme.colorScheme.onSurface,
                                            onKnowledgeLinkClick = onKnowledgeLinkClick
                                        )
                                        
                                        // Terminal Output (if visible)
                                        if (message.isTerminalVisible) {
                                            TerminalView(message, onInput = { input -> 
                                                AgentService.sendTerminalInput(message.id, input)
                                            })
                                        }
                                        
                                        // Tool Results (legacy/finished)
                                        message.toolOutput?.let { output ->
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                if (toolPreviewImageFile != null) {
                                                    Surface(
                                                        shape = RoundedCornerShape(12.dp),
                                                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable { showImagePreview = true }
                                                    ) {
                                                        AsyncImage(
                                                            model = toolPreviewImageFile,
                                                            contentDescription = stringResource(R.string.agent_image_attachment_title),
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .heightIn(min = 160.dp, max = 260.dp)
                                                        )
                                                    }
                                                }
                                                Surface(
                                                    color = Color.Black.copy(alpha = 0.05f),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    SelectionContainer {
                                                        Text(
                                                            text = boundedPreview(
                                                                output.replaceFirst(Regex("(?m)^PREVIEW_IMAGE_PATH:.*$"), "").trim(),
                                                                TOOL_OUTPUT_PREVIEW_CHARS
                                                            ),
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 11.sp,
                                                            modifier = Modifier.padding(8.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                    }
                                }
                            }
                        }
                    }

                    if (message.toolName == null) {
                        ChatMessageContent(
                            message = message,
                            isEditing = isEditing,
                            editingText = editingText,
                            onEditingTextChange = onEditingTextChange,
                            onCancelEdit = onCancelEdit,
                            onSaveEdit = onSaveEdit,
                            textColor = MaterialTheme.colorScheme.onSurface,
                            onKnowledgeLinkClick = onKnowledgeLinkClick
                        )
                    }
                    
                    if (message.isStreaming) {
                        val currentStatusText by AgentService.statusText.collectAsState()
                        val streamingMessageId by AgentService.streamingMessageId.collectAsState()
                        val isTargeted = streamingMessageId == message.id
                        
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            if (isTargeted) {
                                TypingIndicator()
                                Spacer(modifier = Modifier.width(8.dp))
                            } else {
                                CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(currentStatusText, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
        
        if (
            message.isPlan &&
            message.isPlanApproved == null &&
            !isEditing
        ) {
            AgentPlanDecisionButtons(
                onDeny = onDeny,
                onModify = onEdit,
                onApprove = onApprove,
                isResolving = isPlanResolving,
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .padding(top = 8.dp)
            )
        }

        if (!message.isStreaming) {
            val roleLabel = when {
                isUser -> stringResource(R.string.agent_user_label)
                isTool -> stringResource(R.string.agent_tool_label, message.toolName ?: "Tool")
                isSystem -> stringResource(R.string.agent_system_label)
                message.customAgentName != null -> stringResource(R.string.agent_custom_agent_label, message.customAgentName)
                message.agentRole != null -> stringResource(R.string.agent_role_label, agentRoleLabel(message.agentRole))
                else -> stringResource(R.string.agent_role_label, agentRoleLabel("ORCHESTRATOR"))
            }
            Row(
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$formattedTimestamp · $roleLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        // ========== Action Row Below Bubble (like Llama Native) ==========
        if (showMessageActions && !message.isStreaming && !isSystem) {
            Row(
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    stringResource(R.string.action_copy),
                    modifier = Modifier.size(14.dp).clickable {
                        val clip = ClipData.newPlainText(clipboardLabelMessage, message.content)
                        clipboardManager.setPrimaryClip(clip)
                    },
                    tint = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.width(8.dp))

                if ((isUser || isAssistant || message.isPlan) && !isEditing) {
                    Icon(
                        Icons.Default.Edit,
                        stringResource(R.string.action_edit),
                        modifier = Modifier.size(14.dp).clickable { onEdit() },
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                if (isAssistant) {
                    Icon(
                        Icons.Default.Refresh,
                        stringResource(R.string.action_regenerate),
                        modifier = Modifier.size(14.dp).clickable { onRegenerate() },
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }

                Icon(
                    Icons.Default.Delete,
                    stringResource(R.string.action_delete),
                    modifier = Modifier.size(14.dp).clickable { onDelete() },
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }

    if (showImagePreview && (imageFile != null || toolPreviewImageFile != null)) {
        Dialog(onDismissRequest = { showImagePreview = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    AsyncImage(
                        model = imageFile ?: toolPreviewImageFile,
                        contentDescription = stringResource(R.string.agent_image_attachment_title),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp, max = 520.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = { showImagePreview = false }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentPlanDecisionButtons(
    onDeny: () -> Unit,
    onModify: () -> Unit,
    onApprove: () -> Unit,
    isResolving: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onDeny,
            enabled = !isResolving,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(R.string.action_deny),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onModify,
                enabled = !isResolving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    stringResource(R.string.agent_modify_plan),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            Button(
                onClick = onApprove,
                enabled = !isResolving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CAF50)
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isResolving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color.White
                    )
                } else {
                    Icon(
                        Icons.Default.Check,
                        null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    stringResource(
                        if (isResolving) R.string.agent_plan_saving
                        else R.string.action_approve
                    ),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun TerminalView(
    message: AgentService.Companion.ChatMessage,
    onInput: (String) -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(Color.Black, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        // Terminal Window
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
        ) {
            val scrollState = rememberScrollState()
            LaunchedEffect(message.terminalOutput) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
            
            SelectionContainer {
                Text(
                    text = message.terminalOutput ?: "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF4CAF50), // Classic terminal green
                    modifier = Modifier.verticalScroll(scrollState)
                )
            }
        }
        
        // Input Line
        HorizontalDivider(color = Color.White.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 4.dp))
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "$ ",
                color = Color(0xFF4CAF50),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
            BasicTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (inputText.isNotBlank()) {
                        onInput(inputText)
                        inputText = ""
                    }
                })
            )
        }
    }
}

@Composable
fun ChatMessageContent(
    message: AgentService.Companion.ChatMessage,
    isEditing: Boolean,
    editingText: String,
    onEditingTextChange: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    textColor: Color,
    onKnowledgeLinkClick: (String) -> Boolean = { false }
) {
    var showFullMessage by remember(message.id) { mutableStateOf(false) }
    if (showFullMessage) {
        Dialog(onDismissRequest = { showFullMessage = false }) {
            Card(modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SelectionContainer(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = remember(message.content) {
                                boundedPreview(message.content, MAX_MESSAGE_VIEWER_CHARS)
                            },
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        )
                    }
                    TextButton(
                        onClick = { showFullMessage = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        }
    }
    if (isEditing) {
        Column {
            OutlinedTextField(
                value = editingText,
                onValueChange = onEditingTextChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp)
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancelEdit) { Text(stringResource(R.string.action_cancel), color = textColor) }
                TextButton(onClick = onSaveEdit) { Text(stringResource(R.string.action_save), color = textColor) }
            }
        }
    } else {
        Column {
            if (message.toolName != null && !message.needsApproval) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.agent_running_tool, message.toolName ?: ""), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
            if (message.isStreaming) {
                val streamingMessageId by AgentService.streamingMessageId.collectAsState()
                val isTargeted = streamingMessageId == message.id
                
                if (isTargeted) {
                    val streamingContent by AgentService.streamingContent.collectAsState()
                    val textToDisplay = streamingContent.ifEmpty { message.content }
                    if (textToDisplay.isNotBlank()) {
                        Text(
                            text = remember(textToDisplay) { boundedAgentStreamingPreview(textToDisplay) },
                            color = textColor,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    // Show whatever content it has, but don't collect the global stream
                    if (message.content.isNotBlank()) {
                        Text(
                            text = remember(message.content) { boundedAgentStreamingPreview(message.content) },
                            color = textColor,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                if (message.content.isNotBlank()) {
                    val preview = remember(message.content) {
                        boundedAgentStreamingPreview(message.content)
                    }
                    MarkdownText(
                        text = preview,
                        textColor = textColor,
                        modifier = Modifier.fillMaxWidth(),
                        onLinkClick = onKnowledgeLinkClick
                    )
                    if (preview.length < message.content.length) {
                        TextButton(onClick = { showFullMessage = true }) {
                            Text(stringResource(R.string.agent_message_view_full))
                        }
                    }
                }
            }
            message.guidanceDeliveryState?.let { deliveryState ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (deliveryState == "QUEUED") {
                                R.string.agent_guidance_state_queued
                            } else {
                                R.string.agent_guidance_state_delivered
                            }
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

private const val MAX_STREAMING_PREVIEW_CHARS = 24_000
private const val MAX_STREAMING_LINE_CHARS = 2_000
private const val MAX_MESSAGE_VIEWER_CHARS = 120_000

internal fun boundedAgentStreamingPreview(text: String): String {
    val boundedLines = text.lineSequence().map { line ->
        if (line.length <= MAX_STREAMING_LINE_CHARS) line
        else line.take(MAX_STREAMING_LINE_CHARS) + "…"
    }.joinToString("\n")
    return if (boundedLines.length <= MAX_STREAMING_PREVIEW_CHARS) {
        boundedLines
    } else {
        boundedLines.take(MAX_STREAMING_PREVIEW_CHARS) + "\n…"
    }
}

@Composable
fun ThinkingBlock(message: AgentService.Companion.ChatMessage) {
    if (message.isStreaming) {
        val streamingMessageId by AgentService.streamingMessageId.collectAsState()
        val isTargeted = streamingMessageId == message.id
        
        if (isTargeted) {
            val streamingThinking by AgentService.streamingThinking.collectAsState()
            val text = streamingThinking.ifEmpty { message.thinking ?: "" }
            if (text.isNotBlank()) {
                ThinkingBlockContent(text = text, messageId = message.id, isStreaming = true)
            }
        } else {
            val text = message.thinking ?: ""
            if (text.isNotBlank()) {
                ThinkingBlockContent(text = text, messageId = message.id, isStreaming = false)
            }
        }
    } else {
        val text = message.thinking ?: ""
        if (text.isNotBlank()) {
            ThinkingBlockContent(text = text, messageId = message.id, isStreaming = false)
        }
    }
}

@Composable
fun ThinkingBlockContent(text: String, messageId: String, isStreaming: Boolean = false) {
    var thinkingExpanded by remember(messageId) { mutableStateOf(false) }
    val boundedText = remember(text) { boundedAgentStreamingPreview(text) }

    // Completed reasoning blocks are static. Creating an infinite transition for
    // every historical message kept all of them recomposing forever, even while
    // collapsed, which made long Agent projects continuously exercise Compose/Skia.
    val pulseAlpha = if (isStreaming) {
        val infiniteTransition = rememberInfiniteTransition(label = "agent-thinking-pulse")
        val animatedAlpha by infiniteTransition.animateFloat(
            initialValue = 0.05f,
            targetValue = 0.15f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "agent-thinking-alpha"
        )
        animatedAlpha
    } else {
        0.05f
    }
    
    val bgColor = if (isStreaming) {
        MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.clickable { thinkingExpanded = !thinkingExpanded }.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (thinkingExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, 
                    null, 
                    modifier = Modifier.size(16.dp), 
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    stringResource(R.string.agent_thinking_process), 
                    fontSize = 11.sp, 
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
            }
            AnimatedVisibility(
                visible = thinkingExpanded,
                enter = expandVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)),
                exit = shrinkVertically(animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy))
            ) {
                Text(
                    text = boundedText,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), 
                    fontSize = 12.sp, 
                    fontStyle = FontStyle.Italic, 
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp)
                )
            }
        }
    }
}

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition()
    
    @Composable
    fun Dot(delay: Int) {
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, delayMillis = delay, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            )
        )
        Box(
            modifier = Modifier
                .size(4.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), RoundedCornerShape(2.dp))
        )
    }
    
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Dot(0)
        Dot(150)
        Dot(300)
    }
}
