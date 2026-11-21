package com.example.llamadroid.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.llamadroid.data.model.LlamaMessageCardStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LlamaMessageCardStateDao {
    @Query(
        "SELECT s.* FROM llama_message_card_state s " +
            "INNER JOIN llama_messages m ON m.id = s.messageId WHERE m.chatId = :chatId"
    )
    fun observeForChat(chatId: Long): Flow<List<LlamaMessageCardStateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LlamaMessageCardStateEntity)
}
