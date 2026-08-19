package com.example.llamadroid.data.model

import androidx.annotation.StringRes
import com.example.llamadroid.data.db.ModelType
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
    val downloadUrlOverride: String? = null
) {
    init {
        require(id.isNotBlank())
        require(repoId.count { it == '/' } == 1)
        require(revision.matches(Regex("[0-9a-f]{7,64}|main|v[0-9]+\\.[0-9]+\\.[0-9]+")))
        require(remotePath.isNotBlank() && !remotePath.startsWith('/'))
        require(File(localFilename).name == localFilename)
        require(sizeBytes > 0L)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
    }

    val downloadUrl: String
        get() = downloadUrlOverride
            ?: "https://huggingface.co/$repoId/resolve/$revision/$remotePath"

    fun installedFilename(prefix: String): String {
        val cleanPrefix = sanitizeCuratedBundlePrefix(prefix)
        return if (cleanPrefix.isBlank()) localFilename else "$cleanPrefix-$localFilename"
    }

    fun matchesInstalledFilename(filename: String): Boolean =
        filename == localFilename || filename.endsWith("-$localFilename")
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
        .trim('-', '.', '_')
        .take(48)
    require('/' !in normalized && '\\' !in normalized && ".." !in normalized) {
        "Unsafe bundle prefix"
    }
    return normalized
}

object CuratedModelBundleRegistry {
    val bundles: List<CuratedModelBundle>
        get() = LlamaCuratedBundleCatalog.bundles + AdetailerCuratedBundleCatalog.bundles

    val files: List<CuratedBundleFile>
        get() = bundles.flatMap { it.files }

    fun fileForInstalledFilename(filename: String): CuratedBundleFile? {
        val matches = files.filter { it.matchesInstalledFilename(filename) }
        return matches.singleOrNull()
    }
}

fun verifyCuratedModelDownload(localFilename: String, downloadedFile: File) {
    val expected = CuratedModelBundleRegistry.fileForInstalledFilename(localFilename) ?: return
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
