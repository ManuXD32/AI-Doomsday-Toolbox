package com.example.llamadroid.service.compaction

import com.example.llamadroid.service.OllamaService.ChatMessage
import com.example.llamadroid.service.OllamaService.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationCompactorTest {
    private val est = TokenEstimator()
    private val policy = CompactionPolicy()

    private fun u(t: String) = ChatMessage(role = "user", content = t)
    private fun a(t: String) = ChatMessage(role = "assistant", content = t)
    private fun sys(t: String) = ChatMessage(role = "system", content = t)
    private fun toolCall(id: String) = ChatMessage(
        role = "assistant", content = "", toolCalls = listOf(ToolCall("t", mapOf("k" to "v"), id))
    )
    private fun toolRes(id: String, t: String) = ChatMessage(role = "tool", content = t, toolCallId = id)

    private fun history(turns: Int, size: Int = 400): List<ChatMessage> =
        listOf(sys("system")) + (1..turns).flatMap { listOf(u("q$it " + "x".repeat(size)), a("a$it " + "y".repeat(size))) }

    @Test fun thresholdBoundary() {
        // usable = 10000 - 1000 = 9000, threshold 7200
        assertFalse(ConversationCompactor.shouldCompact(7199, 10000, 1000))
        assertTrue(ConversationCompactor.shouldCompact(7200, 10000, 1000))
        assertFalse(ConversationCompactor.shouldCompact(100, 0, 0))
    }

    @Test fun clamps() {
        assertEquals(512, ConversationCompactor.outputReserve(1000, null))
        assertEquals(4096, ConversationCompactor.outputReserve(100000, null))
        assertEquals(777, ConversationCompactor.outputReserve(8000, 777))
        assertEquals(512, ConversationCompactor.summaryMaxTokens(1000))
        assertEquals(1000, ConversationCompactor.summaryMaxTokens(8000))
        assertEquals(2048, ConversationCompactor.summaryMaxTokens(100000))
    }

    @Test fun planKeepsMinExchangesAndStartsAtUser() {
        val h = history(10)
        val plan = ConversationCompactor.plan(h, 8000, est, policy)!!
        assertEquals("user", plan.kept.first().role)
        assertTrue(plan.kept.count { it.role == "user" } >= 2)
        assertEquals(h.size, plan.leadingSystem.size + plan.toSummarize.size + plan.kept.size)
        assertEquals(plan.keptStartIndex, plan.leadingSystem.size + plan.toSummarize.size)
        assertEquals(listOf(h[0]), plan.leadingSystem)
        // ~16% of body, but never fewer than 2 turns
        assertTrue(plan.kept.count { it.role == "user" } <= 3)
    }

    @Test fun planNullWhenTooFewExchanges() {
        assertNull(ConversationCompactor.plan(history(2), 8000, est, policy))
        assertNotNull(ConversationCompactor.plan(history(3), 8000, est, policy))
    }

    @Test fun neverSplitsToolPairs() {
        val h = listOf(sys("s")) + (1..8).flatMap {
            listOf(u("q$it"), toolCall("c$it"), toolRes("c$it", "r".repeat(300)), a("done$it"))
        }
        val plan = ConversationCompactor.plan(h, 4000, est, policy)!!
        assertEquals("user", plan.kept.first().role)
        val keptIds = plan.kept.mapNotNull { it.toolCallId }.toSet()
        val callIds = plan.kept.flatMap { it.toolCalls.orEmpty() }.mapNotNull { it.id }.toSet()
        assertEquals(callIds, keptIds)
        val sumIds = plan.toSummarize.mapNotNull { it.toolCallId }.toSet()
        val sumCalls = plan.toSummarize.flatMap { it.toolCalls.orEmpty() }.mapNotNull { it.id }.toSet()
        assertEquals(sumCalls, sumIds)
    }

    @Test fun pruneExactAndIdempotent() {
        val big = "H".repeat(4096) + "M".repeat(5000) + "T".repeat(1024)
        val small = "s".repeat(8192)
        val out = ConversationCompactor.pruneToolResults(listOf(toolRes("1", big), toolRes("2", small), u(big)), policy)
        assertTrue(out[0].content.startsWith("H".repeat(4096)))
        assertTrue(out[0].content.endsWith("T".repeat(1024)))
        assertTrue(out[0].content.contains("5000 chars omitted"))
        assertEquals(small, out[1].content)
        assertEquals(big, out[2].content) // non-tool untouched
        assertEquals(out, ConversationCompactor.pruneToolResults(out, policy))
    }

    @Test fun applySummaryMergesAndKeepsAlternation() {
        val h = history(10)
        val plan = ConversationCompactor.plan(h, 8000, est, policy)!!
        val out = ConversationCompactor.applySummary(plan, "SUM")
        assertEquals("system", out[0].role)
        assertEquals("user", out[1].role)
        assertTrue(out[1].content.contains("<compacted-summary>"))
        assertTrue(out[1].content.contains("SUM"))
        assertTrue(out[1].content.contains(plan.kept.first().content))
        assertEquals(1 + plan.kept.size, out.size)
        assertEquals(plan.kept.drop(1), out.drop(2))
    }

    @Test fun applySummaryReplacesExistingBlock() {
        val first = ConversationCompactor.applySummary(
            ConversationCompactor.plan(history(10), 8000, est, policy)!!, "OLDSUM")
        val again = ConversationCompactor.applySummary(
            CompactionPlan(emptyList(), emptyList(), first.drop(1), 1, 0), "NEWSUM")
        val text = again.first().content
        assertEquals(1, Regex("<compacted-summary>").findAll(text).count())
        assertTrue(text.contains("NEWSUM"))
        assertFalse(text.contains("OLDSUM"))
    }

    @Test fun applySummaryInsertsUserWhenKeptNotUser() {
        val out = ConversationCompactor.applySummary(CompactionPlan(emptyList(), emptyList(), listOf(a("x")), 0, 0), "S")
        assertEquals(listOf("user", "assistant"), out.map { it.role })
    }

    @Test fun stripSummary() {
        val merged = ConversationCompactor.applySummary(
            CompactionPlan(emptyList(), emptyList(), listOf(u("hello")), 0, 0), "S").first().content
        assertEquals("hello", ConversationCompactor.stripSummaryFromText(merged))
    }

    @Test fun summaryRequestReplaysFullPrompt() {
        val plan = ConversationCompactor.plan(history(10), 8000, est, policy)!!
        val req = ConversationCompactor.buildSummaryRequest(plan, CompactionProfile.MAIN_CHAT)
        assertEquals(10 * 2 + 1 + 1, req.size)
        assertEquals("user", req.last().role)
        assertEquals(ConversationCompactor.summaryInstruction(CompactionProfile.MAIN_CHAT), req.last().content)
    }

    @Test fun summaryRequestMergesInstructionIntoTrailingUserTurn() {
        val plan = ConversationCompactor.plan(history(10) + u("newest question"), 8000, est, policy)!!
        val req = ConversationCompactor.buildSummaryRequest(plan, CompactionProfile.MAIN_CHAT)
        assertEquals("user", req.last().role)
        assertNotEquals("user", req[req.size - 2].role)
        assertTrue(req.last().content.startsWith("newest question"))
        assertTrue(req.last().content.endsWith(ConversationCompactor.summaryInstruction(CompactionProfile.MAIN_CHAT)))
    }

    @Test fun validity() {
        for (p in CompactionProfile.values()) {
            val full = ConversationCompactor.sections(p).joinToString("\n") { "## $it\nsome content here for it" }
            assertTrue(ConversationCompactor.isValidSummary(full, p))
            assertFalse(ConversationCompactor.isValidSummary(full.replace("Next step", "x"), p))
            assertFalse(ConversationCompactor.isValidSummary("short", p))
            val instr = ConversationCompactor.summaryInstruction(p)
            ConversationCompactor.sections(p).forEach { assertTrue(instr.contains("## $it")) }
        }
    }

    @Test fun deterministicBoundedAndValid() {
        val msgs = listOf(u("Fix src/main/App.kt " + "z".repeat(20000)), toolRes("1", "Error: boom\nok"), u("now do f12"))
        for (p in CompactionProfile.values()) {
            val s = ConversationCompactor.deterministicSummary(msgs, p, 3000)
            assertTrue(s.length <= 3000)
            assertTrue(ConversationCompactor.isValidSummary(s, p))
        }
        val s = ConversationCompactor.deterministicSummary(msgs, CompactionProfile.MAIN_CHAT)
        assertTrue(s.contains("src/main/App.kt"))
        assertTrue(s.contains("Error: boom"))
    }
}
