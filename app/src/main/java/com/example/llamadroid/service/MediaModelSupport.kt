package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.util.Downloader
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class DownloadableMediaAsset(
    val id: String,
    val displayName: String,
    val version: String,
    val type: MediaAssetType,
    val backend: String,
    val installDirectory: String,
    val files: List<DownloadableMediaFile>,
    val estimatedBytes: Long,
    val licenseName: String?,
    val licenseUrl: String?
)

data class DownloadableMediaFile(
    val relativePath: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String? = null
)

enum class MediaAssetType {
    RIFE_INTERPOLATION
}

sealed interface MediaModelValidationResult {
    data class Installed(val modelDir: File, val sizeBytes: Long) : MediaModelValidationResult
    data class Missing(val missingPaths: List<String>) : MediaModelValidationResult
    data class Incomplete(val partialPaths: List<String>) : MediaModelValidationResult
    data class ChecksumMismatch(val relativePath: String) : MediaModelValidationResult
}

data class MediaModelDownloadProgress(
    val asset: DownloadableMediaAsset,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val currentFile: String,
    val progress: Float,
    val speedBytesPerSecond: Long,
    val phase: MediaModelDownloadPhase
) {
    val downloadedFormatted: String get() = FormatUtils.Technical.formatBytes(downloadedBytes)
    val totalFormatted: String get() = FormatUtils.Technical.formatBytes(totalBytes)
    val speedFormatted: String get() = "${FormatUtils.Technical.formatBytes(speedBytesPerSecond)}/s"
}

enum class MediaModelDownloadPhase {
    DOWNLOADING,
    VALIDATING,
    COMPLETED
}

object MediaModelRegistry {
    // This release contains the three small model directories exposed in the UI.
    // A release tag is intentionally used instead of a hand-copied commit SHA so
    // download links stay stable and cannot fail because of a malformed revision.
    private const val RIFE_RELEASE = "20221029"
    private const val RIFE_RAW_BASE =
        "https://raw.githubusercontent.com/nihui/rife-ncnn-vulkan/$RIFE_RELEASE/models"

    val rifeModels: List<DownloadableMediaAsset> = listOf(
        rifeAsset(
            id = "rife-v4.6",
            displayName = "RIFE v4.6",
            version = "4.6",
            estimatedBytes = 15L * 1024L * 1024L
        ),
        rifeAsset(
            id = "rife-v4",
            displayName = "RIFE v4",
            version = "4.0",
            estimatedBytes = 15L * 1024L * 1024L
        ),
        rifeAsset(
            id = "rife-anime",
            displayName = "RIFE Anime",
            version = "1.8",
            estimatedBytes = 15L * 1024L * 1024L
        )
    )

    val defaultRifeModel: DownloadableMediaAsset = rifeModels.first()

    fun rifeById(id: String): DownloadableMediaAsset? = rifeModels.firstOrNull { it.id == id }

    private fun rifeAsset(
        id: String,
        displayName: String,
        version: String,
        estimatedBytes: Long
    ): DownloadableMediaAsset = DownloadableMediaAsset(
        id = id,
        displayName = displayName,
        version = version,
        type = MediaAssetType.RIFE_INTERPOLATION,
        backend = "ncnn",
        installDirectory = "media_models/rife/$id",
        estimatedBytes = estimatedBytes,
        licenseName = "MIT",
        licenseUrl = "https://github.com/nihui/rife-ncnn-vulkan/blob/master/LICENSE",
        files = listOf(
            DownloadableMediaFile(
                relativePath = "flownet.param",
                url = "$RIFE_RAW_BASE/$id/flownet.param",
                sizeBytes = 128L * 1024L
            ),
            DownloadableMediaFile(
                relativePath = "flownet.bin",
                url = "$RIFE_RAW_BASE/$id/flownet.bin",
                sizeBytes = estimatedBytes - (128L * 1024L)
            )
        )
    )
}

object MediaModelManager {
    fun modelDir(context: Context, asset: DownloadableMediaAsset): File =
        File(context.filesDir, asset.installDirectory)

    fun validate(context: Context, asset: DownloadableMediaAsset): MediaModelValidationResult {
        return validateDirectory(modelDir(context, asset), asset)
    }

