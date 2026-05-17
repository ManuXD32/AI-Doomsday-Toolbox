package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentChatDao {
    
    // ========== Conversations ==========
    
    @Query("SELECT * FROM agent_conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<AgentConversationEntity>>
    
    @Query("SELECT * FROM agent_conversations WHERE id = :id")
    suspend fun getConversation(id: Long): AgentConversationEntity?
    
    @Insert
    suspend fun insertConversation(conversation: AgentConversationEntity): Long
    
    @Update
    suspend fun updateConversation(conversation: AgentConversationEntity)
    
    @Delete
    suspend fun deleteConversation(conversation: AgentConversationEntity)
    
    @Query("DELETE FROM agent_conversations WHERE id = :id")
    suspend fun deleteConversationById(id: Long)
    
    @Query("UPDATE agent_conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConversationTitle(id: Long, title: String, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE agent_conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touchConversation(id: Long, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE agent_conversations SET lastAgentRole = :role, lastTask = :task, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateConversationState(id: Long, role: String, task: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE agent_conversations SET knowledgeBaseIds = :knowledgeBaseIds, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateKnowledgeBaseIds(id: Long, knowledgeBaseIds: String, updatedAt: Long = System.currentTimeMillis())
    
    // ========== Messages ==========
    
    @Query("SELECT * FROM agent_messages WHERE conversationId = :conversationId ORDER BY sequenceNumber ASC")
    fun getMessagesForConversation(conversationId: Long): Flow<List<AgentMessageEntity>>
    
    @Query("SELECT * FROM agent_messages WHERE conversationId = :conversationId ORDER BY sequenceNumber ASC")
    suspend fun getMessagesForConversationSync(conversationId: Long): List<AgentMessageEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: AgentMessageEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<AgentMessageEntity>)
    
    @Update
    suspend fun updateMessage(message: AgentMessageEntity)
    
    @Delete
    suspend fun deleteMessage(message: AgentMessageEntity)
    
    @Query("DELETE FROM agent_messages WHERE id = :id")
    suspend fun deleteMessageById(id: Long)
    
    @Query("DELETE FROM agent_messages WHERE conversationId = :conversationId")
    suspend fun deleteAllMessagesInConversation(conversationId: Long)
    
    @Query("DELETE FROM agent_messages WHERE conversationId = :conversationId AND timestamp >= :afterTimestamp")
    suspend fun deleteMessagesAfter(conversationId: Long, afterTimestamp: Long)
    
    // ========== Utilities ==========
    
    @Query("SELECT COUNT(*) FROM agent_messages WHERE conversationId = :conversationId")
    suspend fun getMessageCount(conversationId: Long): Int
    
    @Query("SELECT * FROM agent_messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessage(conversationId: Long): AgentMessageEntity?
}
