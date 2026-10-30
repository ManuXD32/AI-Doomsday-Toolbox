package com.example.llamadroid.tama.transfer

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** `/link/ping` body: the caller's listener [port] (0 = not listening) and the addresses it can be reached on. */
@Serializable
data class LinkPing(val port: Int = 0, val addresses: List<String> = emptyList(), val deviceName: String = "")

/** `/link/ping` answer; signed by the responder so a caller only trusts a peer that holds the pairing key. */
@Serializable
data class LinkPong(
    val deviceId: String,
    val deviceName: String = "",
    val port: Int = 0,
    val addresses: List<String> = emptyList()
)

/** `/link/reclaim` body. [returnHost] null means "the address this request came from". */
@Serializable
data class ReclaimRequest(
    val transferId: String,
    val petId: String,
    val returnHost: String? = null,
    val returnPort: Int = 0
)

@Serializable
data class ReclaimReply(val accepted: Boolean, val reason: String? = null)

/** Facts worth a user notification; collected by the link service. */
sealed interface PetLinkEvent {
    /** Host side: the guest was sent home. */
    data class WentHome(val petName: String, val deviceName: String) : PetLinkEvent

    /** Origin side: the pet came back (reclaimed, or sent home by the host). */
    data class PetBack(val petName: String) : PetLinkEvent

    /** Host side: an automatic send-home could not reach the origin. */
    data class Unreachable(val deviceName: String) : PetLinkEvent
}

/** Outcome of [PetLinkController.reclaim], observable by the UI. */
sealed interface ReclaimState {
    object Idle : ReclaimState
    object Reclaiming : ReclaimState
    /** The host accepted; the pet arrives through this device's link listener. */
    object WaitingForReturn : ReclaimState
    data class Unreachable(val addressesTried: List<String>) : ReclaimState
    data class Failed(val reason: String) : ReclaimState
    object Done : ReclaimState
}

/** Outcome of [PetLinkController.testPeer]. */
sealed interface PeerTestResult {
    data class Ok(val latencyMs: Long, val address: String) : PeerTestResult
    data class Unreachable(val addressesTried: List<String>) : PeerTestResult
    data class Failed(val reason: String) : PeerTestResult
}

/** Outcome of [PetLinkController.sendHome] on the host. */
sealed interface SendHomeResult {
    object Done : SendHomeResult
    data class Unreachable(val addressesTried: List<String>) : SendHomeResult
    data class Failed(val reason: String) : SendHomeResult
}

/** The active visit as the link layer sees it; null when there is none or the peer is not paired. */
data class LinkVisit(
    val peerDeviceId: String,
    val peerName: String,
    val petName: String,
    val transferId: String,
    /** True when this device is the origin (pet away), false when it hosts the guest. */
    val awayAtPeer: Boolean
)

/**
 * Remembers which paired device an origin's pet is visiting (the persisted row only keeps the
 * peer's display name). The host side needs no entry: it reads the origin id from its row.
 */
interface VisitPeerRegistry {
    fun peerFor(transferId: String): String?
    fun set(transferId: String, peerDeviceId: String)
    fun clearExcept(transferId: String?)
}

class InMemoryVisitPeerRegistry : VisitPeerRegistry {
    private val map = ConcurrentHashMap<String, String>()
    override fun peerFor(transferId: String): String? = map[transferId]
    override fun set(transferId: String, peerDeviceId: String) { map[transferId] = peerDeviceId }
    override fun clearExcept(transferId: String?) {
        map.keys.toList().filter { it != transferId }.forEach { map.remove(it) }
    }
}

/** Single-entry registry in SharedPreferences (one visit at a time). */
class PrefsVisitPeerRegistry(private val prefs: SharedPreferences) : VisitPeerRegistry {
    override fun peerFor(transferId: String): String? =
        if (prefs.getString(KEY_TRANSFER, null) == transferId) prefs.getString(KEY_PEER, null) else null

    override fun set(transferId: String, peerDeviceId: String) {
        prefs.edit { putString(KEY_TRANSFER, transferId); putString(KEY_PEER, peerDeviceId) }
    }

    override fun clearExcept(transferId: String?) {
        if (prefs.getString(KEY_TRANSFER, null) != transferId) prefs.edit { remove(KEY_TRANSFER); remove(KEY_PEER) }
    }

    private companion object {
        const val KEY_TRANSFER = "tama_link_visit_transfer"
        const val KEY_PEER = "tama_link_visit_peer"
    }
}

/** Signs requests to one paired peer. */
class PairedSigner(
    val deviceId: String,
    private val key: ByteArray,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Headers for a request; [nonce] is fixed only by callers that must correlate the reply. */
    fun headers(
        method: String, path: String, bodySha256: String, nonce: String = UUID.randomUUID().toString()
    ): PairedAuthHeaders = PairedAuth.sign(key, deviceId, method, path, bodySha256, clock(), nonce)

    /** True when a reply carries a signature over [bodySha256] made with the shared key for this request [nonce]. */
    fun replyValid(h: PairedAuthHeaders?, path: String, nonce: String, bodySha256: String, peerDeviceId: String): Boolean {
        if (h == null || h.nonce != nonce || h.deviceId != peerDeviceId) return false
        val expected = PairedAuth.mac(key, REPLY_METHOD, path, h.timestamp, nonce, bodySha256)
        return java.security.MessageDigest.isEqual(
            h.signature.lowercase().toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8)
        )
    }

    companion object {
        const val REPLY_METHOD = "REPLY"
        /** Body hash that binds /upload and /commit auth to their transfer. */
        fun transferBodyHash(transferId: String): String = PairedAuth.sha256Hex(transferId.toByteArray(Charsets.UTF_8))
    }
}

/** Non-loopback interface addresses (Wi-Fi, mobile, tun/wg VPN) this device can be reached on. */
object LinkAddresses {
    fun local(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress && !it.isMulticastAddress }
            .filter { it is Inet4Address || it is Inet6Address }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .distinct()
    }.getOrDefault(emptyList())
}
