package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.ColumnInfo
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

const val LIVE_TRANSLATOR_ENGINE_LLAMA_SERVER = "llama-server"
const val LIVE_TRANSLATOR_ENGINE_LLAMA_SWAP = "llama-swap"
const val LIVE_TRANSLATOR_ENGINE_OLLAMA = "ollama"
const val LIVE_TRANSLATOR_ENGINE_LITERT = "litert-lm"

const val LIVE_TRANSLATOR_SPEAKER_ONE = 1
const val LIVE_TRANSLATOR_SPEAKER_TWO = 2

@Entity(
    tableName = "live_translator_templates",
    indices = [
        Index("updatedAt"),
        Index("backendEngine"),
        Index("liteRtModelId")
    ]
)
data class LiveTranslatorTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val speaker1Language: String = "English",
    val speaker2Language: String = "Spanish",
    val whisperModelPath: String? = null,
    val whisperThreads: Int = 4,
    val ttsModelPath: String? = null,
    val ttsModelName: String? = null,
    val ttsLanguage: String = "en",
    @ColumnInfo(defaultValue = "'en'")
    val speaker1TtsLanguage: String = "en",
    @ColumnInfo(defaultValue = "'es'")
    val speaker2TtsLanguage: String = "es",
    val ttsVoiceName: String? = null,
    val ttsSteps: Int = 8,
    val ttsSpeed: Float = 1.05f,
    val backendEngine: String = LIVE_TRANSLATOR_ENGINE_LLAMA_SERVER,
    @ColumnInfo(defaultValue = "'http://localhost:8080'")
    val llamaServerUrl: String = "http://localhost:8080",
    @ColumnInfo(defaultValue = "'http://localhost:9292'")
    val llamaSwapUrl: String = "http://localhost:9292",
    val llamaHost: String = "127.0.0.1",
    val llamaPort: Int = 8080,
    val llamaModelName: String? = null,
    @ColumnInfo(defaultValue = "'http://localhost:11434'")
    val ollamaUrl: String = "http://localhost:11434",
    val ollamaHost: String = "127.0.0.1",
    val ollamaPort: Int = 11434,
    val ollamaModelName: String? = null,
    val liteRtModelId: Long? = null,
    val liteRtBackend: String = "auto",
    @ColumnInfo(defaultValue = "0")
    val liteRtMtpEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val liteRtThinkingEnabled: Boolean = false,
    val contextSize: Int = 4096,
    val maxTokens: Int = 512,
    val temperature: Float = 0.2f,
    val timeoutSeconds: Int = 120,
    val startSpeakingTimeoutSeconds: Int = 10,
    val finishedTalkingTimeoutSeconds: Int = 5,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "live_translator_sessions",
    indices = [
        Index("updatedAt"),
        Index("templateId")
    ]
)
data class LiveTranslatorSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val templateId: Long? = null,
    val templateSnapshotJson: String,
    val speaker1Language: String,
    val speaker2Language: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "live_translator_turns",
    foreignKeys = [
        ForeignKey(
            entity = LiveTranslatorSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("sessionId"),
        Index("timestamp")
    ]
)
data class LiveTranslatorTurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val speaker: Int,
    val originalText: String,
    val translatedText: String? = null,
    val detectedLanguage: String? = null,
    val sourceLanguage: String,
    val targetLanguage: String,
    val audioPath: String? = null,
    val ttsAudioPath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    val errorMessage: String? = null
)

@Dao
interface LiveTranslatorTemplateDao {
    @Query("SELECT * FROM live_translator_templates ORDER BY updatedAt DESC, name COLLATE NOCASE ASC")
    fun observeTemplates(): Flow<List<LiveTranslatorTemplateEntity>>

    @Query("SELECT * FROM live_translator_templates ORDER BY updatedAt DESC, name COLLATE NOCASE ASC")
    suspend fun getTemplatesOnce(): List<LiveTranslatorTemplateEntity>

    @Query("SELECT * FROM live_translator_templates WHERE id = :id")
    suspend fun getTemplateById(id: Long): LiveTranslatorTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(template: LiveTranslatorTemplateEntity): Long

    @Update
    suspend fun update(template: LiveTranslatorTemplateEntity)

    @Delete
    suspend fun delete(template: LiveTranslatorTemplateEntity)
}

@Dao
interface LiveTranslatorSessionDao {
    @Query("SELECT * FROM live_translator_sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<LiveTranslatorSessionEntity>>

    @Query("SELECT * FROM live_translator_sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): LiveTranslatorSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: LiveTranslatorSessionEntity): Long

    @Update
    suspend fun update(session: LiveTranslatorSessionEntity)

    @Delete
    suspend fun delete(session: LiveTranslatorSessionEntity)

    @Query("UPDATE live_translator_sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE live_translator_sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long = System.currentTimeMillis())
}

@Dao
interface LiveTranslatorTurnDao {
    @Query("SELECT * FROM live_translator_turns WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    fun observeTurns(sessionId: Long): Flow<List<LiveTranslatorTurnEntity>>

    @Query("SELECT * FROM live_translator_turns WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    suspend fun getTurnsOnce(sessionId: Long): List<LiveTranslatorTurnEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(turn: LiveTranslatorTurnEntity): Long

    @Update
    suspend fun update(turn: LiveTranslatorTurnEntity)

    @Delete
    suspend fun delete(turn: LiveTranslatorTurnEntity)

    @Query("DELETE FROM live_translator_turns WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}
