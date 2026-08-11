package com.example.llamadroid.data.model

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.ModelType
import java.io.File
import java.io.InputStream
import java.net.URLConnection

object ModelLibraryManager {

    const val LITERT_RELATIVE_DIR = "litert"
    const val ONNX_RELATIVE_DIR = "onnx"
    const val QUADTRIX_RELATIVE_DIR = "quadtrix"

    private val CANONICAL_LIBRARY_TYPES = emptySet<ModelType>()

    private val RUNTIME_MIRROR_TYPES = emptySet<ModelType>()

    private val MANAGED_EXTERNAL_CANONICAL_TYPES = setOf(
        ModelType.LLM,
        ModelType.LORA,
        ModelType.EMBEDDING,
        ModelType.VISION,
        ModelType.VISION_PROJECTOR,
        ModelType.MMPROJ,
        ModelType.WHISPER,
        ModelType.SD_CHECKPOINT,
        ModelType.SD_UPSCALER,
        ModelType.SD_DIFFUSION,
        ModelType.SD_CLIP_L,
        ModelType.SD_CLIP_G,
        ModelType.SD_T5XXL,
        ModelType.SD_TAE,
        ModelType.SD_VAE,
        ModelType.SD_LORA,
        ModelType.SD_TEXTUAL_INVERSION,
        ModelType.SD_CONTROLNET,
        ModelType.SD_PHOTOMAKER,
        ModelType.SD_CLIP_VISION,
        ModelType.SD_IP_ADAPTER,
        ModelType.LLM_DRAFT,
        ModelType.SD_ADETAILER
    )

    fun relativeDirFor(type: ModelType): String = when (type) {
        ModelType.LLM,
        ModelType.LORA,
        ModelType.EMBEDDING,
        ModelType.VISION -> "llm"
        ModelType.LLM_DRAFT -> "llm/drafts"
        ModelType.VISION_PROJECTOR,
        ModelType.MMPROJ -> "mmproj"
        ModelType.QUADTRIX -> QUADTRIX_RELATIVE_DIR
        ModelType.SD_CHECKPOINT,
        ModelType.SD_UPSCALER -> "sd/checkpoints"
        ModelType.SD_DIFFUSION -> "sd/flux"
        ModelType.SD_CLIP_L -> "sd/clip_l"
        ModelType.SD_CLIP_G -> "sd/clip_g"
        ModelType.SD_T5XXL -> "sd/t5xxl"
        ModelType.SD_TAE -> "sd/tae"
        ModelType.SD_VAE -> "sd/vae"
        ModelType.SD_LORA -> "sd/lora"
        ModelType.SD_TEXTUAL_INVERSION -> "sd/embeddings"
        ModelType.SD_CONTROLNET -> "sd/controlnet"
        ModelType.SD_PHOTOMAKER -> "sd/photomaker"
        ModelType.SD_CLIP_VISION -> "sd/clip_vision"
        ModelType.SD_IP_ADAPTER -> "sd/ip_adapter"
        ModelType.SD_ADETAILER -> "sd/adetailer"
        ModelType.ONNX_IMAGE_GEN,
        ModelType.ONNX_BACKGROUND_REMOVAL,
        ModelType.ONNX_IMAGE_UPSCALER,
        ModelType.ONNX_TTS -> ONNX_RELATIVE_DIR
        ModelType.WHISPER -> "whisper"
    }

    fun relativePathFor(type: ModelType, filename: String): String =
        "${relativeDirFor(type).trimEnd('/')}/${filename.trimStart('/')}"

    fun relativePathForLiteRt(filename: String): String =
        "$LITERT_RELATIVE_DIR/${filename.trimStart('/')}"

    fun supportsCanonicalLibrary(type: ModelType): Boolean = type in CANONICAL_LIBRARY_TYPES

    fun requiresRuntimeMirror(type: ModelType): Boolean = type in RUNTIME_MIRROR_TYPES

    fun usesManagedExternalCanonicalStorage(type: ModelType): Boolean = type in MANAGED_EXTERNAL_CANONICAL_TYPES

    fun supportsZeroCopyImport(type: ModelType): Boolean = false

