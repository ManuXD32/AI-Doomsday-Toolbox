package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.ResponsiveAction
import com.example.llamadroid.ui.components.ResponsiveActionGroup
import com.example.llamadroid.ui.components.ResponsiveActionStyle

/**
 * Structured transcript card. The normal row shows bounded previews; the
 * detail callback fetches exact Session records only after the user expands it.
 */
@Composable
internal fun NativeHarnessStructuredTranscriptCard(
    item: NativeHarnessStructuredTranscriptItem,
    onAction: (NativeHarnessUiAction) -> Unit,
    modifier: Modifier = Modifier,
    sessionId: String? = null,
    actionHooks: NativeHarnessTranscriptActionHooks = NativeHarnessTranscriptActionHooks(),
    detail: NativeHarnessStructuredTranscriptDetailPage? = null,
    detailLoading: Boolean = false,
    detailGeneration: Long = 0L,
    onRequestDetail: (String, Int) -> Unit = { _, _ -> },
) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    var requestedDetail by rememberSaveable(item.id, item.detailRef?.key, detailGeneration) { mutableStateOf(false) }
    LaunchedEffect(item.id, item.detailRef?.key, expanded, detailGeneration) {
        if (expanded && item.detailRef != null && !requestedDetail) {
            requestedDetail = true
            onRequestDetail(item.id, 0)
        }
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppChromeDefaults.InnerCardShape,
        colors = CardDefaults.cardColors(containerColor = structuredTranscriptColor(item.role)),
        border = if (item.role == HarnessTranscriptRole.TOOL) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = structuredTranscriptRoleLabel(item.role),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                item.label?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when {
                item.retry != null -> NativeHarnessRetryContent(item.retry)
                item.compaction != null -> NativeHarnessCompactionContent(item.compaction)
                else -> item.parts.forEach { part ->
                    NativeHarnessStructuredPart(
                        part = part,
                        expanded = expanded,
                        onAction = onAction,
                        depth = 0,
                        userReferences = item.role == HarnessTranscriptRole.USER,
                        skillNames = if (item.role == HarnessTranscriptRole.USER) item.skillNames else emptySet(),
                    )
                }
            }
            if (item.isExpandable) {
                TextButton(onClick = {
                    val opening = !expanded
                    expanded = opening
                    if (opening && item.detailRef != null &&
                        (!requestedDetail || detail == null || detail.errorCode != null)
                    ) {
                        requestedDetail = true
                        onRequestDetail(item.id, 0)
                    }
                }) {
                    Text(
                        stringResource(
                            if (expanded) R.string.harness_structured_hide_details
                            else R.string.harness_structured_show_details,
                        ),
                    )
                }
            }
            if (expanded && item.detailRef != null) {
                NativeHarnessStructuredDetail(
                    detail = detail,
                    loading = detailLoading,
                    onPage = { page -> onRequestDetail(item.id, page) },
                )
            }
            item.turnTail?.let { tail ->
                NativeHarnessTurnTailActions(
                    target = NativeHarnessTurnActionTarget(
                        sessionId = sessionId,
                        turn = tail.turn,
                        tailSequence = tail.tailSequence,
                        branchSequence = tail.branchSequence,
                        fallbackPreview = tail.text,
                    ),
                    tail = tail,
                    hooks = actionHooks,
                )
            }
            if (item.role == HarnessTranscriptRole.USER) {
                NativeHarnessMessageCopyAction(
                    target = NativeHarnessMessageActionTarget(
                        sessionId = sessionId,
                        turn = item.turn,
                        messageSequence = item.sequence,
                        messageId = item.messageId,
                        fallbackPreview = item.parts.joinToString("") { part ->
                            when (part) {
                                is NativeHarnessStructuredTranscriptPart.Text -> part.value
                                is NativeHarnessStructuredTranscriptPart.Reasoning -> part.value
                                else -> ""
                            }
                        }.let(::boundedHarnessMessage),
                    ),
                    hooks = actionHooks,
                )
            }
        }
    }
}

