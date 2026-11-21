package com.example.llamadroid.branchy.cluster

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** What a cluster device is willing to do. Stored as the lowercase [value]. */
enum class BranchyClusterRole(val value: String) {
    TRAIN("train"), INFER("infer"), BOTH("both");

    /** Whether a device with this role can take part in distributed training. */
    val canTrain: Boolean get() = this != INFER

    /** Whether a device with this role can serve inference slices. */
    val canInfer: Boolean get() = this != TRAIN

    companion object {
        /** Unknown or missing text is [BOTH]: an older helper that did not advertise a role. */
        fun fromValue(value: String?): BranchyClusterRole =
            entries.firstOrNull { it.value == value?.trim()?.lowercase() } ?: BOTH
    }
}

/**
 * One helper device known to the main device (discovered over NSD or added by hand). [id] is the
 * [BranchyClusterIds.deviceId] of host and port, which is also the device id used in cluster.json.
 */
@Entity(tableName = "branchy_cluster_device")
data class BranchyClusterDeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val port: Int,
    /** `train`, `infer` or `both`. */
    val role: String,
    /** Advertised or measured matmul GFLOP/s, if known. */
    val gflops: Double? = null,
    /** Advertised free RAM in MiB, if known. */
    val ramMb: Int? = null,
    val lastSeen: Long,
    /** Pinned devices survive [BranchyClusterRepository.pruneStale]. */
    val pinned: Boolean = false,
    /** The other known address (Wi-Fi vs VPN); [BranchyEndpointPreflight] swaps it with [host] when only it answers. */
    val altHost: String? = null,
    /** `auto`, `vpn` or `lan` (see [com.example.llamadroid.util.DeviceAddresses.orderedCandidates]). */
    @ColumnInfo(defaultValue = "'auto'") val preferredInterface: String = "auto"
) {
    val roleEnum: BranchyClusterRole get() = BranchyClusterRole.fromValue(role)
    val address: String get() = "$host:$port"
}

@Dao
interface BranchyClusterDeviceDao {
    @Query("SELECT * FROM branchy_cluster_device ORDER BY pinned DESC, name COLLATE NOCASE ASC, id ASC")
    fun observeAll(): Flow<List<BranchyClusterDeviceEntity>>

    @Query("SELECT * FROM branchy_cluster_device ORDER BY pinned DESC, name COLLATE NOCASE ASC, id ASC")
    suspend fun getAll(): List<BranchyClusterDeviceEntity>

    @Query("SELECT * FROM branchy_cluster_device WHERE id = :id")
    suspend fun getById(id: String): BranchyClusterDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: BranchyClusterDeviceEntity)

    @Query("UPDATE branchy_cluster_device SET host = :host, altHost = :altHost WHERE id = :id")
    suspend fun setEndpoints(id: String, host: String, altHost: String?)

    @Query("UPDATE branchy_cluster_device SET preferredInterface = :pref WHERE id = :id")
    suspend fun setPreferredInterface(id: String, pref: String)

    @Query("DELETE FROM branchy_cluster_device WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE branchy_cluster_device SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE branchy_cluster_device SET lastSeen = :lastSeen WHERE id = :id")
    suspend fun touch(id: String, lastSeen: Long)

    @Query("UPDATE branchy_cluster_device SET gflops = :gflops, ramMb = COALESCE(:ramMb, ramMb) WHERE id = :id")
    suspend fun updateCaps(id: String, gflops: Double?, ramMb: Int?)

    @Query("DELETE FROM branchy_cluster_device WHERE pinned = 0 AND lastSeen < :cutoff")
    suspend fun deleteUnpinnedSeenBefore(cutoff: Long): Int
}

/** Pure id and address helpers for cluster devices. */
object BranchyClusterIds {
    const val DEFAULT_WORKER_PORT = 50210
    const val COORDINATOR_ID = "coordinator"
    const val COORDINATOR_HOST = "127.0.0.1"

    private val unsafe = Regex("[^a-z0-9_.-]")

    /** `host-port` lowercased with every character outside `[a-z0-9_.-]` replaced by `-` (native `safe_shard_name`). */
    fun deviceId(host: String, port: Int): String = "${host.trim().lowercase().replace(unsafe, "-")}-$port"

    /**
     * Parses `host:port` (or a bare host, which gets [defaultPort]). Returns null for a blank host, a port outside
     * 1..65535 or text that is not a host at all (spaces or a scheme).
     */
    fun parseHostPort(text: String, defaultPort: Int = DEFAULT_WORKER_PORT): Pair<String, Int>? {
        val t = text.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() } || "://" in t) return null
        val host: String
        val portText: String?
        if (t.startsWith("[")) {
            val end = t.indexOf(']')
            if (end < 2) return null
            host = t.substring(1, end)
            val rest = t.substring(end + 1)
            portText = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return null
            }
        } else if (t.count { it == ':' } == 1) {
            host = t.substringBefore(':')
            portText = t.substringAfter(':')
        } else {
            host = t
            portText = null
        }
        if (host.isEmpty()) return null
        val port = if (portText == null) defaultPort else portText.toIntOrNull() ?: return null
        return if (port in 1..65535) host to port else null
    }
}
