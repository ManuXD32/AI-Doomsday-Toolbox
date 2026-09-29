package com.example.llamadroid.harness

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** An estimate of mutable guest files, never a readiness or integrity check. */
internal fun estimateHarnessStorage(roots: List<File>): Long {
    var bytes = 0L
    val visitor = object : SimpleFileVisitor<Path>() {
        override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (attrs.isRegularFile) bytes += attrs.size().coerceAtMost(Long.MAX_VALUE - bytes)
            return FileVisitResult.CONTINUE
        }

        // A running guest can unlink a file between enumeration and stat. Permissions on
        // user-managed subtrees can also change. Neither makes the runtime unusable.
        override fun visitFileFailed(path: Path, error: IOException) = FileVisitResult.CONTINUE
        override fun postVisitDirectory(path: Path, error: IOException?) = FileVisitResult.CONTINUE
    }
    roots.forEach { root ->
        try {
            if (Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                // The default walk does not follow guest links, including absolute links.
                Files.walkFileTree(root.toPath(), visitor)
            }
        } catch (_: IOException) {
            // Retain the already measured bytes and continue with the other owned roots.
        } catch (_: SecurityException) {
            // Size is optional presentation metadata, including when a root is unreadable.
        }
    }
    return bytes
}
