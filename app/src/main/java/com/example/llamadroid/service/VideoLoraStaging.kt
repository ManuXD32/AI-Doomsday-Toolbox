package com.example.llamadroid.service

import com.example.llamadroid.sd.SdLoraSpec
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Runtime LoRA bindings for the video command.
 *
 * The pinned native parser accepts an absolute path in a `<lora:...>` token.
 * That is the preferred plan because it keeps the original files in place and
 * makes two files with the same basename unambiguous.  The optional fallback
 * creates one per-run directory with collision-safe links when a future native
 * build does not support absolute prompt paths.
 */
class VideoLoraStagingPlan private constructor(
    val loras: List<SdLoraSpec>,
    private val promptPaths: List<String>,
    val loraModelDirectory: String,
    private val ownedDirectory: File?
) : AutoCloseable {

    fun promptPath(index: Int, item: SdLoraSpec): String {
        val planned = promptPaths.getOrNull(index)
        return planned?.takeIf { it.isNotBlank() } ?: File(item.path).absolutePath
    }

    override fun close() {
        ownedDirectory?.takeIf { it.exists() }?.walkBottomUp()?.forEach { file ->
            runCatching { file.delete() }
        }
    }

    companion object {
        /** Use the native absolute-path prompt contract without touching weights. */
        fun nativeAbsolute(loras: List<SdLoraSpec>): VideoLoraStagingPlan {
            val normalized = loras.map(SdLoraSpec::normalized)
            val modelDirectory = normalized.firstOrNull()
                ?.let { File(it.path).absoluteFile.parentFile?.absolutePath }
                ?.takeIf { it.isNotBlank() }
                ?: "."
            return VideoLoraStagingPlan(
                loras = normalized,
                promptPaths = normalized.map { File(it.path).absolutePath },
                loraModelDirectory = modelDirectory,
                ownedDirectory = null
            )
        }

        /**
         * Link or copy adapters into one directory for older native parsers.
         * Symlinks and hardlinks are attempted before a copy so large weights
         * are not duplicated when the filesystem permits either link type.
         */
        fun linkedFallback(
            loras: List<SdLoraSpec>,
            rootDirectory: File
        ): VideoLoraStagingPlan {
            val normalized = loras.map(SdLoraSpec::normalized)
            if (normalized.isEmpty()) return nativeAbsolute(emptyList())

            val runDirectory = File(rootDirectory, "video-lora-${UUID.randomUUID()}").apply {
                mkdirs()
            }
            val stagedPaths = mutableListOf<String>()
            try {
                normalized.forEachIndexed { index, item ->
                    val source = File(item.path).canonicalFile
                    require(source.isFile && source.canRead()) {
                        "Video LoRA is not readable: ${item.path}"
                    }
                    val safeStem = source.nameWithoutExtension
                        .replace(UNSAFE_NAME, "_")
                        .trim('_')
                        .ifBlank { "adapter" }
                    val extension = source.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
                    val target = File(runDirectory, "%04d_%s%s".format(index, safeStem, extension))
                    linkWithoutCopying(source, target)
                    stagedPaths += target.absolutePath
                }
            } catch (error: Throwable) {
                runCatching { runDirectory.deleteRecursively() }
                throw error
            }
            return VideoLoraStagingPlan(
                loras = normalized,
                promptPaths = stagedPaths,
                loraModelDirectory = runDirectory.absolutePath,
                ownedDirectory = runDirectory
            )
        }

        private fun linkWithoutCopying(source: File, target: File) {
            val sourcePath = source.toPath()
            val targetPath = target.toPath()
            runCatching {
                Files.createSymbolicLink(targetPath, sourcePath)
            }.recoverCatching {
                Files.createLink(targetPath, sourcePath)
            }.recoverCatching {
                Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }.getOrThrow()
        }

        private val UNSAFE_NAME = Regex("[^A-Za-z0-9._-]")
    }
}
