package com.example.llamadroid.util

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache

data class ContentUriMetadata(
    val displayName: String?,
    val sizeBytes: Long?,
    val mimeType: String?
)

/** Resolves stable metadata while a document-provider grant is still valid. */
object ContentUriMetadataResolver {
    private const val CACHE_SIZE = 64
    private val cache = LruCache<String, ContentUriMetadata>(CACHE_SIZE)

    fun resolve(context: Context, uri: Uri): ContentUriMetadata {
        val key = uri.toString()
        synchronized(cache) {
            cache.get(key)?.let { return it }
        }

        val resolver = context.contentResolver
        val queried = runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use(::readCursorMetadata)
        }.getOrNull()
        val metadata = ContentUriMetadata(
            displayName = queried?.displayName,
            sizeBytes = queried?.sizeBytes,
            mimeType = runCatching { resolver.getType(uri) }.getOrNull()
        )

        // Do not cache a completely unreadable lookup: a temporary grant may become available
        // later in the same process. Successful provider metadata is safe to reuse in Compose.
        if (metadata.displayName != null || metadata.sizeBytes != null || metadata.mimeType != null) {
            remember(uri, metadata)
        }
        return metadata
    }

    fun remember(uri: Uri, metadata: ContentUriMetadata) {
        synchronized(cache) {
            cache.put(uri.toString(), metadata)
        }
    }

    private fun readCursorMetadata(cursor: Cursor): ContentUriMetadata? {
        if (!cursor.moveToFirst()) return null
        val displayNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        return ContentUriMetadata(
            displayName = displayNameIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getString)
                ?.trim()
                ?.takeIf(String::isNotEmpty),
            sizeBytes = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getLong)
                ?.takeIf { it >= 0L },
            mimeType = null
        )
    }

    internal fun clearCache() {
        synchronized(cache) {
            cache.evictAll()
        }
    }
}

/** Compatibility facade for the existing PDF UI call sites. */
object DocumentUriDisplayName {
    fun resolve(context: Context, uri: Uri, fallback: String): String =
        ContentUriMetadataResolver.resolve(context, uri).displayName
            ?: fallbackName(uri, fallback)

    /** Associates an app-cache URI with metadata captured before a share grant expires. */
    fun remember(
        uri: Uri,
        displayName: String,
        sizeBytes: Long? = null,
        mimeType: String? = null
    ) {
        val name = displayName.trim().takeIf { it.isNotEmpty() } ?: return
        ContentUriMetadataResolver.remember(
            uri,
            ContentUriMetadata(name, sizeBytes?.takeIf { it >= 0L }, mimeType)
        )
    }

    /**
     * Uses a path segment only when it resembles an actual filename. Provider document IDs such
     * as `msf:84`, numeric row IDs and directory-like labels intentionally use localized copy.
     */
    internal fun fallbackName(uri: Uri, fallback: String): String {
        val segment = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.let(Uri::decode)
        return fallbackNameFromPathSegment(segment, fallback)
    }

    /** Pure fallback logic, kept separate so provider-token handling has local unit coverage. */
    internal fun fallbackNameFromPathSegment(segment: String?, fallback: String): String {
        val candidate = segment
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        return candidate?.takeIf(::resemblesFileName) ?: fallback
    }

    private fun resemblesFileName(value: String): Boolean {
        if ('/' in value || '\\' in value) return false
        val dotIndex = value.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex == value.lastIndex) return false
        val extension = value.substring(dotIndex + 1)
        return extension.length in 1..12 && extension.all { it.isLetterOrDigit() }
    }

    internal fun clearCache() = ContentUriMetadataResolver.clearCache()
}
