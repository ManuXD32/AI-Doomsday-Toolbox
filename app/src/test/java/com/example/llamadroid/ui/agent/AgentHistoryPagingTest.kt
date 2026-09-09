package com.example.llamadroid.ui.agent

import androidx.room.Room
import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.AgentMessageEntity
import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AgentHistoryPagingTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `page projection stays bounded and uses stable sequence and id ordering`() {
        val rows = listOf(
            message(id = 12, sequence = 2),
            message(id = 10, sequence = 1),
            message(id = 14, sequence = 2),
            message(id = 11, sequence = 1),
            message(id = 15, sequence = 3)
        )

        val page = AgentHistoryPage.newest(rows, pageSize = 3)

        assertEquals(listOf(12L, 14L, 15L), page.messages.map { it.id })
        assertTrue(page.hasOlder)
        assertFalse(page.hasNewer)
        assertTrue(page.atNewest)
        assertEquals(AgentHistoryCursor(sequenceNumber = 2, messageId = 12), page.oldestCursor)
        assertEquals(AgentHistoryCursor(sequenceNumber = 3, messageId = 15), page.newestCursor)
    }

    @Test
    fun `dao pages older and newer rows without reducing full history`() = runBlocking {
        val conversationId = database.agentChatDao().insertConversation(
            AgentConversationEntity(title = "Paging test")
        )
        database.agentChatDao().insertMessages(
            (0 until 405).map { index ->
                message(
                    id = 0,
                    sequence = index,
                    conversationId = conversationId
                )
            }
        )

        val pager = AgentHistoryPager(database.agentChatDao(), pageSize = 200)
        val newest = pager.newest(conversationId)
        val older = pager.older(conversationId, newest)
        val newer = pager.newer(conversationId, older)

        assertEquals(200, newest.messages.size)
        assertEquals(205, newest.messages.first().sequenceNumber)
        assertEquals(404, newest.messages.last().sequenceNumber)
        assertTrue(newest.hasOlder)
        assertFalse(newest.hasNewer)

        assertEquals(200, older.messages.size)
        assertEquals(5, older.messages.first().sequenceNumber)
        assertEquals(204, older.messages.last().sequenceNumber)
        assertTrue(older.hasOlder)
        assertTrue(older.hasNewer)

        assertEquals(newest.messages.map { it.sequenceNumber }, newer.messages.map { it.sequenceNumber })
        assertFalse(newer.hasNewer)
        assertTrue(newer.atNewest)
        assertEquals(405, database.agentChatDao().getMessagesForConversationSync(conversationId).size)
    }

    @Test
    fun `older and newer cursors distinguish equal sequence numbers`() = runBlocking {
        val conversationId = database.agentChatDao().insertConversation(
            AgentConversationEntity(title = "Tie cursor test")
        )
        database.agentChatDao().insertMessages(
            (0 until 5).map { index ->
                message(
                    id = 0,
                    sequence = 7,
                    conversationId = conversationId,
                    originalId = "tie-$index"
                )
            }
        )

        val pager = AgentHistoryPager(database.agentChatDao(), pageSize = 2)
        val newest = pager.newest(conversationId)
        val older = pager.older(conversationId, newest)
        val newer = pager.newer(conversationId, older)

        assertEquals(listOf(4L, 5L), newest.messages.map { it.id })
        assertEquals(listOf(2L, 3L), older.messages.map { it.id })
        assertEquals(listOf(4L, 5L), newer.messages.map { it.id })
    }

    @Test
    fun `cursor deletion preserves the older history prefix`() = runBlocking {
        val conversationId = database.agentChatDao().insertConversation(
            AgentConversationEntity(title = "Truncation test")
        )
        database.agentChatDao().insertMessages(
            (0 until 5).map { index ->
                message(
                    id = index.toLong() + 1L,
                    sequence = index,
                    conversationId = conversationId,
                    originalId = "truncate-$index"
                )
            }
        )

        val deleted = database.agentChatDao().deleteMessagesAtOrAfter(
            conversationId = conversationId,
            fromSequenceNumber = 2,
            fromMessageId = 3L
        )

        assertEquals(3, deleted)
        assertEquals(
            listOf(1L, 2L),
            database.agentChatDao().getMessagesForConversationSync(conversationId).map { it.id }
        )
    }

    private fun message(
        id: Long,
        sequence: Int,
        conversationId: Long = 1L,
        originalId: String = "message-$id-$sequence"
    ) = AgentMessageEntity(
        id = id,
        originalId = originalId,
        conversationId = conversationId,
        role = "user",
        content = "message-$sequence",
        sequenceNumber = sequence
    )
}
