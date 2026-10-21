package com.example.llamadroid.service.filestation.volume

import java.io.InputStream
import java.io.OutputStream

/** Typed error codes shared by volumes and the HTTP layer (see docs/file-station.md). */
enum class FsErrorCode { NOT_FOUND, EXISTS, READ_ONLY, INVALID_PATH, NO_SPACE, UNSUPPORTED, IO }

class FsException(
    val code: FsErrorCode,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * One entry of a volume. [path] is the normalized volume-relative path ("" is the root, no leading
 * or trailing slash, "/" separators). [mtime] is epoch millis (0 when unknown). [size] is -1 for
 * directories or unknown. [etag] is the weak listing ETag `"size-mtime"` (quotes included).
 */
data class FsEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
    val mime: String,
    val etag: String,
)

/**
 * A browsable folder tree. All paths are normalized volume-relative paths (FsPaths.normalize);
 * implementations must never escape their root. Methods block and throw [FsException].
 * Writers on a [readOnly] volume throw [FsException] with READ_ONLY.
 */
interface FileStationVolume {
    val id: String
    val label: String
    val readOnly: Boolean

    /** NOT_FOUND when missing. */
    fun stat(path: String): FsEntry

    /** Direct children, unsorted, including hidden names (the API filters). NOT_FOUND if not a dir. */
    fun list(path: String): List<FsEntry>

    /** Stream positioned at [offset] bytes. Caller closes. */
    fun openRead(path: String, offset: Long = 0): InputStream

    /** Creates the file if missing (parent must exist); truncates unless [append]. Caller closes. */
    fun openWrite(path: String, append: Boolean = false): OutputStream

    /** Creates the directory and missing parents; no error if it already exists as a directory. */
    fun mkdirs(path: String)

    /** Renames within the same parent; returns the new path (providers may alter the name). EXISTS on clash. */
    fun rename(path: String, newName: String): String

    /** Deletes a file or a directory recursively. */
    fun delete(path: String)

    /** Provider-side move into [dstDir] keeping the name; false when unsupported (caller streams). */
    fun fastMove(src: String, dstDir: String): Boolean

    /** Provider-side copy into [dstDir] keeping the name; false when unsupported (caller streams). */
    fun fastCopy(src: String, dstDir: String): Boolean

    /** Free bytes on the backing storage, or -1 when unknown. */
    fun freeBytes(): Long

    /** Depth-first walk of [path] (excluded itself), files and directories. */
    fun walk(path: String): Sequence<FsEntry>
}
