package com.example.llamadroid.tama.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A device this install has completed a code-authenticated pet transfer with. [wrappedKey] is the
 * 32-byte pairing key encrypted with an Android Keystore AES-GCM key ([keyIv] is the GCM nonce);
 * [addressesJson] is a JSON list of known reachable addresses (see `PeerAddress`).
 */
@Entity(tableName = "tama_transfer_peers")
data class TamaTransferPeerEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val wrappedKey: ByteArray,
    val keyIv: ByteArray,
    val addressesJson: String,
    val lastSeenAt: Long,
    val createdAt: Long
)

@Dao
interface TamaTransferPeerDao {
    @Query("SELECT * FROM tama_transfer_peers WHERE deviceId = :deviceId")
    suspend fun get(deviceId: String): TamaTransferPeerEntity?

    @Query("SELECT * FROM tama_transfer_peers ORDER BY lastSeenAt DESC")
    suspend fun getAll(): List<TamaTransferPeerEntity>

    @Query("SELECT * FROM tama_transfer_peers ORDER BY lastSeenAt DESC")
    fun observeAll(): Flow<List<TamaTransferPeerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(peer: TamaTransferPeerEntity)

    @Query("DELETE FROM tama_transfer_peers WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)
}
