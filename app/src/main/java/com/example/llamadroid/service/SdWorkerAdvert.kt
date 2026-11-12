package com.example.llamadroid.service

import com.example.llamadroid.data.db.SdDistributedWorkerEntity
import com.example.llamadroid.util.DeviceAddresses

/** A Media (SD) worker found over NSD, with its advertised capacity. */
data class SdDiscoveredWorker(
    val name: String,
    val host: String,
    val port: Int,
    val ramMb: Int?,
    val cores: Int?,
    val appVersion: String?
) {
    val address: String get() = "$host:$port"

    /** Saved-worker row for a one-tap add: advertised RAM, half the cores as threads (defaults 4096 / 4). */
    fun toEntity(sortOrder: Int): SdDistributedWorkerEntity = SdDistributedWorkerEntity(
        host = host,
        port = port,
        deviceName = name.ifBlank { host },
        ramMB = ramMb ?: 4096,
        threads = cores?.let { (it / 2).coerceIn(1, 32) } ?: 4,
        sortOrder = sortOrder
    )
}

/** Pure helpers for the `_adt-sd._tcp.` NSD record: TXT building/parsing and endpoint choice. */
object SdWorkerAdvert {
    const val SERVICE_TYPE = "_adt-sd._tcp."
    const val SERVICE_NAME_PREFIX = "SdWorker"
    const val TXT_VPN_IP = "vpn_ip"

    /** TXT record: `ram_free_mb`, `cores`, `app_ver` and `vpn_ip` (only when this device has a VPN address). */
    fun buildTxt(ramFreeMb: Int?, cores: Int?, appVersion: String?, vpnIp: String?): Map<String, String> {
        val txt = WorkerAdvert(ramFreeMb?.takeIf { it > 0 }, cores, appVersion).toAttributes().toMutableMap()
        vpnIp?.takeIf { it.isNotBlank() }?.let { txt[TXT_VPN_IP] = it }
        return txt
    }

    /**
     * Builds the discovered worker from an NSD service's pieces. The endpoint is the advertised
     * `vpn_ip` when this device also has a VPN address, otherwise [resolvedIp].
     */
    fun parse(
        serviceName: String,
        resolvedIp: String,
        port: Int,
        attributes: Map<String, ByteArray?>?,
        localHasVpn: Boolean
    ): SdDiscoveredWorker {
        val advert = WorkerAdvert.parse(attributes)
        val vpn = attributes?.get(TXT_VPN_IP)?.toString(Charsets.UTF_8)
        return SdDiscoveredWorker(
            name = serviceName.removePrefix("$SERVICE_NAME_PREFIX-"),
            host = DeviceAddresses.chooseWorkerEndpoint(resolvedIp, vpn, localHasVpn),
            port = port,
            ramMb = advert.ramFreeMb,
            cores = advert.cores,
            appVersion = advert.appVersion
        )
    }

    /** Discovered workers that are not yet saved (same host and port). */
    fun unsaved(
        discovered: List<SdDiscoveredWorker>,
        saved: List<SdDistributedWorkerEntity>
    ): List<SdDiscoveredWorker> {
        val known = saved.map { "${it.host}:${it.port}" }.toSet()
        return discovered.filter { it.address !in known }
    }
}