    fun validateDirectory(dir: File, asset: DownloadableMediaAsset): MediaModelValidationResult {
        val partials = asset.files
            .map { File(dir, "${it.relativePath}.part") }
            .filter { it.exists() }
            .map { it.absolutePath }
        if (partials.isNotEmpty()) {
            return MediaModelValidationResult.Incomplete(partials)
        }

        val missing = asset.files
            .map { File(dir, it.relativePath) to it.relativePath }
            .filterNot { (file, _) -> file.isFile && file.length() > 0L }
            .map { (_, relativePath) -> relativePath }
        if (missing.isNotEmpty()) {
            return MediaModelValidationResult.Missing(missing)
        }

        asset.files.forEach { entry ->
            val expected = entry.sha256 ?: return@forEach
            val actual = sha256(File(dir, entry.relativePath))
            if (!actual.equals(expected, ignoreCase = true)) {
                return MediaModelValidationResult.ChecksumMismatch(entry.relativePath)
            }
        }

        return MediaModelValidationResult.Installed(dir, diskUsageBytes(dir))
    }

    fun isInstalled(context: Context, asset: DownloadableMediaAsset): Boolean =
        validate(context, asset) is MediaModelValidationResult.Installed

    fun installedSizeBytes(context: Context, asset: DownloadableMediaAsset): Long =
        (validate(context, asset) as? MediaModelValidationResult.Installed)?.sizeBytes ?: 0L

    fun delete(context: Context, asset: DownloadableMediaAsset): Boolean {
        val dir = modelDir(context, asset)
        return if (dir.exists()) dir.deleteRecursively() else true
    }

    fun cleanupIncomplete(context: Context, asset: DownloadableMediaAsset) {
        val dir = modelDir(context, asset)
        asset.files.forEach { entry ->
            File(dir, "${entry.relativePath}.part").delete()
        }
    }

    private fun diskUsageBytes(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

class MediaAssetDownloader(private val context: Context) {
    fun download(asset: DownloadableMediaAsset): Flow<MediaModelDownloadProgress> = flow {
        val totalBytes = asset.files.sumOf { it.sizeBytes }.coerceAtLeast(asset.estimatedBytes)
        val dir = MediaModelManager.modelDir(context, asset).apply { mkdirs() }
        val startedAt = System.currentTimeMillis()
        var completedBytes = 0L

        asset.files.forEach { fileEntry ->
            coroutineContext.ensureActive()
            val outputFile = File(dir, fileEntry.relativePath)
            outputFile.parentFile?.mkdirs()
            val downloadId = "${asset.id}:${fileEntry.relativePath}"
            Downloader.download(
                url = fileEntry.url,
                destFile = outputFile,
                context = context,
                downloadId = downloadId
            ).collect { fileProgress ->
                coroutineContext.ensureActive()
                val fileDownloaded = when {
                    fileProgress >= 0f -> (fileEntry.sizeBytes * fileProgress).toLong()
                    outputFile.exists() -> outputFile.length()
                    else -> File(outputFile.parentFile, "${outputFile.name}.part").length()
                }.coerceIn(0L, fileEntry.sizeBytes)
                val downloaded = (completedBytes + fileDownloaded).coerceAtMost(totalBytes)
                val elapsedSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(1L)
                emit(
                    MediaModelDownloadProgress(
                        asset = asset,
                        downloadedBytes = downloaded,
                        totalBytes = totalBytes,
                        currentFile = fileEntry.relativePath,
                        progress = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f),
                        speedBytesPerSecond = downloaded / elapsedSeconds,
                        phase = MediaModelDownloadPhase.DOWNLOADING
                    )
                )
            }
            completedBytes += outputFile.length().coerceAtLeast(fileEntry.sizeBytes)
        }

        emit(
            MediaModelDownloadProgress(
                asset = asset,
                downloadedBytes = totalBytes,
                totalBytes = totalBytes,
                currentFile = "",
                progress = 0.98f,
                speedBytesPerSecond = 0L,
                phase = MediaModelDownloadPhase.VALIDATING
            )
        )

        when (val validation = MediaModelManager.validate(context, asset)) {
            is MediaModelValidationResult.Installed -> emit(
                MediaModelDownloadProgress(
                    asset = asset,
                    downloadedBytes = validation.sizeBytes.coerceAtMost(totalBytes),
                    totalBytes = totalBytes,
                    currentFile = "",
                    progress = 1f,
                    speedBytesPerSecond = 0L,
                    phase = MediaModelDownloadPhase.COMPLETED
                )
            )
            is MediaModelValidationResult.ChecksumMismatch -> {
                throw IllegalStateException("Checksum mismatch: ${validation.relativePath}")
            }
            is MediaModelValidationResult.Incomplete -> {
                throw IllegalStateException("Incomplete download")
            }
            is MediaModelValidationResult.Missing -> {
                throw IllegalStateException("Missing model files: ${validation.missingPaths.joinToString()}")
            }
        }
    }.flowOn(Dispatchers.IO)

    fun cancel(asset: DownloadableMediaAsset) {
        asset.files.forEach { file ->
            Downloader.cancelDownload("${asset.id}:${file.relativePath}")
        }
    }
}
