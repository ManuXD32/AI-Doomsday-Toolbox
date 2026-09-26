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
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
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

/** Policy applied to symbolic links while resolving a managed guest mount. */
enum class GuestSymlinkPolicy {
    /** A link may resolve only inside the host root backing the same guest mount. */
    INTERNAL_ONLY,
}

/** A confined host directory exposed at a guest path inside the PRoot namespace. */
data class GuestMount(
    val guestPrefix: String,
    val hostRoot: Path,
    val writable: Boolean = true,
    val symlinkPolicy: GuestSymlinkPolicy = GuestSymlinkPolicy.INTERNAL_ONLY,
)

/**
 * Writable native explorer for the managed Debian rootfs.
 *
 * Every operation is confined to one of the configured guest mounts. Existing symlinks may point
 * at another location inside the same mount, but external targets are rejected. Recursive
 * operations never follow directory symlinks. The limits also protect a phone from accidentally
 * archiving an entire image.
 */
class HarnessRootfsFileStore(private val mounts: List<GuestMount>) {
    constructor(root: Path) : this(listOf(GuestMount("/", root)))

    /**
     * Resolves the same mounts used by the running PRoot process. In particular, projects are a
     * host bind mount and therefore do not live below the installed rootfs directory. Keeping the
     * guest path in the UI while resolving it here makes `/workspace/projects` usable while the
     * Harness is either running or stopped.
     */
    constructor(context: Context) : this(defaultGuestMounts(context))

    private data class MountState(
        val definition: GuestMount,
        val guestPrefix: String,
        val hostRoot: Path,
        val hostReal: Path,
    )

    private data class ResolvedPath(
        val guestPath: String,
        val mount: MountState,
        val path: Path,
        /** Lexical host path, retained so callers can reject a symlink leaf before following it. */
        val lexicalPath: Path,
    )

    private val mountStates = mounts
        .map { definition ->
            val guestPrefix = normalizeGuestPrefix(definition.guestPrefix)
            val hostRoot = definition.hostRoot.toAbsolutePath().normalize()
            val hostReal = hostRoot.toRealPath()
            require(Files.isDirectory(hostReal)) { "HARNESS_MOUNT_UNAVAILABLE" }
            MountState(definition, guestPrefix, hostRoot, hostReal)
        }
        .sortedByDescending { it.guestPrefix.length }
        .also { states ->
            require(states.any { it.guestPrefix == "/" }) { "HARNESS_ROOTFS_MOUNT_MISSING" }
            require(states.map { it.guestPrefix }.distinct().size == states.size) {
                "HARNESS_DUPLICATE_GUEST_MOUNT"
            }
        }

    /** Test seam for deterministic short writes and cancellation between streamed chunks. */
    internal var copyChunkCheckpoint: (() -> Unit)? = null

    init {
        mountStates.filter { it.definition.writable }.forEach(::recoverAbandonedCopyStages)
    }

    /** Guest-only parent directories needed when a bind target is absent from the base image. */
    private val virtualGuestDirectories: Set<String> = mountStates
        .asSequence()
        .filter { it.guestPrefix != "/" }
        .flatMap { mount ->
            val parts = mount.guestPrefix.trim('/').split('/').filter { it.isNotBlank() }
            (1..parts.size).asSequence().map { index -> "/${parts.take(index).joinToString("/")}" }
        }
        .toSet()

