package com.example.llamadroid.branchy.cluster

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.flow.Flow

/** Room access for the helper devices the main device knows (discovered, manual, pinned). */
class BranchyClusterRepository(
    context: Context,
    private val dao: BranchyClusterDeviceDao = AppDatabase.getDatabase(context).branchyClusterDeviceDao()
) {
    fun observeDevices(): Flow<List<BranchyClusterDeviceEntity>> = dao.observeAll()

    suspend fun getDevices(): List<BranchyClusterDeviceEntity> = dao.getAll()

    suspend fun get(id: String): BranchyClusterDeviceEntity? = dao.getById(id)

    /**
     * Saves or refreshes a device found over NSD: name, role, capacity and lastSeen are updated; `pinned`
     * and a previously measured GFLOP/s are kept when the advert carries no value.
     */
    suspend fun upsertFromNsd(found: BranchyDiscoveredDevice, now: Long = System.currentTimeMillis()) {
        val old = dao.getById(found.id)
        dao.upsert(
            BranchyClusterDeviceEntity(
                id = found.id,
                name = found.name.ifBlank { found.host },
                host = found.host,
                port = found.port,
                role = found.role.value,
                gflops = found.gflops ?: old?.gflops,
                ramMb = found.ramMb ?: old?.ramMb,
                lastSeen = now,
                pinned = old?.pinned ?: false,
                altHost = found.altHost ?: old?.altHost?.takeIf { it != found.host },
                preferredInterface = old?.preferredInterface ?: "auto"
            )
        )
    }

    /**
     * Adds a device typed as `ip:port` (a bare host gets the default port) and pins it. Returns the saved
     * device, or null when [text] is not a valid address.
     */
    suspend fun addManual(
        text: String,
        name: String? = null,
        now: Long = System.currentTimeMillis()
    ): BranchyClusterDeviceEntity? {
        val (host, port) = BranchyClusterIds.parseHostPort(text) ?: return null
        val id = BranchyClusterIds.deviceId(host, port)
        val old = dao.getById(id)
        val device = BranchyClusterDeviceEntity(
            id = id,
            name = name?.trim()?.takeIf { it.isNotEmpty() } ?: old?.name ?: host,
            host = host,
            port = port,
            role = old?.role ?: BranchyClusterRole.BOTH.value,
            gflops = old?.gflops,
            ramMb = old?.ramMb,
            lastSeen = old?.lastSeen ?: now,
            pinned = true,
            altHost = old?.altHost?.takeIf { it != host },
            preferredInterface = old?.preferredInterface ?: "auto"
        )
        dao.upsert(device)
        return device
    }

    /** Swaps which address is primary (see [BranchyEndpointPreflight]). */
    suspend fun setEndpoints(id: String, host: String, altHost: String?) = dao.setEndpoints(id, host, altHost)

    suspend fun setPreferredInterface(id: String, pref: String) = dao.setPreferredInterface(id, pref)

    suspend fun remove(id: String) = dao.delete(id)

    suspend fun setPinned(id: String, pinned: Boolean) = dao.setPinned(id, pinned)

    /** Marks a device as alive now (heartbeat answered). */
    suspend fun touch(id: String, now: Long = System.currentTimeMillis()) = dao.touch(id, now)

    /** Stores a measured GFLOP/s (and RAM when known) for a device. */
    suspend fun updateCaps(id: String, gflops: Double?, ramMb: Int? = null) = dao.updateCaps(id, gflops, ramMb)

    /**
     * Records a heartbeat answer: lastSeen always; free RAM and GFLOP/s when the helper reported them; the
     * reported name only while the saved one is blank or still the bare host (never over a typed name).
     */
    suspend fun applyProbe(
        id: String,
        specs: com.example.llamadroid.util.HelperSpecs?,
        now: Long = System.currentTimeMillis()
    ) {
        val old = dao.getById(id) ?: return
        val reportedName = specs?.name?.trim()?.takeIf { it.isNotEmpty() }
        dao.upsert(
            old.copy(
                lastSeen = now,
                ramMb = specs?.ramFreeMb ?: old.ramMb,
                gflops = specs?.gflops ?: old.gflops,
                name = if (reportedName != null && (old.name.isBlank() || old.name == old.host)) reportedName else old.name
            )
        )
    }

    /** Deletes unpinned devices not seen since [cutoff]; returns how many were removed. */
    suspend fun pruneStale(cutoff: Long): Int = dao.deleteUnpinnedSeenBefore(cutoff)
}
