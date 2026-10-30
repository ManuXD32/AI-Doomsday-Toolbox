package com.example.llamadroid.ui.ai.llama.chat

import com.example.llamadroid.R
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.ui.ai.llama.chatlist.defaultPersistKvCacheForServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDelegationScreenSupportTest {
    @Test
    fun sanitizeKeepsSafeCharactersAndStripsPaths() {
        assertEquals("report 1.pdf", sanitizeChatFileName("report 1.pdf"))
        assertEquals("passwd", sanitizeChatFileName("../../etc/passwd"))
        assertEquals("a_b_c.txt", sanitizeChatFileName("a:b*c.txt"))
        assertEquals("file", sanitizeChatFileName("   "))
        assertEquals(80, sanitizeChatFileName("x".repeat(200)).length)
    }

    @Test
    fun noticeLabelMapsKnownTags() {
        assertEquals(
            R.string.native_chat_compaction_notice_finished,
            delegationNoticeLabelRes("[DeepSeek task finished]\nDone.")
        )
        assertEquals(R.string.native_chat_compaction_notice_check, delegationNoticeLabelRes("[DeepSeek scheduled check] x"))
        assertEquals(R.string.native_chat_compaction_notice_question, delegationNoticeLabelRes("[DeepSeek subagent question] x"))
        assertEquals(R.string.native_chat_compaction_notice_direction, delegationNoticeLabelRes("[DeepSeek task needs direction] x"))
        assertNull(delegationNoticeLabelRes("plain text"))
    }

    @Test
    fun ackedIdsAreDeduplicatedAndCapped() {
        assertEquals(listOf(1L, 2L), appendAckedChatId(listOf(1L, 2L), 2L))
        assertEquals(listOf(1L, 2L, 3L), appendAckedChatId(listOf(1L, 2L), 3L))
        assertEquals(listOf(1L, 3L, 2L), appendAckedChatId(listOf(1L, 2L, 3L), 2L))
        assertEquals(listOf(4L, 5L), appendAckedChatId(listOf(3L, 4L), 5L, cap = 2))
    }

    @Test
    fun persistKvCacheDefaultsOnlyForLoopbackLlamaServer() {
        val local = LlamaServerEntity(name = "l", host = "127.0.0.1", port = 8080)
        assertTrue(defaultPersistKvCacheForServer(local))
        assertFalse(defaultPersistKvCacheForServer(local.copy(host = "192.168.1.5")))
        assertFalse(defaultPersistKvCacheForServer(local.copy(engine = LlamaServerEntity.ENGINE_OLLAMA)))
        assertFalse(defaultPersistKvCacheForServer(null))
    }
}
