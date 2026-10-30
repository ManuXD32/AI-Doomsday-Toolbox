package com.example.llamadroid.service.chat

import com.example.llamadroid.service.OllamaService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live tool loop builds its request messages from the recorder's steps ([NativeChatStepHistory.turnMessages]);
 * later turns replay the stored row ([NativeChatStepHistory.expand]). Every live request must therefore be a
 * byte-identical prefix of the replay of the finished reply.
 */
class NativeChatTranscriptParityTest {
    private fun project(m: OllamaService.ChatMessage) = listOf(
        m.role, m.content, m.toolCallId, m.thinking, m.imagePath,
        m.toolCalls?.map { Triple(it.id, it.name, it.rawArgumentsJson) }
    )

    private fun roundTrip(steps: NativeChatSteps) = NativeChatStepsCodec.decode(NativeChatStepsCodec.encode(steps))!!

    private fun record(
        recorder: NativeChatStepRecorder,
        round: Int,
        thinking: String,
        text: String,
        calls: List<Triple<String, String, String>>,
        notes: List<NativeChatTurnNotes.Note> = emptyList(),
        skipDuplicates: Set<String> = emptySet()
    ) {
        recorder.addThinking(round, thinking)
        recorder.addText(round, text)
        for ((id, name, args) in calls) {
            if (id in skipDuplicates) {
                recorder.addSkippedTool(round, id, name, args, NativeChatTurnNotes.SKIPPED_DUPLICATE)
            } else {
                val i = recorder.startTool(round, id, name, args)
                recorder.finishTool(i, NativeChatStepStatus.OK, "result of $id " + "x".repeat(9_000), 3L)
            }
        }
        notes.forEach { recorder.addNote(round, it.text, it.role, it.imagePath) }
    }

    @Test
    fun everyLiveRequestIsAPrefixOfTheReplay() {
        val recorder = NativeChatStepRecorder()
        val requests = ArrayList<List<OllamaService.ChatMessage>>()
        fun request() = requests.add(NativeChatStepHistory.turnMessages(recorder.snapshot(false).steps))

        recorder.addNote(0, "deepseek hint")
        request()
        record(recorder, 0, "think a", "  I will read.\n", listOf(Triple("c1", "read", "{\"p\":1}")))
        request()
        record(
            recorder, 1, "think b", "Now again", listOf(Triple("c1b", "read", "{\"p\":1}"), Triple("c2", "list", "{}")),
            notes = listOf(NativeChatTurnNotes.duplicateCall("read"), NativeChatTurnNotes.Note("look", "user", "/img.png")),
            skipDuplicates = setOf("c1b")
        )
        request()
        recorder.addNote(2, NativeChatTurnNotes.BLANK_ANSWER_RETRY)
        request()
        val finalContent = "The answer. "
        val replay = NativeChatStepHistory.expand(finalContent, roundTrip(recorder.snapshot(true)), withReasoning = true)

        requests.forEach { live ->
            assertEquals(live.map(::project), replay.take(live.size).map(::project))
        }
        // Reasoning is carried in-turn, round text is verbatim, results are the stored bounded text.
        val assistants = replay.filter { it.role == "assistant" && !it.toolCalls.isNullOrEmpty() }
        assertEquals("think a", assistants[0].thinking)
        assertEquals("  I will read.\n", assistants[0].content)
        assertEquals(NativeChatStepsCodec.boundResult("result of c1 " + "x".repeat(9_000)).first, replay.first { it.role == "tool" }.content)
        assertEquals(finalContent, replay.last().content)
    }

    @Test
    fun limitWrapUpAndAfterLimitContinueKeepTheSamePrefix() {
        val recorder = NativeChatStepRecorder()
        record(recorder, 0, "", "", listOf(Triple("c1", "t", "{}")))
        recorder.addNote(1, NativeChatTurnNotes.ROUND_LIMIT)
        val limitRequest = NativeChatStepHistory.turnMessages(recorder.snapshot(false).steps)
        recorder.limitReached = true
        recorder.addText(1, "progress note")
        recorder.addNotice(1, "Step limit reached")
        val stored = recorder.snapshot(false)
        val replay = NativeChatStepHistory.expand("", roundTrip(stored))
        assertEquals(limitRequest.map(::project), replay.take(limitRequest.size).map(::project))
        assertEquals(listOf("assistant", "tool", "system", "assistant"), replay.map { it.role })
        assertEquals("progress note", replay.last().content)
    }

