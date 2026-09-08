package com.example.llamadroid.data.model

import android.content.Context
import androidx.annotation.StringRes
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.isAudioTtsComponentType
import com.example.llamadroid.data.db.isStableAudioComponentType
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Shared, hash-pinned bundle metadata used by llama.cpp and ADetailer catalogs. */
data class CuratedBundleFile(
    val id: String,
    val repoId: String,
    val revision: String,
    val remotePath: String,
    val localFilename: String,
    val type: ModelType,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val strictSize: Boolean = false,
    val note: String = "",
    val downloadUrlOverride: String? = null,
    /** Stable role consumed by runtime adapters; never inferred from filename suffixes. */
    val componentRole: String? = null,
    /** Native speech family, when this file is an audio component. */
    val audioFamily: String? = null,
    /** Native speech language metadata, when the checkpoint fixes one. */
    val audioLanguage: String? = null,
    /** Optional semantic identity shared by equivalent files across bundles. */
    val sharedArtifactKey: String? = null
) {
    init {
        require(id.isNotBlank())
        require(repoId.count { it == '/' } == 1)
        require(revision.matches(Regex("[0-9a-f]{7,64}|main|v[0-9]+\\.[0-9]+\\.[0-9]+")))
        require(remotePath.isNotBlank() && !remotePath.startsWith('/'))
        require(File(localFilename).name == localFilename)
        require(sizeBytes > 0L)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(componentRole == null || componentRole.trim().isNotEmpty())
        require(audioFamily == null || audioFamily.trim().isNotEmpty())
        require(audioLanguage == null || audioLanguage.trim().isNotEmpty())
        require(sharedArtifactKey == null || sharedArtifactKey.trim().isNotEmpty())
    }

    val downloadUrl: String
        get() = downloadUrlOverride
            ?: "https://huggingface.co/$repoId/resolve/$revision/$remotePath"

    /** Stable source identity retained for provenance and diagnostics. */
    val sourceIdentity: String
        get() = "hf:$repoId@$revision:$remotePath"

    /** Stable content identity used for reuse and deletion protection. */
    val artifactIdentity: String
        get() = sharedArtifactKey?.trim()
            ?: "sha256:$sha256"

    /** Payload digest retained separately from source identity for verification. */
    val artifactDigest: String get() = sha256

    fun installedFilename(prefix: String): String {
        val cleanPrefix = sanitizeCuratedBundlePrefix(prefix)
        // Shared components use a digest-derived canonical name so a second
        // bundle reuses the already verified payload instead of downloading a
        // prefix-specific copy. Main model names retain their bundle prefix.
        if (sharedArtifactKey != null) {
            return "shared-${sha256.take(12)}-$localFilename"
        }
        return if (cleanPrefix.isBlank()) localFilename else "$cleanPrefix-$localFilename"
    }

    fun matchesInstalledFilename(filename: String): Boolean =
        filename == localFilename || filename.endsWith("-$localFilename")

    /**
     * Installed-state check used by bundle UI. Native audio files need both a
     * physical size and the persisted digest identity; a same-name row alone
     * is never sufficient because imports and interrupted/corrupt copies can
     * collide with a curated prefix.
     */
    fun matchesVerifiedInstalledModel(expectedFilename: String, model: ModelEntity): Boolean {
        if (model.filename != expectedFilename || model.type != type || !model.isDownloaded) return false
        val payload = File(model.path)
        if (!payload.isFile || payload.length() != sizeBytes) return false
        return !(type.isAudioTtsComponentType() || type.isStableAudioComponentType()) ||
            model.audioArtifactIdentity == artifactIdentity
    }
}

data class CuratedModelBundle(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val files: List<CuratedBundleFile>,
    val capabilityRes: List<Int> = emptyList(),
    val defaultPrefix: String = ""
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]*")))
        require(files.isNotEmpty())
        require(files.map { it.id }.distinct().size == files.size)
        require(files.map { it.localFilename }.distinct().size == files.size)
    }

    val totalBytes: Long get() = files.sumOf { it.sizeBytes }
}

fun sanitizeCuratedBundlePrefix(value: String): String {
    val normalized = value.trim()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("[^A-Za-z0-9._-]"), "-")
        .replace(Regex("-+"), "-")
        .replace(Regex("\\.{2,}"), ".")
        .trim('-', '.', '_')
        .take(48)
    return normalized
}

object CuratedModelBundleRegistry {
    val bundles: List<CuratedModelBundle>
        get() = LlamaCuratedBundleCatalog.bundles + AdetailerCuratedBundleCatalog.bundles +
            AudioCuratedBundleCatalog.bundles

    val files: List<CuratedBundleFile>
        get() = bundles.flatMap { it.files }

