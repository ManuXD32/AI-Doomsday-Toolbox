package com.example.llamadroid.tama.transfer

import com.example.llamadroid.tama.db.TamaTransferPeerDao
import com.example.llamadroid.tama.db.TamaTransferPeerEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets

enum class PeerAddressSource { LAN, MANUAL, ADVERTISED }

/** A way to reach a peer. [port] 0 means "port not known yet". */
@Serializable
data class PeerAddress(
    val host: String,
    val port: Int,
    val source: PeerAddressSource = PeerAddressSource.LAN,
    val lastOkAt: Long = 0L
)

/** A remembered device; the pairing key is never exposed here, use [PetTransferPeerStore.keyFor]. */
data class PairedPeer(
    val deviceId: String,
    val displayName: String,
    val addresses: List<PeerAddress>,
    val lastSeenAt: Long,
    val createdAt: Long
)

/** Paired devices over [TamaTransferPeerDao]; pairing keys are wrapped by [keyStore] at rest. */
class PetTransferPeerStore(
    private val dao: TamaTransferPeerDao,
    private val keyStore: PeerKeyStore,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PeerAddress.serializer())
    private val mutex = Mutex()

    val peers: Flow<List<PairedPeer>> get() = dao.observeAll().map { rows -> rows.map(::toPeer) }

    suspend fun list(): List<PairedPeer> = dao.getAll().map(::toPeer)

    suspend fun get(deviceId: String): PairedPeer? = dao.get(deviceId)?.let(::toPeer)

    /** Pairing key for [deviceId], or null when unknown or undecryptable. */
    suspend fun keyFor(deviceId: String): ByteArray? {
        val row = dao.get(deviceId) ?: return null
        return keyStore.unwrap(WrappedKey(row.wrappedKey, row.keyIv), aad(deviceId))
    }

    /** Inserts or refreshes a peer after a successful pairing; a new [key] replaces the old one. */
    suspend fun recordPairing(
        deviceId: String, displayName: String, key: ByteArray, addresses: List<PeerAddress>
    ) = mutex.withLock {
        if (deviceId.isBlank()) return@withLock
        val now = clock()
        val existing = dao.get(deviceId)
        val wrapped = keyStore.wrap(key, aad(deviceId))
        val merged = merge(existing?.let { decode(it.addressesJson) }.orEmpty(), addresses)
        dao.upsert(
            TamaTransferPeerEntity(
                deviceId = deviceId,
                displayName = displayName.ifBlank { existing?.displayName.orEmpty() },
                wrappedKey = wrapped.cipherText,
                keyIv = wrapped.iv,
                addressesJson = json.encodeToString(serializer, merged),
                lastSeenAt = now,
                createdAt = existing?.createdAt ?: now
            )
        )
    }

    /** Merges [addresses] into a known peer (no-op for unknown ids) and refreshes lastSeenAt. */
    suspend fun mergeAddresses(deviceId: String, addresses: List<PeerAddress>) = mutex.withLock {
        val row = dao.get(deviceId) ?: return@withLock
        val merged = merge(decode(row.addressesJson), addresses)
        dao.upsert(row.copy(addressesJson = json.encodeToString(serializer, merged), lastSeenAt = clock()))
    }

    /** Local display name only; the name the peer announces is not touched. Blank names are ignored. */
    suspend fun rename(deviceId: String, displayName: String) = mutex.withLock {
        val name = displayName.trim().take(MAX_NAME)
        val row = dao.get(deviceId) ?: return@withLock
        if (name.isNotEmpty()) dao.upsert(row.copy(displayName = name))
    }

    /** Rewrites a known peer's address list (used by the manual add/edit/remove UI); no-op for unknown ids. */
    suspend fun updateAddresses(deviceId: String, transform: (List<PeerAddress>) -> List<PeerAddress>) =
        mutex.withLock {
            val row = dao.get(deviceId) ?: return@withLock
            val next = transform(decode(row.addressesJson))
            dao.upsert(row.copy(addressesJson = json.encodeToString(serializer, next)))
        }

    suspend fun remove(deviceId: String) = mutex.withLock { dao.delete(deviceId) }

    private fun toPeer(e: TamaTransferPeerEntity) =
        PairedPeer(e.deviceId, e.displayName, decode(e.addressesJson), e.lastSeenAt, e.createdAt)

    private fun decode(text: String): List<PeerAddress> =
        runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())

    private fun aad(deviceId: String) = deviceId.toByteArray(StandardCharsets.UTF_8)

    companion object {
        const val MAX_ADDRESSES = 8
        const val MAX_NAME = 40

        /**
         * Same host+port collapses to the newest data; most recently working first; capped at
         * [MAX_ADDRESSES]. A MANUAL address keeps its source and is never evicted by the cap, and a
         * known port replaces an earlier "port unknown" (0) entry for the same host.
         */
        internal fun merge(old: List<PeerAddress>, added: List<PeerAddress>): List<PeerAddress> {
            val byKey = LinkedHashMap<Pair<String, Int>, PeerAddress>()
            old.forEach { byKey[it.host to it.port] = it }
            added.filter { it.host.isNotBlank() }.forEach { a ->
                val prev = byKey[a.host to a.port]
                val source = if (prev?.source == PeerAddressSource.MANUAL) PeerAddressSource.MANUAL else a.source
                byKey[a.host to a.port] = a.copy(source = source, lastOkAt = maxOf(a.lastOkAt, prev?.lastOkAt ?: 0L))
                if (a.port > 0) {
                    val unknown = byKey[a.host to 0]
                    if (unknown != null && unknown.source != PeerAddressSource.MANUAL) byKey.remove(a.host to 0)
                }
            }
            val sorted = byKey.values.sortedByDescending { it.lastOkAt }
            if (sorted.size <= MAX_ADDRESSES) return sorted
            val manual = sorted.count { it.source == PeerAddressSource.MANUAL }
            val room = (MAX_ADDRESSES - manual).coerceAtLeast(0)
            var kept = 0
            return sorted.filter { a ->
                a.source == PeerAddressSource.MANUAL || (kept < room).also { if (it) kept++ }
            }
        }
    }
}
