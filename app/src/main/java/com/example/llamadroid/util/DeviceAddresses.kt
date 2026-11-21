package com.example.llamadroid.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/** Kind of IPv4 address a device exposes to other devices. */
enum class AddressKind { VPN, LAN }

data class DeviceAddress(val ip: String, val kind: AddressKind, val interfaceName: String)

/** Plain description of a network interface so address selection can be tested without Android. */
data class InterfaceAddresses(
    val name: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val ipv4: List<String>
)

/**
 * Lists this device's IPv4 addresses with their kind, VPN first and Wi-Fi/LAN after.
 *
 * VPN detection: addresses reported by networks with TRANSPORT_VPN (passed as [vpnAddresses]),
 * then interface names (`tun*`, `wg*`, `tailscale*`, `ppp*`, `ipsec*`) and the CGNAT range
 * 100.64.0.0/10 as fallbacks.
 */
object DeviceAddresses {
    private val VPN_PREFIXES = listOf("tun", "wg", "tailscale", "ppp", "ipsec")
    private val LAN_PREFERRED_PREFIXES = listOf("wlan", "eth", "ap", "swlan")

    fun isVpnInterfaceName(name: String): Boolean {
        val lower = name.lowercase()
        return VPN_PREFIXES.any { lower.startsWith(it) }
    }

    /** True for 100.64.0.0/10 (carrier-grade NAT, used by Tailscale and similar). */
    fun isCgnat(ip: String): Boolean {
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
    }

    fun isLinkLocal(ip: String): Boolean = ip.startsWith("169.254.")

    fun resolve(
        interfaces: List<InterfaceAddresses>,
        vpnAddresses: Set<String> = emptySet()
    ): List<DeviceAddress> {
        val all = interfaces
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                iface.ipv4
                    .filter { it.isNotBlank() && !it.startsWith("127.") && !isLinkLocal(it) }
                    .map { ip ->
                        val vpn = ip in vpnAddresses || isVpnInterfaceName(iface.name) || isCgnat(ip)
                        DeviceAddress(ip, if (vpn) AddressKind.VPN else AddressKind.LAN, iface.name)
                    }
            }
            .distinctBy { it.ip }
        val vpn = all.filter { it.kind == AddressKind.VPN }
        val lan = all.filter { it.kind == AddressKind.LAN }
            .sortedBy { addr ->
                val index = LAN_PREFERRED_PREFIXES.indexOfFirst { addr.interfaceName.lowercase().startsWith(it) }
                if (index >= 0) index else LAN_PREFERRED_PREFIXES.size
            }
        return vpn + lan
    }

    fun vpnIp(addresses: List<DeviceAddress>): String? =
        addresses.firstOrNull { it.kind == AddressKind.VPN }?.ip

    fun lanIp(addresses: List<DeviceAddress>): String? =
        addresses.firstOrNull { it.kind == AddressKind.LAN }?.ip

    /**
     * Endpoint to use for a discovered worker: its `vpn_ip` when it advertised one and this
     * device also has a VPN address, otherwise the address NSD resolved.
     */
    fun chooseWorkerEndpoint(resolvedIp: String, advertisedVpnIp: String?, localHasVpn: Boolean): String {
        val vpn = advertisedVpnIp?.trim()?.takeIf { it.isNotEmpty() }
        return if (vpn != null && localHasVpn) vpn else resolvedIp
    }

    /**
     * Addresses to try for a worker, preferred first: [chooseWorkerEndpoint]'s pick (its VPN address when both
     * sides have a VPN), then the other address. Consumer VPNs (tunnel to a server, not peer-to-peer) advertise
     * addresses the peer can never reach, so callers probe with [firstReachable] and fall back.
     */
    fun endpointCandidates(resolvedIp: String, advertisedVpnIp: String?, localHasVpn: Boolean): List<String> {
        val vpn = advertisedVpnIp?.trim()?.takeIf { it.isNotEmpty() }
        val ordered = if (vpn != null && localHasVpn) listOf(vpn, resolvedIp) else listOf(resolvedIp, vpn)
        return ordered.filterNotNull().filter { it.isNotBlank() }.distinct()
    }

    /** Stored preference values for [orderedCandidates]. */
    const val PREF_AUTO = "auto"
    const val PREF_VPN = "vpn"
    const val PREF_LAN = "lan"

    /** Unknown or blank text is [PREF_AUTO]. */
    fun normalizePref(pref: String?): String = when (pref?.trim()?.lowercase()) {
        PREF_VPN -> PREF_VPN
        PREF_LAN -> PREF_LAN
        else -> PREF_AUTO
    }

    /** True when [ip] is a VPN address: reported by a VPN network ([vpnAddresses]) or in the CGNAT range. */
    fun isVpnAddress(ip: String, vpnAddresses: Set<String> = emptySet()): Boolean =
        ip in vpnAddresses || isCgnat(ip)

    /**
     * Addresses to try for one helper, in order. [PREF_AUTO] keeps the stored order ([host] then [altHost]);
     * [PREF_VPN] / [PREF_LAN] put the address of that kind first and the other one after it (stable: when neither
     * or both match, the stored order is kept). Blank and duplicate addresses are dropped.
     */
    fun orderedCandidates(
        pref: String,
        host: String,
        altHost: String?,
        vpnAddresses: Set<String> = emptySet()
    ): List<String> {
        val all = listOfNotNull(host, altHost).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val wantVpn = when (normalizePref(pref)) {
            PREF_VPN -> true
            PREF_LAN -> false
            else -> return all
        }
        return all.sortedBy { if (isVpnAddress(it, vpnAddresses) == wantVpn) 0 else 1 }
    }

    /** True when a TCP connect to [host]:[port] succeeds within [timeoutMs]. Blocking; call off the main thread. */
    fun canConnect(host: String, port: Int, timeoutMs: Int = 1500): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), timeoutMs) }
        true
    }.getOrDefault(false)

    /**
     * First of [candidates] that accepts a TCP connection on [port], or null when none does. Blocking
     * (up to [timeoutMs] per candidate); call from an IO dispatcher.
     */
    fun firstReachable(candidates: List<String>, port: Int, timeoutMs: Int = 1500): String? =
        candidates.firstOrNull { canConnect(it, port, timeoutMs) }

    fun readInterfaces(): List<InterfaceAddresses> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().map { iface ->
            InterfaceAddresses(
                name = iface.name.orEmpty(),
                isUp = runCatching { iface.isUp }.getOrDefault(false),
                isLoopback = runCatching { iface.isLoopback }.getOrDefault(false),
                ipv4 = iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .mapNotNull { it.hostAddress }
            )
        }
    }.getOrDefault(emptyList())

    /** IPv4 addresses of networks Android flags as VPN (TRANSPORT_VPN). */
    fun vpnAddressesFromConnectivity(context: Context): Set<String> = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching emptySet<String>()
        @Suppress("DEPRECATION")
        manager.allNetworks
            .filter { network ->
                manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            .flatMap { network ->
                manager.getLinkProperties(network)?.linkAddresses.orEmpty()
                    .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
            }
            .toSet()
    }.getOrDefault(emptySet())

    fun current(context: Context): List<DeviceAddress> =
        resolve(readInterfaces(), vpnAddressesFromConnectivity(context))
}
