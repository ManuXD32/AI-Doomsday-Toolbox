package com.example.llamadroid.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object KnowledgeBaseSourceType {
    const val FILE = "file"
    const val NOTE = "note"
    const val WEB = "web"
}

object KnowledgeBaseSourceStatus {
    const val QUEUED = "queued"
    const val EXTRACTING = "extracting"
    const val CHUNKING = "chunking"
    const val EMBEDDING = "embedding"
    const val INDEXING = "indexing"
    const val INDEXED = "indexed"
    const val STALE = "stale"
    const val ERROR = "error"
}

@Entity(
    tableName = "knowledge_bases",
    indices = [Index(value = ["name"], unique = true)]
)
data class KnowledgeBaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val contentSummary: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "knowledge_sources",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeBaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledgeBaseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("knowledgeBaseId"),
        Index("status"),
        Index(value = ["knowledgeBaseId", "sourceRef"], unique = true)
    ]
)
data class KnowledgeSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val knowledgeBaseId: Long,
    val type: String,
    val sourceRef: String,
    val title: String,
    val contentHash: String = "",
    val enabled: Boolean = true,
    val status: String = KnowledgeBaseSourceStatus.QUEUED,
    val errorMessage: String? = null,
    val embeddingModelPath: String? = null,
    val embeddingBackend: String = "",
    val embeddingConfigHash: String = "",
    val embeddingDim: Int = 0,
    val chunkCount: Int = 0,
    val embeddedChunkCount: Int = 0,
    val processingStage: String = KnowledgeBaseSourceStatus.QUEUED,
    val progressTotal: Int = 0,
    val progressDone: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val indexedAt: Long? = null,
    val progressUpdatedAt: Long? = null
)

@Entity(
    tableName = "knowledge_chunks",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeBaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["knowledgeBaseId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = KnowledgeSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("knowledgeBaseId"),
        Index("sourceId"),
        Index(value = ["sourceId", "chunkIndex"], unique = true)
    ]
)
data class KnowledgeChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val knowledgeBaseId: Long,
    val sourceId: Long,
    val chunkIndex: Int,
    val text: String,
    val startOffset: Int = 0,
    val endOffset: Int = 0,
    val embedding: ByteArray? = null,
    val embeddingNorm: Float? = null,
    val createdAt: Long = System.currentTimeMillis()
)
