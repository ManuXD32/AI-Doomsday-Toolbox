package com.example.llamadroid.harness

import android.content.Context
import com.example.llamadroid.data.proot.AgentProotEnvironmentPaths
import com.example.llamadroid.harness.runtime.HarnessRuntimePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/** A bounded, rootfs-relative entry returned by the native Debian file browser. */
data class HarnessRootfsFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val isSymbolicLink: Boolean,
    val sizeBytes: Long,
)

/**
 * Writable native explorer for the managed Debian rootfs.
 *
 * Every operation is confined to [root]. Existing symlinks may point at another location inside
 * the same rootfs, but external targets are rejected. Recursive operations never follow directory
 * symlinks. The limits also protect a phone from accidentally archiving an entire image.
 */
class HarnessRootfsFileStore(private val root: Path) {
    constructor(context: Context) : this(
        AgentProotEnvironmentPaths.rootfs(
            context,
            HarnessRuntimePaths.SHARED_ENVIRONMENT_ID,
        ).toPath()
    )

    private val rootPath = root.toAbsolutePath().normalize()
    private val rootReal = rootPath.toRealPath()

    init {
        require(Files.isDirectory(rootReal)) { "HARNESS_ROOTFS_UNAVAILABLE" }
    }

    suspend fun listDirectory(path: String = "/"): Result<List<HarnessRootfsFileEntry>> = operation {
        val directory = resolveRead(path)
        require(Files.isDirectory(directory)) { "ROOTFS_NOT_DIRECTORY" }
        Files.newDirectoryStream(directory).use { children ->
            children.map { child ->
                val attributes = Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                HarnessRootfsFileEntry(
                    path = displayPath(child),
                    name = child.fileName.toString(),
                    isDirectory = attributes.isDirectory && !Files.isSymbolicLink(child),
                    isSymbolicLink = Files.isSymbolicLink(child),
                    sizeBytes = attributes.takeIf { it.isRegularFile }?.size() ?: 0L,
                )
            }.sortedWith(compareByDescending<HarnessRootfsFileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    suspend fun readText(path: String, maxBytes: Int = MAX_FILE_BYTES): Result<String> = operation {
        val file = resolveRead(path)
        require(Files.isRegularFile(file)) { "ROOTFS_NOT_FILE" }
        require(Files.size(file) <= maxBytes) { "ROOTFS_FILE_TOO_LARGE" }
        Files.readAllBytes(file).toString(Charsets.UTF_8)
    }

    suspend fun readBytes(path: String, maxBytes: Int = MAX_FILE_BYTES): Result<ByteArray> = operation {
        val file = resolveRead(path)
        require(Files.isRegularFile(file)) { "ROOTFS_NOT_FILE" }
        require(Files.size(file) <= maxBytes) { "ROOTFS_FILE_TOO_LARGE" }
        Files.readAllBytes(file)
    }

    suspend fun writeText(path: String, text: String): Result<Unit> = operation {
        writeBytesInternal(path, text.toByteArray(Charsets.UTF_8))
    }

    suspend fun writeBytes(path: String, bytes: ByteArray): Result<Unit> = operation {
        writeBytesInternal(path, bytes)
    }

    suspend fun upload(path: String, input: InputStream): Result<Unit> = operation {
        val bytes = input.use { readBounded(it, MAX_FILE_BYTES) }
        writeBytesInternal(path, bytes)
    }

    suspend fun createFolder(path: String): Result<Unit> = operation {
        val directory = resolveMutation(path)
        require(!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) { "ROOTFS_PATH_EXISTS" }
        Files.createDirectories(directory.parent ?: rootPath)
        Files.createDirectory(directory)
    }

    suspend fun createFile(path: String): Result<Unit> = operation {
        val file = resolveMutation(path)
        require(!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) { "ROOTFS_PATH_EXISTS" }
        Files.createDirectories(file.parent ?: rootPath)
        Files.createFile(file)
    }

    suspend fun delete(path: String, recursive: Boolean = true): Result<Unit> = operation {
        val target = resolveMutation(path)
        require(target != rootPath) { "ROOTFS_ROOT_DELETE_FORBIDDEN" }
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) return@operation
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
            require(recursive) { "ROOTFS_DIRECTORY_RECURSIVE_REQUIRED" }
            Files.walkFileTree(target, noFollowDeleteVisitor())
        } else {
            Files.deleteIfExists(target)
        }
    }

    suspend fun rename(source: String, destination: String): Result<Unit> = operation {
        val from = resolveMutation(source)
        val to = resolveMutation(destination)
        require(from != rootPath && from != to) { "ROOTFS_RENAME_FORBIDDEN" }
        require(Files.exists(from, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(from)) { "ROOTFS_PATH_MISSING" }
        require(!Files.exists(to, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(to)) { "ROOTFS_DESTINATION_EXISTS" }
        Files.createDirectories(to.parent ?: rootPath)
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(from, to)
        }
    }

    /** Moves an entry without following a directory symlink during the operation. */
    suspend fun move(source: String, destination: String): Result<Unit> = rename(source, destination)

    suspend fun copy(source: String, destination: String): Result<Unit> = operation {
        val sourcePath = lexicalPath(source)
        validateComponents(sourcePath, allowExternalLeafSymlink = false)
        require(!Files.isSymbolicLink(sourcePath)) { "ROOTFS_SYMLINK_COPY_FORBIDDEN" }
        val from = sourcePath.toRealPath()
        val to = resolveMutation(destination)
        require(from != rootPath && from != to && !to.startsWith(from)) { "ROOTFS_COPY_FORBIDDEN" }
        require(!Files.exists(to, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(to)) { "ROOTFS_DESTINATION_EXISTS" }
        copyTreeNoFollow(from, to, ArchiveBudget())
    }

    /** Exports a file or directory to a bounded `.tar.gz` stream for Android document providers. */
    suspend fun exportArchive(path: String, output: OutputStream): Result<Unit> = operation {
        val sourcePath = lexicalPath(path)
        validateComponents(sourcePath, allowExternalLeafSymlink = false)
        require(!Files.isSymbolicLink(sourcePath)) { "ROOTFS_SYMLINK_ARCHIVE_FORBIDDEN" }
        val source = sourcePath.toRealPath()
        val budget = ArchiveBudget()
        TarArchiveOutputStream(GZIPOutputStream(output)).use { archive ->
            archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            archiveTree(source, archive, source.fileName?.toString()?.ifBlank { "root" } ?: "root", budget)
        }
    }

    /** Creates a bounded tar.gz file inside the managed rootfs. */
    suspend fun compressToArchive(sourcePath: String, destinationPath: String): Result<Unit> = operation {
        val sourceLexical = lexicalPath(sourcePath)
        validateComponents(sourceLexical, allowExternalLeafSymlink = false)
        require(!Files.isSymbolicLink(sourceLexical)) { "ROOTFS_SYMLINK_ARCHIVE_FORBIDDEN" }
        val source = sourceLexical.toRealPath()
        val destination = resolveMutation(destinationPath)
        require(source != rootPath && destination != rootPath) { "ROOTFS_ARCHIVE_PATH_FORBIDDEN" }
        require(destination != source && !destination.startsWith(source)) { "ROOTFS_ARCHIVE_PATH_FORBIDDEN" }
        require(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(destination)) {
            "ROOTFS_DESTINATION_EXISTS"
        }
        Files.createDirectories(destination.parent ?: rootPath)
        val temporary = Files.createTempFile(
            destination.parent ?: rootPath,
            ".rootfs-archive-${UUID.randomUUID()}",
            ".tmp",
        )
        try {
            Files.newOutputStream(temporary).use { output ->
                TarArchiveOutputStream(GZIPOutputStream(output)).use { archive ->
                    archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    archiveTree(
                        source,
                        archive,
                        source.fileName?.toString()?.ifBlank { "root" } ?: "root",
                        ArchiveBudget(),
                    )
                }
            }
            moveTemporary(temporary, destination)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Extracts a bounded tar.gz file into a rootfs directory. Links and special files are rejected. */
    suspend fun extractArchive(archivePath: String, destinationPath: String = "/"): Result<Unit> = operation {
        val archiveLexical = lexicalPath(archivePath)
        validateComponents(archiveLexical, allowExternalLeafSymlink = false)
        require(!Files.isSymbolicLink(archiveLexical)) { "ROOTFS_SYMLINK_ARCHIVE_FORBIDDEN" }
        val archiveFile = archiveLexical.toRealPath()
        require(Files.isRegularFile(archiveFile)) { "ROOTFS_ARCHIVE_NOT_FILE" }

        val destination = resolveMutation(destinationPath)
        require(!Files.isSymbolicLink(destination)) { "ROOTFS_SYMLINK_DESTINATION_FORBIDDEN" }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) { "ROOTFS_NOT_DIRECTORY" }
            validateComponents(destination, allowExternalLeafSymlink = false)
        } else {
            Files.createDirectories(destination)
        }

        val staging = Files.createTempDirectory(rootPath, ".rootfs-extract-${UUID.randomUUID()}")
        val committed = mutableListOf<Path>()
        try {
            val budget = ArchiveBudget()
            TarArchiveInputStream(
                BufferedInputStream(GZIPInputStream(Files.newInputStream(archiveFile)))
            ).use { archive ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = archive.nextEntry ?: break
                    val relative = safeArchiveEntryPath(entry.name)
                    budget.accept(if (entry.isDirectory) 0L else entry.size)
                    val regularFile = entry.linkFlag == TarArchiveEntry.LF_NORMAL ||
                        entry.linkFlag == TarArchiveEntry.LF_OLDNORM
                    require(entry.isDirectory || regularFile) { "ROOTFS_ARCHIVE_SPECIAL_FILE_FORBIDDEN" }
                    require(!entry.isSymbolicLink && !entry.isLink) { "ROOTFS_ARCHIVE_LINK_FORBIDDEN" }
                    val target = staging.resolve(relative).normalize()
                    require(target.startsWith(staging)) { "ROOTFS_ARCHIVE_PATH_TRAVERSAL" }
                    require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
                        "ROOTFS_ARCHIVE_DUPLICATE_ENTRY"
                    }
                    ensureDirectoryParents(staging, target.parent ?: staging)
                    if (entry.isDirectory) {
                        Files.createDirectory(target)
                    } else {
                        Files.newOutputStream(target).use { output ->
                            copyArchiveEntry(archive, output, entry.size)
                        }
                    }
                }
            }

            Files.newDirectoryStream(staging).use { children ->
                children.forEach { child ->
                    val target = destination.resolve(child.fileName.toString()).normalize()
                    require(target.startsWith(rootPath)) { "ROOTFS_ARCHIVE_PATH_ESCAPE" }
                    require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
                        "ROOTFS_DESTINATION_EXISTS"
                    }
                }
            }
            Files.newDirectoryStream(staging).use { children ->
                children.forEach { child ->
                    val target = destination.resolve(child.fileName.toString()).normalize()
                    moveTemporary(child, target)
                    committed.add(target)
                }
            }
        } catch (failure: Throwable) {
            committed.asReversed().forEach { committedPath ->
                runCatching { deletePathNoFollow(committedPath) }
            }
            throw failure
        } finally {
            deletePathNoFollow(staging)
        }
    }