    suspend fun listDirectory(path: String = "/"): Result<List<HarnessRootfsFileEntry>> = operation {
        val guestPath = normalizeGuestPath(path)
        val mount = selectMount(guestPath)
        val candidate = hostPath(mount, guestPath)
        val physicalEntries = if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            val directory = resolveRead(guestPath)
            require(Files.isDirectory(directory.path)) { "ROOTFS_NOT_DIRECTORY" }
            Files.newDirectoryStream(directory.path).use { children ->
                children.map { child ->
                    val attributes = Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                    HarnessRootfsFileEntry(
                        path = displayPath(child, directory),
                        name = child.fileName.toString(),
                        isDirectory = attributes.isDirectory && !Files.isSymbolicLink(child),
                        isSymbolicLink = Files.isSymbolicLink(child),
                        sizeBytes = attributes.takeIf { it.isRegularFile }?.size() ?: 0L,
                    )
                }.toList()
            }
        } else {
            require(guestPath in virtualGuestDirectories) { "ROOTFS_NOT_DIRECTORY" }
            emptyList()
        }
        val virtualEntries = virtualGuestDirectories
            .asSequence()
            .filter { virtual -> parentGuestPath(virtual) == guestPath }
            .map { virtual ->
                HarnessRootfsFileEntry(
                    path = virtual,
                    name = virtual.substringAfterLast('/'),
                    isDirectory = true,
                    isSymbolicLink = false,
                    sizeBytes = 0L,
                )
            }
            .filterNot { virtual ->
                physicalEntries.any { physical -> physical.name == virtual.name && physical.isDirectory }
            }
            .toList()
        val virtualNames = virtualEntries.map { it.name }.toSet()
        (physicalEntries.filterNot { it.name in virtualNames ||
            (directoryIsMountRoot(guestPath) && it.name == COPY_STAGE_DIRECTORY) } + virtualEntries)
            .sortedWith(compareByDescending<HarnessRootfsFileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun readText(path: String, maxBytes: Int = MAX_FILE_BYTES): Result<String> = operation {
        val file = resolveRead(path)
        require(Files.isRegularFile(file.path)) { "ROOTFS_NOT_FILE" }
        require(Files.size(file.path) <= maxBytes) { "ROOTFS_FILE_TOO_LARGE" }
        Files.readAllBytes(file.path).toString(Charsets.UTF_8)
    }

    suspend fun readBytes(path: String, maxBytes: Int = MAX_FILE_BYTES): Result<ByteArray> = operation {
        val file = resolveRead(path)
        require(Files.isRegularFile(file.path)) { "ROOTFS_NOT_FILE" }
        require(Files.size(file.path) <= maxBytes) { "ROOTFS_FILE_TOO_LARGE" }
        Files.readAllBytes(file.path)
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
        require(!Files.exists(directory.path, LinkOption.NOFOLLOW_LINKS)) { "ROOTFS_PATH_EXISTS" }
        Files.createDirectories(directory.path.parent ?: directory.mount.hostRoot)
        Files.createDirectory(directory.path)
    }

    suspend fun createFile(path: String): Result<Unit> = operation {
        val file = resolveMutation(path)
        require(!Files.exists(file.path, LinkOption.NOFOLLOW_LINKS)) { "ROOTFS_PATH_EXISTS" }
        Files.createDirectories(file.path.parent ?: file.mount.hostRoot)
        Files.createFile(file.path)
    }

    suspend fun delete(path: String, recursive: Boolean = true): Result<Unit> = operation {
        val target = resolveMutation(path)
        require(target.path != target.mount.hostRoot) { "ROOTFS_ROOT_DELETE_FORBIDDEN" }
        if (!Files.exists(target.path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target.path)) return@operation
        if (Files.isDirectory(target.path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target.path)) {
            require(recursive) { "ROOTFS_DIRECTORY_RECURSIVE_REQUIRED" }
            Files.walkFileTree(target.path, noFollowDeleteVisitor())
        } else {
            Files.deleteIfExists(target.path)
        }
    }

    suspend fun rename(source: String, destination: String): Result<Unit> = operation {
        val from = resolveMutation(source)
        val to = resolveMutation(destination)
        require(from.mount == to.mount) { "ROOTFS_CROSS_MOUNT_OPERATION" }
        require(from.path != from.mount.hostRoot && from.path != to.path) { "ROOTFS_RENAME_FORBIDDEN" }
        require(Files.exists(from.path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(from.path)) { "ROOTFS_PATH_MISSING" }
        require(!Files.exists(to.path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(to.path)) { "ROOTFS_DESTINATION_EXISTS" }
        Files.createDirectories(to.path.parent ?: to.mount.hostRoot)
        try {
            Files.move(from.path, to.path, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(from.path, to.path)
        }
    }

    /** Moves an entry without following a directory symlink during the operation. */
    suspend fun move(source: String, destination: String): Result<Unit> = rename(source, destination)

    suspend fun copy(source: String, destination: String): Result<Unit> = operation {
        val sourcePath = resolveRead(source)
        require(!Files.isSymbolicLink(sourcePath.lexicalPath)) { "ROOTFS_SYMLINK_COPY_FORBIDDEN" }
        val from = sourcePath.path.toRealPath()
        val to = resolveMutation(destination)
        require(from != sourcePath.mount.hostRoot && from != to.path && !to.path.startsWith(from)) { "ROOTFS_COPY_FORBIDDEN" }
        require(!Files.exists(to.path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(to.path)) { "ROOTFS_DESTINATION_EXISTS" }
        Files.createDirectories(to.path.parent ?: to.mount.hostRoot)
        val stageBase = copyStageBase(to.mount)
        require(!Files.isSymbolicLink(stageBase)) { "ROOTFS_SYMLINK_ESCAPE" }
        Files.createDirectories(stageBase)
        require(!Files.isSymbolicLink(stageBase) && stageBase.toRealPath() == stageBase) {
            "ROOTFS_SYMLINK_ESCAPE"
        }
        val operationId = UUID.randomUUID().toString()
        val stage = Files.createDirectory(stageBase.resolve(operationId))
        try {
            Files.write(stage.resolve(COPY_OWNER_RECORD), operationId.toByteArray(), StandardOpenOption.CREATE_NEW)
            FileChannel.open(stage.resolve(COPY_ACTIVE_LOCK), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val payload = stage.resolve("payload")
                    copyTreeNoFollow(from, payload, to.mount.hostRoot, ArchiveBudget())
                    currentCoroutineContext().ensureActive()
                    require(Files.getFileStore(payload) == Files.getFileStore(to.path.parent)) {
                        "ROOTFS_CROSS_MOUNT_OPERATION"
                    }
                    synchronized(COPY_PUBLICATION_LOCK) {
                        require(!Files.exists(to.path, LinkOption.NOFOLLOW_LINKS) &&
                            !Files.isSymbolicLink(to.path)) { "ROOTFS_DESTINATION_EXISTS" }
                        // This is a same-filesystem rename with no replacement option.
                        Files.move(payload, to.path)
                    }
                }
            }
        } catch (failure: Throwable) {
            cleanupOwnedStage(stage, operationId, failure, createdHere = true)
            throw failure
        }
        cleanupOwnedStage(stage, operationId, null)
    }

    /** Exports a file or directory to a bounded `.tar.gz` stream for Android document providers. */
    suspend fun exportArchive(path: String, output: OutputStream): Result<Unit> = operation {
        val sourcePath = resolveRead(path)
        require(!Files.isSymbolicLink(sourcePath.lexicalPath)) { "ROOTFS_SYMLINK_ARCHIVE_FORBIDDEN" }
        val source = sourcePath.path.toRealPath()
        require(sourcePath.guestPath != "/") { "ROOTFS_ARCHIVE_PATH_FORBIDDEN" }
        val budget = ArchiveBudget()
        TarArchiveOutputStream(GZIPOutputStream(output)).use { archive ->
            archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            archiveTree(source, archive, source.fileName?.toString()?.ifBlank { "root" } ?: "root", budget)
        }
    }

    /** Creates a bounded tar.gz file inside the managed rootfs. */
    suspend fun compressToArchive(sourcePath: String, destinationPath: String): Result<Unit> = operation {
        val sourceLexical = resolveRead(sourcePath)
        require(!Files.isSymbolicLink(sourceLexical.lexicalPath)) { "ROOTFS_SYMLINK_ARCHIVE_FORBIDDEN" }
        val source = sourceLexical.path.toRealPath()
        val destination = resolveMutation(destinationPath)
        require(sourceLexical.guestPath != "/" && destination.path != destination.mount.hostRoot) { "ROOTFS_ARCHIVE_PATH_FORBIDDEN" }
        require(destination.path != source && !destination.path.startsWith(source)) { "ROOTFS_ARCHIVE_PATH_FORBIDDEN" }
        require(!Files.exists(destination.path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(destination.path)) {
            "ROOTFS_DESTINATION_EXISTS"
        }
        Files.createDirectories(destination.path.parent ?: destination.mount.hostRoot)
        val temporary = Files.createTempFile(
            destination.path.parent ?: destination.mount.hostRoot,
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
            moveTemporary(temporary, destination.path)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Extracts a bounded tar.gz file into a rootfs directory. Links and special files are rejected. */
    suspend fun extractArchive(archivePath: String, destinationPath: String = "/"): Result<Unit> = operation {
        val archiveFile = resolveRead(archivePath)
        require(!Files.isSymbolicLink(archiveFile.lexicalPath)) { "ROOTFS_SYMLINK_ARCHIVE_FORBIDDEN" }
        require(Files.isRegularFile(archiveFile.path)) { "ROOTFS_ARCHIVE_NOT_FILE" }

        val destination = resolveMutation(destinationPath)
        require(!Files.isSymbolicLink(destination.path)) { "ROOTFS_SYMLINK_DESTINATION_FORBIDDEN" }
        if (Files.exists(destination.path, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(destination.path, LinkOption.NOFOLLOW_LINKS)) { "ROOTFS_NOT_DIRECTORY" }
            validateComponents(destination.mount, destination.path, allowExternalLeafSymlink = false)
        } else {
            Files.createDirectories(destination.path)
        }

        val staging = Files.createTempDirectory(
            destination.mount.hostRoot,
            ".rootfs-extract-${UUID.randomUUID()}"
        )
        val committed = mutableListOf<Path>()
        try {
            val budget = ArchiveBudget()
            TarArchiveInputStream(
                BufferedInputStream(GZIPInputStream(Files.newInputStream(archiveFile.path)))
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
                    val target = destination.path.resolve(child.fileName.toString()).normalize()
                    require(target.startsWith(destination.mount.hostRoot)) { "ROOTFS_ARCHIVE_PATH_ESCAPE" }
                    validateComponents(destination.mount, target, allowExternalLeafSymlink = true)
                    require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
                        "ROOTFS_DESTINATION_EXISTS"
                    }
                }
            }
            Files.newDirectoryStream(staging).use { children ->
                children.forEach { child ->
                    val target = destination.path.resolve(child.fileName.toString()).normalize()
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
        require(!Files.isSymbolicLink(file.path)) { "ROOTFS_SYMLINK_WRITE_FORBIDDEN" }
        Files.createDirectories(file.path.parent ?: file.mount.hostRoot)
        Files.write(file.path, bytes)
    }

    /** Resolves an existing path and checks every symlink target, including the final component. */
    private fun resolveRead(path: String): ResolvedPath {
        val guestPath = normalizeGuestPath(path)
        val mount = selectMount(guestPath)
        val candidate = hostPath(mount, guestPath)
        requireNotCopyStage(mount, candidate)
        validateComponents(mount, candidate, allowExternalLeafSymlink = false)
        return ResolvedPath(guestPath, mount, candidate.toRealPath(), candidate)
    }

    /** Resolves a mutation path, allowing a symlink leaf to be removed or renamed safely. */
    private fun resolveMutation(path: String): ResolvedPath {
        val guestPath = normalizeGuestPath(path)
        val mount = selectMount(guestPath)
        require(mount.definition.writable) { "ROOTFS_MOUNT_READ_ONLY" }
        val candidate = hostPath(mount, guestPath)
        requireNotCopyStage(mount, candidate)
        require(!(mount.guestPrefix == "/" && guestPath in virtualGuestDirectories &&
            !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS))) { "ROOTFS_MOUNT_PATH_REQUIRED" }
        validateComponents(mount, candidate, allowExternalLeafSymlink = true)
        return ResolvedPath(guestPath, mount, candidate, candidate)
    }

    private fun normalizeGuestPath(path: String): String {
        val normalized = path.trim().ifBlank { "/" }.replace('\\', '/')
        require('\u0000' !in normalized)
        require(normalized.split('/').none { it == ".." }) { "ROOTFS_PATH_TRAVERSAL" }
        val components = normalized.split('/').filter { it.isNotBlank() && it != "." }
        return if (components.isEmpty()) "/" else "/${components.joinToString("/")}"
    }

    private fun normalizeGuestPrefix(prefix: String): String = normalizeGuestPath(prefix).trimEnd('/').ifBlank { "/" }

    private fun parentGuestPath(path: String): String = path.substringBeforeLast('/').ifBlank { "/" }

    private fun selectMount(guestPath: String): MountState = mountStates.first { mount ->
        mount.guestPrefix == "/" || guestPath == mount.guestPrefix || guestPath.startsWith("${mount.guestPrefix}/")
    }

    private fun hostPath(mount: MountState, guestPath: String): Path {
        val relative = if (mount.guestPrefix == "/") {
            guestPath.removePrefix("/")
        } else {
            guestPath.removePrefix(mount.guestPrefix).removePrefix("/")
        }
        val candidate = mount.hostRoot.resolve(relative).normalize()
        require(candidate.startsWith(mount.hostRoot)) { "ROOTFS_PATH_ESCAPE" }
        return candidate
    }

    private fun directoryIsMountRoot(guestPath: String): Boolean =
        selectMount(guestPath).guestPrefix == guestPath

    private fun requireNotCopyStage(mount: MountState, candidate: Path) {
        require(!candidate.startsWith(mount.hostRoot.resolve(COPY_STAGE_DIRECTORY))) {
            "ROOTFS_RESERVED_PATH"
        }
    }

    private fun validateComponents(mount: MountState, candidate: Path, allowExternalLeafSymlink: Boolean) {
        require(candidate.startsWith(mount.hostRoot)) { "ROOTFS_PATH_ESCAPE" }
        val relative = mount.hostRoot.relativize(candidate)
        var current = mount.hostRoot
        relative.forEachIndexed { index, component ->
            current = current.resolve(component)
            val exists = Files.exists(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)
            if (!exists) return
            if (Files.isSymbolicLink(current)) {
                val leaf = index == relative.nameCount - 1
                if (leaf && allowExternalLeafSymlink) return@forEachIndexed
                val real = current.toRealPath()
                if (mount.definition.symlinkPolicy == GuestSymlinkPolicy.INTERNAL_ONLY) {
                    require(real.startsWith(mount.hostReal)) { "ROOTFS_SYMLINK_ESCAPE" }
                }
            }
        }
    }

    private fun displayPath(path: Path, directory: ResolvedPath): String {
        val relative = directory.path.relativize(path)
        return if (relative.nameCount == 0) {
            directory.guestPath
        } else {
            "${directory.guestPath.trimEnd('/')}/${relative.joinToString("/")}"
        }
    }

    private suspend fun copyTreeNoFollow(source: Path, destination: Path, destinationRoot: Path, budget: ArchiveBudget) {
        currentCoroutineContext().ensureActive()
        if (Files.isSymbolicLink(source)) throw IllegalArgumentException("ROOTFS_SYMLINK_COPY_FORBIDDEN")
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            budget.accept(0)
            Files.createDirectories(destination)
            Files.newDirectoryStream(source).use { children -> children.forEach { child ->
                copyTreeNoFollow(child, destination.resolve(child.fileName), destinationRoot, budget)
            } }
        } else {
            require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) { "ROOTFS_SPECIAL_FILE_FORBIDDEN" }
            val expectedSize = Files.size(source)
            budget.accept(expectedSize)
            Files.createDirectories(destination.parent ?: destinationRoot)
            Files.newInputStream(source).use { input ->
                Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        require(copied <= expectedSize) { "ROOTFS_ARCHIVE_LIMIT" }
                        copyChunkCheckpoint?.invoke()
                    }
                    require(copied == expectedSize) { "ROOTFS_COPY_SOURCE_CHANGED" }
                }
            }
            runCatching { Files.setLastModifiedTime(destination,
                Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS)) }
            runCatching { Files.setPosixFilePermissions(destination,
                Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS)) }
        }
    }

    private fun copyStageBase(mount: MountState): Path = mount.hostReal.resolve(COPY_STAGE_DIRECTORY)

    private fun cleanupOwnedStage(stage: Path, operationId: String, original: Throwable?,
        createdHere: Boolean = false) {
        if (!Files.isDirectory(stage, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(stage)) return
        val marker = stage.resolve(COPY_OWNER_RECORD)
        val recorded = if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS))
            runCatching { String(Files.readAllBytes(marker), Charsets.UTF_8) }.getOrNull() else null
        if (recorded != operationId && !(createdHere && recorded == null)) return
        runCatching { deletePathNoFollow(stage) }.onFailure { cleanupError ->
            runCatching {
                Files.write(stage.resolve("cleanup-failed"), cleanupError.javaClass.simpleName.toByteArray(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            }
            original?.addSuppressed(cleanupError)
        }
    }

    private fun recoverAbandonedCopyStages(mount: MountState) {
        val base = copyStageBase(mount)
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(base)) return
        runCatching {
            Files.newDirectoryStream(base).use { entries -> entries.forEach { stage ->
                val id = stage.fileName.toString()
                if (runCatching { UUID.fromString(id).toString() }.getOrNull() != id ||
                    !Files.isDirectory(stage, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(stage) ||
                    runCatching { String(Files.readAllBytes(stage.resolve(COPY_OWNER_RECORD)), Charsets.UTF_8) }.getOrNull() != id) {
                    return@forEach
                }
                runCatching {
                    FileChannel.open(stage.resolve(COPY_ACTIVE_LOCK), StandardOpenOption.READ,
                        StandardOpenOption.WRITE).use { channel ->
                        val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                        lock?.use { cleanupOwnedStage(stage, id, null) }
                    }
                }
            } }
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
        const val COPY_STAGE_DIRECTORY = ".adt-copy-staging"
        const val COPY_OWNER_RECORD = "owner-v1"
        const val COPY_ACTIVE_LOCK = "active.lock"
        val COPY_PUBLICATION_LOCK = Any()
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

private fun defaultGuestMounts(context: Context): List<GuestMount> {
    val environmentId = HarnessRuntimePaths.SHARED_ENVIRONMENT_ID
    val rootfs = AgentProotEnvironmentPaths.rootfs(context, environmentId).toPath()
    val runtime = HarnessRuntimePaths.runtimeRoot(context, environmentId).apply { mkdirs() }
    val temp = java.io.File(runtime, "tmp").apply { mkdirs() }.toPath()
    val run = java.io.File(runtime, "run").apply { mkdirs() }.toPath()
    return listOf(
        // The fallback mount must be present even when the runtime is stopped.
        GuestMount("/", rootfs),
        GuestMount("/workspace/projects", HarnessRuntimePaths.projects(context).apply { mkdirs() }.toPath()),
        GuestMount("/root/.dsh", HarnessRuntimePaths.harnessHome(context).apply { mkdirs() }.toPath()),
        GuestMount(AgentProotEnvironmentPaths.TMP_MOUNT, temp),
        GuestMount(AgentProotEnvironmentPaths.RUN_MOUNT, run),
    )
}
