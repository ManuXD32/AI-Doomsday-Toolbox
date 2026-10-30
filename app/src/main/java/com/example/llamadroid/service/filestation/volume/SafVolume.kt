package com.example.llamadroid.service.filestation.volume

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.os.StatFs
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Storage Access Framework tree volume. Device-only; pure mapping and caching live in
 * SafPathCache.kt. One children query per folder, path -> docId LRU, evict + retry once on
 * FileNotFoundException.
 */
class SafVolume(
    private val context: Context,
    private val treeUri: Uri,
    override val id: String,
    override val label: String,
    override val readOnly: Boolean = false,
) : FileStationVolume {
    private val resolver = context.contentResolver
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)
    private val cache = SafPathCache()

    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
    )

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun writable() {
        if (readOnly) throw FsException(FsErrorCode.READ_ONLY, "volume is read-only")
    }

    private fun <T> withRetry(path: String, block: () -> T): T = try {
        block()
    } catch (_: FileNotFoundException) {
        cache.invalidateSubtree(path)
        try {
            block()
        } catch (e: FileNotFoundException) {
            throw FsException(FsErrorCode.NOT_FOUND, "not found: $path", e)
        }
    } catch (e: SecurityException) {
        throw FsException(FsErrorCode.IO, "permission lost for volume $id", e)
    } catch (e: IllegalArgumentException) {
        throw FsException(FsErrorCode.NOT_FOUND, "not found: $path", e)
    }

    private fun readRows(uri: Uri): List<SafRow> {
        val out = ArrayList<SafRow>()
        resolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += SafRow(
                    docId = c.getString(0) ?: continue,
                    name = c.getString(1) ?: continue,
                    mime = c.getString(2) ?: "",
                    size = if (c.isNull(3)) -1L else c.getLong(3),
                    mtime = if (c.isNull(4)) 0L else c.getLong(4),
                    flags = if (c.isNull(5)) 0 else c.getInt(5),
                )
            }
        } ?: throw FileNotFoundException("query failed")
        return out
    }

    private fun childRows(path: String): List<SafRow> = withRetry(path) {
        val docId = docIdOf(path)
        val rows = readRows(DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId))
        rows.forEach { cache.put(FsPaths.join(path, it.name), it.docId) }
        rows
    }

    private fun rowOf(path: String): SafRow {
        if (path.isEmpty()) {
            return withRetry(path) { readRows(docUri(rootDocId)).firstOrNull() }
                ?: throw FsException(FsErrorCode.NOT_FOUND, "root missing")
        }
        return childRows(FsPaths.parent(path)).firstOrNull { it.name == FsPaths.name(path) }
            ?: run {
                cache.invalidateSubtree(path)
                throw FsException(FsErrorCode.NOT_FOUND, "not found: $path")
            }
    }

    private fun docIdOf(path: String): String {
        if (path.isEmpty()) return rootDocId
        cache.get(path)?.let { return it }
        val row = childRows(FsPaths.parent(path)).firstOrNull { it.name == FsPaths.name(path) }
            ?: throw FsException(FsErrorCode.NOT_FOUND, "not found: $path")
        return row.docId
    }

    private fun exists(path: String): Boolean = try { rowOf(path); true } catch (_: FsException) { false }

    override fun stat(path: String): FsEntry {
        val norm = FsPaths.normalize(path)
        val row = rowOf(norm)
        val entry = SafRows.toEntry(FsPaths.parent(norm), row)
        return if (norm.isEmpty()) entry.copy(name = label, path = "") else entry
    }

    override fun list(path: String): List<FsEntry> {
        val norm = FsPaths.normalize(path)
        if (!rowOf(norm).isDir) throw FsException(FsErrorCode.NOT_FOUND, "not a directory: $norm")
        return childRows(norm).map { SafRows.toEntry(norm, it) }
    }

    override fun openRead(path: String, offset: Long): InputStream {
        val norm = FsPaths.normalize(path)
        if (rowOf(norm).isDir) throw FsException(FsErrorCode.NOT_FOUND, "not a file: $norm")
        return withRetry(norm) {
            val pfd = resolver.openFileDescriptor(docUri(docIdOf(norm)), "r")
                ?: throw FileNotFoundException(norm)
            PfdInputStream(pfd, offset)
        }
    }

    override fun openWrite(path: String, append: Boolean): OutputStream {
        writable()
        val norm = FsPaths.normalize(path)
        if (norm.isEmpty()) throw FsException(FsErrorCode.INVALID_PATH, "cannot write the root")
        val mode = if (append) "wa" else "wt"
        val existing = try { rowOf(norm) } catch (_: FsException) { null }
        if (existing?.isDir == true) throw FsException(FsErrorCode.EXISTS, "is a directory: $norm")
        val docId = existing?.docId ?: createFile(norm)
        return withRetry(norm) {
            resolver.openOutputStream(docUri(docId), mode) ?: throw FileNotFoundException(norm)
        }
    }

    /** Creates an empty file; re-reads DISPLAY_NAME because providers may rename on clash. */
    private fun createFile(norm: String): String {
        val parent = FsPaths.parent(norm)
        val name = FsPaths.name(norm)
        if (!rowOf(parent).isDir) throw FsException(FsErrorCode.NOT_FOUND, "parent missing: $parent")
        val parentUri = docUri(docIdOf(parent))
        val created = withRetry(parent) {
            DocumentsContract.createDocument(resolver, parentUri, FsPaths.guessMime(name), name)
        } ?: throw FsException(FsErrorCode.IO, "cannot create: $norm")
        val createdId = DocumentsContract.getDocumentId(created)
        val actual = readRows(docUri(createdId)).firstOrNull()?.name
        if (actual != null && actual != name) {
            // The provider saw a clashing file our cache missed; drop the duplicate and reuse the original.
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            cache.invalidateSubtree(parent)
            return rowOf(norm).docId
        }
        cache.put(norm, createdId)
        return createdId
    }

    override fun mkdirs(path: String) {
        writable()
        val norm = FsPaths.normalize(path)
        var current = ""
        for (segment in norm.split('/').filter { it.isNotEmpty() }) {
            val next = FsPaths.join(current, segment)
            val row = try { rowOf(next) } catch (_: FsException) { null }
            if (row != null) {
                if (!row.isDir) throw FsException(FsErrorCode.EXISTS, "a file exists at: $next")
            } else {
                val parentUri = docUri(docIdOf(current))
                val created = DocumentsContract.createDocument(
                    resolver, parentUri, SafRow.DIR_MIME, segment,
                ) ?: throw FsException(FsErrorCode.IO, "cannot create: $next")
                cache.put(next, DocumentsContract.getDocumentId(created))
            }
            current = next
        }
    }

    override fun rename(path: String, newName: String): String {
        writable()
        val norm = FsPaths.normalize(path)
        if (norm.isEmpty()) throw FsException(FsErrorCode.INVALID_PATH, "cannot rename the root")
        val name = FsPaths.validateSegment(newName)
        val row = rowOf(norm)
        if (name == row.name) return norm
        val newPath = FsPaths.join(FsPaths.parent(norm), name)
        if (exists(newPath)) throw FsException(FsErrorCode.EXISTS, "exists: $newPath")
        val renamed = DocumentsContract.renameDocument(resolver, docUri(row.docId), name)
            ?: throw FsException(FsErrorCode.IO, "rename failed: $norm")
        cache.invalidateSubtree(norm)
        // The provider may change both the docId and the final name.
        val actual = readRows(docUri(DocumentsContract.getDocumentId(renamed))).firstOrNull()?.name ?: name
        return FsPaths.join(FsPaths.parent(norm), actual)
    }

    override fun delete(path: String) {
        writable()
        val norm = FsPaths.normalize(path)
        if (norm.isEmpty()) throw FsException(FsErrorCode.INVALID_PATH, "cannot delete the root")
        val row = rowOf(norm)
        val ok = try {
            DocumentsContract.deleteDocument(resolver, docUri(row.docId))
        } catch (e: FileNotFoundException) {
            throw FsException(FsErrorCode.NOT_FOUND, "not found: $norm", e)
        }
        cache.invalidateSubtree(norm)
        if (!ok) throw FsException(FsErrorCode.IO, "delete failed: $norm")
    }

    override fun fastMove(src: String, dstDir: String): Boolean {
        writable()
        val srcNorm = FsPaths.normalize(src)
        if (srcNorm.isEmpty()) return false
        return try {
            val row = rowOf(srcNorm)
            if (row.flags and SafRow.FLAG_SUPPORTS_MOVE == 0) return false
            val dst = FsPaths.normalize(dstDir)
            if (exists(FsPaths.join(dst, row.name)) || !rowOf(dst).isDir) return false
            val moved = DocumentsContract.moveDocument(
                resolver, docUri(row.docId), docUri(docIdOf(FsPaths.parent(srcNorm))), docUri(docIdOf(dst)),
            )
            cache.invalidateSubtree(srcNorm)
            cache.invalidateSubtree(FsPaths.join(dst, row.name))
            moved != null
        } catch (_: Exception) {
            cache.invalidateSubtree(srcNorm)
            false
        }
    }

    override fun fastCopy(src: String, dstDir: String): Boolean {
        writable()
        val srcNorm = FsPaths.normalize(src)
        if (srcNorm.isEmpty()) return false
        return try {
            val row = rowOf(srcNorm)
            if (row.flags and SafRow.FLAG_SUPPORTS_COPY == 0) return false
            val dst = FsPaths.normalize(dstDir)
            if (exists(FsPaths.join(dst, row.name)) || !rowOf(dst).isDir) return false
            val copy = DocumentsContract.copyDocument(resolver, docUri(row.docId), docUri(docIdOf(dst)))
            cache.invalidateSubtree(FsPaths.join(dst, row.name))
            copy != null
        } catch (_: Exception) {
            false
        }
    }

    /** Free bytes only when the docId maps to a real path (ExternalStorageProvider), else -1. */
    override fun freeBytes(): Long {
        val path = SafRows.externalStoragePath(
            rootDocId, Environment.getExternalStorageDirectory().absolutePath,
        ) ?: return -1L
        return try {
            if (!java.io.File(path).exists()) -1L else StatFs(path).availableBytes
        } catch (_: Exception) {
            -1L
        }
    }

    override fun walk(path: String): Sequence<FsEntry> = sequence {
        for (child in list(path)) {
            yield(child)
            if (child.isDir) yieldAll(walk(child.path))
        }
    }

    /** Seekable read: channel.position(offset) with a skip() fallback for non-seekable descriptors. */
    private class PfdInputStream(private val pfd: ParcelFileDescriptor, offset: Long) :
        FilterInputStream(FileInputStream(pfd.fileDescriptor)) {
        init {
            if (offset > 0) {
                try {
                    (`in` as FileInputStream).channel.position(offset)
                } catch (_: IOException) {
                    var left = offset
                    while (left > 0) {
                        val n = `in`.skip(left)
                        if (n <= 0) break
                        left -= n
                    }
                }
            }
        }

        override fun close() {
            try { super.close() } finally { pfd.close() }
        }
    }
}
