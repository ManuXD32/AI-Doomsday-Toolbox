package com.example.llamadroid.service

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.example.llamadroid.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

open class SdConfigurationException(message: String) : IllegalStateException(message)

enum class SdIpAdapterIssue {
    UNSUPPORTED_FAMILY,
    MISSING_ADAPTER,
    MISSING_CLIP_VISION,
    MISSING_REFERENCE_IMAGE,
    INCOMPATIBLE_ADAPTER,
    INCOMPATIBLE_CLIP_VISION,
    UNREADABLE_ADAPTER,
    UNREADABLE_CLIP_VISION,
    UNREADABLE_REFERENCE_IMAGE,
    INVALID_STRENGTH
}

class SdIpAdapterConfigurationException(
    val issue: SdIpAdapterIssue,
    val detail: String? = null
) : SdConfigurationException(
    buildString {
        append("Invalid IP-Adapter configuration: ")
        append(issue.name)
        detail?.takeIf { it.isNotBlank() }?.let {
            append(" (")
            append(it)
            append(')')
        }
    }
)

fun validateSdIpAdapterConfig(
    config: SdIpAdapterConfig?,
    supportsIpAdapter: Boolean,
    adapterCompatible: Boolean = true,
    clipVisionCompatible: Boolean = true,
    requireReadableFiles: Boolean = true
): SdIpAdapterConfig? {
    config ?: return null
    if (!supportsIpAdapter) {
        throw SdIpAdapterConfigurationException(SdIpAdapterIssue.UNSUPPORTED_FAMILY)
    }

    val adapterPath = config.adapterPath.trim()
    val clipVisionPath = config.clipVisionPath.trim()
    val imagePath = config.imagePath.trim()

    if (adapterPath.isBlank()) {
        throw SdIpAdapterConfigurationException(SdIpAdapterIssue.MISSING_ADAPTER)
    }
    if (clipVisionPath.isBlank()) {
        throw SdIpAdapterConfigurationException(SdIpAdapterIssue.MISSING_CLIP_VISION)
    }
    if (imagePath.isBlank()) {
        throw SdIpAdapterConfigurationException(SdIpAdapterIssue.MISSING_REFERENCE_IMAGE)
    }
    if (!adapterCompatible) {
        throw SdIpAdapterConfigurationException(
            SdIpAdapterIssue.INCOMPATIBLE_ADAPTER,
            adapterPath
        )
    }
    if (!clipVisionCompatible) {
        throw SdIpAdapterConfigurationException(
            SdIpAdapterIssue.INCOMPATIBLE_CLIP_VISION,
            clipVisionPath
        )
    }
    if (!config.strength.isFinite() || config.strength < 0f) {
        throw SdIpAdapterConfigurationException(
            SdIpAdapterIssue.INVALID_STRENGTH,
            config.strength.toString()
        )
    }

    if (requireReadableFiles) {
        if (!isReadableFile(adapterPath)) {
            throw SdIpAdapterConfigurationException(
                SdIpAdapterIssue.UNREADABLE_ADAPTER,
                adapterPath
            )
        }
        if (!isReadableFile(clipVisionPath)) {
            throw SdIpAdapterConfigurationException(
                SdIpAdapterIssue.UNREADABLE_CLIP_VISION,
                clipVisionPath
            )
        }
        if (!isReadableFile(imagePath)) {
            throw SdIpAdapterConfigurationException(
                SdIpAdapterIssue.UNREADABLE_REFERENCE_IMAGE,
                imagePath
            )
        }
    }

    return config.copy(
        adapterPath = adapterPath,
        clipVisionPath = clipVisionPath,
        imagePath = imagePath
    )
}

