package com.example.llamadroid.service.compaction

import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaMessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatTurnDecoratorLogicTest {
    private fun msg(id: Long, role: String, content: String, kind: String? = null) =
        LlamaMessageEntity(id = id, chatId = 1, role = role, content = content, kind = kind)

    private val notice = LlamaMessageKind.DELEGATION_NOTICE

    @Test
    fun bindTargetIsNewestUnansweredOrdinaryUser() {
        val history = listOf(msg(1, "user", "a"), msg(2, "assistant", "b"), msg(3, "user", "c"), msg(4, "user", "[n]", notice))
        assertEquals(3L, NativeChatTurnDecoratorLogic.bindTargetMessageId(history))
    }

    @Test
    fun bindTargetNullWhenAnswered() {
        val history = listOf(msg(1, "user", "a"), msg(2, "assistant", "b"))
        assertNull(NativeChatTurnDecoratorLogic.bindTargetMessageId(history))
        assertNull(NativeChatTurnDecoratorLogic.bindTargetMessageId(emptyList()))
        assertNull(NativeChatTurnDecoratorLogic.bindTargetMessageId(listOf(msg(1, "user", "[n]", notice))))
    }

    @Test
    fun boundaryDropsOlderAndMergesSummaryIntoFirstUser() {
        val history = listOf(msg(1, "user", "a"), msg(2, "assistant", "b"), msg(3, "user", "c"), msg(4, "assistant", "d"))
        val out = NativeChatTurnDecoratorLogic.applyBoundary(history, 1, 2, "SUM")
        assertEquals(listOf(3L, 4L), out.map { it.id })
        assertTrue(out[0].content.startsWith(ConversationCompactor.SUMMARY_OPEN))
        assertTrue(out[0].content.endsWith("c"))
        assertTrue(out[0].content.contains("SUM"))
    }

    @Test
    fun boundaryReplacesExistingSummaryInsteadOfNesting() {
        val first = msg(3, "user", ConversationCompactor.mergeSummaryIntoUserText("c", "OLD"))
        val out = NativeChatTurnDecoratorLogic.applyBoundary(listOf(first, msg(4, "assistant", "d")), 1, 2, "NEW")
        assertFalse(out[0].content.contains("OLD"))
        assertEquals(1, Regex(Regex.escape(ConversationCompactor.SUMMARY_OPEN)).findAll(out[0].content).count())
    }

    @Test
    fun boundaryInsertsSyntheticUserWhenNoUserRemains() {
        val out = NativeChatTurnDecoratorLogic.applyBoundary(
            listOf(msg(1, "user", "a"), msg(2, "assistant", "b")), 7, 2, "SUM"
        )
        assertEquals(1, out.size)
        assertEquals(0L, out[0].id)
        assertEquals("user", out[0].role)
        assertEquals(7L, out[0].chatId)
    }

    @Test
    fun boundaryPrependsSyntheticWhenFirstRemainingIsAssistant() {
        val out = NativeChatTurnDecoratorLogic.applyBoundary(
            listOf(msg(1, "user", "a"), msg(2, "assistant", "b"), msg(3, "assistant", "c")), 1, 1, "SUM"
        )
        assertEquals(listOf("user", "assistant", "assistant"), out.map { it.role })
    }

    @Test
    fun sizeFormatIsDeterministic() {
        assertEquals("12 B", NativeChatTurnDecoratorLogic.formatSize(12))
        assertEquals("340 KB", NativeChatTurnDecoratorLogic.formatSize(340L * 1024))
        assertEquals("1.2 MB", NativeChatTurnDecoratorLogic.formatSize((1.2 * 1024 * 1024).toLong()))
    }

    @Test
    fun manifestLineFormat() {
        val line = NativeChatTurnDecoratorLogic.manifestLine(
            listOf(
                AttachmentManifestItem("f12", "report.pdf", (1.2 * 1024 * 1024).toLong()),
                AttachmentManifestItem("img55", "photo.jpg", 340L * 1024)
            )
        )
        assertEquals("[Attached files: f12 report.pdf (1.2 MB), img55 photo.jpg (340 KB)]", line)
        assertNull(NativeChatTurnDecoratorLogic.manifestLine(emptyList()))
        assertEquals("hi\n\n[Attached files: f1 a.txt (1 B)]",
            NativeChatTurnDecoratorLogic.appendManifest("hi", listOf(AttachmentManifestItem("f1", "a.txt", 1))))
    }

    @Test
    fun consecutiveUsersMergeAndKeepLastRole() {
        val history = listOf(
            msg(1, "user", "a"), msg(2, "user", "[DeepSeek task finished]", notice), msg(3, "assistant", "b"),
            msg(4, "user", "c"), msg(5, "user", "[n2]", notice)
        )
        val out = NativeChatTurnDecoratorLogic.mergeConsecutiveUsers(history)
        assertEquals(listOf("user", "assistant", "user"), out.map { it.role })
        assertEquals("a\n\n[DeepSeek task finished]", out[0].content)
        assertEquals(2L, out[0].id)
        assertEquals("user", out.last().role)
    }

    @Test
    fun boundaryIdMapping() {
        val ids = listOf<Long?>(null, 10L, 11L, null, 12L, 13L)
        assertEquals(12L, NativeChatTurnDecoratorLogic.boundaryMessageId(ids, 1, 5))
        assertEquals(11L, NativeChatTurnDecoratorLogic.boundaryMessageId(ids, 1, 4))
        assertNull(NativeChatTurnDecoratorLogic.boundaryMessageId(ids, 3, 4))
        assertEquals(13L, NativeChatTurnDecoratorLogic.boundaryMessageId(ids, 1, 99))
    }

    @Test
    fun contextOverflowMatcher() {
        assertTrue(NativeChatTurnDecoratorLogic.isContextOverflowMessage(
            "Server Error 400: {\"error\":{\"message\":\"the request exceeds the available context size (4096 tokens)\"}}"))
        assertTrue(NativeChatTurnDecoratorLogic.isContextOverflowMessage("{\"type\":\"exceed_context_size_error\"}"))
        assertFalse(NativeChatTurnDecoratorLogic.isContextOverflowMessage("Server Error 500: boom"))
        assertFalse(NativeChatTurnDecoratorLogic.isContextOverflowMessage(null))
    }
}
