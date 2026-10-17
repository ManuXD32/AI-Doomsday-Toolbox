package com.example.llamadroid.ui.ai.llama.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.service.NativeChatToolRuntime as Tools
import com.example.llamadroid.service.chat.NativeChatStep
import com.example.llamadroid.service.chat.NativeChatStepStatus
import com.example.llamadroid.service.chat.NativeChatStepType
import com.example.llamadroid.service.chat.NativeChatStepsCodec
import com.example.llamadroid.ui.ai.llama.MarkdownText
import com.example.llamadroid.ui.components.AppTextDetailsDialog
import java.text.NumberFormat

/**
 * Inline work steps of one assistant reply: the model's short messages, folded thinking per round,
 * compact tool cards and notices, in order. Decoded once per [stepsJson]; text is shown as bounded
 * previews and tool arguments/results only in a constrained dialog.
 */
@Composable
internal fun ChatMessageSteps(
    stepsJson: String?,
    messageKey: Long,
    generating: Boolean,
    onKnowledgeLinkClick: (String) -> Boolean,
    modifier: Modifier = Modifier
) {
    val decoded = remember(stepsJson) { NativeChatStepsCodec.decode(stepsJson) }
    // Model-facing notes are part of the replayed transcript, not of what the user reads.
    val steps = decoded?.steps.orEmpty().filter { it.type != NativeChatStepType.NOTE }
    if (steps.isEmpty()) return
    var expanded by rememberSaveable(messageKey) { mutableStateOf(false) }
    var detailsStep by remember(messageKey) { mutableStateOf<NativeChatStep?>(null) }
    val rows = remember(steps.size, generating, expanded) {
        planStepRows(steps.size, collapsible = !generating, expanded = expanded)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            when (row) {
                is StepRow.Hidden -> TextButton(
                    onClick = { expanded = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        pluralStringResource(R.plurals.chat_steps_show_more_steps, row.count, row.count),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                is StepRow.Step -> {
                    val step = steps[row.index]
                    when (step.type) {
                        NativeChatStepType.TEXT -> StepTextBlock(step.text, stringResource(R.string.chat_steps_message_title), onKnowledgeLinkClick)
                        NativeChatStepType.THINKING -> StepThinkingRow(step.text, onKnowledgeLinkClick)
                        NativeChatStepType.TOOL -> StepToolCard(
                            step = step,
                            interrupted = isInterruptedStep(step, generating),
                            onClick = { detailsStep = step }
                        )
                        else -> StepNoticeLine(step.text)
                    }
                }
            }
        }
        if (expanded && !generating && steps.size > STEPS_COLLAPSE_THRESHOLD) {
            TextButton(
                onClick = { expanded = false },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Icon(Icons.Default.ExpandLess, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.chat_steps_hide_steps), style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    detailsStep?.let { step ->
        StepToolDetailsDialog(step = step, onDismiss = { detailsStep = null })
    }
}

/** Text with a bounded preview and a Show more control that opens the full text in the viewer. */
@Composable
private fun StepTextBlock(
    text: String,
    title: String,
    onKnowledgeLinkClick: (String) -> Boolean
) {
    if (text.isBlank()) return
    val preview = remember(text) { previewStepText(text.trim()) }
    var showFull by remember(text) { mutableStateOf(false) }
    Column {
        MarkdownText(
            text = preview.first,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            onLinkClick = onKnowledgeLinkClick
        )
        if (preview.second) {
            Text(
                text = stringResource(R.string.chat_steps_show_more_text),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(role = Role.Button) { showFull = true }
                    .heightIn(min = 32.dp)
                    .padding(vertical = 6.dp)
            )
        }
    }
    if (showFull) {
        AppTextDetailsDialog(
            title = title,
            text = text,
            onDismiss = { showFull = false }
        )
    }
}

/** Folded "Thought process" row; expands to a bounded preview. */
@Composable
private fun StepThinkingRow(text: String, onKnowledgeLinkClick: (String) -> Boolean) {
    if (text.isBlank()) return
    var open by rememberSaveable(text.hashCode()) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 36.dp)
                    .clickable(role = Role.Button) { open = !open }
            ) {
                Text(
                    stringResource(R.string.chat_steps_thought),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            if (open) {
                Box(modifier = Modifier.padding(bottom = 8.dp)) {
                    StepTextBlock(text, stringResource(R.string.chat_steps_thought), onKnowledgeLinkClick)
                }
            }
        }
    }
}

@Composable
private fun StepNoticeLine(text: String) {
    if (text.isBlank()) return
    Text(
        text = text.trim(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun StepToolCard(step: NativeChatStep, interrupted: Boolean, onClick: () -> Unit) {
    val name = step.toolName.orEmpty()
    val label = toolStepLabel(name, step.arguments)
    val subtitle = remember(step.arguments) { toolPrimaryArgument(step.arguments) }
    val statusText = stringResource(
        when {
            interrupted -> R.string.chat_steps_tool_interrupted
            step.status == NativeChatStepStatus.RUNNING -> R.string.chat_steps_tool_running
            step.status == NativeChatStepStatus.ERROR -> R.string.chat_steps_tool_failed
            step.status == NativeChatStepStatus.SKIPPED -> R.string.chat_steps_tool_skipped
            else -> R.string.chat_steps_tool_done
        }
    )
    val duration = formatStepDuration(step.durationMs)
    val description = stringResource(R.string.chat_steps_tool_card_description, label, statusText)
    val isError = step.status == NativeChatStepStatus.ERROR
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .heightIn(min = 40.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank() && !label.contains(subtitle)) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (duration != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    duration,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                interrupted || isError -> Icon(
                    Icons.Default.Warning,
                    contentDescription = statusText,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                step.status == NativeChatStepStatus.RUNNING -> CircularProgressIndicator(
                    modifier = Modifier
                        .size(14.dp)
                        .semantics { contentDescription = statusText },
                    strokeWidth = 2.dp
                )
                step.status == NativeChatStepStatus.SKIPPED -> Icon(
                    Icons.Default.Block,
                    contentDescription = statusText,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                else -> Icon(
                    Icons.Default.Check,
                    contentDescription = statusText,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun StepToolDetailsDialog(step: NativeChatStep, onDismiss: () -> Unit) {
    val label = toolStepLabel(step.toolName.orEmpty(), step.arguments)
    val argumentsHeading = stringResource(R.string.chat_steps_details_arguments)
    val resultHeading = stringResource(R.string.chat_steps_details_result)
    val noArguments = stringResource(R.string.chat_steps_details_no_arguments)
    val noResult = stringResource(R.string.chat_steps_details_no_result)
    val shortened = step.originalChars?.let {
        stringResource(R.string.chat_steps_details_shortened, NumberFormat.getIntegerInstance().format(it))
    }
    val text = remember(step, argumentsHeading, resultHeading, noArguments, noResult, shortened) {
        buildString {
            append(argumentsHeading).append("\n").append(step.arguments?.takeIf { it.isNotBlank() } ?: noArguments)
            append("\n\n").append(resultHeading).append("\n")
            append(step.text.takeIf { it.isNotBlank() } ?: noResult)
            if (shortened != null) append("\n\n").append(shortened)
        }
    }
    AppTextDetailsDialog(title = label, text = text, onDismiss = onDismiss)
}

/** Localized, past-tense-neutral tool label; unknown tools fall back to their raw name. */
@Composable
private fun toolStepLabel(toolName: String, arguments: String?): String {
    val res = toolStatusRes(toolName) ?: return toolName.ifBlank { stringResource(R.string.llama_tool_status_running).trimEnd('…') }
    val raw = if (res.second) {
        val arg = remember(arguments) { toolPrimaryArgument(arguments).orEmpty() }
        stringResource(res.first, arg)
    } else {
        stringResource(res.first)
    }
    return raw.trimEnd('…', '.', ' ')
}

/** Tool name to (status string, takes the primary argument). */
private fun toolStatusRes(name: String): Pair<Int, Boolean>? = when (name) {
    Tools.TOOL_WEB_SEARCH -> R.string.llama_tool_status_web_search to true
    Tools.TOOL_SEARCH_PAGE -> R.string.llama_tool_status_search_page to true
    Tools.TOOL_KIWIX_SEARCH -> R.string.llama_tool_status_kiwix_search to true
    Tools.TOOL_FETCH_URL -> R.string.llama_tool_status_fetch_url to true
    Tools.TOOL_DEEP_RESEARCH -> R.string.llama_tool_status_deep_research to true
    Tools.TOOL_KB_SEARCH -> R.string.llama_tool_status_kb_search to true
    Tools.TOOL_GET_DATETIME -> R.string.llama_tool_status_datetime to false
    Tools.TOOL_CALCULATOR -> R.string.llama_tool_status_calculator to false
    Tools.TOOL_LIST_NOTES -> R.string.llama_tool_status_list_notes to false
    Tools.TOOL_READ_NOTE -> R.string.llama_tool_status_read_note to false
    Tools.TOOL_CREATE_NOTE -> R.string.llama_tool_status_create_note to false
    Tools.TOOL_UPDATE_NOTE, Tools.TOOL_REPLACE_NOTE_TEXT -> R.string.llama_tool_status_update_note to false
    Tools.TOOL_CREATE_TODO_LIST -> R.string.llama_tool_status_create_todo to false
    Tools.TOOL_ADD_TODO_ITEM, Tools.TOOL_UPDATE_TODO_ITEM, Tools.TOOL_REMOVE_TODO_ITEM,
    Tools.TOOL_SET_TODO_ITEM_CHECKED -> R.string.llama_tool_status_update_todo to false
    Tools.TOOL_LIST_CALENDAR_EVENTS, Tools.TOOL_READ_CALENDAR_EVENT -> R.string.llama_tool_status_calendar to false
    Tools.TOOL_CREATE_CALENDAR_EVENT, Tools.TOOL_UPDATE_CALENDAR_EVENT,
    Tools.TOOL_DELETE_CALENDAR_EVENT -> R.string.llama_tool_status_update_calendar to false
    Tools.TOOL_LIST_ALARMS, Tools.TOOL_READ_ALARM -> R.string.llama_tool_status_alarms to false
    Tools.TOOL_CREATE_ALARM, Tools.TOOL_UPDATE_ALARM, Tools.TOOL_DELETE_ALARM ->
        R.string.llama_tool_status_update_alarm to false
    Tools.TOOL_KB_READ_CHUNK -> R.string.llama_tool_status_kb_read to false
    Tools.TOOL_KB_LIST_SOURCES -> R.string.llama_tool_status_kb_sources to false
    Tools.TOOL_GENERATE_IMAGE -> R.string.llama_tool_status_generate_image to false
    Tools.TOOL_REMOVE_IMAGE_BACKGROUND -> R.string.llama_tool_status_bgr to false
    else -> null
}
