package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "ai_server_configs")
data class AiServerConfigEntity(
    @PrimaryKey val serverType: String,
    val displayName: String,
    val port: Int,
    val lanVisible: Boolean = false,
    val accessMode: String = "PUBLIC",
    val enabled: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ai_server_users",
    indices = [Index(value = ["username"], unique = true)]
)
data class AiServerUserEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val username: String,
    val displayName: String = username,
    val passwordHash: String,
    val passwordSalt: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ai_server_permissions",
    primaryKeys = ["userId", "serverType"],
    foreignKeys = [
        ForeignKey(
            entity = AiServerUserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("userId"),
        Index("serverType")
    ]
)
data class AiServerPermissionEntity(
    val userId: Long,
    val serverType: String,
    val canAccess: Boolean = true,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ai_server_sessions",
    foreignKeys = [
        ForeignKey(
            entity = AiServerUserEntity::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("userId"),
        Index("expiresAt")
    ]
)
data class AiServerSessionEntity(
    @PrimaryKey val tokenHash: String,
    val userId: Long,
    val createdAt: Long,
    val expiresAt: Long,
    val lastSeenAt: Long
)

@Entity(
    tableName = "ai_server_artifacts",
    foreignKeys = [
        ForeignKey(
            entity = AiServerUserEntity::class,
            parentColumns = ["id"],
            childColumns = ["ownerUserId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("serverType"),
        Index("ownerUserId"),
        Index("origin"),
        Index("path", unique = true),
        Index("jobId")
    ]
)
data class AiServerArtifactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverType: String,
    val ownerUserId: Long? = null,
    val origin: String = "SERVER",
    val jobId: String,
    val artifactType: String,
    val path: String,
    val mimeType: String,
    val title: String,
    val metadataJson: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ai_server_web_providers",
    indices = [Index("engine"), Index("ownerUserId"), Index("updatedAt")]
)
data class AiServerWebProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerUserId: Long? = null,
    val name: String,
    val engine: String,
    val baseUrl: String = "",
    val modelName: String? = null,
    val liteRtModelId: Long? = null,
    val liteRtBackend: String = "auto",
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val defaultParamsJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ai_server_web_chats",
    foreignKeys = [
        ForeignKey(
            entity = AiServerWebProviderEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("providerId"), Index("ownerUserId"), Index("updatedAt")]
)
data class AiServerWebChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ownerUserId: Long? = null,
    val title: String,
    val providerId: Long? = null,
    val systemPrompt: String? = null,
    val apiParamsJson: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ai_server_web_messages",
    foreignKeys = [
        ForeignKey(
            entity = AiServerWebChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chatId"), Index("createdAt")]
)
data class AiServerWebMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: String,
    val content: String,
    val imagePath: String? = null,
    val audioPath: String? = null,
    val documentPath: String? = null,
    val thinking: String? = null,
    val toolActivity: String? = null,
    val isError: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ai_server_web_message_attachments",
    foreignKeys = [
        ForeignKey(
            entity = AiServerWebMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("messageId"),
        Index("attachmentType"),
        Index("path")
    ]
)
data class AiServerWebMessageAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val attachmentType: String,
    val path: String,
    val mimeType: String? = null,
    val name: String? = null,
    val sizeBytes: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "ai_server_web_tool_events",
    foreignKeys = [
        ForeignKey(
            entity = AiServerWebMessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("messageId"),
        Index("toolName"),
        Index("status"),
        Index("createdAt")
    ]
)
data class AiServerWebToolEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val toolName: String,
    val phase: String = "",
    val status: String = "RUNNING",
    val argumentsJson: String? = null,
    val resultText: String? = null,
    val errorText: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface AiServerDao {
    @Query("SELECT * FROM ai_server_configs ORDER BY port ASC")
    fun observeConfigs(): Flow<List<AiServerConfigEntity>>

    @Query("SELECT * FROM ai_server_configs ORDER BY port ASC")
    suspend fun getConfigs(): List<AiServerConfigEntity>

    @Query("SELECT * FROM ai_server_configs WHERE serverType = :serverType LIMIT 1")
    suspend fun getConfig(serverType: String): AiServerConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(config: AiServerConfigEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfigs(configs: List<AiServerConfigEntity>)

    @Query(
        """
        UPDATE ai_server_configs
        SET enabled = :enabled,
            updatedAt = :updatedAt
        WHERE serverType = :serverType
        """
    )
    suspend fun setServerEnabled(serverType: String, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM ai_server_users ORDER BY username COLLATE NOCASE ASC")
    fun observeUsers(): Flow<List<AiServerUserEntity>>

    @Query("SELECT * FROM ai_server_users ORDER BY username COLLATE NOCASE ASC")
    suspend fun getUsers(): List<AiServerUserEntity>

    @Query("SELECT * FROM ai_server_users WHERE username = :username COLLATE NOCASE LIMIT 1")
    suspend fun getUserByUsername(username: String): AiServerUserEntity?

    @Query("SELECT * FROM ai_server_users WHERE id = :id LIMIT 1")
    suspend fun getUserById(id: Long): AiServerUserEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUser(user: AiServerUserEntity): Long

    @Query("DELETE FROM ai_server_users WHERE id = :id")
    suspend fun deleteUser(id: Long)

    @Query("SELECT * FROM ai_server_permissions ORDER BY userId ASC, serverType ASC")
    fun observePermissions(): Flow<List<AiServerPermissionEntity>>

    @Query("SELECT * FROM ai_server_permissions WHERE userId = :userId")
    suspend fun getPermissionsForUser(userId: Long): List<AiServerPermissionEntity>

    @Query("SELECT * FROM ai_server_permissions WHERE userId = :userId AND serverType = :serverType LIMIT 1")
    suspend fun getPermission(userId: Long, serverType: String): AiServerPermissionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPermission(permission: AiServerPermissionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPermissions(permissions: List<AiServerPermissionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: AiServerSessionEntity)

    @Query("SELECT * FROM ai_server_sessions WHERE tokenHash = :tokenHash LIMIT 1")
    suspend fun getSession(tokenHash: String): AiServerSessionEntity?

    @Query("DELETE FROM ai_server_sessions WHERE tokenHash = :tokenHash")
    suspend fun deleteSession(tokenHash: String)

    @Query("DELETE FROM ai_server_sessions WHERE expiresAt < :now")
    suspend fun deleteExpiredSessions(now: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtifact(artifact: AiServerArtifactEntity): Long

    @Query("SELECT * FROM ai_server_artifacts WHERE serverType = :serverType ORDER BY createdAt DESC")
    fun observeArtifactsForServer(serverType: String): Flow<List<AiServerArtifactEntity>>

    @Query("SELECT * FROM ai_server_artifacts WHERE artifactType = :artifactType ORDER BY createdAt DESC")
    fun observeArtifactsByType(artifactType: String): Flow<List<AiServerArtifactEntity>>

    @Query("SELECT path FROM ai_server_artifacts WHERE artifactType = :artifactType AND origin = 'SERVER'")
    fun observeServerArtifactPathsByType(artifactType: String): Flow<List<String>>

    @Query(
        """
        SELECT * FROM ai_server_artifacts
        WHERE serverType = :serverType
          AND (:ownerUserId IS NULL OR ownerUserId = :ownerUserId)
        ORDER BY createdAt DESC
        """
    )
    suspend fun getArtifactsForServer(serverType: String, ownerUserId: Long?): List<AiServerArtifactEntity>

    @Query("SELECT * FROM ai_server_artifacts WHERE id = :id LIMIT 1")
    suspend fun getArtifactById(id: Long): AiServerArtifactEntity?

    @Query("DELETE FROM ai_server_artifacts WHERE path = :path")
    suspend fun deleteArtifactByPath(path: String)

    @Query("DELETE FROM ai_server_artifacts WHERE id = :id")
    suspend fun deleteArtifactById(id: Long)

    @Query("SELECT * FROM ai_server_web_providers ORDER BY updatedAt DESC, name COLLATE NOCASE ASC")
    suspend fun getWebProviders(): List<AiServerWebProviderEntity>

    @Query(
        """
        SELECT * FROM ai_server_web_providers
        WHERE (:ownerUserId IS NULL AND ownerUserId IS NULL)
           OR ownerUserId = :ownerUserId
        ORDER BY updatedAt DESC, name COLLATE NOCASE ASC
        """
    )
    suspend fun getWebProvidersForOwner(ownerUserId: Long?): List<AiServerWebProviderEntity>

    @Query("SELECT * FROM ai_server_web_providers WHERE id = :id LIMIT 1")
    suspend fun getWebProvider(id: Long): AiServerWebProviderEntity?

    @Query(
        """
        SELECT * FROM ai_server_web_providers
        WHERE id = :id
          AND ((:ownerUserId IS NULL AND ownerUserId IS NULL) OR ownerUserId = :ownerUserId)
        LIMIT 1
        """
    )
    suspend fun getWebProviderForOwner(id: Long, ownerUserId: Long?): AiServerWebProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWebProvider(provider: AiServerWebProviderEntity): Long

    @Query("DELETE FROM ai_server_web_providers WHERE id = :id")
    suspend fun deleteWebProvider(id: Long)

    @Query("SELECT * FROM ai_server_web_chats ORDER BY updatedAt DESC")
    suspend fun getWebChats(): List<AiServerWebChatEntity>

    @Query(
        """
        SELECT * FROM ai_server_web_chats
        WHERE (:ownerUserId IS NULL AND ownerUserId IS NULL)
           OR ownerUserId = :ownerUserId
        ORDER BY updatedAt DESC
        """
    )
    suspend fun getWebChatsForOwner(ownerUserId: Long?): List<AiServerWebChatEntity>

    @Query("SELECT * FROM ai_server_web_chats WHERE id = :id LIMIT 1")
    suspend fun getWebChat(id: Long): AiServerWebChatEntity?

    @Query(
        """
        SELECT * FROM ai_server_web_chats
        WHERE id = :id
          AND ((:ownerUserId IS NULL AND ownerUserId IS NULL) OR ownerUserId = :ownerUserId)
        LIMIT 1
        """
    )
    suspend fun getWebChatForOwner(id: Long, ownerUserId: Long?): AiServerWebChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWebChat(chat: AiServerWebChatEntity): Long

    @Update
    suspend fun updateWebChat(chat: AiServerWebChatEntity)

    @Query("DELETE FROM ai_server_web_chats WHERE id = :id")
    suspend fun deleteWebChat(id: Long)

    @Query("SELECT * FROM ai_server_web_messages WHERE chatId = :chatId ORDER BY createdAt ASC, id ASC")
    suspend fun getWebMessages(chatId: Long): List<AiServerWebMessageEntity>

    @Query("SELECT * FROM ai_server_web_messages WHERE id = :id LIMIT 1")
    suspend fun getWebMessage(id: Long): AiServerWebMessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWebMessage(message: AiServerWebMessageEntity): Long

    @Query(
        """
        UPDATE ai_server_web_messages
        SET content = :content,
            thinking = :thinking,
            toolActivity = :toolActivity,
            isError = :isError
        WHERE id = :id
        """
    )
    suspend fun updateWebMessageContent(
        id: Long,
        content: String,
        thinking: String? = null,
        toolActivity: String? = null,
        isError: Boolean = false
    )

    @Query("DELETE FROM ai_server_web_messages WHERE id = :id")
    suspend fun deleteWebMessage(id: Long)

    @Query(
        """
        DELETE FROM ai_server_web_messages
        WHERE chatId = :chatId
          AND (createdAt > :createdAt OR (createdAt = :createdAt AND id >= :messageId))
        """
    )
    suspend fun deleteWebMessagesFrom(chatId: Long, createdAt: Long, messageId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWebMessageAttachment(attachment: AiServerWebMessageAttachmentEntity): Long

    @Query("SELECT * FROM ai_server_web_message_attachments WHERE messageId IN (:messageIds) ORDER BY createdAt ASC, id ASC")
    suspend fun getWebMessageAttachments(messageIds: List<Long>): List<AiServerWebMessageAttachmentEntity>

    @Query("SELECT COUNT(*) FROM ai_server_web_message_attachments WHERE path = :path")
    suspend fun countWebMessageAttachmentsByPath(path: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWebToolEvent(event: AiServerWebToolEventEntity): Long

    @Query("SELECT * FROM ai_server_web_tool_events WHERE messageId IN (:messageIds) ORDER BY createdAt ASC, id ASC")
    suspend fun getWebToolEvents(messageIds: List<Long>): List<AiServerWebToolEventEntity>

    @Query("DELETE FROM ai_server_web_tool_events WHERE messageId = :messageId")
    suspend fun deleteWebToolEventsForMessage(messageId: Long)

    @Query(
        """
        DELETE FROM ai_server_web_tool_events
        WHERE messageId IN (
            SELECT id FROM ai_server_web_messages WHERE chatId = :chatId
        )
        """
    )
    suspend fun clearWebToolEventsForChat(chatId: Long): Int
}
