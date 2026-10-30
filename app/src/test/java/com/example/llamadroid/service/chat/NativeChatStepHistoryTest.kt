package com.example.llamadroid.service.chat

import com.example.llamadroid.data.model.LlamaMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatStepHistoryTest {
    private fun tool(round: Int, id: String?, name: String, args: String, result: String, status: String = NativeChatStepStatus.OK) =
        NativeChatStep(
            type = NativeChatStepType.TOOL, round = round, text = result, toolCallId = id,
            toolName = name, arguments = args, status = status
        )

    private fun text(round: Int, value: String) = NativeChatStep(NativeChatStepType.TEXT, round, value)

    private val twoRounds = NativeChatSteps(
        steps = listOf(
            text(0, "I will read the file."),
            NativeChatStep(NativeChatStepType.THINKING, 0, "hidden reasoning"),
            tool(0, "call_a", "read_file", "{\"path\":\"a.txt\"}", "alpha"),
            tool(0, "call_b", "list_files", "{}", "a.txt"),
            text(1, "Found it, now searching."),
            tool(1, null, "web_search", "{\"query\":\"x\"}", "tool_error: boom", NativeChatStepStatus.ERROR),
            NativeChatStep(NativeChatStepType.NOTICE, 1, "ignored notice")
        ),
        complete = true
    )

    private fun project(messages: List<com.example.llamadroid.service.OllamaService.ChatMessage>) = messages.map {
        listOf(it.role, it.content, it.toolCallId, it.toolCalls?.map { c -> Triple(c.id, c.name, c.rawArgumentsJson) })
    }

    @Test
    fun groupsRoundsIntoAssistantAndToolMessages() {
        val out = NativeChatStepHistory.expand("Final answer", twoRounds)
        assertEquals(
            listOf("assistant", "tool", "tool", "assistant", "tool", "assistant"),
            out.map { it.role }
        )
        assertEquals("I will read the file.", out[0].content)
        assertEquals(listOf("call_a", "call_b"), out[0].toolCalls!!.map { it.id })
        assertEquals("{\"path\":\"a.txt\"}", out[0].toolCalls!![0].rawArgumentsJson)
        assertEquals(mapOf("path" to "a.txt"), out[0].toolCalls!![0].arguments)
        assertEquals("call_a", out[1].toolCallId)
        assertEquals("alpha", out[1].content)
        assertEquals("call_b", out[2].toolCallId)
        assertEquals("Found it, now searching.", out[3].content)
        // Missing ids get a deterministic one.
        assertEquals("call_1_0", out[3].toolCalls!![0].id)
        assertEquals("call_1_0", out[4].toolCallId)
        assertEquals("tool_error: boom", out[4].content)
        assertEquals("Final answer", out[5].content)
        assertTrue(out[5].toolCalls.isNullOrEmpty())
    }

    @Test
    fun skippedAndRunningToolsStillProduceToolMessages() {
        val steps = NativeChatSteps(
            steps = listOf(
                tool(0, "s1", "x", "{}", "Skipped: duplicate", NativeChatStepStatus.SKIPPED),
                NativeChatStep(NativeChatStepType.TOOL, 0, "", "r1", "y", "{}", NativeChatStepStatus.RUNNING)
            )
        )
        val out = NativeChatStepHistory.expand("", steps)
        assertEquals(listOf("assistant", "tool", "tool"), out.map { it.role })
        assertEquals("Skipped: duplicate", out[1].content)
        assertEquals(NativeChatStepRecorder.INTERRUPTED_RESULT, out[2].content)
    }

    @Test
    fun interruptedReplyEndsAfterToolResultsWithoutFinalAnswer() {
        val interrupted = twoRounds.copy(complete = false)
        val out = NativeChatStepHistory.expand("", interrupted)
        assertEquals("tool", out.last().role)
    }

    @Test
    fun textOnlyRoundFoldsIntoNextAssistant() {
        val steps = NativeChatSteps(steps = listOf(text(0, "Intro."), tool(1, "c", "t", "{}", "r")))
        val out = NativeChatStepHistory.expand("Done", steps)
        assertEquals("Intro.", out[0].content)
        assertEquals(listOf("assistant", "tool", "assistant"), out.map { it.role })
    }

    @Test
    fun malformedOrCutArgumentsBecomeEmptyObject() {
        val steps = NativeChatSteps(steps = listOf(tool(0, "c", "t", "{\"path\":\"unterminated", "r")))
        val call = NativeChatStepHistory.expand("", steps)[0].toolCalls!![0]
        assertEquals("{}", call.rawArgumentsJson)
        assertTrue(call.arguments.isEmpty())
    }

    @Test
    fun expansionIsDeterministicAndDoesNotRebound() {
        val long = "x".repeat(20_000)
        val steps = NativeChatSteps(steps = listOf(tool(0, "c", "t", "{}", long)))
        val first = NativeChatStepHistory.expand("a", steps)
        val second = NativeChatStepHistory.expand("a", steps)
        assertEquals(project(first), project(second))
        assertEquals(long, first[1].content)
        // Round-trip through JSON keeps it byte-identical too.
        val decoded = NativeChatStepsCodec.decode(NativeChatStepsCodec.encode(steps))!!
        assertEquals(project(first), project(NativeChatStepHistory.expand("a", decoded)))
    }

    @Test
    fun outOfOrderRoundsAreSortedStably() {
        val steps = NativeChatSteps(
            steps = listOf(tool(1, "b", "t", "{}", "rb"), tool(0, "a", "t", "{}", "ra"))
        )
        assertEquals(listOf("a", "b"), NativeChatStepHistory.expand("", steps).filter { it.role == "tool" }.map { it.toolCallId })
    }

    @Test
    fun flattenListsToolsBeforeContent() {
        val flat = NativeChatStepHistory.flatten("Final answer", twoRounds)
        val lines = flat.split("\n").filter { it.isNotBlank() }
        assertEquals("I will read the file.", lines[0])
        assertEquals("[Used tool read_file: alpha]", lines[1])
        assertEquals("[Used tool list_files: a.txt]", lines[2])
        assertEquals("[Used tool web_search: tool_error: boom]", lines[4])
        assertEquals("Final answer", lines.last())
        assertTrue(!flat.contains("hidden reasoning"))
    }

    @Test
    fun flattenCutsResultsAtTwoHundredChars() {
        val steps = NativeChatSteps(steps = listOf(tool(0, "c", "t", "{}", "y".repeat(500) + "\nmore")))
        val flat = NativeChatStepHistory.flatten("", steps)
        assertEquals("[Used tool t: ${"y".repeat(200)}]", flat)
    }

    @Test
    fun entitiesWithoutStepsAreUntouched() {
        val plain = LlamaMessageEntity(id = 1, chatId = 1, role = "assistant", content = "hi")
        val user = LlamaMessageEntity(id = 2, chatId = 1, role = "user", content = "yo")
        val history = listOf(user, plain)
        assertSame(history, NativeChatStepHistory.flattenEntities(history))
        assertEquals(listOf<Long?>(2L, 1L), NativeChatStepHistory.entityIds(history))
        assertNull(NativeChatStepHistory.decode(null))
    }

    @Test
    fun entityIdsFollowExpansionAndFlattenClearsSteps() {
        val withSteps = LlamaMessageEntity(
            id = 7, chatId = 1, role = "assistant", content = "Final answer",
            stepsJson = NativeChatStepsCodec.encode(twoRounds)
        )
        val history = listOf(LlamaMessageEntity(id = 6, chatId = 1, role = "user", content = "q"), withSteps)
        assertEquals(listOf<Long?>(6L, 7L, 7L, 7L, 7L, 7L, 7L), NativeChatStepHistory.entityIds(history))
        val flat = NativeChatStepHistory.flattenEntities(history)
        assertNull(flat[1].stepsJson)
        assertTrue(flat[1].content.endsWith("Final answer"))
        assertEquals(history[0], flat[0])
    }
}
