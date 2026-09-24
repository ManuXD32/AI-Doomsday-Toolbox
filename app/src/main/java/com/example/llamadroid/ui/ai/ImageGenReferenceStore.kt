package com.example.llamadroid.ui.ai

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

internal const val MAX_IMAGE_GEN_REFERENCE_IMAGES = 10
internal const val MAX_IMAGE_GEN_REFERENCE_BYTES = 128L * 1024L * 1024L

internal class ImageGenReferenceTooLargeException : IllegalArgumentException()

internal data class ImageGenReferenceSelection(
    val primaryPath: String?,
    val additionalPaths: List<String>
)

/** Deselects the source in every image mode; Qwen editing may promote the next reference. */
internal fun removePrimaryImageReference(
    primaryPath: String?,
    additionalPaths: List<String>,
    promoteNext: Boolean,
    isReadable: (String) -> Boolean
): ImageGenReferenceSelection {
    val readable = additionalPaths.asSequence()
        .filter { it != primaryPath && isReadable(it) }
        .distinct()
        .take(MAX_IMAGE_GEN_REFERENCE_IMAGES - 1)
        .toList()
    return if (promoteNext) {
        ImageGenReferenceSelection(readable.firstOrNull(), readable.drop(1))
    } else {
        ImageGenReferenceSelection(null, readable)
    }
}

internal fun requireImageGenReferenceSizeWithinLimit(
    sizeBytes: Long?,
    maxBytes: Long = MAX_IMAGE_GEN_REFERENCE_BYTES
) {
    if (sizeBytes != null && sizeBytes > maxBytes) throw ImageGenReferenceTooLargeException()
}

/** Copies a reference with a streaming cap, including when the provider reports no size. */
internal fun copyImageGenReferenceWithinLimit(
    input: InputStream,
    target: File,
    maxBytes: Long = MAX_IMAGE_GEN_REFERENCE_BYTES
): Long {
    require(maxBytes > 0L)
    var copiedBytes = 0L
    try {
        input.use { source ->
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    copiedBytes += read
                    requireImageGenReferenceSizeWithinLimit(copiedBytes, maxBytes)
                    output.write(buffer, 0, read)
                }
            }
        }
        return copiedBytes
    } catch (failure: Throwable) {
        target.delete()
        throw failure
    }
}

/** Copies picked references into app-owned files so saved drafts survive picker permission expiry. */
internal object ImageGenReferenceStore {
    private const val ROOT_DIRECTORY = "imagegen_references"
    private val allowedExtensions = setOf("png", "jpg", "jpeg", "webp", "bmp")

    suspend fun import(
        context: Context,
        uris: List<Uri>,
        existingPaths: List<String>
    ): List<String> = withContext(Dispatchers.IO) {
        val current = existingPaths
            .filter { isOwnedReadableFile(context, it) }
            .distinct()
            .take(MAX_IMAGE_GEN_REFERENCE_IMAGES)
            .toMutableList()
        val remaining = (MAX_IMAGE_GEN_REFERENCE_IMAGES - current.size).coerceAtLeast(0)
        if (remaining == 0 || uris.isEmpty()) return@withContext current

        val directory = File(context.filesDir, ROOT_DIRECTORY).apply { mkdirs() }
        val created = mutableListOf<File>()
        try {
            uris.distinct().take(remaining).forEach { uri ->
                requireImageGenReferenceSizeWithinLimit(reportedSize(context, uri))
                val extension = imageExtension(context, uri)
                val file = File(directory, "reference-${UUID.randomUUID()}.$extension")
                created += file
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Reference image could not be opened")
                copyImageGenReferenceWithinLimit(input, file)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (!file.isFile || file.length() <= 0L || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    error("Reference image could not be decoded")
                }
                current += file.absolutePath
            }
            current
        } catch (failure: Throwable) {
            created.forEach(File::delete)
            throw failure
        }
    }

    fun deleteOwned(context: Context, path: String) {
        val file = File(path)
        val root = File(context.filesDir, ROOT_DIRECTORY)
        if (runCatching { file.canonicalFile.parentFile == root.canonicalFile }.getOrDefault(false)) {
            file.delete()
        }
    }

    fun isOwnedReadableFile(context: Context, path: String): Boolean {
        val file = File(path)
        val root = File(context.filesDir, ROOT_DIRECTORY)
        return file.isFile && file.canRead() &&
            runCatching { file.canonicalFile.parentFile == root.canonicalFile }.getOrDefault(false)
    }

    private fun imageExtension(context: Context, uri: Uri): String {
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        val fromName = displayName?.substringAfterLast('.', "")?.lowercase(java.util.Locale.ROOT)
        val fromMime = context.contentResolver.getType(uri)
            ?.let(MimeTypeMap.getSingleton()::getExtensionFromMimeType)
            ?.lowercase(java.util.Locale.ROOT)
        return sequenceOf(fromName, fromMime)
            .filterNotNull()
            .firstOrNull { it in allowedExtensions }
            ?: error("Unsupported image format")
    }

    private fun reportedSize(context: Context, uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index < 0) null else cursor.getLong(index).takeIf { it >= 0L }
            }
}
