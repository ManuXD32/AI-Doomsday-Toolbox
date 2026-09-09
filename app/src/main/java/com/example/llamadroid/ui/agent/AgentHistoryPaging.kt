package com.example.llamadroid.ui.agent

import com.example.llamadroid.data.db.AgentChatDao
import com.example.llamadroid.data.db.AgentMessageEntity
import com.example.llamadroid.data.db.AGENT_CONVERSATION_HISTORY_PAGE_SIZE

internal data class AgentHistoryCursor(
    val sequenceNumber: Int,
    val messageId: Long
)

/** One bounded timeline window. Room rows are exposed in ascending timeline order. */
internal data class AgentHistoryPage(
    val messages: List<AgentMessageEntity>,
    val hasOlder: Boolean,
    val hasNewer: Boolean,
    val atNewest: Boolean
) {
    val oldestCursor: AgentHistoryCursor?
        get() = messages.minWithOrNull(compareBy<AgentMessageEntity> { it.sequenceNumber }.thenBy { it.id })
            ?.let { AgentHistoryCursor(it.sequenceNumber, it.id) }

    val newestCursor: AgentHistoryCursor?
        get() = messages.maxWithOrNull(compareBy<AgentMessageEntity> { it.sequenceNumber }.thenBy { it.id })
            ?.let { AgentHistoryCursor(it.sequenceNumber, it.id) }

    companion object {
        private val ascendingOrder = compareBy<AgentMessageEntity> { it.sequenceNumber }
            .thenBy { it.id }
        private val descendingOrder = ascendingOrder.reversed()

        fun newest(
            rowsDescending: List<AgentMessageEntity>,
            pageSize: Int = AGENT_CONVERSATION_HISTORY_PAGE_SIZE
        ): AgentHistoryPage {
            require(pageSize > 0) { "pageSize must be positive" }
            val boundedRows = rowsDescending.sortedWith(descendingOrder).take(pageSize)
            return AgentHistoryPage(
                messages = boundedRows.sortedWith(ascendingOrder),
                hasOlder = rowsDescending.size > pageSize,
                hasNewer = false,
                atNewest = true
            )
        }

        fun older(
            rowsDescending: List<AgentMessageEntity>,
            pageSize: Int = AGENT_CONVERSATION_HISTORY_PAGE_SIZE
        ): AgentHistoryPage {
            require(pageSize > 0) { "pageSize must be positive" }
            val boundedRows = rowsDescending.sortedWith(descendingOrder).take(pageSize)
            return AgentHistoryPage(
                messages = boundedRows.sortedWith(ascendingOrder),
                hasOlder = rowsDescending.size > pageSize,
                hasNewer = true,
                atNewest = false
            )
        }

        fun newer(
            rowsAscending: List<AgentMessageEntity>,
            pageSize: Int = AGENT_CONVERSATION_HISTORY_PAGE_SIZE
        ): AgentHistoryPage {
            require(pageSize > 0) { "pageSize must be positive" }
            val boundedRows = rowsAscending.sortedWith(ascendingOrder).take(pageSize)
            val hasNewer = rowsAscending.size > pageSize
            return AgentHistoryPage(
                messages = boundedRows,
                hasOlder = true,
                hasNewer = hasNewer,
                atNewest = !hasNewer
            )
        }
    }
}

/** DAO-only pager; it has no Service or Compose ownership. */
internal class AgentHistoryPager(
    private val dao: AgentChatDao,
    private val pageSize: Int = AGENT_CONVERSATION_HISTORY_PAGE_SIZE
) {
    suspend fun newest(conversationId: Long): AgentHistoryPage = AgentHistoryPage.newest(
        dao.getRecentMessagesForConversationSync(conversationId, pageSize + 1),
        pageSize
    )

    suspend fun older(conversationId: Long, current: AgentHistoryPage): AgentHistoryPage {
        if (!current.hasOlder) return current
        val cursor = current.oldestCursor ?: return current
        val rows = dao.getOlderMessagesForConversation(
            conversationId,
            cursor.sequenceNumber,
            cursor.messageId,
            pageSize + 1
        )
        return if (rows.isEmpty()) {
            current
        } else {
            AgentHistoryPage.older(rows, pageSize)
        }
    }

    suspend fun newer(conversationId: Long, current: AgentHistoryPage): AgentHistoryPage {
        if (!current.hasNewer) return current
        val cursor = current.newestCursor ?: return current
        val rows = dao.getNewerMessagesForConversation(
            conversationId,
            cursor.sequenceNumber,
            cursor.messageId,
            pageSize + 1
        )
        return if (rows.isEmpty()) {
            current
        } else {
            AgentHistoryPage.newer(rows, pageSize)
        }
    }
}
