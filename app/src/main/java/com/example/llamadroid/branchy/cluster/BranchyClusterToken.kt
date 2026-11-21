package com.example.llamadroid.branchy.cluster

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.security.SecureRandom

/**
 * The shared cluster token: 32 random bytes as 64 lowercase hex characters, generated on the main device and
 * typed (or pasted) on every helper. It lives in the `branchy_cluster` preferences and reaches the native process
 * only through a 0600 file, never through argv.
 */
object BranchyClusterToken {
    private const val PREFS = "branchy_cluster"
    private const val KEY_TOKEN = "token"
    private const val TOKEN_BYTES = 32
    private const val QR_PREFIX = "adt-branchy-token:"

    // region pure

    /** A fresh random token. */
    fun generateHex(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Whether [token] is exactly 64 hex characters (case-insensitive). */
    fun isValid(token: String?): Boolean =
        token != null && token.length == TOKEN_BYTES * 2 && token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** Text encoded in the QR shown on the main device. */
    fun qrPayload(token: String): String = QR_PREFIX + token

    /**
     * Extracts a valid lowercase token from user input: bare hex, or the QR payload, with surrounding whitespace
     * ignored. Returns null for anything else.
     */
    fun parseInput(text: String?): String? {
        val t = text?.trim()?.removePrefix(QR_PREFIX)?.trim()?.lowercase() ?: return null
        return t.takeIf { isValid(it) }
    }

    // endregion

    /** The saved token, or null when none was generated or entered yet. */
    fun get(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)?.takeIf { isValid(it) }

    /** The saved token, generating and saving one first when there is none (main device). */
    fun getOrCreate(context: Context): String = get(context) ?: generate(context)

    /** Replaces the saved token with a fresh one and returns it. Helpers must then enter the new token. */
    fun generate(context: Context): String {
        val token = generateHex()
        prefs(context).edit { putString(KEY_TOKEN, token) }
        return token
    }

    /** Saves a token entered on a helper; returns false (and keeps the old one) when [input] is not valid. */
    fun set(context: Context, input: String): Boolean {
        val token = parseInput(input) ?: return false
        prefs(context).edit { putString(KEY_TOKEN, token) }
        return true
    }

    fun clear(context: Context) {
        prefs(context).edit { remove(KEY_TOKEN) }
    }

    /**
     * Writes the current token (created when missing) to `filesDir/branchy/cluster/token` with owner-only
     * permissions and returns the file, for the native `--token-file`.
     */
    fun writeTokenFile(context: Context): File {
        val dir = File(context.filesDir, "branchy/cluster").apply { mkdirs() }
        val file = File(dir, "token")
        // Create empty, lock down, then write, so the secret is never in a world-readable file.
        if (!file.exists()) file.createNewFile()
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.writeText(getOrCreate(context))
        return file
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
