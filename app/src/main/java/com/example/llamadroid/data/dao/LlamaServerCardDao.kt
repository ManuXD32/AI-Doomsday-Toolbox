package com.example.llamadroid.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.llamadroid.data.model.LlamaServerCardEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO kept separate from [AppDatabase] so database wiring/migrations can be integrated in one
 * coordinated change.  The entity is intentionally usable by Room without a foreign key to
 * saved_commands: old backups may contain a missing preset and the UI must surface that state.
 */
@Dao
interface LlamaServerCardDao {
    @Query("SELECT * FROM llama_server_cards ORDER BY updatedAt DESC, id ASC")
    fun observeCards(): Flow<List<LlamaServerCardEntity>>

    @Query("SELECT * FROM llama_server_cards WHERE id = :id")
    suspend fun getCard(id: Long): LlamaServerCardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCard(card: LlamaServerCardEntity): Long

    @Update
    suspend fun updateCard(card: LlamaServerCardEntity)

    @Delete
    suspend fun deleteCard(card: LlamaServerCardEntity)

    @Query("DELETE FROM llama_server_cards WHERE id = :id")
    suspend fun deleteCardById(id: Long)

    @Query("UPDATE llama_server_cards SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateName(id: Long, name: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE llama_server_cards SET savedCommandId = :savedCommandId, presetNameSnapshot = :presetNameSnapshot, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateSavedCommand(
        id: Long,
        savedCommandId: Long,
        presetNameSnapshot: String = "",
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE llama_server_cards SET port = :port, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePort(id: Long, port: Int, updatedAt: Long = System.currentTimeMillis())
}
