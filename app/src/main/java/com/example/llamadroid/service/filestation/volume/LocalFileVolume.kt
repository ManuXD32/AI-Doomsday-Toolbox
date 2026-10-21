package com.example.llamadroid.service.filestation.volume

import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** java.io.File volume with canonical-path containment (symlinks that leave the root are rejected). */
class LocalFileVolume(
    override val id: String,
    override val label: String,
    root: File,
    override val readOnly: Boolean = false,
) : FileStationVolume {
    private val rootCanonical: File = root.canonicalFile

    private fun resolve(path: String): File {
        val norm = FsPaths.normalize(path)
        val file = if (norm.isEmpty()) rootCanonical else File(rootCanonical, norm)
        val canonical = try {
            file.canonicalFile
        } catch (e: IOException) {
            throw FsException(FsErrorCode.IO, "cannot resolve path", e)
        }
        if (canonical != rootCanonical &&
            !canonical.path.startsWith(rootCanonical.path + File.separator)
        ) {
            throw FsException(FsErrorCode.INVALID_PATH, "path escapes volume")
        }
        return canonical
    }

    private fun writable() {
        if (readOnly) throw FsException(FsErrorCode.READ_ONLY, "volume is read-only")
    }

    private fun entryOf(file: File, path: String): FsEntry {
        val isDir = file.isDirectory
        val size = if (isDir) -1L else file.length()
        val mtime = file.lastModified()
        return FsEntry(
            name = if (path.isEmpty()) label else FsPaths.name(path),
            path = path,
            isDir = isDir,
            size = size,
            mtime = mtime,
            mime = if (isDir) "inode/directory" else FsPaths.guessMime(file.name),
            etag = "\"$size-$mtime\"",
        )
    }

    override fun stat(path: String): FsEntry {
        val norm = FsPaths.normalize(path)
        val file = resolve(norm)
        if (!file.exists()) throw FsException(FsErrorCode.NOT_FOUND, "not found: $norm")
        return entryOf(file, norm)
    }

    override fun list(path: String): List<FsEntry> {
        val norm = FsPaths.normalize(path)
        val dir = resolve(norm)
        if (!dir.isDirectory) throw FsException(FsErrorCode.NOT_FOUND, "not a directory: $norm")
        val children = dir.listFiles() ?: throw FsException(FsErrorCode.IO, "cannot list: $norm")
        return children.mapNotNull { child ->
            val childPath = FsPaths.join(norm, child.name)
            try {
                // Re-resolve so symlinks escaping the root are not exposed.
                resolve(childPath)
                entryOf(child, childPath)
            } catch (_: FsException) {
                null
            }
        }
    }

    override fun openRead(path: String, offset: Long): InputStream {
        val file = resolve(path)
        if (!file.isFile) throw FsException(FsErrorCode.NOT_FOUND, "not a file: $path")
        return try {
            val input = FileInputStream(file)
            if (offset > 0) input.channel.position(offset)
            input
        } catch (e: FileNotFoundException) {
            throw FsException(FsErrorCode.NOT_FOUND, "not found: $path", e)
        } catch (e: IOException) {
            throw FsException(FsErrorCode.IO, "cannot read: $path", e)
        }
    }

    override fun openWrite(path: String, append: Boolean): OutputStream {
        writable()
        val norm = FsPaths.normalize(path)
        if (norm.isEmpty()) throw FsException(FsErrorCode.INVALID_PATH, "cannot write the root")
        val file = resolve(norm)
        val parent = file.parentFile
        if (parent == null || !parent.isDirectory) {
            throw FsException(FsErrorCode.NOT_FOUND, "parent missing: ${FsPaths.parent(norm)}")
        }
        if (file.isDirectory) throw FsException(FsErrorCode.EXISTS, "is a directory: $norm")
        return try {
            FileOutputStream(file, append)
        } catch (e: IOException) {
            throw FsException(FsErrorCode.IO, "cannot write: $norm", e)
        }
    }

    override fun mkdirs(path: String) {
        writable()
        val dir = resolve(path)
        if (dir.isDirectory) return
        if (dir.exists()) throw FsException(FsErrorCode.EXISTS, "a file exists at: $path")
        if (!dir.mkdirs() && !dir.isDirectory) throw FsException(FsErrorCode.IO, "cannot create: $path")
    }

    override fun rename(path: String, newName: String): String {
        writable()
        val norm = FsPaths.normalize(path)
        if (norm.isEmpty()) throw FsException(FsErrorCode.INVALID_PATH, "cannot rename the root")
        val name = FsPaths.validateSegment(newName)
        val src = resolve(norm)
        if (!src.exists()) throw FsException(FsErrorCode.NOT_FOUND, "not found: $norm")
        val newPath = FsPaths.join(FsPaths.parent(norm), name)
        if (newPath == norm) return norm
        val dst = resolve(newPath)
        if (dst.exists()) throw FsException(FsErrorCode.EXISTS, "exists: $newPath")
        if (!src.renameTo(dst)) throw FsException(FsErrorCode.IO, "rename failed: $norm")
        return newPath
    }

    override fun delete(path: String) {
        writable()
        val norm = FsPaths.normalize(path)
        if (norm.isEmpty()) throw FsException(FsErrorCode.INVALID_PATH, "cannot delete the root")
        // Resolve only the parent so a symlink leaving the root is deleted as a link, never followed.
        val target = File(resolve(FsPaths.parent(norm)), FsPaths.name(norm))
        if (!java.nio.file.Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw FsException(FsErrorCode.NOT_FOUND, "not found: $norm")
        }
        if (!deleteRecursive(target)) throw FsException(FsErrorCode.IO, "delete failed: $norm")
    }

    private fun deleteRecursive(file: File): Boolean {
        if (file.isDirectory && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            file.listFiles()?.forEach { if (!deleteRecursive(it)) return false }
        }
        return file.delete()
    }

    override fun fastMove(src: String, dstDir: String): Boolean {
        writable()
        val srcNorm = FsPaths.normalize(src)
        if (srcNorm.isEmpty()) return false
        val from = resolve(srcNorm)
        val dir = resolve(dstDir)
        if (!from.exists() || !dir.isDirectory) return false
        val to = File(dir, from.name)
        if (to.exists()) return false
        return from.renameTo(to)
    }

    override fun fastCopy(src: String, dstDir: String): Boolean = false

    // File.usableSpace is the right call here; this class must stay pure JVM (no StatFs/StorageManager).
    @Suppress("UsableSpace")
    override fun freeBytes(): Long = rootCanonical.usableSpace.takeIf { it > 0 } ?: -1L

    override fun walk(path: String): Sequence<FsEntry> = sequence {
        for (child in list(path)) {
            yield(child)
            if (child.isDir) yieldAll(walk(child.path))
        }
    }
}
