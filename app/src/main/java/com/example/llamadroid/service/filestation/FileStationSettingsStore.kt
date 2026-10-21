package com.example.llamadroid.service.filestation

import androidx.core.net.toUri
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.llamadroid.service.AiServerAuth
import com.example.llamadroid.service.filestation.http.PasswordCredentials
import com.example.llamadroid.util.AIConstants
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent File Station settings in their own prefs file (`file_station`). Migrates the legacy
 * `file_server_folder_uri` / `file_server_port` keys from the app settings prefs exactly once.
 */
class FileStationSettingsStore(
    context: Context,
    legacyPrefsName: String = LEGACY_PREFS_NAME,
) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()
    private val lock = Any()
    private val _settings: MutableStateFlow<FileStationSettings>
    val settings: StateFlow<FileStationSettings>

    init {
        migrateLegacy(context.applicationContext.getSharedPreferences(legacyPrefsName, Context.MODE_PRIVATE))
        if (!prefs.contains(KEY_SECRET)) rotateSecret()
        _settings = MutableStateFlow(read())
        settings = _settings.asStateFlow()
    }

    private fun migrateLegacy(legacy: SharedPreferences) {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val uri = legacy.getString(LEGACY_URI, null)?.takeIf { it.isNotBlank() }
        val port = if (legacy.contains(LEGACY_PORT)) legacy.getInt(LEGACY_PORT, DEFAULT_PORT) else null
        prefs.edit {
            if (uri != null && !prefs.contains(KEY_VOLUMES)) {
                val label = uri.toUri().lastPathSegment?.substringAfterLast(':')
                    ?.takeIf { it.isNotBlank() } ?: ROOT_ID
                putString(KEY_VOLUMES, encode(listOf(VolumeConfig(ROOT_ID, label, uri, readOnly = false, isRoot = true))))
            }
            if (port != null && !prefs.contains(KEY_PORT) && port in PORT_RANGE) putInt(KEY_PORT, port)
            putBoolean(KEY_MIGRATED, true)
        }
    }

    private fun read(): FileStationSettings = FileStationSettings(
        volumes = decode(prefs.getString(KEY_VOLUMES, null)),
        port = prefs.getInt(KEY_PORT, DEFAULT_PORT),
        lanVisible = prefs.getBoolean(KEY_LAN, true),
        hasPassword = prefs.getString(KEY_PW_HASH, null) != null,
        trashRetentionDays = prefs.getInt(KEY_TRASH_DAYS, 30),
        showHidden = prefs.getBoolean(KEY_HIDDEN, false),
    )

    private fun refresh() { _settings.value = read() }

    /** Replaces all volumes; guarantees at most one root (the first flagged one). */
    fun setVolumes(volumes: List<VolumeConfig>) = synchronized(lock) {
        var rootSeen = false
        val clean = volumes.map { v ->
            val root = v.isRoot && !rootSeen
            if (root) rootSeen = true
            v.copy(isRoot = root)
        }
        prefs.edit { putString(KEY_VOLUMES, encode(clean)) }
        refresh()
    }

    fun upsertVolume(volume: VolumeConfig) {
        val current = _settings.value.volumes
        val updated = if (current.any { it.id == volume.id }) current.map { if (it.id == volume.id) volume else it }
        else current + volume
        setVolumes(if (volume.isRoot) updated.map { if (it.id == volume.id) it else it.copy(isRoot = false) } else updated)
    }

    fun removeVolume(id: String) = setVolumes(_settings.value.volumes.filterNot { it.id == id })

    /** Unique id for an extra folder (`[a-z0-9_-]+`). */
    fun newVolumeId(): String {
        val used = _settings.value.volumes.map { it.id }.toSet()
        while (true) {
            val id = "v" + ByteArray(3).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
            if (id !in used) return id
        }
    }

    fun setPort(port: Int) = synchronized(lock) { prefs.edit { putInt(KEY_PORT, port) }; refresh() }

    fun setLanVisible(value: Boolean) = synchronized(lock) { prefs.edit { putBoolean(KEY_LAN, value) }; refresh() }

    fun setTrashRetentionDays(days: Int) = synchronized(lock) {
        prefs.edit { putInt(KEY_TRASH_DAYS, days.coerceIn(0, 3650)) }; refresh()
    }

    fun setShowHidden(value: Boolean) = synchronized(lock) { prefs.edit { putBoolean(KEY_HIDDEN, value) }; refresh() }

    /** Sets or changes the password and rotates the session secret (signs everyone out). */
    fun setPassword(password: String) = synchronized(lock) {
        val salt = AiServerAuth.createSalt()
        prefs.edit {
            putString(KEY_PW_SALT, salt)
            putString(KEY_PW_HASH, AiServerAuth.hashPassword(password, salt))
        }
        rotateSecret()
        refresh()
    }

    fun clearPassword() = synchronized(lock) {
        prefs.edit { remove(KEY_PW_SALT); remove(KEY_PW_HASH) }
        rotateSecret()
        refresh()
    }

    fun verifyPassword(password: String): Boolean {
        val c = credentials() ?: return false
        return AiServerAuth.verifyPassword(password, c.salt, c.hash)
    }

    fun credentials(): PasswordCredentials? {
        val salt = prefs.getString(KEY_PW_SALT, null)
        val hash = prefs.getString(KEY_PW_HASH, null)
        return if (salt != null && hash != null) PasswordCredentials(salt, hash) else null
    }

    fun sessionSecret(): String = prefs.getString(KEY_SECRET, null) ?: rotateSecret()

    /** New random secret: invalidates every issued session cookie. */
    fun rotateSecret(): String = synchronized(lock) {
        val secret = AiServerAuth.createSessionToken()
        prefs.edit { putString(KEY_SECRET, secret) }
        secret
    }

    private fun encode(volumes: List<VolumeConfig>): String = JSONArray().also { arr ->
        volumes.forEach {
            arr.put(JSONObject().put("id", it.id).put("label", it.label).put("treeUri", it.treeUri)
                .put("readOnly", it.readOnly).put("isRoot", it.isRoot))
        }
    }.toString()

    private fun decode(json: String?): List<VolumeConfig> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id"); val uri = o.optString("treeUri")
                if (id.isBlank() || uri.isBlank()) null
                else VolumeConfig(id, o.optString("label", id), uri, o.optBoolean("readOnly"), o.optBoolean("isRoot"))
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        const val PREFS_NAME = "file_station"
        const val LEGACY_PREFS_NAME = "llamadroid_settings"
        const val ROOT_ID = "root"
        const val DEFAULT_PORT = AIConstants.Ports.FILE_SERVER
        val PORT_RANGE = 1024..65535
        private const val LEGACY_URI = "file_server_folder_uri"
        private const val LEGACY_PORT = "file_server_port"
        private const val KEY_MIGRATED = "legacy_migrated"
        private const val KEY_VOLUMES = "volumes"
        private const val KEY_PORT = "port"
        private const val KEY_LAN = "lan_visible"
        private const val KEY_PW_SALT = "pw_salt"
        private const val KEY_PW_HASH = "pw_hash"
        private const val KEY_SECRET = "session_secret"
        private const val KEY_TRASH_DAYS = "trash_retention_days"
        private const val KEY_HIDDEN = "show_hidden"
    }
}
