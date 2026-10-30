package com.example.llamadroid.tama.transfer

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Pairing-code authentication: receiver shows a 6-digit code, sender proves knowledge via HMAC. */
object PetTransferAuth {
    private const val CODE_DIGITS = 6
    private const val PBKDF2_ITERATIONS = 20_000
    private const val KEY_BITS = 256
    private val SALT = "adt-pet-transfer-v1".toByteArray(StandardCharsets.UTF_8)

    fun generateCode(random: SecureRandom = SecureRandom()): String =
        random.nextInt(1_000_000).toString().padStart(CODE_DIGITS, '0')

    fun isWellFormedCode(code: String): Boolean =
        code.length == CODE_DIGITS && code.all { it in '0'..'9' }

    internal fun deriveKey(code: String): ByteArray {
        val spec = PBEKeySpec(code.toCharArray(), SALT, PBKDF2_ITERATIONS, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Length-prefixed fields so no two distinct Hellos share a canonical form. */
    internal fun canonical(h: Hello): ByteArray {
        val fields = listOf(
            h.transferId, h.petId, h.petName, h.species, h.stage, h.zipBytes.toString(),
            h.zipSha256, h.bundleVersion.toString(), h.originDeviceId, h.senderDeviceName,
            h.isReturn.toString()
        )
        val sb = StringBuilder()
        fields.forEach { sb.append(it.toByteArray(StandardCharsets.UTF_8).size).append(':').append(it).append('\n') }
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun hmac(code: String, hello: Hello): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(deriveKey(code), "HmacSHA256"))
        return mac.doFinal(canonical(hello))
    }

    fun sign(hello: Hello, code: String): Hello = hello.copy(hmac = toHex(hmac(code, hello)))

    fun verify(hello: Hello, code: String): Boolean {
        val provided = fromHex(hello.hmac) ?: return false
        return MessageDigest.isEqual(provided, hmac(code, hello))
    }

    private fun proofMac(code: String, transferId: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(deriveKey(code), "HmacSHA256"))
        return mac.doFinal("proof:$transferId".toByteArray(StandardCharsets.UTF_8))
    }

    /** Per-request proof of the pairing code, sent in the `X-Adt-Auth` header after /hello. */
    fun proof(transferId: String, code: String): String = toHex(proofMac(code, transferId))

    fun verifyProof(transferId: String, code: String, provided: String?): Boolean {
        val bytes = provided?.let(::fromHex) ?: return false
        return MessageDigest.isEqual(bytes, proofMac(code, transferId))
    }

    /** Length-prefixed tag over [parts] with the code-derived key; authenticates pairing fields. */
    internal fun pairTag(code: String, parts: List<String>): String {
        val sb = StringBuilder("pair\n")
        parts.forEach { sb.append(it.toByteArray(StandardCharsets.UTF_8).size).append(':').append(it).append('\n') }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(deriveKey(code), "HmacSHA256"))
        return toHex(mac.doFinal(sb.toString().toByteArray(StandardCharsets.UTF_8)))
    }

    internal fun verifyPairTag(code: String, parts: List<String>, provided: String): Boolean {
        val bytes = fromHex(provided) ?: return false
        val expected = fromHex(pairTag(code, parts)) ?: return false
        return MessageDigest.isEqual(bytes, expected)
    }

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun fromHex(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
