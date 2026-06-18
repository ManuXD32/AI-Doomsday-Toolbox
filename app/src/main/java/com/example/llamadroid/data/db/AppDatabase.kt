package com.example.llamadroid.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.llamadroid.util.DebugLog

/**
 * Type converters for Room database.
 * Uses safe enum conversion to prevent crashes if enum values are removed/renamed.
 */
class Converters {
    
    /**
     * Safely convert string to enum, returning default if conversion fails.
     * This prevents app crashes when database contains old enum values.
     */
    private inline fun <reified T : Enum<T>> safeEnumValueOf(value: String?, default: T): T {
        if (value == null) return default
        return try {
            enumValueOf<T>(value)
        } catch (e: IllegalArgumentException) {
            // Log for debugging, but don't crash
            com.example.llamadroid.util.DebugLog.log(
                "[DB] Unknown ${T::class.simpleName} value: '$value', using default: $default"
            )
            default
        }
    }
    
    // NoteType converters
    @TypeConverter
    fun fromNoteType(value: NoteType): String = value.name
    
    @TypeConverter
    fun toNoteType(value: String?): NoteType = safeEnumValueOf(value, NoteType.MANUAL)
    
    // WorkflowType converters
    @TypeConverter
    fun fromWorkflowType(value: WorkflowType): String = value.name
    
    @TypeConverter
    fun toWorkflowType(value: String?): WorkflowType = safeEnumValueOf(value, WorkflowType.TRANSCRIBE_SUMMARY)
    
    // Dataset converters
    @TypeConverter
    fun fromSourceType(value: SourceType): String = value.name
    
    @TypeConverter
    fun toSourceType(value: String?): SourceType = safeEnumValueOf(value, SourceType.TXT)
    
    @TypeConverter
    fun fromChunkStatus(value: ChunkStatus): String = value.name
    
    @TypeConverter
    fun toChunkStatus(value: String?): ChunkStatus = safeEnumValueOf(value, ChunkStatus.PENDING)
    
    @TypeConverter
    fun fromQAStatus(value: QAStatus): String = value.name
    
    @TypeConverter
    fun toQAStatus(value: String?): QAStatus = safeEnumValueOf(value, QAStatus.QUESTIONED)
    
    @TypeConverter
    fun fromPromptType(value: PromptType): String = value.name
    
    @TypeConverter
    fun toPromptType(value: String?): PromptType = safeEnumValueOf(value, PromptType.CLEAN)

}

