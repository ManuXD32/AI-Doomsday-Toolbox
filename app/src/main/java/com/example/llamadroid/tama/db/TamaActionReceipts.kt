package com.example.llamadroid.tama.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Stable lifecycle values for a queued queued action. */
object TamaActionReceiptStatus {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val SUCCEEDED = "SUCCEEDED"
    const val REJECTED = "REJECTED"
    const val FAILED = "FAILED"
    const val ACKNOWLEDGED = "ACKNOWLEDGED"

    val terminal: Set<String> = setOf(SUCCEEDED, REJECTED, FAILED, ACKNOWLEDGED)
    val active: Set<String> = setOf(QUEUED, RUNNING)
}

/** Receipts use one canonical table and this kind discriminant (stored as a string column). */
object TamaActionReceiptKind {
    const val ARCADE_SESSION = "ARCADE_SESSION"

    val all: Set<String> = setOf(ARCADE_SESSION)
}

/** The request is encoded into requestJson and is the source of truth at completion. */
@Serializable
data class TamaActionReceiptRequest(
    val receiptId: String,
    val petId: String,
    val worldId: String,
    val kind: String,
    val destinationId: String,
    val targetNpcId: String,
    val requestedAt: Long,
    val questId: String? = null,
    val itemId: String? = null,
    val quantity: Int? = null,
    val arguments: Map<String, String> = emptyMap()
) {
    init {
        require(receiptId.isNotBlank()) { "receipt_id_required" }
        require(petId.isNotBlank()) { "pet_id_required" }
        require(worldId.isNotBlank()) { "world_id_required" }
        require(kind in TamaActionReceiptKind.all) { "unsupported_receipt_kind" }
        require(destinationId.isNotBlank()) { "destination_required" }
        require(targetNpcId.isNotBlank()) { "target_npc_required" }
        require(requestedAt >= 0L) { "requested_at_invalid" }
    }
}

/** Result payload kept after completion so UI can survive process death. */
@Serializable
data class TamaActionReceiptResult(
    val success: Boolean,
    val message: String = "",
    val action: String = "",
    val errorCode: String? = null,
    val completedAt: Long? = null,
    /** Canonical reward fields used by Arcade terminal replay. */
    val rewardCoins: Long = 0L,
    val rewardHappiness: Float = 0f
)

/**
 * A receipt is scoped by pet and action id. Terminal results are retained until
 * the UI acknowledges them; acknowledged rows can then be pruned by policy.
 */
@Entity(
    tableName = "tama_world_action_receipts",
    primaryKeys = ["petId", "id"],
    indices = [
        Index(value = ["petId", "status", "updatedAt"]),
        Index(value = ["petId", "kind", "updatedAt"])
    ]
)
@Serializable
data class TamaActionReceiptEntity(
    val petId: String,
    val id: String,
    val worldId: String,
    val kind: String,
    val requestJson: String,
    val resultJson: String? = null,
    val status: String = TamaActionReceiptStatus.QUEUED,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
    val acknowledgedAt: Long? = null
)

@Dao
abstract class TamaActionReceiptDao {
    @Query("SELECT * FROM tama_world_action_receipts WHERE petId = :petId AND id = :id LIMIT 1")
    abstract suspend fun byId(petId: String, id: String): TamaActionReceiptEntity?

    @Query("SELECT * FROM tama_world_action_receipts WHERE petId = :petId AND status IN ('QUEUED', 'RUNNING') ORDER BY createdAt, id")
    abstract suspend fun active(petId: String): List<TamaActionReceiptEntity>

    @Query("SELECT * FROM tama_world_action_receipts WHERE petId = :petId AND status IN ('SUCCEEDED', 'REJECTED', 'FAILED') ORDER BY updatedAt DESC, id DESC")
    abstract suspend fun unacknowledged(petId: String): List<TamaActionReceiptEntity>

    @Query("SELECT * FROM tama_world_action_receipts WHERE petId = :petId ORDER BY updatedAt DESC, id DESC LIMIT :limit")
    abstract suspend fun recent(petId: String, limit: Int = 100): List<TamaActionReceiptEntity>

    @Query("SELECT * FROM tama_world_action_receipts WHERE petId = :petId ORDER BY createdAt, id")
    abstract suspend fun all(petId: String): List<TamaActionReceiptEntity>

    @Query("SELECT * FROM tama_world_action_receipts WHERE petId = :petId AND status IN ('SUCCEEDED', 'REJECTED', 'FAILED') ORDER BY updatedAt DESC, id DESC")
    abstract fun observeUnacknowledged(petId: String): Flow<List<TamaActionReceiptEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(value: TamaActionReceiptEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun save(value: TamaActionReceiptEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun saveAll(values: List<TamaActionReceiptEntity>)

    /** Insert is idempotent and reports whether this call created the row. */
    @Transaction
    open suspend fun createOrGet(value: TamaActionReceiptEntity): Pair<Boolean, TamaActionReceiptEntity> {
        // Room returns SQLite's row id, not a row count. With INSERT IGNORE a
        // conflict returns -1; any non-negative row id means this call won.
        val inserted = insertIfAbsent(value) != -1L
        return inserted to (byId(value.petId, value.id) ?: error("receipt_insert_failed"))
    }

    @Query("""
        UPDATE tama_world_action_receipts
        SET status = :status, updatedAt = :updatedAt, resultJson = :resultJson,
            completedAt = :completedAt
        WHERE petId = :petId AND id = :id AND status IN ('QUEUED', 'RUNNING')
    """)
    abstract suspend fun completeActive(
        petId: String,
        id: String,
        status: String,
        updatedAt: Long,
        completedAt: Long?,
        resultJson: String?
    ): Int

    @Query("""
        UPDATE tama_world_action_receipts
        SET status = 'RUNNING', updatedAt = :updatedAt
        WHERE petId = :petId AND id = :id AND status = 'QUEUED'
    """)
    abstract suspend fun markRunning(petId: String, id: String, updatedAt: Long): Int

    /** Replaces only the active request payload inside the completion transaction. */
    @Query("""
        UPDATE tama_world_action_receipts
        SET requestJson = :requestJson, updatedAt = :updatedAt
        WHERE petId = :petId AND id = :id AND status IN ('QUEUED', 'RUNNING')
    """)
    abstract suspend fun updateActiveRequest(
        petId: String,
        id: String,
        requestJson: String,
        updatedAt: Long
    ): Int

    @Query("""
        UPDATE tama_world_action_receipts
        SET status = 'ACKNOWLEDGED', acknowledgedAt = :acknowledgedAt, updatedAt = :acknowledgedAt
        WHERE petId = :petId AND id = :id AND status IN ('SUCCEEDED', 'REJECTED', 'FAILED')
    """)
    abstract suspend fun acknowledge(petId: String, id: String, acknowledgedAt: Long): Int

    @Query("""
        DELETE FROM tama_world_action_receipts
        WHERE petId = :petId AND status = 'ACKNOWLEDGED' AND updatedAt < :before
    """)
    abstract suspend fun pruneAcknowledged(petId: String, before: Long): Int

    @Query("DELETE FROM tama_world_action_receipts WHERE petId = :petId")
    abstract suspend fun clearPet(petId: String)
}
