package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.llamadroid.R
import com.example.llamadroid.ui.components.AppChromeDefaults
import com.example.llamadroid.ui.components.AppStateKind
import com.example.llamadroid.ui.components.AppStatePanel

/** A contiguous set of internal events followed by one visible assistant message. */
internal data class NativeHarnessActivityGroup(
    val id: String,
    val sequence: Long,
    val activities: List<NativeHarnessStructuredTranscriptItem>,
    val assistant: NativeHarnessStructuredTranscriptItem?,
    val activityOrigins: Map<String, String> = emptyMap(),
)

internal sealed interface NativeHarnessStructuredTimelineUnit {
    val sequence: Long

    data class Row(val item: NativeHarnessStructuredTranscriptItem) : NativeHarnessStructuredTimelineUnit {
        override val sequence: Long = item.sequence
    }

    data class Group(val value: NativeHarnessActivityGroup) : NativeHarnessStructuredTimelineUnit {
        override val sequence: Long = value.sequence
    }
}

/**
 * Groups activity by the actual visible assistant messages. A turn can contain
 * several assistant messages, so turnTail is deliberately not used as a
 * boundary. The item IDs supplied by the projection are retained as keys.
 */
internal fun nativeHarnessStructuredTimelineUnits(
    rows: List<NativeHarnessStructuredTranscriptItem>,
): List<NativeHarnessStructuredTimelineUnit> {
    val units = ArrayList<NativeHarnessStructuredTimelineUnit>()
    val activities = ArrayList<NativeHarnessStructuredTranscriptItem>()
    val activityOrigins = LinkedHashMap<String, String>()
    var firstActivitySequence = Long.MAX_VALUE

    fun flush() {
        if (activities.isEmpty()) return
        val first = activities.first()
        units += NativeHarnessStructuredTimelineUnit.Group(
            NativeHarnessActivityGroup(
                id = "activity-${first.id}",
                sequence = firstActivitySequence,
                activities = activities.toList(),
                assistant = null,
                activityOrigins = activityOrigins.toMap(),
            ),
        )
        activities.clear()
        activityOrigins.clear()
        firstActivitySequence = Long.MAX_VALUE
    }

    fun appendActivity(item: NativeHarnessStructuredTranscriptItem, originId: String = item.id) {
        if (activities.isEmpty()) firstActivitySequence = item.sequence
        activities += item
        activityOrigins[item.id] = originId
    }

    rows.sortedWith(compareBy<NativeHarnessStructuredTranscriptItem> { it.sequence }.thenBy { it.id })
        .filterNot { it.isMetadataOnly }
        .forEach { row ->
            val projection = nativeHarnessActivityProjection(row)
            projection.activities.forEach { appendActivity(it, row.id) }
            val visibleAssistant = projection.visibleAssistant
            when {
                visibleAssistant != null && activities.isNotEmpty() -> {
                    units += NativeHarnessStructuredTimelineUnit.Group(
                        NativeHarnessActivityGroup(
                            id = "activity-${activities.first().id}",
                            sequence = firstActivitySequence,
                            activities = activities.toList(),
                            assistant = visibleAssistant,
                            activityOrigins = activityOrigins.toMap(),
                        ),
                    )
                    activities.clear()
                    activityOrigins.clear()
                    firstActivitySequence = Long.MAX_VALUE
                }
                visibleAssistant != null -> units += NativeHarnessStructuredTimelineUnit.Row(visibleAssistant)
                row.role == HarnessTranscriptRole.USER -> {
                    flush()
                    units += NativeHarnessStructuredTimelineUnit.Row(row)
                }
                projection.activities.isEmpty() -> appendActivity(row)
            }
        }
    flush()
    return units.sortedWith(compareBy<NativeHarnessStructuredTimelineUnit> { it.sequence }.thenBy {
        when (it) {
            is NativeHarnessStructuredTimelineUnit.Row -> it.item.id
            is NativeHarnessStructuredTimelineUnit.Group -> it.value.id
        }
    })
}

private data class NativeHarnessActivityProjection(
    val visibleAssistant: NativeHarnessStructuredTranscriptItem?,
    val activities: List<NativeHarnessStructuredTranscriptItem>,
)

/**
 * Assistant messages can carry visible text beside reasoning and tool parts.
 * Keep the message row as the assistant result, while projecting every
 * internal part into its own second-level activity row. Synthetic IDs are
 * stable for the source row and part index; [NativeHarnessActivityGroup]
 * retains the source ID so detail requests still address the canonical row.
 */
