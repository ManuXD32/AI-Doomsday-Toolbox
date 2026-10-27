package com.example.llamadroid.tama.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Insert
import kotlinx.coroutines.flow.Flow

/**
 * Single-row (id = [CURRENT_ID]) durable record of the pet transfer in flight, so an interrupted
 * handoff can be resolved after process death. Role and status hold names of the enums in
 * `com.example.llamadroid.tama.transfer`.
 */
@Entity(tableName = "tama_transfer_state")
data class TamaTransferStateEntity(
    @PrimaryKey val id: String = CURRENT_ID,
    val role: String,
    val status: String,
    val transferId: String?,
    val petId: String?,
    val petName: String?,
    val peerName: String?,
    val originDeviceId: String?,
    /** Outbound zip (sender) or the paused pet's zip (guest host). */
    val archivePath: String?,
    val pausedPetId: String?,
    val pausedArchivePath: String?,
    val updatedAt: Long
) {
    companion object {
        const val CURRENT_ID = "current"
    }
}

@Dao
interface TamaTransferStateDao {
    @Query("SELECT * FROM tama_transfer_state WHERE id = 'current'")
    suspend fun get(): TamaTransferStateEntity?

    @Query("SELECT * FROM tama_transfer_state WHERE id = 'current'")
    fun observe(): Flow<TamaTransferStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: TamaTransferStateEntity)

    @Query("DELETE FROM tama_transfer_state")
    suspend fun clear()
}
