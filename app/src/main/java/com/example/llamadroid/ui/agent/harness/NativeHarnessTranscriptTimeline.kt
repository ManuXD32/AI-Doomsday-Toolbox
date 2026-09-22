package com.example.llamadroid.ui.agent.harness

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable

internal sealed interface NativeHarnessTimelineEntry {
    val sequence: Long
    val order: Int
    val key: String

    data class Plain(
        val item: HarnessTranscriptItem,
        override val sequence: Long,
        override val order: Int,
    ) : NativeHarnessTimelineEntry {
        override val key: String = "plain-${item.id}"
    }

    data class Structured(
        val item: NativeHarnessStructuredTranscriptItem,
        override val sequence: Long,
        override val order: Int,
    ) : NativeHarnessTimelineEntry {
        override val key: String = "structured-${item.id}"
    }

    data class ActivityGroup(
        val group: NativeHarnessActivityGroup,
        override val sequence: Long,
        override val order: Int,
    ) : NativeHarnessTimelineEntry {
        override val key: String = "activity-${group.id}"
    }
}

private data class NativeHarnessTimelineSource(
    val sequence: Long,
    val order: Int,
    val structured: NativeHarnessStructuredTranscriptItem? = null,
    val plain: HarnessTranscriptItem? = null,
)

/**
 * Renders structured rows in place of their plain event rows. Tool calls and
 * results share one row through their sequence references; rows with no
 * structured representation keep the existing plain transcript card.
 */
internal fun LazyListScope.harnessTranscriptTimeline(
    sessionKey: String?,
    transcript: List<HarnessTranscriptItem>,
    structured: List<NativeHarnessStructuredTranscriptItem>,
    detail: HarnessStructuredTranscriptDetailUiState,
    feedback: Map<String, HarnessMessageFeedbackUi>,
    onAction: (NativeHarnessUiAction) -> Unit,
    actionHooks: NativeHarnessTranscriptActionHooks = NativeHarnessTranscriptActionHooks(),
) {
    val entries = nativeHarnessTimelineEntries(transcript, structured)
    items(entries, key = { "${it.key}-$sessionKey" }) { entry ->
        when (entry) {
            is NativeHarnessTimelineEntry.Plain -> HarnessTranscriptCard(
                item = entry.item,
                feedback = entry.item.messageId?.let(feedback::get),
                onToggle = { onAction(NativeHarnessUiAction.ToggleTranscriptItem(entry.item.id)) },
                onAction = onAction,
            )
            is NativeHarnessTimelineEntry.Structured -> NativeHarnessStructuredRow(
                item = entry.item,
                detail = detail,
                feedback = feedback,
                onAction = onAction,
                actionHooks = actionHooks,
                sessionId = sessionKey,
            )
            is NativeHarnessTimelineEntry.ActivityGroup -> HarnessActivityGroupCard(
                group = entry.group,
                sessionKey = sessionKey,
                detail = detail,
                feedback = feedback,
                onAction = onAction,
                actionHooks = actionHooks,
            )
        }
    }
}

