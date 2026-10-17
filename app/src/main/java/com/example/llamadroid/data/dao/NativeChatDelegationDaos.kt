package com.example.llamadroid.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.llamadroid.data.model.LlamaMessageAttachmentEntity
import com.example.llamadroid.data.model.NativeChatCompactionEntity
import com.example.llamadroid.data.model.NativeChatDelegateEventEntity
import com.example.llamadroid.data.model.NativeChatDelegateMessageEntity
import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import kotlinx.coroutines.flow.Flow

@Dao
interface NativeChatDelegateTaskDao {
    @Query("SELECT id FROM native_chat_delegate_tasks WHERE chatId = :chatId")
    suspend fun getIdsForChat(chatId: Long): List<Long>

    @Query("SELECT * FROM native_chat_delegate_tasks WHERE id = :id")
    suspend fun getById(id: Long): NativeChatDelegateTaskEntity?

    @Query("SELECT * FROM native_chat_delegate_tasks WHERE id = :id")
    fun observeById(id: Long): Flow<NativeChatDelegateTaskEntity?>

    @Query("SELECT * FROM native_chat_delegate_tasks WHERE chatId = :chatId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun getLatestForChat(chatId: Long): NativeChatDelegateTaskEntity?

    @Query("SELECT * FROM native_chat_delegate_tasks WHERE chatId = :chatId ORDER BY createdAt DESC, id DESC LIMIT 1")
    fun observeLatestForChat(chatId: Long): Flow<NativeChatDelegateTaskEntity?>

    @Query("SELECT * FROM native_chat_delegate_tasks WHERE chatId = :chatId ORDER BY createdAt DESC, id DESC")
    fun observeForChat(chatId: Long): Flow<List<NativeChatDelegateTaskEntity>>

    @Query("SELECT * FROM native_chat_delegate_tasks WHERE state IN (:states) ORDER BY createdAt ASC")
    suspend fun getInStates(states: List<String>): List<NativeChatDelegateTaskEntity>

    @Query("SELECT * FROM native_chat_delegate_tasks WHERE chatId = :chatId AND state IN (:states) ORDER BY createdAt DESC LIMIT 1")
    suspend fun getForChatInStates(chatId: Long, states: List<String>): NativeChatDelegateTaskEntity?

    @Query("SELECT * FROM native_chat_delegate_tasks WHERE nextCheckAt IS NOT NULL AND state IN (:states)")
    suspend fun getWithScheduledChecks(states: List<String>): List<NativeChatDelegateTaskEntity>

    @Query("SELECT * FROM native_chat_delegate_tasks WHERE pendingWakeKind IS NOT NULL ORDER BY pendingWakeAt ASC")
    suspend fun getWithPendingWakes(): List<NativeChatDelegateTaskEntity>

    /** The most recent finished task of a chat that created a project; used by `project = continue`. */
    @Query(
        "SELECT * FROM native_chat_delegate_tasks WHERE chatId = :chatId AND workspaceId IS NOT NULL " +
            "ORDER BY createdAt DESC, id DESC LIMIT 1"
    )
    suspend fun getLatestWithWorkspace(chatId: Long): NativeChatDelegateTaskEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(task: NativeChatDelegateTaskEntity): Long

    @Update
    suspend fun update(task: NativeChatDelegateTaskEntity)

    @Query("UPDATE native_chat_delegate_tasks SET state = :state, updatedAt = :now WHERE id = :id")
    suspend fun setState(id: Long, state: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE native_chat_delegate_tasks SET state = :state, lastErrorCode = :errorCode, progressNote = COALESCE(:note, progressNote), updatedAt = :now WHERE id = :id")
    suspend fun setStateWithError(id: Long, state: String, errorCode: String?, note: String?, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE native_chat_delegate_tasks SET subagentModelCalls = :modelCalls, harnessPrompts = :harnessPrompts, " +
            "compactionCount = :compactions, ctxUsedTokens = :ctxUsed, ctxWindowTokens = :ctxWindow, updatedAt = :now WHERE id = :id"
    )
    suspend fun updateCounters(
        id: Long, modelCalls: Int, harnessPrompts: Int, compactions: Int, ctxUsed: Int, ctxWindow: Int,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE native_chat_delegate_tasks SET optionsJson = :options, updatedAt = :now WHERE id = :id")
    suspend fun setOptions(id: Long, options: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE native_chat_delegate_tasks SET progressNote = :note, updatedAt = :now WHERE id = :id")
    suspend fun setProgressNote(id: Long, note: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE native_chat_delegate_tasks SET digestJson = :digest, updatedAt = :now WHERE id = :id")
    suspend fun setDigest(id: Long, digest: String?, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE native_chat_delegate_tasks SET nextCheckAt = :at, checkNote = :note, " +
            "checkCadenceMinutes = :cadence, alarmCount = alarmCount + :increment, updatedAt = :now WHERE id = :id"
    )
    suspend fun setScheduledCheck(
        id: Long,
        at: Long?,
        note: String?,
        cadence: Int?,
        increment: Int,
        now: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE native_chat_delegate_tasks SET pendingWakeKind = :kind, pendingWakeAt = :at, " +
            "updatedAt = :now WHERE id = :id"
    )
    suspend fun setPendingWake(id: Long, kind: String?, at: Long?, now: Long = System.currentTimeMillis())

    @Query(
        "UPDATE native_chat_delegate_tasks SET pendingWakeKind = NULL, pendingWakeAt = NULL, " +
            "wakeCount = wakeCount + 1, updatedAt = :now WHERE id = :id AND pendingWakeKind IS NOT NULL"
    )
    suspend fun claimPendingWake(id: Long, now: Long = System.currentTimeMillis()): Int

    @Query("SELECT COUNT(*) FROM native_chat_delegate_tasks WHERE state IN (:states)")
    suspend fun countInStates(states: List<String>): Int

    @Query("SELECT COUNT(*) FROM native_chat_delegate_tasks WHERE chatId = :chatId AND state IN (:states)")
    suspend fun countForChatInStates(chatId: Long, states: List<String>): Int

    /**
     * Inserts [task] only when its chat has no active task (one active task per chat; different
     * chats may run tasks at the same time). Returns the new id, or null when one is still active.
     */
    @Transaction
    suspend fun insertIfNoneActive(task: NativeChatDelegateTaskEntity): Long? {
        if (countForChatInStates(task.chatId, NativeChatDelegateTaskState.ACTIVE.toList()) > 0) return null
        return insert(task)
    }
}

@Dao
interface NativeChatDelegateMessageDao {
    @Query("SELECT * FROM native_chat_delegate_messages WHERE taskId = :taskId ORDER BY seq ASC")
    suspend fun getForTask(taskId: Long): List<NativeChatDelegateMessageEntity>

    @Query("SELECT * FROM native_chat_delegate_messages WHERE taskId = :taskId ORDER BY seq ASC")
    fun observeForTask(taskId: Long): Flow<List<NativeChatDelegateMessageEntity>>

    /** Messages still sent to the model: not compacted out, in order. */
    @Query("SELECT * FROM native_chat_delegate_messages WHERE taskId = :taskId AND compactedOut = 0 ORDER BY seq ASC")
    suspend fun getActiveForTask(taskId: Long): List<NativeChatDelegateMessageEntity>

    @Query("SELECT * FROM native_chat_delegate_messages WHERE taskId = :taskId AND role IN (:roles) ORDER BY seq ASC")
    fun observeForTaskWithRoles(taskId: Long, roles: List<String>): Flow<List<NativeChatDelegateMessageEntity>>

    @Query("SELECT COALESCE(MAX(seq), 0) FROM native_chat_delegate_messages WHERE taskId = :taskId")
    suspend fun maxSeq(taskId: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: NativeChatDelegateMessageEntity): Long

    /** Appends [message] with the next sequence number of its task and returns the stored row id. */
    @Transaction
    suspend fun append(message: NativeChatDelegateMessageEntity): Long =
        insert(message.copy(seq = maxSeq(message.taskId) + 1))

    // ---- durable mailbox (SUB_TO_MAIN rows keep their kind reply/question/result/progress in toolName) ----

    /** Main-to-subagent messages the loop has not injected yet, oldest first. */
    @Query("SELECT * FROM native_chat_delegate_messages WHERE taskId = :taskId AND role = 'main_to_sub' AND delivered = 0 ORDER BY seq ASC")
    suspend fun getUndeliveredMainToSub(taskId: Long): List<NativeChatDelegateMessageEntity>

    /** Undelivered subagent reply/question/result rows after [afterSeq], oldest first. */
    @Query(
        "SELECT * FROM native_chat_delegate_messages WHERE taskId = :taskId AND role = 'sub_to_main' AND delivered = 0 " +
            "AND seq > :afterSeq AND toolName IN ('reply', 'question', 'result') ORDER BY seq ASC"
    )
    suspend fun getUndeliveredSubToMain(taskId: Long, afterSeq: Int): List<NativeChatDelegateMessageEntity>

    /** The newest undelivered subagent reply/question, else the latest one (for the status text). */
    @Query(
        "SELECT * FROM native_chat_delegate_messages WHERE taskId = :taskId AND role = 'sub_to_main' " +
            "AND toolName IN ('reply', 'question') ORDER BY delivered ASC, seq DESC LIMIT 1"
    )
    suspend fun getStatusSubMessage(taskId: Long): NativeChatDelegateMessageEntity?

    @Query("SELECT DISTINCT taskId FROM native_chat_delegate_messages WHERE role = 'sub_to_main' AND delivered = 0 AND toolName IN ('reply', 'question', 'result')")
    suspend fun taskIdsWithUndeliveredSubToMain(): List<Long>

    /** Atomic claim: returns 1 only for the caller that flipped the row from undelivered to delivered. */
    @Query("UPDATE native_chat_delegate_messages SET delivered = 1 WHERE id = :id AND delivered = 0")
    suspend fun markDelivered(id: Long): Int

    /** Claims every undelivered SUB_TO_MAIN row of the given kinds; returns how many were claimed. */
    @Query("UPDATE native_chat_delegate_messages SET delivered = 1 WHERE taskId = :taskId AND role = 'sub_to_main' AND delivered = 0 AND toolName IN (:kinds)")
    suspend fun markSubToMainDelivered(taskId: Long, kinds: List<String>): Int

    @Query("SELECT seq FROM native_chat_delegate_messages WHERE id = :id")
    suspend fun seqOf(id: Long): Int

    /** Content of the newest main-to-subagent message (to tell a viewer-typed "[User]" message apart). */
    @Query("SELECT content FROM native_chat_delegate_messages WHERE taskId = :taskId AND role = 'main_to_sub' ORDER BY seq DESC LIMIT 1")
    suspend fun latestMainToSubContent(taskId: Long): String?

    /**
     * Injects one undelivered main-to-subagent row: claims it and appends its USER copy in ONE
     * transaction, so a crash can neither lose nor duplicate the message.
     */
    @Transaction
    suspend fun deliverMainToSub(row: NativeChatDelegateMessageEntity): Boolean {
        if (markDelivered(row.id) != 1) return false
        append(NativeChatDelegateMessageEntity(taskId = row.taskId, seq = 0, role = "user", content = row.content))
        return true
    }

    @Query("UPDATE native_chat_delegate_messages SET compactedOut = 1 WHERE taskId = :taskId AND seq <= :throughSeq AND isSummary = 0")
    suspend fun markCompactedThrough(taskId: Long, throughSeq: Int)

    @Query("UPDATE native_chat_delegate_messages SET compactedOut = 1 WHERE taskId = :taskId AND isSummary = 1 AND id != :keepId")
    suspend fun retireOlderSummaries(taskId: Long, keepId: Long)
}

@Dao
interface NativeChatDelegateEventDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: NativeChatDelegateEventEntity): Long

    @Query("SELECT * FROM native_chat_delegate_events WHERE taskId = :taskId ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun recent(taskId: Long, limit: Int = 200): List<NativeChatDelegateEventEntity>

    @Query(
        "DELETE FROM native_chat_delegate_events WHERE taskId = :taskId AND id NOT IN " +
            "(SELECT id FROM native_chat_delegate_events WHERE taskId = :taskId ORDER BY id DESC LIMIT :keep)"
    )
    suspend fun prune(taskId: Long, keep: Int = 2000)
}

@Dao
interface LlamaMessageAttachmentDao {
    @Query("SELECT * FROM llama_message_attachments WHERE id = :id")
    suspend fun getById(id: Long): LlamaMessageAttachmentEntity?

    @Query("SELECT * FROM llama_message_attachments WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<LlamaMessageAttachmentEntity>

    @Query("SELECT * FROM llama_message_attachments WHERE chatId = :chatId ORDER BY createdAt ASC, id ASC")
    fun observeForChat(chatId: Long): Flow<List<LlamaMessageAttachmentEntity>>

    @Query("SELECT * FROM llama_message_attachments WHERE chatId = :chatId ORDER BY createdAt ASC, id ASC")
    suspend fun getForChat(chatId: Long): List<LlamaMessageAttachmentEntity>

    @Query("SELECT * FROM llama_message_attachments WHERE messageId = :messageId ORDER BY id ASC")
    suspend fun getForMessage(messageId: Long): List<LlamaMessageAttachmentEntity>

    @Query("SELECT * FROM llama_message_attachments WHERE chatId = :chatId AND messageId IS NULL AND source = :source ORDER BY id ASC")
    suspend fun getPending(chatId: Long, source: String): List<LlamaMessageAttachmentEntity>

    @Query("SELECT * FROM llama_message_attachments WHERE chatId = :chatId AND messageId IS NULL AND source = :source ORDER BY id ASC")
    fun observePending(chatId: Long, source: String): Flow<List<LlamaMessageAttachmentEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(attachment: LlamaMessageAttachmentEntity): Long

    @Query("UPDATE llama_message_attachments SET messageId = :messageId WHERE id IN (:ids) AND messageId IS NULL")
    suspend fun bind(ids: List<Long>, messageId: Long): Int

    @Query("DELETE FROM llama_message_attachments WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM llama_message_attachments WHERE chatId = :chatId")
    suspend fun deleteForChat(chatId: Long)
}

@Dao
interface NativeChatCompactionDao {
    @Query("SELECT * FROM native_chat_compactions WHERE chatId = :chatId ORDER BY boundaryMessageId DESC, id DESC LIMIT 1")
    suspend fun getLatest(chatId: Long): NativeChatCompactionEntity?

    @Query("SELECT * FROM native_chat_compactions WHERE chatId = :chatId ORDER BY boundaryMessageId ASC, id ASC")
    fun observeForChat(chatId: Long): Flow<List<NativeChatCompactionEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(compaction: NativeChatCompactionEntity): Long

    @Query("UPDATE native_chat_compactions SET tokensAfter = :tokensAfter, measured = 1 WHERE id = :id")
    suspend fun setMeasured(id: Long, tokensAfter: Int)

    /** Drops compactions whose summarized range includes [messageId]; call before editing or deleting it. */
    @Query("DELETE FROM native_chat_compactions WHERE chatId = :chatId AND boundaryMessageId >= :messageId")
    suspend fun deleteCoveringOrAfter(chatId: Long, messageId: Long)

    /** Same as [deleteCoveringOrAfter] for callers that only know the message id. */
    @Query(
        "DELETE FROM native_chat_compactions WHERE boundaryMessageId >= :messageId AND " +
            "chatId = (SELECT chatId FROM llama_messages WHERE id = :messageId)"
    )
    suspend fun deleteCoveringOrAfterMessage(messageId: Long)

    @Query("DELETE FROM native_chat_compactions WHERE chatId = :chatId")
    suspend fun deleteForChat(chatId: Long)
}
