package com.example.llamadroid.service.chat

import com.example.llamadroid.data.api.LlamaChatMessage
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.LlamaServerRequestOptions
import com.example.llamadroid.service.buildLlamaServerChatRequestPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turn N+1's request must start with turn N's request plus the assistant reply (with its reasoning),
 * so llama-server's KV prefix keeps matching across turns on both the plain and the tools path.
 */
class CacheStablePrefixTest {
    private fun user(id: Long, text: String) = LlamaMessageEntity(id = id, chatId = 1, role = "user", content = text)

    // ---- plain path ----

    private fun plainRequest(history: List<LlamaMessageEntity>, liveHint: String?): List<LlamaChatMessage> {
        val flat = PlainTurnNotes.flattenKeepingNotes(history)
        val messages = mutableListOf(LlamaChatMessage("system", "base"))
        messages += PlainTurnNotes.requestMessages(flat) { _, message -> message.content }
        liveHint?.let { messages += PlainTurnNotes.noteMessage(it) }
        return messages
    }

    private fun plainReply(id: Long, content: String, thinking: String, hint: String?) = LlamaMessageEntity(
        id = id, chatId = 1, role = "assistant", content = content, thinking = thinking,
        stepsJson = hint?.let { PlainTurnNotes.recordHints(listOf(it))?.toJson(complete = true) }
    )

    @Test
    fun plainPathKeepsHintsAndReasoningAcrossThreeTurns() {
        val u1 = user(1, "hello"); val u2 = user(3, "next"); val u3 = user(5, "again")
        val turn1 = plainRequest(listOf(u1), "hint-1")
        val a1 = plainReply(2, "A1", "think-1", "hint-1")
        val turn2 = plainRequest(listOf(u1, a1, u2), "hint-2")
        val a2 = plainReply(4, "A2", "think-2", "hint-2")
        val turn3 = plainRequest(listOf(u1, a1, u2, a2, u3), null)

        val reply1 = LlamaChatMessage("assistant", "A1", reasoningContent = "think-1")
        assertEquals(turn1 + reply1, turn2.take(turn1.size + 1))
        val reply2 = LlamaChatMessage("assistant", "A2", reasoningContent = "think-2")
        assertEquals(turn2 + reply2, turn3.take(turn2.size + 1))
        // The hint travels as a tagged user message (Qwen templates reject a late system message).
        assertEquals("user", turn1.last().role)
        assertTrue((turn1.last().content as String).startsWith("[Runtime context]"))
    }

    @Test
    fun plainRowsWithoutHintsKeepTheirShapeAndNoReasoningOnUsers() {
        val rows = PlainTurnNotes.requestMessages(listOf(user(1, "q"), plainReply(2, "a", "", null))) { _, m -> m.content }
        assertEquals(listOf("user", "assistant"), rows.map { it.role })
        assertNull(rows[0].reasoningContent)
        assertNull(rows[1].reasoningContent)
    }

    // ---- tools path ----

    private fun toolsRecorder(hint: String, round0Thinking: String): NativeChatStepRecorder {
        val recorder = NativeChatStepRecorder()
        val round = recorder.roundFor(0)
        recorder.addNote(round, hint)
        recorder.addThinking(round, round0Thinking)
        val index = recorder.startTool(round, "call_$hint", "search", "{\"q\":\"x\"}")
        recorder.finishTool(index, NativeChatStepStatus.OK, "result-$hint", 5L)
        return recorder
    }

    private fun payload(messages: List<OllamaService.ChatMessage>): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return buildLlamaServerChatRequestPayload(
            messages = listOf(OllamaService.ChatMessage(role = "system", content = "base")) + messages,
            tools = emptyList(),
            thinkingEnabled = true,
            requestOptions = LlamaServerRequestOptions(sendReasoningContent = true)
        )["messages"] as List<Map<String, Any?>>
    }

    private fun replay(rows: List<LlamaMessageEntity>): List<OllamaService.ChatMessage> = rows.flatMap { row ->
        val steps = if (row.role == "assistant") NativeChatStepHistory.decode(row.stepsJson) else null
        if (steps != null) NativeChatStepHistory.expand(row.content, steps, true, null, row.thinking)
        else listOf(OllamaService.ChatMessage(role = row.role, content = row.content))
    }

    @Test
    fun toolsPathReplaysHintsAndReasoningAcrossThreeTurns() {
        val u1 = user(1, "hello"); val u2 = user(3, "next"); val u3 = user(5, "again")
        val rec1 = toolsRecorder("hint-1", "r0-1")
        // The last call of turn 1 carries the tool results; the final answer follows it.
        val turn1 = payload(replay(listOf(u1)) + NativeChatStepHistory.turnMessages(rec1.snapshot(false).steps))
        val a1 = LlamaMessageEntity(
            id = 2, chatId = 1, role = "assistant", content = "A1", thinking = "think-1",
            stepsJson = rec1.toJson(complete = true)
        )
        val finalReply1 = mapOf<String, Any?>("role" to "assistant", "content" to "A1", "reasoning_content" to "think-1")
        val turn2 = payload(replay(listOf(u1, a1, u2)))
        assertEquals(turn1 + finalReply1, turn2.take(turn1.size + 1))

        val rec2 = toolsRecorder("hint-2", "r0-2")
        val turn2Live = payload(replay(listOf(u1, a1, u2)) + NativeChatStepHistory.turnMessages(rec2.snapshot(false).steps))
        val a2 = LlamaMessageEntity(
            id = 4, chatId = 1, role = "assistant", content = "A2", thinking = "think-2",
            stepsJson = rec2.toJson(complete = true)
        )
        val finalReply2 = mapOf<String, Any?>("role" to "assistant", "content" to "A2", "reasoning_content" to "think-2")
        val turn3 = payload(replay(listOf(u1, a1, u2, a2, u3)))
        assertEquals(turn2Live + finalReply2, turn3.take(turn2Live.size + 1))
        assertTrue(turn3.any { it["reasoning_content"] == "r0-1" })
    }
}