/** Single production projection shared by rendering and chronology regression checks. */
internal fun nativeHarnessTimelineEntries(
    transcript: List<HarnessTranscriptItem>,
    structured: List<NativeHarnessStructuredTranscriptItem>,
): List<NativeHarnessTimelineEntry> {
    val compactionCheckpointSequences = structured.mapNotNull { it.compaction?.checkpointSequence }.toSet()
    val displayedStructured = structured.filterNot { it.isMetadataOnly }
    val representedSequences = displayedStructured.flatMap { item ->
        (item.detailRef as? NativeHarnessTranscriptDetailRef.SessionEvents)
            ?.sequences.orEmpty()
    }.toSet()
    val structuredSequenceSet = displayedStructured.map { it.sequence }.toSet()
    val sources = buildList {
        displayedStructured.forEachIndexed { index, item ->
            add(NativeHarnessTimelineSource(item.sequence, index, structured = item))
        }
        transcript.forEachIndexed { index, item ->
            val sequence = harnessPlainTimelineSequence(item)
            if (sequence != null && (
                    sequence in compactionCheckpointSequences ||
                        sequence in representedSequences ||
                        sequence in structuredSequenceSet
                )
            ) return@forEachIndexed
            if (item.role == HarnessTranscriptRole.USER || item.role == HarnessTranscriptRole.ASSISTANT) {
                add(
                    NativeHarnessTimelineSource(
                        sequence = sequence ?: Long.MAX_VALUE,
                        order = displayedStructured.size + index,
                        plain = item,
                    ),
                )
            } else {
                add(
                    NativeHarnessTimelineSource(
                        sequence = sequence ?: Long.MAX_VALUE,
                        order = displayedStructured.size + index,
                        structured = harnessPlainActivityRow(item, sequence ?: Long.MAX_VALUE),
                    ),
                )
            }
        }
    }.sortedWith(compareBy<NativeHarnessTimelineSource> { it.sequence }.thenBy { it.order })
    val entries = buildList {
        val segment = ArrayList<NativeHarnessStructuredTranscriptItem>()
        var nextOrder = 0

        fun appendSegment() {
            if (segment.isEmpty()) return
            nativeHarnessStructuredTimelineUnits(segment).forEach { unit ->
                when (unit) {
                    is NativeHarnessStructuredTimelineUnit.Row -> add(
                        NativeHarnessTimelineEntry.Structured(unit.item, unit.sequence, nextOrder++),
                    )
                    is NativeHarnessStructuredTimelineUnit.Group -> add(
                        NativeHarnessTimelineEntry.ActivityGroup(unit.value, unit.sequence, nextOrder++),
                    )
                }
            }
            segment.clear()
        }

        sources.forEach { source ->
            source.plain?.let { plain ->
                appendSegment()
                add(
                    NativeHarnessTimelineEntry.Plain(
                        item = plain,
                        sequence = source.sequence,
                        order = nextOrder++,
                    ),
                )
            } ?: source.structured?.let(segment::add)
        }
        appendSegment()
    }.sortedWith(compareBy<NativeHarnessTimelineEntry> { it.sequence }.thenBy { it.order })
    return entries
}

internal fun nativeHarnessTimelineStructuredUnits(
    transcript: List<HarnessTranscriptItem>,
    structured: List<NativeHarnessStructuredTranscriptItem>,
): List<NativeHarnessStructuredTimelineUnit> = nativeHarnessTimelineEntries(transcript, structured).mapNotNull {
    when (it) {
        is NativeHarnessTimelineEntry.Plain -> null
        is NativeHarnessTimelineEntry.Structured -> NativeHarnessStructuredTimelineUnit.Row(it.item)
        is NativeHarnessTimelineEntry.ActivityGroup -> NativeHarnessStructuredTimelineUnit.Group(it.group)
    }
}

private fun harnessPlainTimelineSequence(item: HarnessTranscriptItem): Long? =
    item.id.substringAfterLast('-').toLongOrNull()

private fun harnessPlainActivityRow(
    item: HarnessTranscriptItem,
    sequence: Long,
): NativeHarnessStructuredTranscriptItem = NativeHarnessStructuredTranscriptItem(
    id = "plain-activity-${item.id}",
    sequence = sequence,
    role = item.role,
    label = item.label,
    parts = listOf(NativeHarnessStructuredTranscriptPart.Text(boundedHarnessMessage(item.text))),
    isExpandable = true,
)

@Composable
internal fun NativeHarnessStructuredRow(
    item: NativeHarnessStructuredTranscriptItem,
    detail: HarnessStructuredTranscriptDetailUiState,
    feedback: Map<String, HarnessMessageFeedbackUi>,
    onAction: (NativeHarnessUiAction) -> Unit,
    actionHooks: NativeHarnessTranscriptActionHooks,
    sessionId: String?,
) {
    Column {
        NativeHarnessStructuredTranscriptCard(
            item = item,
            onAction = onAction,
            detail = detail.page.takeIf { detail.itemId == item.id },
            detailLoading = detail.itemId == item.id && detail.isLoading,
            detailGeneration = detail.generation,
            sessionId = sessionId,
            actionHooks = actionHooks,
            onRequestDetail = { itemId, page ->
                onAction(NativeHarnessUiAction.LoadTranscriptDetail(itemId, page))
            },
        )
        if (item.role == HarnessTranscriptRole.ASSISTANT && item.messageId != null) {
            HarnessMessageFeedbackActions(
                messageId = item.messageId,
                feedback = feedback[item.messageId],
                onAction = onAction,
            )
        }
    }
}
