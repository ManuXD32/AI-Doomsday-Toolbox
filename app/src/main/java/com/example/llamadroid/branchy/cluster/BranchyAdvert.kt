package com.example.llamadroid.branchy.cluster

import com.example.llamadroid.util.DeviceAddresses

/** A Branchy helper found over NSD, with the capacity it advertised. */
data class BranchyDiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    val role: BranchyClusterRole,
    val ramMb: Int?,
    val gflops: Double?,
    val appVersion: String?,
    /** Port of the helper's inference server, advertised by helpers that take slices. */
    val inferPort: Int? = null,
    /** The other address (Wi-Fi vs VPN) to fall back to when [host] is unreachable. */
    val altHost: String? = null
) {
    val id: String get() = BranchyClusterIds.deviceId(host, port)
    val address: String get() = "$host:$port"
}

/** Pure helpers for the `_adt-branchy._tcp.` NSD record: TXT building and parsing. */
object BranchyAdvert {
    const val SERVICE_TYPE = "_adt-branchy._tcp."
    const val SERVICE_NAME_PREFIX = "BranchyHelper"
    const val KEY_ROLE = "role"
    const val KEY_NAME = "name"
    const val KEY_RAM_MB = "ram_mb"
    const val KEY_GFLOPS = "gflops"
    const val KEY_APP_VER = "app_ver"
    const val KEY_PORT = "port"
    const val KEY_VPN_IP = "vpn_ip"
    const val KEY_INFER_PORT = "infer_port"

    /** Heartbeat banner served on worker port + 1. */
    const val HEARTBEAT_BANNER = "adt-branchy-hb 1"

    /** TXT record; optional values are left out when unknown. Each entry stays far below the 255-byte TXT limit. */
    fun buildTxt(
        role: BranchyClusterRole,
        name: String,
        ramMb: Int?,
        gflops: Double?,
        appVersion: String?,
        port: Int,
        vpnIp: String? = null,
        inferPort: Int? = null
    ): Map<String, String> = buildMap {
        put(KEY_ROLE, role.value)
        put(KEY_NAME, name.trim().take(48))
        ramMb?.takeIf { it > 0 }?.let { put(KEY_RAM_MB, it.toString()) }
        gflops?.takeIf { it > 0.0 && it.isFinite() }?.let { put(KEY_GFLOPS, "%.1f".format(java.util.Locale.US, it)) }
        appVersion?.takeIf { it.isNotBlank() }?.let { put(KEY_APP_VER, it.take(32)) }
        put(KEY_PORT, port.toString())
        vpnIp?.takeIf { it.isNotBlank() }?.let { put(KEY_VPN_IP, it) }
        inferPort?.takeIf { role.canInfer && it in 1..65535 }?.let { put(KEY_INFER_PORT, it.toString()) }
    }

    /**
     * Builds a device from an NSD service's pieces. The endpoint is the advertised `vpn_ip` when this device
     * also has a VPN address, otherwise [resolvedIp]. The service [port] wins over the TXT `port` unless it
     * is not a valid port. Returns null when the port is unusable.
     */
    fun parse(
        serviceName: String,
        resolvedIp: String,
        port: Int,
        attributes: Map<String, ByteArray?>?,
        localHasVpn: Boolean
    ): BranchyDiscoveredDevice? {
        fun text(key: String) = attributes?.get(key)?.toString(Charsets.UTF_8)?.trim()?.takeIf(String::isNotEmpty)
        val effectivePort = port.takeIf { it in 1..65535 } ?: text(KEY_PORT)?.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: return null
        val candidates = DeviceAddresses.endpointCandidates(resolvedIp, text(KEY_VPN_IP), localHasVpn)
        return BranchyDiscoveredDevice(
            name = text(KEY_NAME)?.take(48) ?: serviceName.removePrefix("$SERVICE_NAME_PREFIX-"),
            host = candidates.first(),
            port = effectivePort,
            role = BranchyClusterRole.fromValue(text(KEY_ROLE)),
            ramMb = text(KEY_RAM_MB)?.toIntOrNull()?.takeIf { it in 1..4_194_304 },
            gflops = text(KEY_GFLOPS)?.toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() && it < 1e6 },
            appVersion = text(KEY_APP_VER)?.take(32),
            inferPort = text(KEY_INFER_PORT)?.toIntOrNull()?.takeIf { it in 1..65535 },
            altHost = candidates.getOrNull(1)
        )
    }
}
