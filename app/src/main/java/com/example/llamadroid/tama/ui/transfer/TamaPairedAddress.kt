package com.example.llamadroid.tama.ui.transfer

import com.example.llamadroid.tama.transfer.PairedPeer
import com.example.llamadroid.tama.transfer.PeerAddress
import com.example.llamadroid.tama.transfer.PeerAddressSource

/** A manual (VPN / hostname) address typed by the user; [port] 0 means "use the link's default port". */
internal data class ManualAddress(val host: String, val port: Int)

/**
 * Parses `host`, `host:port`, `[v6]:port` or a bare IPv6 address. Hostnames, IPv4 and IPv6 only;
 * returns null for anything else (spaces, schemes, out-of-range ports).
 */
internal fun parseManualAddress(raw: String): ManualAddress? {
    val text = raw.trim()
    if (text.isEmpty() || text.any(Char::isWhitespace)) return null
    val host: String
    var port = 0
    when {
        text.startsWith("[") -> {
            val end = text.indexOf(']')
            if (end < 2) return null
            host = text.substring(1, end)
            val rest = text.substring(end + 1)
            if (rest.isNotEmpty()) {
                if (!rest.startsWith(":")) return null
                port = rest.substring(1).toIntOrNull() ?: return null
            }
            if (!host.contains(':')) return null
        }
        text.count { it == ':' } == 1 -> {
            host = text.substringBefore(':')
            port = text.substringAfter(':').toIntOrNull() ?: return null
        }
        else -> host = text
    }
    if (port != 0 && port !in 1..65535) return null
    if (text.count { it == ':' } == 1 && port == 0) return null // "host:0" or "host:" is a typo, not "unknown"
    return if (isValidHost(host)) ManualAddress(host, port) else null
}

private fun isValidHost(host: String): Boolean {
    if (host.length !in 1..253) return false
    if (host.contains(':')) {
        return host.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' } &&
            host.count { it == ':' } in 2..7
    }
    return host.split('.').all { label ->
        label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
            label.all { it.isLetterOrDigit() || it == '-' }
    }
}

/** Display text of an address; a port of 0 shows the host only. */
internal fun PeerAddress.display(): String = when {
    port <= 0 -> host
    host.contains(':') -> "[$host]:$port"
    else -> "$host:$port"
}

/** Badge category of an address for the UI; MANUAL entries are what users reach over a VPN. */
internal enum class AddressBadge { LAN, VPN, LEARNED }

internal fun PeerAddress.badge(): AddressBadge = when (source) {
    PeerAddressSource.LAN -> AddressBadge.LAN
    PeerAddressSource.MANUAL -> AddressBadge.VPN
    PeerAddressSource.ADVERTISED -> AddressBadge.LEARNED
}

/** Latest time any address of [peer] answered, or 0 when none has. */
internal fun PairedPeer.lastOkAt(): Long = addresses.maxOfOrNull { it.lastOkAt } ?: 0L

/** Replaces [old] (or adds when null) with a MANUAL [new] address, dropping a duplicate host+port. */
internal fun List<PeerAddress>.withManual(old: PeerAddress?, new: ManualAddress): List<PeerAddress> {
    val kept = filter { it != old && !(it.host == new.host && it.port == new.port && it.source == PeerAddressSource.MANUAL) }
    return kept + PeerAddress(new.host, new.port, PeerAddressSource.MANUAL, old?.lastOkAt ?: 0L)
}
