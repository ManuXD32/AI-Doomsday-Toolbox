package com.example.llamadroid.data.model.library

import com.example.llamadroid.data.db.AppDatabase
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.util.UUID

/** Model filenames remain primary keys, while nested repository paths stay intact on disk. */
internal suspend fun availableModelRecordKey(database: AppDatabase, file: File, artifactId: String): String {
    val dao = database.modelDao()
    dao.getModelByPath(file.absolutePath)?.let { return it.filename }
    val basename = file.name
    if (dao.getModelByFilename(basename) == null) return basename
    val digest = MessageDigest.getInstance("SHA-256").digest(artifactId.toByteArray())
        .take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
    val prefix = "$digest-$basename"
    var candidate = prefix
    var sequence = 1
    while (dao.getModelByFilename(candidate) != null) candidate = "${sequence++}-$prefix"
    return candidate
}

/** Never replace another repository's payload merely because it shares a destination name. */
internal fun copyArtifactWithoutOverwrite(source: File, destination: File, acceptIdentical: Boolean = false) {
    if (source.canonicalPath == destination.canonicalPath) return
    if (acceptIdentical && destination.exists() && identicalArtifactFiles(source, destination)) return
    if (destination.exists()) throw ModelLibraryException(
        ModelLibraryErrorCode.BUNDLE_ITEM_PATH_INVALID,
        "The destination already exists. Choose a different bundle file path."
    )
    destination.parentFile?.mkdirs()
    val partial = File(destination.parentFile, ".${UUID.randomUUID()}.model-install.part")
    try {
        if (source.isDirectory) {
            check(source.copyRecursively(partial, overwrite = false)) { "Could not prepare model directory" }
        } else {
            source.copyTo(partial, overwrite = false)
        }
        Files.move(partial.toPath(), destination.toPath())
    } finally {
        // This private temporary copy has no installed-model association. The source is retained.
        if (partial.isDirectory) partial.deleteRecursively() else partial.delete()
    }
}

private fun identicalArtifactFiles(first: File, second: File): Boolean {
    if (first.isDirectory != second.isDirectory) return false
    if (first.isDirectory) {
        val a = first.walkTopDown().filter { it.isFile }.map { it.relativeTo(first).path }.toSet()
        val b = second.walkTopDown().filter { it.isFile }.map { it.relativeTo(second).path }.toSet()
        return a == b && a.all { identicalArtifactFiles(File(first, it), File(second, it)) }
    }
    if (!first.isFile || !second.isFile || first.length() != second.length()) return false
    fun compare(a: java.io.InputStream, b: java.io.InputStream): Boolean {
        val left = ByteArray(64 * 1024)
        val right = ByteArray(left.size)
        while (true) {
            val size = a.read(left)
            if (size < 0) return b.read() < 0
            var read = 0
            while (read < size) {
                val count = b.read(right, read, size - read)
                if (count < 0) return false
                read += count
            }
            if (!(0 until size).all { left[it] == right[it] }) return false
        }
    }
    return first.inputStream().buffered().use { a -> second.inputStream().buffered().use { b -> compare(a, b) } }
}
