package com.example.llamadroid.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow
import com.example.llamadroid.service.LlamaSpeculativeMode

object SavedCommandScopes {
    const val GENERAL = "GENERAL"
    const val MASTER = "MASTER"
}

@Entity(tableName = "saved_commands")
data class SavedCommand(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "command")
    val commandTemplate: String = "",
    val scope: String = SavedCommandScopes.GENERAL,
    // Model
    val modelPath: String = "",
    val contextSize: Int = 4096,
    val batchSize: Int = 512,
    val temperature: Float = 0.7f,
    val threads: Int = 4,
    val host: String = "127.0.0.1",
    // Speculative decoding
    val speculativeEnabled: Boolean = false,
    val speculativeMode: String = LlamaSpeculativeMode.DRAFT_SIMPLE.flagValue,
    val draftModelPath: String? = null,
    val draftMax: Int = 3,
    val draftMin: Int = 0,
    val draftPMin: Float = 0.0f,
    // Advanced
    val parallel: Int? = null,
    val cacheRam: Int? = null,
    val customFlags: String = "",
    val flashAttention: Boolean = false,
    // KV Cache
    val kvCacheEnabled: Boolean = true,
    val kvCacheTypeK: String = "f16",
    val kvCacheTypeV: String = "f16",
    val kvCacheReuse: Int = 0,
    // Master RAM & Workers
    val masterRamMB: Int = 4096,
    val workersListStr: String = "",
    // Legacy settings (kept for compatibility but unused in master)
    val lowMemoryMode: Boolean = false,
    val enableVision: Boolean = false,
    val mmprojPath: String? = null
)

// Type alias for backward compatibility with MasterModeScreen imports
typealias SavedCommandEntity = SavedCommand

@Dao
interface SavedCommandDao {
    @Query("SELECT * FROM saved_commands ORDER BY name ASC")
    fun getAllCommands(): Flow<List<SavedCommand>>

    @Query("SELECT * FROM saved_commands WHERE scope = :scope ORDER BY name ASC")
    fun getCommandsByScope(scope: String): Flow<List<SavedCommand>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommand(command: SavedCommand): Long

    @Delete
    suspend fun deleteCommand(command: SavedCommand)
}
