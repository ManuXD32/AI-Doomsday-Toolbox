package com.example.llamadroid.ui.ai.llama.chat

import com.example.llamadroid.service.chat.NativeChatStep
import com.example.llamadroid.service.chat.NativeChatStepStatus
import com.example.llamadroid.service.chat.NativeChatStepType
import com.example.llamadroid.service.chat.NativeChatSteps
import com.example.llamadroid.service.chat.NativeChatStepsCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageStepsLogicTest {
    @Test
    fun shortOrGeneratingListsShowEverything() {
        assertEquals(8, planStepRows(8, collapsible = true, expanded = false).size)
        assertEquals(20, planStepRows(20, collapsible = false, expanded = false).size)
        assertEquals(20, planStepRows(20, collapsible = true, expanded = true).size)
        assertTrue(planStepRows(0, true, false).isEmpty())
    }

    @Test
    fun longCompleteListCollapsesToHeadAndTail() {
        val rows = planStepRows(12, collapsible = true, expanded = false)
        assertEquals(
            listOf(StepRow.Step(0), StepRow.Step(1), StepRow.Hidden(8), StepRow.Step(10), StepRow.Step(11)),
            rows
        )
    }

    @Test
    fun durationFormatting() {
        assertNull(formatStepDuration(null))
        assertNull(formatStepDuration(-1))
        assertEquals("850 ms", formatStepDuration(850))
        assertEquals("1.2 s", formatStepDuration(1_200))
        assertEquals("42 s", formatStepDuration(42_000))
        assertEquals("2 min 5 s", formatStepDuration(125_000))
        assertEquals("3 min", formatStepDuration(180_000))
    }

    @Test
    fun previewIsBounded() {
        assertEquals("abc" to false, previewStepText("abc"))
        val (text, cut) = previewStepText("x".repeat(1_000))
        assertTrue(cut)
        assertEquals(STEP_TEXT_PREVIEW_CHARS + 1, text.length)
    }

    @Test
    fun primaryArgumentIsExtracted() {
        assertEquals("cats", toolPrimaryArgument("""{"limit":3,"query":"cats"}"""))
        assertNull(toolPrimaryArgument("not json"))
        assertNull(toolPrimaryArgument(null))
        assertEquals(80, toolPrimaryArgument("""{"url":"${"u".repeat(200)}"}""")?.length)
    }

    @Test
    fun unfinishedStepsAllowContinue() {
        val step = NativeChatStep(type = NativeChatStepType.TOOL, round = 0, toolName = "t")
        assertTrue(hasUnfinishedSteps(NativeChatStepsCodec.encode(NativeChatSteps(steps = listOf(step), complete = false))))
        assertFalse(hasUnfinishedSteps(NativeChatStepsCodec.encode(NativeChatSteps(steps = listOf(step), complete = true))))
        assertFalse(hasUnfinishedSteps(null))
        assertFalse(hasUnfinishedSteps("garbage"))
    }

    @Test
    fun exportLinesIncludeToolsAndText() {
        val steps = NativeChatSteps(
            steps = listOf(
                NativeChatStep(NativeChatStepType.TEXT, 0, text = "Reading the file."),
                NativeChatStep(NativeChatStepType.THINKING, 0, text = "secret"),
                NativeChatStep(
                    NativeChatStepType.TOOL, 0, toolName = "read_file",
                    status = NativeChatStepStatus.OK, durationMs = 1_200
                )
            ),
            complete = true
        )
        val out = stepsToPlainLines(steps, StepsExportLabels("Used tool %1\$s (%2\$s)", "ok", "error", "skipped", "running"))
        assertTrue(out.contains("Reading the file."))
        assertTrue(out.contains("> Used tool read_file (ok, 1.2 s)"))
        assertFalse(out.contains("secret"))
    }
}