    @Test
    fun earlierTurnsOmitReasoningButCurrentTurnKeepsIt() {
        val steps = NativeChatSteps(
            steps = listOf(
                NativeChatStep(NativeChatStepType.THINKING, 0, "deep thought"),
                NativeChatStep(NativeChatStepType.TEXT, 0, "go"),
                NativeChatStep(NativeChatStepType.TOOL, 0, "r", "c", "t", "{}", NativeChatStepStatus.OK)
            ),
            complete = true
        )
        assertNull(NativeChatStepHistory.expand("done", steps, withReasoning = false)[0].thinking)
        assertEquals("deep thought", NativeChatStepHistory.expand("done", steps, withReasoning = true)[0].thinking)
        assertEquals("deep thought", NativeChatStepHistory.turnMessages(steps.steps)[0].thinking)
    }

    @Test
    fun continueMidTextSendsThePartialAsTrailingAssistantAfterToolSendsNone() {
        val tool = NativeChatStep(NativeChatStepType.TOOL, 0, "r", "c", "t", "{}", NativeChatStepStatus.OK)
        val prefill = OllamaService.ChatMessage(role = "assistant", content = "The file says ", thinking = "mm")
        val midText = NativeChatStepHistory.turnMessages(listOf(tool), prefill)
        assertEquals(listOf("assistant", "tool", "assistant"), midText.map { it.role })
        assertEquals("The file says ", midText.last().content)
        assertEquals("mm", midText.last().thinking)
        val afterTool = NativeChatStepHistory.turnMessages(listOf(tool))
        assertEquals(listOf("assistant", "tool"), afterTool.map { it.role })
    }

    @Test
    fun noteRoleAndImageSurviveAndEncoderFillsImages() {
        val steps = NativeChatSteps(steps = listOf(NativeChatStep(NativeChatStepType.NOTE, 0, "review", role = "user", imagePath = "/a.png")))
        val msg = NativeChatStepHistory.expand("", steps, encodeImage = { "b64:$it" }).single()
        assertEquals("user", msg.role)
        assertEquals("/a.png", msg.imagePath)
        assertEquals(listOf("b64:/a.png"), msg.images)
    }

    @Test
    fun entityIdCountsMatchExpansionWithNotes() {
        val steps = NativeChatSteps(
            steps = listOf(
                NativeChatStep(NativeChatStepType.NOTE, 0, "hint"),
                NativeChatStep(NativeChatStepType.TOOL, 0, "r", "c", "t", "{}", NativeChatStepStatus.OK),
                NativeChatStep(NativeChatStepType.NOTE, 0, "warn")
            ),
            complete = true
        )
        assertEquals(listOf("system", "assistant", "tool", "system", "assistant"), NativeChatStepHistory.expand("end", steps).map { it.role })
        assertTrue(NativeChatStepHistory.flatten("end", steps).let { !it.contains("hint") && !it.contains("warn") })
    }

    @Test
    fun signaturesAndMutatingCountsRebuildFromSteps() {
        val steps = listOf(
            NativeChatStep(NativeChatStepType.TOOL, 0, "r", "a", "write", "{\"b\":\"2\",\"a\":\"1\"}", NativeChatStepStatus.OK),
            NativeChatStep(NativeChatStepType.TOOL, 0, "boom", "b", "write", "{\"a\":\"9\"}", NativeChatStepStatus.ERROR),
            NativeChatStep(NativeChatStepType.TEXT, 0, "ignored"),
            NativeChatStep(NativeChatStepType.TOOL, 1, "s", "c", "read", "{}", NativeChatStepStatus.SKIPPED)
        )
        val state = NativeChatToolLoopState.rebuild(steps) { it == "write" }
        assertTrue(NativeChatToolLoopState.signature("write", mapOf("a" to "1", "b" to "2")) in state.executedSignatures)
        assertTrue(NativeChatToolLoopState.signature("write", mapOf("a" to "9")) in state.executedSignatures)
        assertTrue(NativeChatToolLoopState.signature("read", emptyMap()) in state.executedSignatures)
        assertEquals(mapOf("write" to 1), state.completedMutatingCounts)
    }
}