fun sdIpAdapterErrorMessage(
    context: Context,
    error: SdIpAdapterConfigurationException
): String = when (error.issue) {
    SdIpAdapterIssue.UNSUPPORTED_FAMILY ->
        context.getString(R.string.imagegen_error_ip_adapter_unsupported)
    SdIpAdapterIssue.MISSING_ADAPTER ->
        context.getString(R.string.imagegen_error_ip_adapter_missing_adapter)
    SdIpAdapterIssue.MISSING_CLIP_VISION ->
        context.getString(R.string.imagegen_error_ip_adapter_missing_clip_vision)
    SdIpAdapterIssue.MISSING_REFERENCE_IMAGE ->
        context.getString(R.string.imagegen_error_ip_adapter_missing_reference)
    SdIpAdapterIssue.INCOMPATIBLE_ADAPTER ->
        context.getString(R.string.imagegen_error_ip_adapter_incompatible_adapter)
    SdIpAdapterIssue.INCOMPATIBLE_CLIP_VISION ->
        context.getString(R.string.imagegen_error_ip_adapter_incompatible_clip_vision)
    SdIpAdapterIssue.UNREADABLE_ADAPTER ->
        context.getString(
            R.string.imagegen_error_ip_adapter_unreadable_adapter,
            error.detail.orEmpty()
        )
    SdIpAdapterIssue.UNREADABLE_CLIP_VISION ->
        context.getString(
            R.string.imagegen_error_ip_adapter_unreadable_clip_vision,
            error.detail.orEmpty()
        )
    SdIpAdapterIssue.UNREADABLE_REFERENCE_IMAGE ->
        context.getString(
            R.string.imagegen_error_ip_adapter_unreadable_reference,
            error.detail.orEmpty()
        )
    SdIpAdapterIssue.INVALID_STRENGTH ->
        context.getString(R.string.imagegen_error_ip_adapter_invalid_strength)
}

internal fun formatSdIpAdapterStrength(value: Float): String {
    val rendered = String.format(Locale.US, "%.6f", value)
        .trimEnd('0')
        .trimEnd('.')
    return rendered.ifEmpty { "0" }
}

private fun isReadableFile(path: String): Boolean =
    File(path).let { it.isFile && it.canRead() && it.length() > 0L }

object SdIpAdapterReferenceStore {
    private const val DIRECTORY = "sd_reference_inputs/ip_adapter"
    private const val MIN_IMAGE_BYTES = 16L
    private const val MAX_IMAGE_BYTES = 128L * 1024L * 1024L

    fun directory(context: Context): File =
        File(context.filesDir, DIRECTORY).apply {
            require(isDirectory || mkdirs()) {
                "Unable to create the IP-Adapter reference-image directory"
            }
        }

    fun isReadableImage(file: File): Boolean {
        if (!file.isFile || !file.canRead() || file.length() !in MIN_IMAGE_BYTES..MAX_IMAGE_BYTES) {
            return false
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    fun resolveOwnedImagePath(context: Context, path: String?): String? {
        val file = path?.trim()?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
        if (!isOwnedFile(context, file) || !isReadableImage(file)) return null
        return runCatching { file.canonicalPath }.getOrNull()
    }

    suspend fun importImage(
        context: Context,
        sourceUri: Uri
    ): File = withContext(Dispatchers.IO) {
        val root = directory(context)
        val extension = resolveExtension(context, sourceUri)
        val destination = File(
            root,
            "ip_adapter_${System.currentTimeMillis()}.$extension"
        )
        val temporary = File(root, ".importing-${destination.name}")
        try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Unable to open the selected reference image")
            require(isReadableImage(temporary)) {
                "The selected document is not a readable image"
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            require(isReadableImage(destination)) {
                "The imported IP-Adapter reference image could not be verified"
            }
            destination
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun deleteOwnedImage(context: Context, path: String?): Boolean {
        val file = path?.let(::File) ?: return false
        if (!isOwnedFile(context, file)) return false
        return file.delete()
    }

    private fun isOwnedFile(context: Context, file: File): Boolean {
        val root = runCatching { directory(context).canonicalFile }.getOrNull() ?: return false
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return canonical.parentFile == root
    }

    private fun resolveExtension(context: Context, uri: Uri): String {
        val displayName = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()
        val fromName = displayName
            ?.substringAfterLast('.', "")
            ?.lowercase(Locale.US)
            ?.takeIf { it in setOf("png", "jpg", "jpeg", "webp", "bmp") }
        if (fromName != null) return fromName
        return when (context.contentResolver.getType(uri)?.lowercase(Locale.US)) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            else -> "png"
        }
    }
}
