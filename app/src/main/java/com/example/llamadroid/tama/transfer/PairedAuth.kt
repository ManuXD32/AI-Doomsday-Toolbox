package com.example.llamadroid.tama.transfer

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Header values of a paired-device request. */
data class PairedAuthHeaders(val deviceId: String, val timestamp: Long, val nonce: String, val signature: String)

enum class PairedAuthResult { OK, MALFORMED, UNKNOWN_DEVICE, BAD_SIGNATURE, SKEWED, REPLAY }

/**
 * Request signing for calls between paired devices (no 6-digit code): HMAC-SHA256 with the pairing
 * key over `METHOD|path|timestamp|nonce|bodySha256`.
 */
object PairedAuth {
    const val HEADER_DEVICE = "X-Adt-Device"
    const val HEADER_TIMESTAMP = "X-Adt-Timestamp"
    const val HEADER_NONCE = "X-Adt-Nonce"
    const val HEADER_SIGNATURE = "X-Adt-Signature"
    const val MAX_SKEW_MS = 5 * 60_000L

    /** SHA-256 of an empty body. */
    val EMPTY_BODY_SHA256: String = sha256Hex(ByteArray(0))

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sign(
        key: ByteArray, deviceId: String, method: String, path: String, bodySha256: String,
        timestamp: Long, nonce: String
    ): PairedAuthHeaders = PairedAuthHeaders(
        deviceId, timestamp, nonce, mac(key, method, path, timestamp, nonce, bodySha256)
    )

    internal fun mac(key: ByteArray, method: String, path: String, timestamp: Long, nonce: String, bodySha256: String): String {
        val canonical = listOf(method.uppercase(), path, timestamp.toString(), nonce, bodySha256.lowercase()).joinToString("|")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(canonical.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/**
 * Server-side check of [PairedAuthHeaders]. [keyFor] resolves a device id to its pairing key; the
 * nonce cache is an in-memory bounded LRU (a restart only reopens the +-5 minute window, which the
 * timestamp check still limits).
 */
class PairedAuthVerifier(
    private val keyFor: (String) -> ByteArray?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxSkewMs: Long = PairedAuth.MAX_SKEW_MS,
    private val maxNonces: Int = 2048
) {
    private val seen = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > maxNonces
    }

    fun verify(headers: PairedAuthHeaders, method: String, path: String, bodySha256: String): PairedAuthResult {
        if (headers.deviceId.isEmpty() || headers.nonce.isEmpty() || headers.nonce.length > 128 ||
            headers.signature.isEmpty()
        ) return PairedAuthResult.MALFORMED
        val key = keyFor(headers.deviceId) ?: return PairedAuthResult.UNKNOWN_DEVICE
        val expected = PairedAuth.mac(key, method, path, headers.timestamp, headers.nonce, bodySha256)
        val ok = MessageDigest.isEqual(
            headers.signature.lowercase().toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8)
        )
        if (!ok) return PairedAuthResult.BAD_SIGNATURE
        val now = clock()
        if (kotlin.math.abs(now - headers.timestamp) > maxSkewMs) return PairedAuthResult.SKEWED
        synchronized(seen) {
            val id = "${headers.deviceId}:${headers.nonce}"
            if (seen.containsKey(id)) return PairedAuthResult.REPLAY
            seen[id] = now
        }
        return PairedAuthResult.OK
    }
}
