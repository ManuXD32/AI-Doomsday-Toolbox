package com.example.llamadroid.tama.transfer

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A pairing key encrypted at rest: AES-GCM [cipherText] and its [iv]. */
class WrappedKey(val cipherText: ByteArray, val iv: ByteArray)

/** Encrypts pairing keys before they reach the database. [aad] binds the blob to its device id. */
interface PeerKeyStore {
    fun wrap(plain: ByteArray, aad: ByteArray): WrappedKey

    /** Null when the blob cannot be decrypted (key lost/tampered); callers must treat the peer as unpaired. */
    fun unwrap(wrapped: WrappedKey, aad: ByteArray): ByteArray?
}

private const val GCM_TAG_BITS = 128
private const val TRANSFORMATION = "AES/GCM/NoPadding"

/** Wraps with a non-exportable AES-256-GCM key in the Android Keystore. */
class AndroidKeystorePeerKeyStore(private val alias: String = "adt_tama_peer_wrap_v1") : PeerKeyStore {
    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun wrap(plain: ByteArray, aad: ByteArray): WrappedKey {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // the Keystore picks the IV; randomised encryption is required
        cipher.updateAAD(aad)
        return WrappedKey(cipher.doFinal(plain), cipher.iv)
    }

    override fun unwrap(wrapped: WrappedKey, aad: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, wrapped.iv))
        cipher.updateAAD(aad)
        cipher.doFinal(wrapped.cipherText)
    }.getOrNull()
}

/** Software AES-GCM with a random in-memory key: test double with identical semantics. */
class InMemoryPeerKeyStore(keyBytes: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }) : PeerKeyStore {
    private val key = SecretKeySpec(keyBytes, "AES")

    override fun wrap(plain: ByteArray, aad: ByteArray): WrappedKey {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return WrappedKey(cipher.doFinal(plain), iv)
    }

    override fun unwrap(wrapped: WrappedKey, aad: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, wrapped.iv))
        cipher.updateAAD(aad)
        cipher.doFinal(wrapped.cipherText)
    }.getOrNull()
}
