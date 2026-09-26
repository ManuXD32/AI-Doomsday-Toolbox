package com.example.llamadroid.ui.agent.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHarnessActivityGroupsTest {
    @Test
    fun everyVisibleAssistantMessageClosesItsOwnActivityGroup() {
        val rows = listOf(
            row("user", 1, HarnessTranscriptRole.USER, NativeHarnessStructuredTranscriptPart.Text("start")),
            row("tool-1", 2, HarnessTranscriptRole.TOOL, toolPart("search")),
            row("system-1", 3, HarnessTranscriptRole.SYSTEM, NativeHarnessStructuredTranscriptPart.Text("status")),
            row("assistant-1", 4, HarnessTranscriptRole.ASSISTANT, NativeHarnessStructuredTranscriptPart.Text("first")),
            row("reasoning-2", 5, HarnessTranscriptRole.THINKING, NativeHarnessStructuredTranscriptPart.Reasoning("thinking")),
            row("assistant-2", 6, HarnessTranscriptRole.ASSISTANT, NativeHarnessStructuredTranscriptPart.Text("second")),
            row("tool-open", 7, HarnessTranscriptRole.TOOL, toolPart("write")),
        )

        val units = nativeHarnessStructuredTimelineUnits(rows)
        val groups = units.filterIsInstance<NativeHarnessStructuredTimelineUnit.Group>()

        assertEquals(listOf("activity-tool-1", "activity-reasoning-2", "activity-tool-open"), groups.map { it.value.id })
        assertEquals("assistant-1", groups[0].value.assistant?.id)
        assertEquals("assistant-2", groups[1].value.assistant?.id)
        assertEquals(null, groups[2].value.assistant)
        assertEquals(listOf("tool-1", "system-1"), groups[0].value.activities.map { it.id })
        assertTrue(groups[0].value.activities[1].isExpandable)
        assertEquals(listOf("reasoning-2"), groups[1].value.activities.map { it.id })
        assertTrue(groups.all { it.value.id.isNotBlank() })
    }

    @Test
    fun mixedAssistantPartsBecomeInternalActivitiesWithoutDuplicatingAssistantActions() {
        val source = NativeHarnessStructuredTranscriptItem(
            id = "assistant-mixed",
            sequence = 8,
            role = HarnessTranscriptRole.ASSISTANT,
            messageId = "message-mixed",
            turn = 2,
            detailRef = NativeHarnessTranscriptDetailRef.SessionEvents(listOf(8)),
            parts = listOf(
                NativeHarnessStructuredTranscriptPart.Reasoning("private reasoning"),
                NativeHarnessStructuredTranscriptPart.Text("visible answer"),
                toolPart("lookup"),
            ),
        )

        val group = nativeHarnessStructuredTimelineUnits(listOf(source))
            .single() as NativeHarnessStructuredTimelineUnit.Group

        assertEquals("assistant-mixed", group.value.assistant?.id)
        assertEquals("activity-assistant-mixed:activity-part-0", group.value.id)
        assertEquals("message-mixed", group.value.assistant?.messageId)
        assertEquals(
            listOf("assistant-mixed:activity-part-0", "assistant-mixed:activity-part-1"),
            group.value.activities.map { it.id },
        )
        assertEquals(
            listOf(HarnessTranscriptRole.THINKING, HarnessTranscriptRole.TOOL),
            group.value.activities.map { it.role },
        )
        assertTrue(group.value.activities.all { it.isExpandable })
        assertEquals(
            listOf("visible answer"),
            group.value.assistant?.parts?.map { (it as NativeHarnessStructuredTranscriptPart.Text).value },
        )
        assertEquals(
            "assistant-mixed",
            group.value.activityOrigins[group.value.activities.first().id],
        )
    }

    @Test
    fun plainApprovalSystemRowJoinsTheFollowingAssistantActivityGroup() {
        val first = row(
            "assistant-first",
            1,
            HarnessTranscriptRole.ASSISTANT,
            NativeHarnessStructuredTranscriptPart.Text("first"),
        )
        val second = row(
            "assistant-second",
            3,
            HarnessTranscriptRole.ASSISTANT,
            NativeHarnessStructuredTranscriptPart.Text("second"),
        )
        val approval = HarnessTranscriptItem(
            id = "event-2",
            role = HarnessTranscriptRole.SYSTEM,
            text = "Approval required",
            isExpandable = false,
        )

        val units = nativeHarnessTimelineStructuredUnits(listOf(approval), listOf(first, second))
        val group = units.filterIsInstance<NativeHarnessStructuredTimelineUnit.Group>().single()

        assertEquals("assistant-first", (units.first() as NativeHarnessStructuredTimelineUnit.Row).item.id)
        assertEquals("assistant-second", group.value.assistant?.id)
        assertEquals(listOf("plain-activity-event-2"), group.value.activities.map { it.id })
        assertEquals(HarnessTranscriptRole.SYSTEM, group.value.activities.single().role)
        assertTrue(group.value.activities.single().isExpandable)
    }

    @Test
    fun assistantWithoutInternalActivityRemainsAStableMessageRow() {
        val row = row(
            id = "assistant-stream",
            sequence = 7,
            role = HarnessTranscriptRole.ASSISTANT,
            part = NativeHarnessStructuredTranscriptPart.Text("partial stream"),
        )

        val units = nativeHarnessStructuredTimelineUnits(listOf(row))

        assertEquals(1, units.size)
        assertEquals(row, (units.single() as NativeHarnessStructuredTimelineUnit.Row).item)
    }

    private fun row(
        id: String,
        sequence: Long,
        role: HarnessTranscriptRole,
        part: NativeHarnessStructuredTranscriptPart,
    ) = NativeHarnessStructuredTranscriptItem(
        id = id,
        sequence = sequence,
        role = role,
        parts = listOf(part),
        turn = 1,
    )

    private fun toolPart(name: String) = NativeHarnessStructuredTranscriptPart.Tool(
        callId = name,
        name = name,
        arguments = "{}",
        status = NativeHarnessStructuredToolStatus.COMPLETED,
    )
}
