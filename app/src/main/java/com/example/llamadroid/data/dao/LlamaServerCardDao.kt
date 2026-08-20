package com.example.llamadroid.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    /** The single card the watch is allowed to start, or null if the user enabled none. */
    @Query("SELECT * FROM llama_server_cards WHERE allowWearStart = 1 ORDER BY id ASC LIMIT 1")
    suspend fun getWearStartCard(): LlamaServerCardEntity?

    @Query("SELECT * FROM llama_server_cards WHERE allowWearStart = 1 ORDER BY id ASC LIMIT 1")
    fun observeWearStartCard(): Flow<LlamaServerCardEntity?>

    @Query("UPDATE llama_server_cards SET allowWearStart = 0, updatedAt = :updatedAt WHERE allowWearStart = 1")
    suspend fun clearWearStartFlags(updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE llama_server_cards SET allowWearStart = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markWearStartCard(id: Long, updatedAt: Long = System.currentTimeMillis())

    /**
     * Make [id] the only wear-startable card, or clear the selection entirely when
     * [id] is null.
     *
     * Exclusivity is enforced here rather than in the UI so it holds for every
     * caller, and the clear+set runs in one transaction so a failure cannot leave
     * two cards enabled.
     */
    @Transaction
    suspend fun setWearStartCard(id: Long?, updatedAt: Long = System.currentTimeMillis()) {
        clearWearStartFlags(updatedAt)
        if (id != null) markWearStartCard(id, updatedAt)
    }

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