@Database(
    entities = [
        ModelEntity::class, 
        ChatMessageEntity::class, 
        SystemPromptEntity::class, 
        NoteEntity::class, 
        ZimEntity::class, 
        com.example.llamadroid.data.model.SavedWorker::class, 
        WorkflowTemplateEntity::class, 
        BenchmarkResult::class,
        // Dataset entities
        DatasetProjectEntity::class,
        DatasetSourceEntity::class,
        DatasetChunkEntity::class,
        DatasetQAEntity::class,
        DatasetPromptEntity::class,
        // Agent entities
        AgentConversationEntity::class,
        AgentMessageEntity::class,
        // Custom tools/agents
        CustomToolEntity::class,
        CustomAgentEntity::class,
        OllamaServerEntity::class,
        AiRuntimeJobEntity::class,
        // Llama Native Client entities
        com.example.llamadroid.data.model.LlamaServerEntity::class,
        com.example.llamadroid.data.model.LlamaChatFolderEntity::class,
        com.example.llamadroid.data.model.LlamaChatPromptProfileEntity::class,
        com.example.llamadroid.data.model.LlamaChatEntity::class,
        com.example.llamadroid.data.model.LlamaMessageEntity::class,
        com.example.llamadroid.data.model.LlamaScheduledTaskEntity::class,
        com.example.llamadroid.data.model.LlamaScheduledTaskLogEntity::class,
        OrganizerEventEntity::class,
        OrganizerAlarmEntity::class,
        OrganizerLlmSettingsEntity::class,
        SavedCommand::class,
        QuadtrixProfileEntity::class,
        QuadtrixRunEntity::class,
        QuadtrixMetricEntity::class,
        KnowledgeBaseEntity::class,
        KnowledgeSourceEntity::class,
        KnowledgeChunkEntity::class,
        com.example.llamadroid.data.model.LiteRtModelEntity::class,
        LiveTranslatorTemplateEntity::class,
        LiveTranslatorSessionEntity::class,
        LiveTranslatorTurnEntity::class,
        AiServerConfigEntity::class,
        AiServerUserEntity::class,
        AiServerPermissionEntity::class,
        AiServerSessionEntity::class,
        AiServerArtifactEntity::class,
        AiServerWebProviderEntity::class,
        AiServerWebChatEntity::class,
        AiServerWebMessageEntity::class,
        AiServerWebMessageAttachmentEntity::class,
        AiServerWebToolEventEntity::class
    ], 
    version = 81,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun chatDao(): ChatDao
    abstract fun systemPromptDao(): SystemPromptDao
    abstract fun noteDao(): NoteDao
    abstract fun zimDao(): ZimDao
    abstract fun savedWorkerDao(): SavedWorkerDao
    abstract fun savedCommandDao(): SavedCommandDao
    abstract fun workflowTemplateDao(): WorkflowTemplateDao
    abstract fun benchmarkDao(): BenchmarkDao
    abstract fun datasetDao(): DatasetDao
    abstract fun agentChatDao(): AgentChatDao
    abstract fun customToolDao(): CustomToolDao
    abstract fun customAgentDao(): CustomAgentDao
    abstract fun ollamaServerDao(): OllamaServerDao
    abstract fun aiRuntimeJobDao(): AiRuntimeJobDao
    
    // Llama Native Client DAOs
    abstract fun llamaServerDao(): com.example.llamadroid.data.dao.LlamaServerDao
    abstract fun llamaChatFolderDao(): com.example.llamadroid.data.dao.LlamaChatFolderDao
    abstract fun llamaChatPromptProfileDao(): com.example.llamadroid.data.dao.LlamaChatPromptProfileDao
    abstract fun llamaChatDao(): com.example.llamadroid.data.dao.LlamaChatDao
    abstract fun llamaMessageDao(): com.example.llamadroid.data.dao.LlamaMessageDao
    abstract fun llamaScheduledTaskDao(): com.example.llamadroid.data.dao.LlamaScheduledTaskDao
    abstract fun organizerDao(): OrganizerDao
    abstract fun quadtrixProfileDao(): QuadtrixProfileDao
    abstract fun quadtrixRunDao(): QuadtrixRunDao
    abstract fun quadtrixMetricDao(): QuadtrixMetricDao
    abstract fun knowledgeBaseDao(): KnowledgeBaseDao
    abstract fun liteRtModelDao(): com.example.llamadroid.data.dao.LiteRtModelDao
    abstract fun liveTranslatorTemplateDao(): LiveTranslatorTemplateDao
    abstract fun liveTranslatorSessionDao(): LiveTranslatorSessionDao
    abstract fun liveTranslatorTurnDao(): LiveTranslatorTurnDao
    abstract fun aiServerDao(): AiServerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "llama_droid_db"
                )
                    // Apply any defined migrations
                    .addMigrations(*Migrations.ALL_MIGRATIONS)
                    // Only allow destructive migration from pre-release versions (1-26)
                    // From v27 onwards, proper migrations are required
                    .fallbackToDestructiveMigrationFrom(*Migrations.DESTRUCTIVE_FALLBACK_VERSIONS)
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            DebugLog.log("[DB] AppDatabase opened, version: ${db.version}")
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
        
        /**
         * Close the database instance. Used before restore to release file locks.
         */
        fun closeInstance() {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
            }
        }
    }
}
