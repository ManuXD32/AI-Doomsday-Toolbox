package com.example.llamadroid.tama.transfer

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Outcome of a successful pairing handshake: the shared key plus who it is shared with. */
class PairingResult(val peerDeviceId: String, val peerName: String, val key: ByteArray)

/** Ephemeral ECDH key pair for one transfer; [publicB64] is the X.509 public key, Base64. */
class PairingKeys(val privateKey: PrivateKey, val publicB64: String)

/**
 * Pairing key agreement piggy-backed on a code-authenticated transfer. Public keys travel in
 * [Hello]/[HelloReply] and are authenticated with a tag derived from the 6-digit code, so a
 * man-in-the-middle without the code cannot substitute its own key. The tags live in separate
 * fields (`pairMac`) so peers that predate pairing still verify the unchanged [Hello.hmac].
 */
object PetPairing {
    private const val INFO = "adt-pet-pair-v1"
    private const val KEY_BYTES = 32

    fun generate(): PairingKeys {
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val pair: KeyPair = gen.generateKeyPair()
        return PairingKeys(pair.private, Base64.getEncoder().encodeToString(pair.public.encoded))
    }

    /** Parses a peer public key; null for anything that is not a P-256 EC key. */
    internal fun decodePublic(b64: String): ECPublicKey? = runCatching {
        val bytes = Base64.getDecoder().decode(b64)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)) as ECPublicKey
        key.takeIf { it.params.order.bitLength() == 256 }
    }.getOrNull()

    /** HKDF-SHA256 (RFC 5869). */
    internal fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = extract.doFinal(secret)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var block = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            expand.update(block)
            expand.update(info)
            expand.update(counter.toByte())
            block = expand.doFinal()
            val n = minOf(block.size, length - pos)
            System.arraycopy(block, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    /**
     * 32-byte pairing key from our private key and the peer's public key; the salt is both device
     * ids sorted, so each side derives the same key. Null when the peer key is invalid.
     */
    fun deriveKey(own: PairingKeys, peerPublicB64: String, ownDeviceId: String, peerDeviceId: String): ByteArray? {
        val peerKey = decodePublic(peerPublicB64) ?: return null
        val secret = runCatching {
            KeyAgreement.getInstance("ECDH").run {
                init(own.privateKey)
                doPhase(peerKey, true)
                generateSecret()
            }
        }.getOrNull() ?: return null
        val salt = listOf(ownDeviceId, peerDeviceId).sorted().joinToString("\n").toByteArray(StandardCharsets.UTF_8)
        return hkdf(secret, salt, INFO.toByteArray(StandardCharsets.UTF_8), KEY_BYTES)
    }

    // ---- Wire fields (code-authenticated) ----

    /** Sender: adds this device's id, public key and the code-derived tag to [hello] (after signing). */
    fun attachToHello(hello: Hello, code: String, keys: PairingKeys, deviceId: String): Hello {
        val tag = PetTransferAuth.pairTag(code, helloParts(hello.transferId, deviceId, keys.publicB64))
        return hello.copy(deviceId = deviceId, pairPub = keys.publicB64, pairMac = tag)
    }

    /** Receiver: true when [hello] carries a pairing offer whose tag matches the code. */
    fun helloOfferValid(hello: Hello, code: String): Boolean =
        hello.pairPub.isNotEmpty() && hello.deviceId.isNotEmpty() &&
            PetTransferAuth.verifyPairTag(code, helloParts(hello.transferId, hello.deviceId, hello.pairPub), hello.pairMac)

    /** Receiver: the accepted reply carrying our public key; its tag also binds the sender's key. */
    fun answer(hello: Hello, code: String, deviceId: String, deviceName: String, keys: PairingKeys): HelloReply =
        HelloReply(
            accepted = true,
            deviceId = deviceId,
            deviceName = deviceName,
            pairPub = keys.publicB64,
            pairMac = PetTransferAuth.pairTag(code, replyParts(hello, deviceId, deviceName, keys.publicB64))
        )

    /** Sender: verifies the reply's tag and derives the key; null means "no pairing", never an error. */
    fun finishAsSender(
        sent: Hello, reply: HelloReply, code: String, own: PairingKeys, ownDeviceId: String
    ): PairingResult? {
        if (!reply.accepted || reply.pairPub.isEmpty() || reply.deviceId.isEmpty()) return null
        val parts = replyParts(sent, reply.deviceId, reply.deviceName, reply.pairPub)
        if (!PetTransferAuth.verifyPairTag(code, parts, reply.pairMac)) return null
        val key = deriveKey(own, reply.pairPub, ownDeviceId, reply.deviceId) ?: return null
        return PairingResult(reply.deviceId, reply.deviceName, key)
    }

    /** Receiver: derives the key for a validated offer; null when the offer is absent or invalid. */
    fun finishAsReceiver(hello: Hello, code: String, own: PairingKeys, ownDeviceId: String): PairingResult? {
        if (!helloOfferValid(hello, code)) return null
        val key = deriveKey(own, hello.pairPub, ownDeviceId, hello.deviceId) ?: return null
        return PairingResult(hello.deviceId, hello.senderDeviceName, key)
    }

    private fun helloParts(transferId: String, deviceId: String, pub: String) =
        listOf("hello", transferId, deviceId, pub)

    private fun replyParts(sent: Hello, deviceId: String, deviceName: String, pub: String) =
        listOf("reply", sent.transferId, deviceId, deviceName, pub, sent.pairPub)
}
