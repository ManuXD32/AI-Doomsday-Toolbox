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