@Composable
private fun NativeHarnessStructuredPart(
    part: NativeHarnessStructuredTranscriptPart,
    expanded: Boolean,
    onAction: (NativeHarnessUiAction) -> Unit,
    depth: Int,
    userReferences: Boolean = false,
    skillNames: Set<String> = emptySet(),
) {
    when (part) {
        is NativeHarnessStructuredTranscriptPart.Text -> NativeHarnessBoundedPartText(
            label = stringResource(R.string.harness_structured_text),
            value = part.value,
            expanded = expanded,
            userReferences = userReferences,
            skillNames = skillNames,
        )
        is NativeHarnessStructuredTranscriptPart.Reasoning -> NativeHarnessBoundedPartText(
            label = stringResource(R.string.harness_structured_reasoning),
            value = part.value,
            expanded = expanded,
            userReferences = userReferences,
            skillNames = skillNames,
        )
        is NativeHarnessStructuredTranscriptPart.Image -> NativeHarnessAttachmentPart(
            attachment = part.attachment,
            onAction = onAction,
            image = true,
        )
        is NativeHarnessStructuredTranscriptPart.File -> NativeHarnessAttachmentPart(
            attachment = part.attachment,
            onAction = onAction,
            image = false,
        )
        is NativeHarnessStructuredTranscriptPart.Tool -> {
            val status = when (part.status) {
                NativeHarnessStructuredToolStatus.RUNNING -> R.string.harness_structured_status_running
                NativeHarnessStructuredToolStatus.COMPLETED -> R.string.harness_structured_status_completed
                NativeHarnessStructuredToolStatus.ERROR -> R.string.harness_structured_status_error
            }
            Text(
                stringResource(R.string.harness_structured_tool_summary, part.name, part.callId),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(stringResource(status), style = MaterialTheme.typography.labelSmall)
            if (expanded) {
                NativeHarnessBoundedPartText(
                    label = stringResource(R.string.harness_structured_arguments),
                    value = part.arguments.ifBlank { stringResource(R.string.harness_structured_no_arguments) },
                    expanded = true,
                    monospace = true,
                )
                if (part.result.isNotEmpty()) {
                    Text(
                        stringResource(R.string.harness_structured_result),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (depth < 2) {
                        part.result.forEach { resultPart ->
                            NativeHarnessStructuredPart(resultPart, true, onAction, depth + 1)
                        }
                    }
                }
                part.errorCode?.let { code ->
                    Text(
                        stringResource(R.string.harness_structured_error_code, code),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        is NativeHarnessStructuredTranscriptPart.Opaque -> {
            NativeHarnessBoundedPartText(
                label = stringResource(R.string.harness_structured_unknown, part.type),
                value = part.preview,
                expanded = expanded,
                monospace = true,
            )
        }
        is NativeHarnessStructuredTranscriptPart.Truncated -> Text(
            stringResource(R.string.harness_structured_more_parts, part.omitted),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun NativeHarnessAttachmentPart(
    attachment: NativeHarnessTranscriptAttachment,
    onAction: (NativeHarnessUiAction) -> Unit,
    image: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = attachment.name ?: attachment.attachmentId,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val metadata = listOfNotNull(
            if (image) attachment.mediaType else stringResource(R.string.harness_structured_file),
            attachment.bytes?.let { stringResource(R.string.harness_structured_bytes, it) },
            if (image && attachment.width != null && attachment.height != null) {
                stringResource(R.string.harness_structured_dimensions, attachment.width, attachment.height)
            } else null,
        ).joinToString(" · ")
        if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.labelSmall)
        if (attachment.canOpen) {
            Button(onClick = {
                onAction(NativeHarnessUiAction.OpenAttachment(attachment.attachmentId))
            }) {
                Text(stringResource(R.string.harness_structured_open_attachment))
            }
        }
    }
}

@Composable
private fun NativeHarnessBoundedPartText(
    label: String,
    value: String,
    expanded: Boolean,
    monospace: Boolean = false,
    userReferences: Boolean = false,
    skillNames: Set<String> = emptySet(),
) {
    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    SelectionContainer {
        val preview = if (expanded) value else boundedHarnessMessage(
            value.take(2_000) + if (value.length > 2_000) "…" else "",
        )
        if (monospace) {
            HarnessInlineText(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                userReferences = userReferences,
                skillNames = skillNames,
            )
        } else {
            HarnessMarkdownText(
                text = preview,
                textColor = MaterialTheme.colorScheme.onSurface,
                userReferences = userReferences,
                skillNames = skillNames,
            )
        }
    }
}

@Composable
private fun NativeHarnessStructuredDetail(
    detail: NativeHarnessStructuredTranscriptDetailPage?,
    loading: Boolean,
    onPage: (Int) -> Unit,
) {
    when {
        loading -> Text(stringResource(R.string.harness_structured_loading_details))
        detail == null -> Text(stringResource(R.string.harness_structured_details_unavailable))
        detail.errorCode != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.harness_structured_details_error, detail.errorCode),
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { onPage(detail.pageIndex) }) {
                Text(stringResource(R.string.harness_continue))
            }
        }
        else -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    detail.lines.forEach { line ->
                        HarnessInlineText(
                            text = "${structuredDetailLineLabel(line.kind)}\n${line.text}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                ResponsiveActionGroup(actions = listOf(
                    ResponsiveAction(
                        stringResource(R.string.harness_structured_previous_page),
                        { onPage(detail.pageIndex - 1) },
                        enabled = detail.hasPrevious,
                        style = ResponsiveActionStyle.Secondary,
                    ),
                    ResponsiveAction(
                        stringResource(R.string.harness_structured_next_page),
                        { onPage(detail.pageIndex + 1) },
                        enabled = detail.hasNext,
                        style = ResponsiveActionStyle.Secondary,
                    ),
                ))
            }
        }
    }
}

@Composable
private fun structuredTranscriptColor(role: HarnessTranscriptRole): Color = when (role) {
    HarnessTranscriptRole.USER -> MaterialTheme.colorScheme.primaryContainer
    HarnessTranscriptRole.ASSISTANT -> MaterialTheme.colorScheme.surfaceContainerLow
    HarnessTranscriptRole.SYSTEM -> MaterialTheme.colorScheme.secondaryContainer
    HarnessTranscriptRole.TOOL, HarnessTranscriptRole.THINKING -> MaterialTheme.colorScheme.surfaceContainerHighest
}

@Composable
private fun structuredTranscriptRoleLabel(role: HarnessTranscriptRole): String = when (role) {
    HarnessTranscriptRole.USER -> stringResource(R.string.harness_role_user)
    HarnessTranscriptRole.ASSISTANT -> stringResource(R.string.harness_role_assistant)
    HarnessTranscriptRole.SYSTEM -> stringResource(R.string.harness_role_system)
    HarnessTranscriptRole.TOOL -> stringResource(R.string.harness_role_tool)
    HarnessTranscriptRole.THINKING -> stringResource(R.string.harness_role_thinking)
}

@Composable
private fun structuredDetailLineLabel(kind: NativeHarnessTranscriptDetailLineKind): String = when (kind) {
    NativeHarnessTranscriptDetailLineKind.TEXT -> stringResource(R.string.harness_structured_text)
    NativeHarnessTranscriptDetailLineKind.REASONING -> stringResource(R.string.harness_structured_reasoning)
    NativeHarnessTranscriptDetailLineKind.ARGUMENTS -> stringResource(R.string.harness_structured_arguments)
    NativeHarnessTranscriptDetailLineKind.RESULT -> stringResource(R.string.harness_structured_result)
    NativeHarnessTranscriptDetailLineKind.OPAQUE -> stringResource(R.string.harness_structured_unknown_label)
}
