package com.example.llamadroid.branchy.cluster

import android.content.Context
import com.example.llamadroid.util.DeviceAddresses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Probes each helper's worker port on `host`, then on `altHost`. When only `altHost` answers (a consumer VPN
 * address the peer cannot reach, or the reverse) the two are swapped in Room and in the returned device, so
 * cluster.json and inference connections use the reachable address. Devices where nothing answers are returned
 * unchanged so the normal connection error still surfaces. A helper with a VPN/LAN preference is probed in
 * that order and reordered in memory only (Room is not swapped).
 */
object BranchyEndpointPreflight {
    private const val TIMEOUT_MS = 1500

    suspend fun preflight(context: Context, devices: List<BranchyClusterDeviceEntity>): List<BranchyClusterDeviceEntity> =
        preflight(devices, BranchyClusterRepository(context))

    suspend fun preflight(
        devices: List<BranchyClusterDeviceEntity>,
        repository: BranchyClusterRepository,
        probe: (host: String, port: Int) -> Boolean = { h, p -> DeviceAddresses.canConnect(h, p, TIMEOUT_MS) }
    ): List<BranchyClusterDeviceEntity> = withContext(Dispatchers.IO) {
        coroutineScope {
            devices.map { device -> async { swapIfNeeded(device, repository, probe) } }.awaitAll()
        }
    }

    private suspend fun swapIfNeeded(
        device: BranchyClusterDeviceEntity,
        repository: BranchyClusterRepository,
        probe: (String, Int) -> Boolean
    ): BranchyClusterDeviceEntity {
        val alt = device.altHost?.takeIf { it.isNotBlank() && it != device.host } ?: return device
        if (DeviceAddresses.normalizePref(device.preferredInterface) != DeviceAddresses.PREF_AUTO) {
            // Explicit VPN/LAN preference: try the preferred address first, fall back to the other one. The
            // order is applied in memory only (cluster.json `hosts`); Room keeps host/altHost untouched.
            val ordered = DeviceAddresses.orderedCandidates(device.preferredInterface, device.host, alt)
            val answering = ordered.firstOrNull { probe(it, device.port) } ?: ordered.first()
            val other = ordered.firstOrNull { it != answering }
            return device.copy(host = answering, altHost = other)
        }
        if (probe(device.host, device.port)) return device
        if (!probe(alt, device.port)) return device
        runCatching { repository.setEndpoints(device.id, alt, device.host) }
        return device.copy(host = alt, altHost = device.host)
    }
}
