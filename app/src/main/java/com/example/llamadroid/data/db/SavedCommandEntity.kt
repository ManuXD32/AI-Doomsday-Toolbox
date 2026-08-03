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
import com.example.llamadroid.service.LlamaServerLaunchProfile

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
    /** Canonical, versioned general llama-server snapshot. Null rows predate v2 snapshots. */
    @ColumnInfo(name = "launchProfileJson")
    val launchProfileJson: String? = null,
    @ColumnInfo(name = "launchProfileSchemaVersion", defaultValue = "1")
    val launchProfileSchemaVersion: Int = 1,
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
    val draftThreads: Int = 4,
    val draftThreadsBatch: Int = 4,
    val ngramModNMatch: Int = 24,
    val ngramModNMin: Int = 48,
    val ngramModNMax: Int = 64,
    val ngramSimpleSizeN: Int = 12,
    val ngramSimpleSizeM: Int = 48,
    val ngramSimpleMinHits: Int = 1,
    val ngramMapKSizeN: Int = 12,
    val ngramMapKSizeM: Int = 48,
    val ngramMapKMinHits: Int = 1,
    val ngramMapK4VSizeN: Int = 12,
    val ngramMapK4VSizeM: Int = 48,
    val ngramMapK4VMinHits: Int = 1,
    val nativeToolsEnabled: Boolean = false,
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

/**
 * General saved commands use [LlamaServerLaunchProfile] as their one source of truth.
 * Old rows keep working by translating their historical columns exactly once at read time.
 */
fun SavedCommand.launchProfile(): LlamaServerLaunchProfile =
    LlamaServerLaunchProfile.decode(launchProfileJson) ?: LlamaServerLaunchProfile(
        modelPath = modelPath,
        mmprojPath = mmprojPath,
        visionEnabled = enableVision,
        host = host,
        threads = threads,
        batchSize = batchSize,
        contextSize = contextSize,
        temperature = temperature,
        kvCacheEnabled = kvCacheEnabled,
        kvCacheTypeK = kvCacheTypeK,
        kvCacheTypeV = kvCacheTypeV,
        kvCacheReuse = kvCacheReuse,
        noMmap = lowMemoryMode,
        parallel = parallel,
        cacheRam = cacheRam,
        customFlags = customFlags.takeIf { it.isNotBlank() },
        flashAttention = flashAttention,
        nativeToolsEnabled = nativeToolsEnabled,
        commandTemplate = commandTemplate.takeIf { it.isNotBlank() },
        speculativeEnabled = speculativeEnabled,
        speculativeMode = speculativeMode,
        draftModelPath = draftModelPath,
        draftMax = draftMax,
        draftMin = draftMin,
        draftPMin = draftPMin,
        draftThreads = draftThreads,
        draftThreadsBatch = draftThreadsBatch,
        ngramModNMatch = ngramModNMatch,
        ngramModNMin = ngramModNMin,
        ngramModNMax = ngramModNMax,
        ngramSimpleSizeN = ngramSimpleSizeN,
        ngramSimpleSizeM = ngramSimpleSizeM,
        ngramSimpleMinHits = ngramSimpleMinHits,
        ngramMapKSizeN = ngramMapKSizeN,
        ngramMapKSizeM = ngramMapKSizeM,
        ngramMapKMinHits = ngramMapKMinHits,
        ngramMapK4VSizeN = ngramMapK4VSizeN,
        ngramMapK4VSizeM = ngramMapK4VSizeM,
        ngramMapK4VMinHits = ngramMapK4VMinHits
    )

fun savedCommandFromLaunchProfile(
    name: String,
    profile: LlamaServerLaunchProfile,
    id: Long = 0L
): SavedCommand = SavedCommand(
    id = id,
    name = name,
    scope = SavedCommandScopes.GENERAL,
    launchProfileJson = LlamaServerLaunchProfile.encode(profile),
    launchProfileSchemaVersion = LlamaServerLaunchProfile.SCHEMA_VERSION,
    // Keep a concise legacy summary for older readers and list rendering.
    modelPath = profile.modelPath,
    commandTemplate = profile.commandTemplate.orEmpty(),
    contextSize = profile.contextSize,
    batchSize = profile.batchSize,
    temperature = profile.temperature,
    threads = profile.threads,
    host = profile.host
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
