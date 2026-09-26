package com.example.llamadroid.harness

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentMessageEntity
import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HarnessLegacyHistoryTest {
    @Test fun largeLegacyMessagesAreReadThroughBoundedCursorWindowsWithoutTruncatingStorage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val conversation = database.agentChatDao().insertConversation(AgentConversationEntity(title = "History QA"))
            val content = "x".repeat(3 * 1024 * 1024) + "final-marker"
            val id = database.agentChatDao().insertMessage(AgentMessageEntity(originalId = "large-history-qa", conversationId = conversation,
                role = "tool", content = content, thinking = "reasoning-marker", toolOutput = "tool-marker", terminalOutput = "terminal-marker", sequenceNumber = 1))
            val preview = database.harnessDao().legacyMessagePreviews(conversation).single()
            assertEquals(4000, preview.preview.length)
            assertEquals(id, preview.id)
            val first = requireNotNull(database.harnessDao().legacyMessageChunk(conversation, id, "content", 1))
            assertEquals(24_000, first.text.length)
            assertEquals(content.length, first.totalLength)
            val last = requireNotNull(database.harnessDao().legacyMessageChunk(conversation, id, "content", content.length - "final-marker".length + 1))
            assertEquals("final-marker", last.text)
            assertEquals("reasoning-marker", database.harnessDao().legacyMessageChunk(conversation, id, "thinking", 1)?.text)
            assertEquals("tool-marker", database.harnessDao().legacyMessageChunk(conversation, id, "toolOutput", 1)?.text)
            assertEquals("terminal-marker", database.harnessDao().legacyMessageChunk(conversation, id, "terminalOutput", 1)?.text)
            assertNull(database.harnessDao().legacyMessageChunk(conversation + 1, id, "content", 1))
        } finally { database.close() }
    }
}
