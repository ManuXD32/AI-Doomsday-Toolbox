package com.example.llamadroid.branchy.cluster

import android.content.Context
import com.example.llamadroid.util.DeviceAddresses
import com.example.llamadroid.util.HelperHeartbeat
import com.example.llamadroid.util.HelperLiveness
import com.example.llamadroid.util.HelperProbeResult
import com.example.llamadroid.util.HelperSpecs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Result of the latest heartbeat probe of one helper. [online] is true only when the heartbeat banner answered. */
data class BranchyHelperStatus(
    val online: Boolean,
    /** Connect + banner round trip; null when unreachable. */
    val rttMs: Long?,
    /** What the helper reported about itself; null for banner-only (older) helpers or when unreachable. */
    val specs: HelperSpecs?,
    /** Address that answered (host or altHost); null when unreachable. */
    val answeredHost: String?,
    val checkedAt: Long
)

/**
 * Probes helpers' heartbeat ports (`port + 1`) for the Devices step. Tries `host`, then `altHost`; when only
 * `altHost` answers the two are swapped in Room (like [BranchyEndpointPreflight]). On an answer Room gets
 * `lastSeen` and, when the helper sent them, ramMb / gflops (and the name when it is still the bare host).
 * Typed values live in the wizard's deviceEdits and are never touched here.
 *
 * Usage: call [probe] with the saved devices on add and every 10 s while the Devices step is visible, and
 * collect [status] (id -> [BranchyHelperStatus]) to show online/unreachable + rtt; absent id = not probed yet.
 */
class BranchyHelperProbe(
    private val repository: BranchyClusterRepository?,
    private val probeFn: (host: String, port: Int) -> HelperProbeResult = { h, p ->
        HelperHeartbeat.probeDetailed(h, p, BranchyAdvert.HEARTBEAT_BANNER, TIMEOUT_MS)
    },
    private val clock: () -> Long = System::currentTimeMillis
) {
    constructor(context: Context) : this(BranchyClusterRepository(context))

    private val _status = MutableStateFlow<Map<String, BranchyHelperStatus>>(emptyMap())
    val status: StateFlow<Map<String, BranchyHelperStatus>> = _status.asStateFlow()

    /** Probes [devices] in parallel, updates [status] and Room, and returns the new statuses by device id. */
    suspend fun probe(devices: List<BranchyClusterDeviceEntity>): Map<String, BranchyHelperStatus> =
        withContext(Dispatchers.IO) {
            val results = coroutineScope {
                devices.map { device -> async { device to probeDevice(device, probeFn, clock()) } }.awaitAll()
            }
            val map = results.associate { (device, outcome) -> device.id to outcome.status }
            results.forEach { (device, outcome) -> persist(device, outcome) }
            _status.update { old -> old + map }
            map
        }

    /** Drops the status of a removed device. */
    fun forget(id: String) {
        _status.update { it - id }
    }

    private suspend fun persist(device: BranchyClusterDeviceEntity, outcome: ProbeOutcome) {
        val repo = repository ?: return
        if (!outcome.status.online) return
        runCatching {
            // An explicit VPN/LAN preference never swaps the stored addresses; only auto does.
            if (outcome.swapped && DeviceAddresses.normalizePref(device.preferredInterface) == DeviceAddresses.PREF_AUTO) {
                repo.setEndpoints(device.id, outcome.status.answeredHost ?: device.host, device.host)
            }
            repo.applyProbe(device.id, outcome.status.specs, outcome.status.checkedAt)
        }
    }

    internal data class ProbeOutcome(val status: BranchyHelperStatus, val swapped: Boolean)

    companion object {
        const val TIMEOUT_MS = 1500
        const val INTERVAL_MS = 10_000L

        /** Pure: probe `host`, then `altHost`; first [HelperLiveness.HEARTBEAT] wins. */
        internal fun probeDevice(
            device: BranchyClusterDeviceEntity,
            probeFn: (String, Int) -> HelperProbeResult,
            now: Long
        ): ProbeOutcome {
            val hosts = DeviceAddresses.orderedCandidates(device.preferredInterface, device.host, device.altHost)
            for (host in hosts) {
                val result = probeFn(host, device.port)
                if (result.liveness == HelperLiveness.HEARTBEAT) {
                    return ProbeOutcome(BranchyHelperStatus(true, result.rttMs, result.specs, host, now), host != device.host)
                }
            }
            return ProbeOutcome(BranchyHelperStatus(false, null, null, null, now), false)
        }
    }
}
