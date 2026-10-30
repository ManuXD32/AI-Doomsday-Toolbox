package com.example.llamadroid.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.llamadroid.data.model.LlamaMessageImageDescriptionEntity

@Dao
interface LlamaMessageImageDescriptionDao {
    @Query("SELECT * FROM llama_message_image_descriptions WHERE messageId = :messageId")
    suspend fun get(messageId: Long): LlamaMessageImageDescriptionEntity?

    @Query("SELECT * FROM llama_message_image_descriptions WHERE messageId IN (:messageIds)")
    suspend fun getMany(messageIds: List<Long>): List<LlamaMessageImageDescriptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LlamaMessageImageDescriptionEntity)
}
