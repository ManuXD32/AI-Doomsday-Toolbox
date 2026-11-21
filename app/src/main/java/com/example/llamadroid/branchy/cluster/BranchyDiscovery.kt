package com.example.llamadroid.branchy.cluster

import android.content.Context
import com.example.llamadroid.util.DeviceAddresses
import com.example.llamadroid.util.NsdHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Browses `_adt-branchy._tcp.` and saves every resolved helper into [repository]. [discovered] holds the
 * helpers currently visible on the network (also the ones the user did not pin).
 */
class BranchyDiscovery(
    context: Context,
    private val repository: BranchyClusterRepository = BranchyClusterRepository(context)
) {
    private val appContext = context.applicationContext
    private val nsd = NsdHelper(appContext, BranchyAdvert.SERVICE_TYPE, BranchyAdvert.SERVICE_NAME_PREFIX)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _discovered = MutableStateFlow<List<BranchyDiscoveredDevice>>(emptyList())
    val discovered: StateFlow<List<BranchyDiscoveredDevice>> = _discovered.asStateFlow()

    /** Starts NSD browsing; calling it twice is harmless. */
    fun start() {
        if (job?.isActive == true) return
        nsd.startDiscovery()
        job = scope.launch {
            nsd.discoveredServices.collect { services ->
                val addresses = DeviceAddresses.current(appContext)
                val localHasVpn = DeviceAddresses.vpnIp(addresses) != null
                val found = services.mapNotNull { service ->
                    val ip = service.ipAddress() ?: return@mapNotNull null
                    // Own device (Wi-Fi, hotspot or VPN address): never offered as a helper of itself.
                    if (addresses.any { it.ip == ip }) return@mapNotNull null
                    BranchyAdvert.parse(service.serviceName, ip, service.port, service.attributes, localHasVpn)
                }
                _discovered.value = found
                found.forEach { runCatching { repository.upsertFromNsd(it) } }
            }
        }
    }

    /** Stops browsing and clears [discovered]. */
    fun stop() {
        job?.cancel()
        job = null
        nsd.stopDiscovery()
        _discovered.value = emptyList()
    }

    /** Stops browsing for good; the instance must not be used afterwards. */
    fun close() {
        stop()
        scope.cancel()
    }
}

/** Resolved IP of an NSD service, without the deprecated `host` getter on API 34+. */
@Suppress("DEPRECATION")
private fun android.net.nsd.NsdServiceInfo.ipAddress(): String? =
    if (android.os.Build.VERSION.SDK_INT >= 34) hostAddresses.firstOrNull()?.hostAddress else host?.hostAddress
