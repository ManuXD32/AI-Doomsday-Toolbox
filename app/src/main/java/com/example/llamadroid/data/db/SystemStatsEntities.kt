package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "system_stats_samples",
    indices = [Index("timestampEpochMs"), Index("deviceId")]
)
data class SystemStatsSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMs: Long,
    val deviceId: String,
    val snapshotJson: String
)

@Entity(
    tableName = "system_stats_events",
    indices = [Index("startedAtEpochMs"), Index("category"), Index("status")]
)
data class SystemStatsEventEntity(
    @PrimaryKey val id: String,
    val category: String,
    val phase: String,
    val scope: String,
    val status: String,
    val label: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null
)

@Dao
interface SystemStatsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSample(sample: SystemStatsSampleEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: SystemStatsEventEntity)

    @Query("SELECT * FROM system_stats_samples WHERE timestampEpochMs BETWEEN :fromEpochMs AND :untilEpochMs ORDER BY timestampEpochMs ASC")
    suspend fun getSamples(fromEpochMs: Long, untilEpochMs: Long): List<SystemStatsSampleEntity>

    @Query("SELECT * FROM system_stats_samples WHERE timestampEpochMs BETWEEN :fromEpochMs AND :untilEpochMs ORDER BY timestampEpochMs ASC")
    fun observeSamples(fromEpochMs: Long, untilEpochMs: Long): Flow<List<SystemStatsSampleEntity>>

    @Query("SELECT * FROM system_stats_events WHERE startedAtEpochMs <= :untilEpochMs AND (endedAtEpochMs IS NULL OR endedAtEpochMs >= :fromEpochMs) ORDER BY startedAtEpochMs ASC")
    suspend fun getEvents(fromEpochMs: Long, untilEpochMs: Long): List<SystemStatsEventEntity>

    @Query("DELETE FROM system_stats_samples WHERE timestampEpochMs < :cutoffEpochMs")
    suspend fun deleteSamplesBefore(cutoffEpochMs: Long): Int

    @Query("DELETE FROM system_stats_events WHERE startedAtEpochMs < :cutoffEpochMs AND (endedAtEpochMs IS NULL OR endedAtEpochMs < :cutoffEpochMs)")
    suspend fun deleteEventsBefore(cutoffEpochMs: Long): Int

    @Query("SELECT * FROM system_stats_samples ORDER BY timestampEpochMs DESC LIMIT 1")
    suspend fun getLatestSample(): SystemStatsSampleEntity?

    @Query("SELECT * FROM system_stats_events WHERE id = :id LIMIT 1")
    suspend fun getEvent(id: String): SystemStatsEventEntity?
}