private fun nativeHarnessActivityProjection(
    row: NativeHarnessStructuredTranscriptItem,
): NativeHarnessActivityProjection {
    if (row.role == HarnessTranscriptRole.USER) {
        return NativeHarnessActivityProjection(visibleAssistant = null, activities = emptyList())
    }
    if (row.role != HarnessTranscriptRole.ASSISTANT) {
        val activity = when (row.role) {
            HarnessTranscriptRole.SYSTEM,
            HarnessTranscriptRole.TOOL,
            HarnessTranscriptRole.THINKING -> row.copy(isExpandable = true)
            else -> row
        }
        return NativeHarnessActivityProjection(visibleAssistant = null, activities = listOf(activity))
    }
    val visible = row.parts.filter { part ->
        when (part) {
            is NativeHarnessStructuredTranscriptPart.Text -> part.value.isNotBlank()
            is NativeHarnessStructuredTranscriptPart.Image,
            is NativeHarnessStructuredTranscriptPart.File -> true
            else -> false
        }
    }
    val internal = row.parts.filterNot { it in visible }
    val activities = internal.mapIndexed { index, part ->
        NativeHarnessStructuredTranscriptItem(
            id = "${row.id}:activity-part-$index",
            sequence = row.sequence,
            role = nativeHarnessActivityRole(part),
            label = nativeHarnessActivityLabel(part),
            parts = listOf(part),
            detailRef = row.detailRef,
            isExpandable = true,
        )
    }
    val assistant = visible.takeIf { it.isNotEmpty() }?.let {
        row.copy(
            parts = it,
            isExpandable = row.detailRef != null,
        )
    }
    return NativeHarnessActivityProjection(assistant, activities)
}

private fun nativeHarnessActivityRole(
    part: NativeHarnessStructuredTranscriptPart,
): HarnessTranscriptRole = when (part) {
    is NativeHarnessStructuredTranscriptPart.Tool -> HarnessTranscriptRole.TOOL
    else -> HarnessTranscriptRole.THINKING
}

private fun nativeHarnessActivityLabel(
    part: NativeHarnessStructuredTranscriptPart,
): String? = when (part) {
    is NativeHarnessStructuredTranscriptPart.Tool -> part.name
    is NativeHarnessStructuredTranscriptPart.Opaque -> part.type
    else -> null
}

@Composable
internal fun HarnessActivityGroupCard(
    group: NativeHarnessActivityGroup,
    sessionKey: String?,
    detail: HarnessStructuredTranscriptDetailUiState,
    feedback: Map<String, HarnessMessageFeedbackUi>,
    onAction: (NativeHarnessUiAction) -> Unit,
    actionHooks: NativeHarnessTranscriptActionHooks,
) {
    val stateKey = "${sessionKey.orEmpty()}:${group.id}"
    var expanded by rememberSaveable(stateKey) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppChromeDefaults.InnerCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.harness_activity_group_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.harness_activity_group_count, group.activities.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                    Text(
                        stringResource(
                            if (expanded) R.string.harness_activity_group_hide
                            else R.string.harness_activity_group_show,
                        ),
                        maxLines = 1,
                    )
                }
            }
            if (expanded) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(group.activities, key = { "activity-${it.id}" }) { item ->
                        val originId = group.activityOrigins[item.id] ?: item.id
                        val activityDetail = detail
                            .takeIf { it.itemId == originId }
                            ?.copy(itemId = item.id)
                        val activityAction: (NativeHarnessUiAction) -> Unit = { action ->
                            onAction(
                                if (action is NativeHarnessUiAction.LoadTranscriptDetail) {
                                    action.copy(itemId = originId)
                                } else {
                                    action
                                },
                            )
                        }
                        NativeHarnessStructuredRow(
                            item = item,
                            detail = activityDetail ?: detail,
                            feedback = feedback,
                            onAction = activityAction,
                            actionHooks = actionHooks,
                            sessionId = sessionKey,
                        )
                    }
                }
            }
            group.assistant?.let { assistant ->
                NativeHarnessStructuredRow(
                    item = assistant,
                    detail = detail,
                    feedback = feedback,
                    onAction = onAction,
                    actionHooks = actionHooks,
                    sessionId = sessionKey,
                )
            }
        }
    }
}

@Composable
internal fun HarnessToolsTab(
    state: NativeHarnessUiState,
    onAction: (NativeHarnessUiAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val toolRows = state.structuredTranscript
        .filterNot { it.isMetadataOnly }
        .filter {
            it.role == HarnessTranscriptRole.TOOL ||
                it.role == HarnessTranscriptRole.THINKING ||
                it.retry != null ||
                it.compaction != null ||
                it.parts.any { part -> part is NativeHarnessStructuredTranscriptPart.Opaque }
        }
        .takeLast(200)
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.notice?.let { notice ->
            item(key = "tools-notice") { HarnessNoticeBanner(notice, onAction) }
        }
        if (toolRows.isEmpty()) {
            item(key = "tools-empty") {
                AppStatePanel(
                    kind = AppStateKind.Empty,
                    title = stringResource(R.string.harness_tools_empty_title),
                    message = stringResource(R.string.harness_tools_empty_description),
                )
            }
        } else {
            items(toolRows, key = { "tool-${it.id}" }) { item ->
                NativeHarnessStructuredTranscriptCard(
                    item = item,
                    onAction = onAction,
                    sessionId = state.selectedSessionId,
                    detail = state.structuredDetail.page.takeIf { state.structuredDetail.itemId == item.id },
                    detailLoading = state.structuredDetail.itemId == item.id && state.structuredDetail.isLoading,
                    detailGeneration = state.structuredDetail.generation,
                    onRequestDetail = { itemId, page ->
                        onAction(NativeHarnessUiAction.LoadTranscriptDetail(itemId, page))
                    },
                )
            }
        }
    }
}
