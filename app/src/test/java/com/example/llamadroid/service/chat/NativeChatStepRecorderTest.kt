package com.example.llamadroid.service.chat

import com.example.llamadroid.data.model.LlamaMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatStepRecorderTest {
    @Test
    fun recordsRoundTextThinkingAndToolLifecycle() {
        val r = NativeChatStepRecorder()
        assertNull(r.toJson(false))
        r.addThinking(0, " thought ")
        r.addText(0, " I will look. ")
        val i = r.startTool(0, "c1", "read_file", "{\"a\":1}")
        assertEquals(NativeChatStepStatus.RUNNING, r.snapshot(false).steps[i].status)
        r.finishTool(i, NativeChatStepStatus.OK, "result", 5L)
        val steps = NativeChatStepsCodec.decode(r.toJson(false))!!
        assertEquals(listOf("thinking", "text", "tool"), steps.steps.map { it.type })
        assertEquals(" I will look. ", steps.steps[1].text)
        val tool = steps.steps[2]
        assertEquals("result", tool.text)
        assertEquals(5L, tool.durationMs)
        assertEquals(NativeChatStepStatus.OK, tool.status)
        assertFalse(steps.complete)
    }

    @Test
    fun blankTextIsIgnoredAndLongResultsAreBoundedOnce() {
        val r = NativeChatStepRecorder()
        assertFalse(r.addText(0, "   "))
        val i = r.startTool(0, "c", "t", "{}")
        r.finishTool(i, NativeChatStepStatus.OK, "z".repeat(50_000), 1L)
        val tool = r.snapshot(false).steps[0]
        assertEquals(NativeChatStepsCodec.boundResult("z".repeat(50_000)).first, tool.text)
        assertEquals(50_000, tool.originalChars)
    }

    @Test
    fun resumedRecorderContinuesRoundNumbersAndSealsRunningTools() {
        val stored = NativeChatSteps(
            steps = listOf(
                NativeChatStep(NativeChatStepType.TOOL, 3, "", "c", "t", "{}", NativeChatStepStatus.RUNNING)
            )
        )
        val r = NativeChatStepRecorder(stored)
        assertEquals(4, r.firstRound)
        assertEquals(6, r.roundFor(2))
        val first = r.snapshot(false).steps[0]
        assertEquals(NativeChatStepStatus.ERROR, first.status)
        assertEquals(NativeChatStepRecorder.INTERRUPTED_RESULT, first.text)
    }

    @Test
    fun limitReachedKeepsReplyResumable() {
        val r = NativeChatStepRecorder()
        r.addSkippedTool(0, "c", "t", "{}", "Skipped: dup")
        r.limitReached = true
        assertFalse(r.snapshot(true).complete)
        assertEquals(NativeChatStepStatus.SKIPPED, r.snapshot(true).steps[0].status)
    }

    @Test
    fun dirtyFlagOnlyEncodesChanges() {
        val r = NativeChatStepRecorder()
        r.addText(0, "hi")
        assertNotNull(r.toJsonIfDirty())
        assertNull(r.toJsonIfDirty())
        r.sealRunning()
        assertNull(r.toJsonIfDirty())
    }

    @Test
    fun stepCapIsEnforced() {
        val r = NativeChatStepRecorder()
        repeat(NativeChatStepsCodec.MAX_STEPS) { assertTrue(r.addText(it, "t$it")) }
        assertFalse(r.addText(999, "extra"))
        assertEquals(-1, r.startTool(999, "c", "t", "{}"))
    }

    @Test
    fun notesAreStoredVerbatimWithRoleAndImage() {
        val r = NativeChatStepRecorder()
        assertTrue(r.addNote(0, " hint ", null, null))
        assertTrue(r.addNote(0, "look", "user", "/img.png"))
        assertFalse(r.addNote(0, "  "))
        val steps = r.snapshot(false).steps
        assertEquals(NativeChatStepType.NOTE, steps[0].type)
        assertEquals(" hint ", steps[0].text)
        assertNull(steps[0].role)
        assertEquals("user", steps[1].role)
        assertEquals("/img.png", steps[1].imagePath)
    }

    @Test
    fun toolCapacityKeepsAReserveForTheWrapUp() {
        val r = NativeChatStepRecorder()
        var index = 0
        while (!r.atToolCapacity) assertTrue(r.startTool(0, "c$index", "t", "{}").also { index++ } >= 0)
        assertEquals(-1, r.startTool(0, "x", "t", "{}"))
        assertTrue(r.addNote(1, "limit"))
        assertTrue(r.addText(1, "progress"))
        assertTrue(r.hasToolSteps)
    }

    @Test
    fun resumeKeepsPartialTextForPrefillAndDoesNotCloseIt() {
        val stored = NativeChatSteps(
            steps = listOf(NativeChatStep(NativeChatStepType.TOOL, 0, "res", "c", "t", "{}", NativeChatStepStatus.OK)),
            complete = false
        )
        val message = LlamaMessageEntity(
            id = 9, chatId = 1, role = "assistant", content = "partial words ", thinking = "mm",
            stepsJson = NativeChatStepsCodec.encode(stored)
        )
        val resumed = NativeChatStepResume.prepare(message)!!
        assertEquals("partial words ", resumed.message.content)
        assertEquals("mm", resumed.message.thinking)
        assertEquals(listOf("tool"), resumed.steps.steps.map { it.type })
        assertEquals(resumed.steps, NativeChatStepsCodec.decode(resumed.message.stepsJson))
    }

    @Test
    fun resumeWithOnlyThinkingKeepsItAsDisplayStepAndClearsTheColumn() {
        val message = LlamaMessageEntity(id = 9, chatId = 1, role = "assistant", content = "", thinking = " mm ")
        val resumed = NativeChatStepResume.prepare(message, allowComplete = true)!!
        assertNull(resumed.message.thinking)
        assertEquals(listOf("thinking"), resumed.steps.steps.map { it.type })
        assertEquals(" mm ".trim(), resumed.steps.steps[0].text)
    }

    @Test
    fun resumeAfterTheRoundLimitAddsOneRecordedNote() {
        val stored = NativeChatSteps(
            steps = listOf(
                NativeChatStep(NativeChatStepType.TOOL, 0, "res", "c", "t", "{}", NativeChatStepStatus.OK),
                NativeChatStep(NativeChatStepType.NOTE, 1, NativeChatTurnNotes.ROUND_LIMIT),
                NativeChatStep(NativeChatStepType.TEXT, 1, "progress"),
                NativeChatStep(NativeChatStepType.NOTICE, 1, "Step limit reached")
            ),
            complete = false
        )
        val message = LlamaMessageEntity(id = 9, chatId = 1, role = "assistant", content = "", stepsJson = NativeChatStepsCodec.encode(stored))
        val resumed = NativeChatStepResume.prepare(message)!!
        val last = resumed.steps.steps.last()
        assertEquals(NativeChatStepType.NOTE, last.type)
        assertEquals(NativeChatStepResume.AFTER_LIMIT_NOTE, last.text)
        assertEquals(2, last.round)
        val live = NativeChatStepHistory.turnMessages(resumed.steps.steps)
        assertEquals(listOf("assistant", "tool", "system", "assistant", "system"), live.map { it.role })
    }

    @Test
    fun resumePreparationRejectsCompleteOrStepLessRepliesUnlessAllowed() {
        val plain = LlamaMessageEntity(id = 1, chatId = 1, role = "assistant", content = "x")
        assertNull(NativeChatStepResume.prepare(plain))
        val complete = plain.copy(
            stepsJson = NativeChatStepsCodec.encode(
                NativeChatSteps(steps = listOf(NativeChatStep(NativeChatStepType.TEXT, 0, "a")), complete = true)
            )
        )
        assertNull(NativeChatStepResume.prepare(complete))
        assertNull(NativeChatStepResume.prepare(complete.copy(role = "user"), allowComplete = true))
        val extended = NativeChatStepResume.prepare(plain, allowComplete = true)!!
        assertEquals("x", extended.message.content)
        assertNull(extended.message.stepsJson)
        assertTrue(extended.steps.isEmpty)
        assertEquals(1, NativeChatStepResume.prepare(complete, allowComplete = true)!!.steps.steps.size)
    }
}