    private suspend fun <T> operation(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            Result.success(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Result.failure(failure)
        }
    }

    private fun writeBytesInternal(path: String, bytes: ByteArray) {
        require(bytes.size <= MAX_FILE_BYTES) { "ROOTFS_FILE_TOO_LARGE" }
        val file = resolveMutation(path)
        require(!Files.isSymbolicLink(file)) { "ROOTFS_SYMLINK_WRITE_FORBIDDEN" }
        Files.createDirectories(file.parent ?: rootPath)
        Files.write(file, bytes)
    }

    /** Resolves an existing path and checks every symlink target, including the final component. */
    private fun resolveRead(path: String): Path {
        val candidate = lexicalPath(path)
        validateComponents(candidate, allowExternalLeafSymlink = false)
        return candidate.toRealPath()
    }

    /** Resolves a mutation path, allowing a symlink leaf to be removed or renamed safely. */
    private fun resolveMutation(path: String): Path {
        val candidate = lexicalPath(path)
        validateComponents(candidate, allowExternalLeafSymlink = true)
        return candidate
    }

    private fun lexicalPath(path: String): Path {
        val normalized = path.trim().ifBlank { "/" }.replace('\\', '/')
        require('\u0000' !in normalized)
        require(normalized.split('/').none { it == ".." }) { "ROOTFS_PATH_TRAVERSAL" }
        val candidate = rootPath.resolve(normalized.removePrefix("/")).normalize()
        require(candidate.startsWith(rootPath)) { "ROOTFS_PATH_ESCAPE" }
        return candidate
    }