    /** Context-aware view used by the shared catalog after asset manifests load. */
    fun bundles(context: Context): List<CuratedModelBundle> =
        bundles + StableAudioCuratedBundleCatalog.bundles(context)

    fun files(context: Context): List<CuratedBundleFile> =
        bundles(context).flatMap { it.files }

    fun fileForInstalledFilename(filename: String, context: Context? = null): CuratedBundleFile? {
        val matches = (context?.let { files(it) } ?: files).filter { it.matchesInstalledFilename(filename) }
        return matches.singleOrNull() ?: matches
            .distinctBy { it.artifactIdentity to it.sha256 }
            .singleOrNull()
    }

    fun fileForDownload(
        localFilename: String,
        repoId: String?,
        sourceUrl: String?,
        context: Context? = null
    ): CuratedBundleFile? {
        val matches = (context?.let { files(it) } ?: files).filter { file ->
            file.matchesInstalledFilename(localFilename) &&
                (repoId == null || file.repoId == repoId) &&
                (sourceUrl == null || file.downloadUrl == sourceUrl)
        }
        return matches.singleOrNull() ?: matches
            .distinctBy { it.artifactIdentity to it.sha256 }
            .singleOrNull()
    }

    /**
     * Resolve a curated component from the bytes that were actually installed.
     * A source row may only retain a repository URL after redirects or manual
     * promotion, while the verified digest is still an unambiguous identity.
     */
    fun fileForArtifactDigest(
        digest: String,
        type: ModelType? = null,
        context: Context? = null
    ): CuratedBundleFile? {
        val normalized = digest.trim()
            .removePrefix("sha256:")
            .trim()
            .lowercase(Locale.US)
        if (!normalized.matches(Regex("[0-9a-f]{64}"))) return null
        val matches = (context?.let { files(it) } ?: files).filter { file ->
            file.sha256.equals(normalized, ignoreCase = true) &&
                (type == null || file.type == type)
        }
        return matches.singleOrNull() ?: matches
            .distinctBy { it.artifactIdentity to it.sha256 }
            .singleOrNull()
    }

    fun filesForArtifactIdentity(identity: String, context: Context? = null): List<CuratedBundleFile> =
        (context?.let { files(it) } ?: files).filter { it.artifactIdentity == identity }

    /** Static catalog information: whether more than one bundle can use it. */
    fun isCuratedArtifactShared(identity: String, context: Context? = null): Boolean =
        (context?.let { bundles(it) } ?: bundles).count { bundle ->
            bundle.files.any { it.artifactIdentity == identity }
        } > 1

    /**
     * Runtime deletion callers supply identities from actual model/provenance
     * and bundle-item rows. The catalog cannot infer installed references.
     */
    fun isArtifactReferenced(identity: String, actualReferences: Set<String>): Boolean =
        identity in actualReferences

    @Deprecated("Use isArtifactReferenced with actual persisted references")
    fun isArtifactReferencedByCatalog(identity: String, excludingBundleId: String? = null): Boolean =
        bundles.any { bundle ->
            bundle.id != excludingBundleId && bundle.files.any { it.artifactIdentity == identity }
        }
}

fun verifyCuratedModelDownload(
    localFilename: String,
    downloadedFile: File,
    repoId: String? = null,
    sourceUrl: String? = null,
    context: Context? = null
) {
    // A filename is only a presentation key. A custom source can deliberately
    // reuse a curated basename, so verification is bound to the repository
    // and exact pinned URL when the download task provides them.
    val expected = CuratedModelBundleRegistry.fileForDownload(
        localFilename = localFilename,
        repoId = repoId,
        sourceUrl = sourceUrl,
        context = context
    ) ?: return
    verifyCuratedBundleFile(expected, localFilename, downloadedFile)
}

/**
 * Verifies a caller-owned catalog entry after the source identity has already
 * been matched. Dynamic catalogs (for example an asset-backed LiteRT audio
 * manifest) use this same byte-level check without being added to the static
 * llama.cpp registry. The filename remains a presentation detail; the
 * expected digest and size come from the pinned entry.
 */
fun verifyCuratedBundleFile(
    expected: CuratedBundleFile,
    localFilename: String,
    downloadedFile: File
) {
    require(downloadedFile.isFile) { "Downloaded file is missing: ${downloadedFile.absolutePath}" }
    if (expected.strictSize) {
        require(downloadedFile.length() == expected.sizeBytes) {
            "Size mismatch for $localFilename: expected ${expected.sizeBytes}, got ${downloadedFile.length()}"
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    downloadedFile.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    require(actual == expected.sha256) {
        "SHA-256 mismatch for $localFilename: expected ${expected.sha256}, got $actual"
    }
}
