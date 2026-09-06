package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.example.llamadroid.data.model.library.ModelFamily
import com.example.llamadroid.data.model.library.ModelSourceKind
import com.example.llamadroid.data.model.library.PendingArtifactStatus
import kotlinx.coroutines.flow.Flow
import java.util.UUID

private fun newModelLibraryId(): String = UUID.randomUUID().toString()

/**
 * A saved user source. This row intentionally has no foreign key to models:
 * deleting an installed model must not delete its reusable source link.
 *
 * Credentials are never represented here. A caller may supply a token for a
 * single authenticated request, but the source row only records that auth may
 * be required.
 */
@Entity(
    tableName = "model_sources",
    indices = [
        Index(value = ["normalizedKey"], unique = true),
        Index(value = ["family"]),
        Index(value = ["updatedAt"])
    ]
)
data class ModelSourceEntity(
    @PrimaryKey val id: String = newModelLibraryId(),
    val kind: String,
    val family: String,
    val label: String,
    val url: String,
    val normalizedKey: String,
    val repositoryId: String? = null,
    val revision: String = "main",
    val filePath: String? = null,
    val authRequired: Boolean = false,
    val verified: Boolean = false,
    val expectedSha256: String? = null,
    val expectedSizeBytes: Long? = null,
    val mediaType: String? = null,
    @ColumnInfo(defaultValue = "'needs_check'") val validationStatus: String = "needs_check",
    val checkedAt: Long? = null,
    val lastErrorCode: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** A provenance edge between an installed/runtime model key and a source. */
@Entity(
    tableName = "model_provenance",
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["modelKey"]),
        Index(value = ["family"]),
        Index(value = ["updatedAt"])
    ]
)
data class ModelProvenanceEntity(
    @PrimaryKey val id: String = newModelLibraryId(),
    val sourceId: String,
    val modelKey: String,
    val family: String,
    val role: String? = null,
    val localPath: String? = null,
    val artifactSha256: String? = null,
    val sizeBytes: Long? = null,
    val importedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "model_bundles",
    indices = [
        Index(value = ["family"]),
        Index(value = ["updatedAt"])
    ]
)
data class ModelBundleEntity(
    @PrimaryKey val id: String = newModelLibraryId(),
    val name: String,
    val family: String,
    val description: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * One logical component in a bundle. sourceId may be null while the item is a
 * durable "Needs source" draft. Multipart files use partGroup/partIndex/count
 * instead of flattening the relationship into a filename convention.
 */
@Entity(
    tableName = "model_bundle_items",
    indices = [
        Index(value = ["bundleId"]),
        Index(value = ["sourceId"]),
        Index(value = ["family"]),
        Index(value = ["bundleId", "itemKey"], unique = true),
        Index(value = ["partGroup"])
    ]
)
data class ModelBundleItemEntity(
    @PrimaryKey val id: String = newModelLibraryId(),
    val bundleId: String,
    val itemKey: String,
    val family: String,
    val role: String? = null,
    val sourceId: String? = null,
    val required: Boolean = true,
    val partGroup: String? = null,
    val partIndex: Int? = null,
    val partCount: Int? = null,
    val localFilename: String? = null,
    val relativePath: String? = null,
    val expectedSha256: String? = null,
    val expectedSizeBytes: Long? = null,
    @ColumnInfo(defaultValue = "'{}'")
    val modelMetadataJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "pending_model_artifacts",
    indices = [
        Index(value = ["downloadTaskId"]),
        Index(value = ["sourceId"]),
        Index(value = ["bundleId"]),
        Index(value = ["bundleItemId"]),
        Index(value = ["status"]),
        Index(value = ["updatedAt"])
    ]
)
data class PendingModelArtifactEntity(
    @PrimaryKey val id: String = newModelLibraryId(),
    val downloadTaskId: String? = null,
    val sourceId: String? = null,
    val bundleId: String? = null,
    val bundleItemId: String? = null,
    val filename: String,
    val stagingPath: String,
    val destinationPath: String? = null,
    val requestedFamily: String? = null,
    val requestedRole: String? = null,
    val detectedFamily: String? = null,
    val detectedRole: String? = null,
    val detectedType: String? = null,
    val status: String = PendingArtifactStatus.STAGED.storedValue,
    val validationJson: String? = null,
    val validationMessage: String? = null,
    val requiresManualPromotion: Boolean = true,
    val promotedModelKey: String? = null,
    val promotedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface ModelLibraryDao {
    @Query("SELECT * FROM model_sources ORDER BY updatedAt DESC")
    fun observeSources(): Flow<List<ModelSourceEntity>>

    @Query("SELECT * FROM model_sources WHERE family = :family ORDER BY updatedAt DESC")
    fun observeSourcesByFamily(family: String): Flow<List<ModelSourceEntity>>

    @Query("SELECT * FROM model_sources WHERE id = :id LIMIT 1")
    suspend fun getSourceById(id: String): ModelSourceEntity?

    @Query("SELECT * FROM model_sources WHERE normalizedKey = :normalizedKey LIMIT 1")
    suspend fun getByNormalizedKey(normalizedKey: String): ModelSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: ModelSourceEntity)

    @Query("UPDATE model_provenance SET localPath=NULL, artifactSha256=NULL, sizeBytes=NULL, updatedAt=:updatedAt WHERE sourceId=:sourceId")
    suspend fun invalidateSourceInstallationEvidence(sourceId: String, updatedAt: Long)

    @Query("UPDATE model_bundle_items SET expectedSha256=NULL, expectedSizeBytes=NULL WHERE sourceId=:sourceId")
    suspend fun invalidateSourceBundleEvidence(sourceId: String)

    @Query("UPDATE pending_model_artifacts SET validationJson=:marker, updatedAt=MAX(updatedAt + 1, :updatedAt) WHERE sourceId=:sourceId AND status IN ('CANCELLED', 'FAILED')")
    suspend fun invalidateSourcePendingPayloads(
        sourceId: String,
        updatedAt: Long,
        marker: String = "{\"sourceIdentityInvalidated\":true}"
    )

    /** A replacement URL keeps its associations, but is a different downloadable artifact. */
    @Transaction
    suspend fun replaceSourceIdentity(source: ModelSourceEntity) {
        invalidateSourceInstallationEvidence(source.id, source.updatedAt)
        invalidateSourceBundleEvidence(source.id)
        invalidateSourcePendingPayloads(source.id, source.updatedAt)
        upsert(source)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSources(sources: List<ModelSourceEntity>)

    @Query("DELETE FROM model_sources WHERE id = :id")
    suspend fun deleteSourceById(id: String)

    @Query("SELECT * FROM model_provenance ORDER BY updatedAt DESC")
    fun observeProvenance(): Flow<List<ModelProvenanceEntity>>

    @Query("SELECT * FROM model_provenance WHERE sourceId = :sourceId ORDER BY updatedAt DESC")
    fun observeProvenanceBySource(sourceId: String): Flow<List<ModelProvenanceEntity>>

    @Query("SELECT * FROM model_provenance WHERE sourceId = :sourceId ORDER BY updatedAt DESC")
    suspend fun getProvenanceBySource(sourceId: String): List<ModelProvenanceEntity>

    @Query(
        "UPDATE model_provenance SET modelKey = CASE WHEN modelKey = :oldModelKey THEN :newModelKey ELSE modelKey END, " +
            "localPath = CASE WHEN localPath = :oldPath THEN :newPath ELSE localPath END, " +
            "updatedAt = :updatedAt WHERE modelKey = :oldModelKey OR localPath = :oldPath"
    )
    suspend fun updateProvenanceReference(
        oldModelKey: String,
        oldPath: String,
        newModelKey: String,
        newPath: String,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("SELECT * FROM model_provenance WHERE modelKey = :modelKey ORDER BY updatedAt DESC")
    suspend fun getByModelKey(modelKey: String): List<ModelProvenanceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(provenance: ModelProvenanceEntity)

    @Query("DELETE FROM model_provenance WHERE id = :id")
    suspend fun deleteProvenanceById(id: String)

    /** Replace one file's attachment without changing its companion files or saved sources. */
    @Transaction
    suspend fun replaceProvenanceForArtifact(candidate: ModelProvenanceEntity): ModelProvenanceEntity {
        requireNotNull(getSourceById(candidate.sourceId)) { "Cannot attach a missing source" }
        val matching = getByModelKey(candidate.modelKey).filter { edge ->
            edge.family == candidate.family && edge.localPath?.let {
                java.io.File(it).canonicalPath == candidate.localPath
            } == true
        }
        val existing = matching.firstOrNull()
        val replacement = candidate.copy(
            id = existing?.id ?: candidate.id,
            importedAt = existing?.importedAt ?: candidate.importedAt
        )
        matching.drop(1).forEach { deleteProvenanceById(it.id) }
        upsert(replacement)
        return replacement
    }

    @Query("DELETE FROM model_provenance WHERE modelKey = :modelKey")
    suspend fun deleteByModelKey(modelKey: String)

    @Query("SELECT * FROM model_bundles ORDER BY updatedAt DESC")
    fun observeBundles(): Flow<List<ModelBundleEntity>>

    @Query("SELECT * FROM model_bundles WHERE family = :family ORDER BY updatedAt DESC")
    fun observeBundlesByFamily(family: String): Flow<List<ModelBundleEntity>>

    @Query("SELECT * FROM model_bundles WHERE id = :id LIMIT 1")
    suspend fun getBundleById(id: String): ModelBundleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bundle: ModelBundleEntity)

    @Query("DELETE FROM model_bundles WHERE id = :id")
    suspend fun deleteBundleById(id: String)

    @Transaction
    suspend fun replaceBundle(
        bundle: ModelBundleEntity,
        items: List<ModelBundleItemEntity>
    ): ModelBundleWithItems {
        upsert(bundle)
        deleteForBundle(bundle.id)
        if (items.isNotEmpty()) upsertBundleItems(items)
        return requireNotNull(getWithItems(bundle.id))
    }

    @Query("SELECT * FROM model_bundle_items WHERE bundleId = :bundleId ORDER BY partGroup, partIndex, itemKey")
    suspend fun getForBundle(bundleId: String): List<ModelBundleItemEntity>

    @Query("SELECT * FROM model_bundle_items WHERE bundleId = :bundleId ORDER BY partGroup, partIndex, itemKey")
    fun observeForBundle(bundleId: String): Flow<List<ModelBundleItemEntity>>

    @Query("SELECT * FROM model_bundle_items WHERE id = :id LIMIT 1")
    suspend fun getBundleItemById(id: String): ModelBundleItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ModelBundleItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBundleItems(items: List<ModelBundleItemEntity>)

    @Query("DELETE FROM model_bundle_items WHERE id = :id")
    suspend fun deleteBundleItemById(id: String)

    @Query("DELETE FROM model_bundle_items WHERE bundleId = :bundleId")
    suspend fun deleteForBundle(bundleId: String)

    @Transaction
    @Query("SELECT * FROM model_bundles WHERE id = :bundleId LIMIT 1")
    suspend fun getWithItems(bundleId: String): ModelBundleWithItems?

    @Query("SELECT * FROM pending_model_artifacts ORDER BY updatedAt DESC")
    fun observePendingArtifacts(): Flow<List<PendingModelArtifactEntity>>

    @Query("SELECT * FROM pending_model_artifacts WHERE status IN (:statuses) ORDER BY updatedAt DESC")
    fun observeByStatuses(statuses: List<String>): Flow<List<PendingModelArtifactEntity>>

    @Query("SELECT * FROM pending_model_artifacts WHERE id = :id LIMIT 1")
    suspend fun getPendingArtifactById(id: String): PendingModelArtifactEntity?

    @Query("SELECT * FROM pending_model_artifacts WHERE bundleId = :bundleId ORDER BY updatedAt DESC")
    suspend fun getPendingArtifactsForBundle(bundleId: String): List<PendingModelArtifactEntity>

    @Query("SELECT * FROM pending_model_artifacts WHERE downloadTaskId = :downloadTaskId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getByDownloadTaskId(downloadTaskId: String): PendingModelArtifactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(artifact: PendingModelArtifactEntity)

    @Transaction
    suspend fun upsertActiveArtifact(artifact: PendingModelArtifactEntity) {
        com.example.llamadroid.data.model.library.ensurePendingArtifactActive(this, artifact.id)
        upsert(artifact)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingArtifacts(artifacts: List<PendingModelArtifactEntity>)

    /**
     * Makes pending rows standalone before removing their bundle definition.
     * Downloaded files and source/provenance metadata remain untouched, while
     * the Unknown surface can still inspect every detached artifact.
     */
    @Transaction
    suspend fun detachPendingArtifactsAndDeleteBundle(
        bundleId: String,
        detachedArtifacts: List<PendingModelArtifactEntity>
    ) {
        if (detachedArtifacts.isNotEmpty()) upsertPendingArtifacts(detachedArtifacts)
        deleteForBundle(bundleId)
        deleteBundleById(bundleId)
    }

    /** Persist a complete bundle plan atomically before a task is started. */
    @Transaction
    suspend fun upsertPendingArtifactsAtomically(artifacts: List<PendingModelArtifactEntity>) {
        if (artifacts.isNotEmpty()) upsertPendingArtifacts(artifacts)
    }

    @Query("DELETE FROM pending_model_artifacts WHERE id = :id")
    suspend fun deletePendingArtifactById(id: String)
}

data class ModelBundleWithItems(
    @Embedded val bundle: ModelBundleEntity,
    @Relation(parentColumn = "id", entityColumn = "bundleId")
    val items: List<ModelBundleItemEntity>
)