    private fun validateComponents(candidate: Path, allowExternalLeafSymlink: Boolean) {
        val relative = rootPath.relativize(candidate)
        var current = rootPath
        relative.forEachIndexed { index, component ->
            current = current.resolve(component)
            val exists = Files.exists(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)
            if (!exists) return
            if (Files.isSymbolicLink(current)) {
                val leaf = index == relative.nameCount - 1
                if (leaf && allowExternalLeafSymlink) return@forEachIndexed
                val real = current.toRealPath()
                require(real.startsWith(rootReal)) { "ROOTFS_SYMLINK_ESCAPE" }
            }
        }
    }

    private fun displayPath(path: Path): String {
        val relative = rootPath.relativize(path)
        return if (relative.nameCount == 0) "/" else "/${relative.joinToString("/")}"
    }

    private suspend fun copyTreeNoFollow(source: Path, destination: Path, budget: ArchiveBudget) {
        currentCoroutineContext().ensureActive()
        if (Files.isSymbolicLink(source)) throw IllegalArgumentException("ROOTFS_SYMLINK_COPY_FORBIDDEN")
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            budget.accept(0)
            Files.createDirectories(destination)
            Files.newDirectoryStream(source).use { children -> children.forEach { child ->
                copyTreeNoFollow(child, destination.resolve(child.fileName), budget)
            } }
        } else {
            require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { "ROOTFS_SPECIAL_FILE_FORBIDDEN" }
            budget.accept(Files.size(source))
            Files.createDirectories(destination.parent ?: rootPath)
            Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }

    private suspend fun archiveTree(
        source: Path,
        archive: TarArchiveOutputStream,
        name: String,
        budget: ArchiveBudget,
    ) {
        currentCoroutineContext().ensureActive()
        if (Files.isSymbolicLink(source)) return
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            budget.accept(0)
            archive.putArchiveEntry(TarArchiveEntry("$name/"))
            archive.closeArchiveEntry()
            Files.newDirectoryStream(source).use { children -> children.forEach { child ->
                archiveTree(child, archive, "$name/${child.fileName}", budget)
            } }
        } else {
            require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { "ROOTFS_SPECIAL_FILE_FORBIDDEN" }
            val size = Files.size(source)
            budget.accept(size)
            archive.putArchiveEntry(TarArchiveEntry(name).apply { this.size = size })
            Files.newInputStream(source).use { input -> input.copyTo(archive, COPY_BUFFER_SIZE) }
            archive.closeArchiveEntry()
        }
    }

    private fun safeArchiveEntryPath(raw: String): String {
        var normalized = raw.replace('\\', '/').trimEnd('/')
        while (normalized.startsWith("./")) normalized = normalized.removePrefix("./")
        require(normalized.isNotBlank() && !normalized.startsWith('/')) { "ROOTFS_ARCHIVE_PATH_TRAVERSAL" }
        val parts = normalized.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "ROOTFS_ARCHIVE_PATH_TRAVERSAL"
        }
        return parts.joinToString("/")
    }

    private suspend fun copyArchiveEntry(archive: TarArchiveInputStream, output: OutputStream, size: Long) {
        require(size in 0..MAX_FILE_BYTES.toLong()) { "ROOTFS_ARCHIVE_LIMIT" }
        var copied = 0L
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = archive.read(buffer)
            if (read < 0) break
            copied += read
            require(copied <= size) { "ROOTFS_ARCHIVE_ENTRY_SIZE_INVALID" }
            output.write(buffer, 0, read)
        }
        require(copied == size) { "ROOTFS_ARCHIVE_ENTRY_SIZE_INVALID" }
    }

    private fun ensureDirectoryParents(base: Path, directory: Path) {
        require(directory.startsWith(base)) { "ROOTFS_PATH_ESCAPE" }
        var current = base
        base.relativize(directory).forEach { component ->
            current = current.resolve(component)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
                require(!Files.isSymbolicLink(current) && Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    "ROOTFS_ARCHIVE_PARENT_INVALID"
                }
            } else {
                Files.createDirectory(current)
            }
        }
    }

    private fun moveTemporary(source: Path, destination: Path) {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, destination)
        }
    }

    private fun deletePathNoFollow(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) return
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            Files.walkFileTree(path, noFollowDeleteVisitor())
        } else {
            Files.deleteIfExists(path)
        }
    }

    private fun noFollowDeleteVisitor() = object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.deleteIfExists(file)
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
            if (Files.isSymbolicLink(file)) {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }
            throw exc
        }

        override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
            if (exc != null) throw exc
            Files.deleteIfExists(dir)
            return FileVisitResult.CONTINUE
        }
    }

    private class ArchiveBudget {
        private var entries = 0
        private var bytes = 0L
        fun accept(size: Long) {
            require(size in 0..MAX_ARCHIVE_BYTES) { "ROOTFS_ARCHIVE_LIMIT" }
            entries++
            bytes += size
            require(entries <= MAX_ARCHIVE_ENTRIES && bytes <= MAX_ARCHIVE_BYTES) { "ROOTFS_ARCHIVE_LIMIT" }
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 64 * 1024 * 1024
        const val MAX_ARCHIVE_BYTES = 256L * 1024 * 1024
        const val MAX_ARCHIVE_ENTRIES = 10_000
        const val COPY_BUFFER_SIZE = 16 * 1024

        fun readBounded(input: InputStream, limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= limit) { "ROOTFS_FILE_TOO_LARGE" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }
}
