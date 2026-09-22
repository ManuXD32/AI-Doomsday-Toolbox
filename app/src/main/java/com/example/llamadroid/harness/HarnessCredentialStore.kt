package com.example.llamadroid.harness

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android-owned backing for both native and Harness credential-provider operations. */
class HarnessCredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("harness_credentials", Context.MODE_PRIVATE)

    @Synchronized
    fun describe(reference: String): JSONObject {
        validateReference(reference)
        return JSONObject().put("configured", preferences.contains("ref:$reference"))
            .put("source", "android-keystore").put("writable", true)
    }

    /** Internal bridge use only; no native read model or diagnostics ever contains this value. */
    @Synchronized
    internal fun resolve(reference: String): String? {
        validateReference(reference)
        return read("ref:$reference")?.getString("value")
    }

    @Synchronized
    fun set(reference: String, secret: String) {
        validateReference(reference)
        require(secret.isNotBlank() && secret.length <= MAX_SECRET_LENGTH)
        write("ref:$reference", JSONObject().put("value", secret))
    }

    @Synchronized
    fun unset(reference: String) {
        validateReference(reference)
        commitPreferences { remove("ref:$reference") }
    }

    /** Clears all Harness-owned provider/API credentials during a full runtime reinstall. */
    @Synchronized
    fun clearAll() {
        commitPreferences { clear() }
    }

    @Synchronized
    internal fun readRecord(key: String): JSONObject? {
        validateRecordKey(key)
        return read("record:$key")
    }

    @Synchronized
    fun describeRecord(key: String): JSONObject {
        val record = readRecord(key)?.optJSONObject("record")
        return JSONObject().put("configured", record != null).put("writable", true).apply {
            record?.optString("kind")?.let { put("kind", it) }
        }
    }

    @Synchronized
    fun listRecords(): JSONArray = JSONArray().apply {
        preferences.all.keys.filter { it.startsWith("record:") && !it.startsWith("record:adt-ssh/") && !it.startsWith("record:adt-ssh-cleanup/") }.sorted().forEach { storedKey ->
            val key = storedKey.removePrefix("record:")
            put(describeRecord(key).put("key", key))
        }
    }

    /** A revision guards native edits against an in-flight provider token refresh. */
    @Synchronized
    internal fun replaceRecord(key: String, expectedRevision: String?, record: JSONObject?): JSONObject? = synchronized(storageLock) {
        validateRecordKey(key)
        val current = readRecord(key)
        val revision = current?.getString("revision")
        check(revision == expectedRevision) { "CREDENTIAL_REVISION_CONFLICT" }
        if (record == null) {
            commitPreferences { remove("record:$key") }
            return null
        }
        require(record.optString("kind") in setOf("api-key", "grant"))
        require(record.toString().length <= MAX_SECRET_LENGTH)
        val value = JSONObject().put("revision", UUID.randomUUID().toString()).put("record", record)
        write("record:$key", value)
        return value
    }

    private fun read(key: String): JSONObject? {
        val encoded = preferences.getString(key, null) ?: return null
        val envelope = JSONObject(encoded)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, decode(envelope.getString("iv"))))
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        return JSONObject(String(cipher.doFinal(decode(envelope.getString("ciphertext"))), Charsets.UTF_8))
    }

    private fun write(key: String, value: JSONObject) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        cipher.updateAAD(key.toByteArray(Charsets.UTF_8))
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        try {
            val encrypted = cipher.doFinal(bytes)
            val envelope = JSONObject().put("iv", encode(cipher.iv)).put("ciphertext", encode(encrypted))
            commitPreferences { putString(key, envelope.toString()) }
        } finally { bytes.fill(0) }
    }

    /**
     * Credential and revision writes must be durable before the caller continues.
     * AndroidX KTX `edit` discards the Boolean returned by `commit`, so this
     * narrow suppression keeps the checked synchronous failure semantics.
     */
    @SuppressLint("UseKtx")
    private fun commitPreferences(action: android.content.SharedPreferences.Editor.() -> Unit) {
        val editor = preferences.edit()
        action(editor)
        check(editor.commit())
    }

    private fun encryptionKey(): SecretKey = synchronized(storageLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    private fun validateReference(value: String) = require(value.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,255}")))
    private fun validateRecordKey(value: String) = require(value.length in 3..512 && value.contains('/') &&
        value.none { it.isISOControl() } && !value.startsWith('/') && !value.endsWith('/'))
    private fun encode(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        // Native and bridge callers can construct separate store facades in one app process.
        val storageLock = Any()
        const val KEY_ALIAS = "adt.harness.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAX_SECRET_LENGTH = 256 * 1024
    }
}