    fun configuredRootUri(context: Context): Uri? =
        SettingsRepository(context).modelStorageUri.value
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)

    fun configuredRoot(context: Context): DocumentFile? =
        configuredRootUri(context)?.let { DocumentFile.fromTreeUri(context, it) }

    fun isConfigured(context: Context): Boolean = configuredRoot(context) != null

    fun configuredRootLabel(context: Context): String? =
        configuredRoot(context)?.name

    fun canonicalFilename(filename: String): String =
        sanitizeFilename(filename)

    fun resolveDirectImportFile(
        context: Context,
        sourceUri: Uri,
        type: ModelType
    ): File? = null

    fun chooseUniqueFilename(
        context: Context,
        relativeDir: String,
        requestedFilename: String,
        runtimeDir: File
    ): String {
        val clean = sanitizeFilename(requestedFilename)
        val base = clean.substringBeforeLast('.', clean)
        val extensionSuffix = clean.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        var candidate = clean
        var index = 1
        while (File(runtimeDir, candidate).exists() || fileExistsInLibrary(context, relativeDir, candidate)) {
            val timestamp = System.currentTimeMillis()
            candidate = if (index == 1) {
                "$base-$timestamp$extensionSuffix"
            } else {
                "$base-$timestamp-$index$extensionSuffix"
            }
            index += 1
        }
        return candidate
    }

    fun copyUriToLibrary(
        context: Context,
        relativeDir: String,
        filename: String,
        sourceUri: Uri
    ): Result<Unit> = runCatching {
        val root = configuredRoot(context) ?: return@runCatching
        val targetDir = ensureDirectory(root, relativeDir)
        val target = replaceFile(targetDir, filename, mimeTypeFor(filename))
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: error("Unable to open library source for $filename")
        input.use {
            writeToDocumentFile(context, target, it)
        }
    }

    fun copyFileToLibrary(
        context: Context,
        relativeDir: String,
        filename: String,
        sourceFile: File
    ): Result<Unit> = runCatching {
        if (!sourceFile.exists() || !sourceFile.isFile) return@runCatching
        val root = configuredRoot(context) ?: return@runCatching
        val targetDir = ensureDirectory(root, relativeDir)
        val target = replaceFile(targetDir, filename, mimeTypeFor(filename))
        sourceFile.inputStream().use { input ->
            writeToDocumentFile(context, target, input)
        }
    }

    fun copyUriToManagedFile(
        context: Context,
        sourceUri: Uri,
        targetFile: File
    ): Result<Unit> = runCatching {
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: error("Unable to open selected model")
        input.use {
            writeToManagedFile(targetFile, it)
        }
    }

    fun copyLibraryFileToManagedFile(
        context: Context,
        relativeDir: String,
        filename: String,
        targetFile: File
    ): Result<Unit> = runCatching {
        val source = libraryFile(context, relativeDir, filename)
            ?: error("Library file not found: $relativeDir/$filename")
        val input = context.contentResolver.openInputStream(source.uri)
            ?: error("Unable to open library source for $filename")
        input.use {
            writeToManagedFile(targetFile, it)
        }
    }

    fun mirrorDirectoryToLibrary(
        context: Context,
        relativeDir: String,
        directoryName: String,
        sourceDir: File
    ): Result<Unit> = runCatching {
        if (!sourceDir.exists() || !sourceDir.isDirectory) return@runCatching
        val root = configuredRoot(context) ?: return@runCatching
        val categoryDir = ensureDirectory(root, relativeDir)
        categoryDir.findFile(directoryName)?.delete()
        val targetDir = categoryDir.createDirectory(directoryName)
            ?: categoryDir.findFile(directoryName)
            ?: error("Unable to create library directory $directoryName")
        copyDirectoryRecursive(context, sourceDir, targetDir)
    }

    fun deleteFromLibrary(context: Context, relativeDir: String, filename: String) {
        val root = configuredRoot(context) ?: return
        val targetDir = findDirectory(root, relativeDir) ?: return
        targetDir.findFile(filename)?.delete()
    }

    fun deleteDirectoryFromLibrary(context: Context, relativeDir: String, directoryName: String) {
        val root = configuredRoot(context) ?: return
        val targetDir = findDirectory(root, relativeDir) ?: return
        targetDir.findFile(directoryName)?.delete()
    }

    fun libraryFile(
        context: Context,
        relativeDir: String,
        filename: String
    ): DocumentFile? {
        val root = configuredRoot(context) ?: return null
        val dir = findDirectory(root, relativeDir) ?: return null
        return dir.findFile(filename)?.takeIf { it.exists() }
    }

    fun hasLibraryFile(
        context: Context,
        relativeDir: String,
        filename: String
    ): Boolean = libraryFile(context, relativeDir, filename) != null

    fun libraryFileSize(
        context: Context,
        relativeDir: String,
        filename: String
    ): Long? = libraryFile(context, relativeDir, filename)?.length()

    fun renameInLibrary(
        context: Context,
        oldRelativeDir: String,
        oldFilename: String,
        newRelativeDir: String,
        newFilename: String,
        sourceFile: File?
    ): Result<Unit> = runCatching {
        deleteFromLibrary(context, oldRelativeDir, oldFilename)
        if (sourceFile != null && sourceFile.exists() && sourceFile.isFile) {
            copyFileToLibrary(context, newRelativeDir, newFilename, sourceFile).getOrThrow()
        }
    }

    fun renameDirectoryInLibrary(
        context: Context,
        oldRelativeDir: String,
        oldName: String,
        newRelativeDir: String,
        newName: String,
        sourceDir: File?
    ): Result<Unit> = runCatching {
        deleteDirectoryFromLibrary(context, oldRelativeDir, oldName)
        if (sourceDir != null && sourceDir.exists() && sourceDir.isDirectory) {
            mirrorDirectoryToLibrary(context, newRelativeDir, newName, sourceDir).getOrThrow()
        }
    }

    private fun fileExistsInLibrary(context: Context, relativeDir: String, filename: String): Boolean {
        val root = configuredRoot(context) ?: return false
        val dir = findDirectory(root, relativeDir) ?: return false
        return dir.findFile(filename)?.exists() == true
    }

    private fun ensureDirectory(root: DocumentFile, relativeDir: String): DocumentFile {
        var current = root
        relativeDir.split('/').filter { it.isNotBlank() }.forEach { segment ->
            current = current.findFile(segment)
                ?: current.createDirectory(segment)
                ?: error("Unable to create library directory $relativeDir")
        }
        return current
    }

    private fun findDirectory(root: DocumentFile, relativeDir: String): DocumentFile? {
        var current: DocumentFile? = root
        relativeDir.split('/').filter { it.isNotBlank() }.forEach { segment ->
            current = current?.findFile(segment)
            if (current == null || current?.isDirectory != true) return null
        }
        return current
    }

    private fun replaceFile(parent: DocumentFile, filename: String, mimeType: String): DocumentFile {
        parent.findFile(filename)?.delete()
        return parent.createFile(mimeType, filename) ?: error("Unable to create $filename")
    }

    private fun copyDirectoryRecursive(context: Context, sourceDir: File, targetDir: DocumentFile) {
        sourceDir.listFiles().orEmpty().forEach { child ->
            if (child.isDirectory) {
                val subdir = targetDir.findFile(child.name)
                    ?: targetDir.createDirectory(child.name)
                    ?: error("Unable to create ${child.name}")
                copyDirectoryRecursive(context, child, subdir)
            } else {
                val target = replaceFile(targetDir, child.name, mimeTypeFor(child.name))
                child.inputStream().use { input ->
                    writeToDocumentFile(context, target, input)
                }
            }
        }
    }

    private fun writeToDocumentFile(context: Context, target: DocumentFile, input: InputStream) {
        context.contentResolver.openOutputStream(target.uri, "w")?.use { output ->
            input.copyTo(output)
        } ?: error("Unable to open library destination for ${target.name}")
    }

    private fun writeToManagedFile(targetFile: File, input: InputStream) {
        targetFile.parentFile?.mkdirs()
        targetFile.outputStream().use { output ->
            input.copyTo(output)
        }
    }

    private fun mimeTypeFor(filename: String): String =
        URLConnection.guessContentTypeFromName(filename) ?: "application/octet-stream"

    private fun sanitizeFilename(filename: String): String =
        filename.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').ifBlank { "model.bin" }
}
