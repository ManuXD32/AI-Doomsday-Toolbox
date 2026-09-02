package com.example.llamadroid.sd

import com.example.llamadroid.data.db.ModelEntity
import java.io.File
import java.util.LinkedHashMap

/**
 * Small bounded in-memory cache for header inspections.
 *
 * The cache key includes the path, file size, modification time, inspector
 * version and the bounded header/descriptor fingerprint. No model payload is
 * copied into the cache. A caller can clear it after replacing an artifact.
 */
class SdArtifactInspectionCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val inspector: SdArtifactInspector = SdArtifactInspector()
) {
    private data class Identity(
        val path: String,
        val sizeBytes: Long,
        val modifiedAtMillis: Long,
        val inspectionVersion: Int
    )

    private data class CacheKey(
        val identity: Identity,
        val headerFingerprint: String?
    )

    private val entries = object : LinkedHashMap<CacheKey, SdArtifactInspection>(
        maxEntries.coerceAtLeast(1),
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, SdArtifactInspection>?): Boolean =
            size > maxEntries.coerceAtLeast(1)
    }

    @Synchronized
    fun get(
        file: File,
        inspectionVersion: Int = SdArtifactInspection.CURRENT_INSPECTION_VERSION,
        headerFingerprint: String? = null
    ): SdArtifactInspection? {
        val identity = identityFor(file, inspectionVersion) ?: return null
        if (headerFingerprint != null) {
            return entries[CacheKey(identity, headerFingerprint)]
        }
        return entries.entries
            .firstOrNull { it.key.identity == identity }
            ?.value
            ?.also { entries[CacheKey(identity, it.headerFingerprint)] = it }
    }

    @Synchronized
    fun put(file: File, inspection: SdArtifactInspection): SdArtifactInspection {
        val identity = identityFor(file, inspection.inspectionVersion) ?: return inspection
        entries[CacheKey(identity, inspection.headerFingerprint)] = inspection
        return inspection
    }

    /** Inspect once for a stable file identity, retaining only bounded facts. */
    fun inspect(
        file: File,
        configuredRole: SdArtifactRole? = null,
        force: Boolean = false
    ): SdArtifactInspection {
        if (!force) get(file)?.let { cached ->
            // A configured role is evidence about the caller's intent, not a
            // reason to re-read a potentially multi-gigabyte artifact. Keep
            // the structural facts and update only that separate field.
            return if (cached.configuredRole == configuredRole || configuredRole == null) {
                cached
            } else {
                put(file, cached.copy(configuredRole = configuredRole))
            }
        }
        return put(file, inspector.inspect(file, configuredRole))
    }

    /**
     * Sequential, bounded batch inspection for a model-management “detect
     * all” action.  Files are intentionally processed one at a time so a
     * library containing several multi-gigabyte models cannot create a burst
     * of header buffers or native loads.
     */
    fun inspectAll(
        files: Iterable<File>,
        configuredRole: (File) -> SdArtifactRole? = { null },
        force: Boolean = false
    ): List<SdArtifactInspection> = files.map { file ->
        inspect(file, configuredRole(file), force)
    }

    fun inspect(path: String, configuredRole: SdArtifactRole? = null, force: Boolean = false): SdArtifactInspection =
        inspect(File(path), configuredRole, force)

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun invalidate(file: File) {
        val path = canonicalPath(file)
        entries.keys.removeAll { it.identity.path == path }
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun identityFor(file: File, inspectionVersion: Int): Identity? {
        if (!file.exists() || !file.isFile) return null
        return Identity(
            path = canonicalPath(file),
            sizeBytes = file.length(),
            modifiedAtMillis = file.lastModified(),
            inspectionVersion = inspectionVersion
        )
    }

    private fun canonicalPath(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    companion object {
        const val DEFAULT_MAX_ENTRIES = 64
    }
}

/** Process-wide cache used by lightweight callers that do not own a lifecycle. */
object SdInspectionCache {
    private val delegate = SdArtifactInspectionCache()

    fun get(
        file: File,
        inspectionVersion: Int = SdArtifactInspection.CURRENT_INSPECTION_VERSION,
        headerFingerprint: String? = null
    ): SdArtifactInspection? = delegate.get(file, inspectionVersion, headerFingerprint)

    fun inspect(file: File, configuredRole: SdArtifactRole? = null, force: Boolean = false): SdArtifactInspection =
        delegate.inspect(file, configuredRole, force)

    fun inspect(path: String, configuredRole: SdArtifactRole? = null, force: Boolean = false): SdArtifactInspection =
        delegate.inspect(path, configuredRole, force)

    fun inspectAll(
        files: Iterable<File>,
        configuredRole: (File) -> SdArtifactRole? = { null },
        force: Boolean = false
    ): List<SdArtifactInspection> = delegate.inspectAll(files, configuredRole, force)

    fun put(file: File, inspection: SdArtifactInspection): SdArtifactInspection = delegate.put(file, inspection)

    fun invalidate(file: File) = delegate.invalidate(file)

    fun clear() = delegate.clear()

    fun size(): Int = delegate.size()
}

/**
 * Whether a row needs lazy inspection. Existing rows from schema 110 have
 * version zero and therefore always return true without touching the file.
 */
fun ModelEntity.needsSdArtifactInspection(
    file: File,
    currentVersion: Int = SdArtifactInspection.CURRENT_INSPECTION_VERSION
): Boolean {
    if (sdInspectionVersion < currentVersion || sdInspectionJson.isNullOrBlank()) return true
    val inspection = sdArtifactInspection() ?: return true
    if (inspection.fileSizeBytes != file.length() ||
        inspection.modifiedAtMillis != file.lastModified() ||
        inspection.inspectionVersion < currentVersion
    ) return true
    // A persisted summary can outlive the process-local cache. When the cache
    // has a same-stat entry with a different bounded fingerprint (for example
    // a replacement artifact preserving size and mtime), force a re-read.
    return SdInspectionCache.get(
        file = file,
        inspectionVersion = currentVersion,
        headerFingerprint = inspection.headerFingerprint
    ) == null
}
