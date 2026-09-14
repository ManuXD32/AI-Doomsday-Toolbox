package com.example.llamadroid.data.proot

import android.content.Context
import android.util.Log
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * Metadata needed to create a Debian environment. The database layer owns the durable
 * representation of this object; this class deliberately only owns filesystem state.
 */
data class AgentProotEnvironmentSpec(
    val id: String,
    val displayName: String = id,
    val imageId: String = DebianAssetPack.IMAGE_ID,
    val imageSha256: String? = null
)

/**
 * Canonical app-private paths for agent PRoot environments.
 *
 * Environment IDs are opaque database values. They are validated before they are ever used
 * as a path component, and callers cannot provide an arbitrary filesystem root.
 */
object AgentProotEnvironmentPaths {
    const val STORAGE_DIRECTORY = "agent_proot/environments"
    const val ROOTFS_DIRECTORY = "rootfs"
    const val WORKSPACE_MOUNT = "/workspace"
    const val TMP_MOUNT = "/tmp"
    const val RUN_MOUNT = "/run"

    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")

    fun requireSafeEnvironmentId(id: String): String {
        require(idPattern.matches(id)) {
            "Environment ID must contain only letters, numbers, '_' or '-'."
        }
        return id
    }

    fun storageRoot(context: Context): File = File(context.filesDir, STORAGE_DIRECTORY).canonicalFile

    fun environmentRoot(context: Context, environmentId: String): File {
        val safeId = requireSafeEnvironmentId(environmentId)
        val root = storageRoot(context)
        val target = File(root, safeId).canonicalFile
        require(isDescendantOrSame(target, root)) {
            "PRoot environment path escaped the app-private storage root."
        }
        return target
    }

    fun rootfs(context: Context, environmentId: String): File =
        File(environmentRoot(context, environmentId), ROOTFS_DIRECTORY).canonicalFile.also {
            require(isDescendantOrSame(it, environmentRoot(context, environmentId)))
        }

    fun stagingRoot(context: Context, environmentId: String): File {
        val environment = environmentRoot(context, environmentId)
        return File(environment, ".staging").canonicalFile.also {
            require(isDescendantOrSame(it, environment))
        }
    }

    fun isDescendantOrSame(candidate: File, root: File): Boolean {
        val candidatePath = candidate.canonicalFile.toPath()
        val rootPath = root.canonicalFile.toPath()
        return candidatePath == rootPath || candidatePath.startsWith(rootPath)
    }

    fun usageBytes(context: Context, environmentId: String): Long {
        val root = environmentRoot(context, environmentId)
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

/** Lookup of assets from the install-time `asset_debian` pack. */
object DebianAssetPack {
    const val PACK_NAME = "asset_debian"
    const val IMAGE_ID = "debian-trixie-arm64-20260824"
    const val ROOTFS_ASSET = "debian/rootfs.tar.xz"
    const val ROOTFS_SHA256_ASSET = "debian/rootfs.tar.xz.sha256"
    const val MANIFEST_ASSET = "debian/manifest.json"

    private const val TAG = "DebianAssetPack"

    private fun packAssetsRoot(context: Context): File? = runCatching {
        AssetPackManagerFactory.getInstance(context)
            .getPackLocation(PACK_NAME)
            ?.assetsPath()
            ?.let(::File)
            ?.takeIf { it.isDirectory }
    }.onFailure {
        Log.w(TAG, "Unable to resolve install-time Debian asset pack", it)
    }.getOrNull()

    /**
     * Opens a pack asset without copying it to writable storage. The fallback is useful for
     * local/fat APK builds where the asset pack is merged into the APK's AssetManager.
     */
    fun open(context: Context, relativePath: String): InputStream? {
        val normalized = normalizeAssetPath(relativePath)
        val fromPack = packAssetsRoot(context)?.let { root ->
            val file = File(root, normalized).canonicalFile
            if (file.isFile && file.path.startsWith(root.canonicalPath + File.separator)) {
                runCatching { FileInputStream(file) }.getOrNull()
            } else {
                null
            }
        }
        return fromPack ?: runCatching { context.assets.open(normalized) }.getOrNull()
    }

    fun isAvailable(context: Context): Boolean =
        packAssetsRoot(context)?.let { File(it, ROOTFS_ASSET).isFile } == true ||
            runCatching { context.assets.open(ROOTFS_ASSET).use { true } }.getOrDefault(false)

    fun readExpectedRootfsSha256(context: Context): String? {
        val declared = open(context, ROOTFS_SHA256_ASSET)?.bufferedReader()?.use { it.readText() }
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        if (declared != null) return declared

        return open(context, MANIFEST_ASSET)?.bufferedReader()?.use { reader ->
            runCatching {
                JSONObject(reader.readText()).optString("rootfsSha256").trim().lowercase()
            }.getOrNull()
        }?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }

    private fun normalizeAssetPath(path: String): String {
        val normalized = path.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/')) { "Invalid Debian asset path" }
        val parts = normalized.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Debian asset path traversal is not allowed"
        }
        return parts.joinToString("/")
    }
}

/**
 * Secure, streaming tar extraction for a rootfs supplied by the signed asset pack.
 *
 * Archive paths, symlink targets and hardlink targets are all checked before touching the
 * destination. Device/FIFO entries are rejected because the app cannot safely materialize
 * them from writable app storage. Hardlinks are copied as regular files, which is compatible
 * with PRoot's `--link2symlink` mode and avoids Android filesystem restrictions.
 */
object AgentProotRootfsExtractor {
    private const val MAX_ENTRIES = 1_000_000
    private const val MAX_EXTRACTED_BYTES = 8L * 1024L * 1024L * 1024L

    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun extract(archive: File, destination: File): Long {
        require(archive.isFile) { "Rootfs archive is missing" }
        require(!destination.exists() || destination.isDirectory) {
            "Rootfs destination must be a directory or not exist"
        }
        destination.mkdirs()
        val root = destination.canonicalFile
        var entries = 0
        var extractedBytes = 0L
        val hardlinkSources = HashMap<String, File>()

        openArchive(archive).use { compressed ->
            TarArchiveInputStream(BufferedInputStream(compressed)).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    entries += 1
                    require(entries <= MAX_ENTRIES) { "Rootfs archive contains too many entries" }
                    val relative = safeArchivePath(entry.name)
                    if (relative.isEmpty()) {
                        require(entry.isDirectory) { "Rootfs archive root entry must be a directory" }
                        entry = tar.nextTarEntry
                        continue
                    }
                    val target = safeTarget(root, relative)
                    ensureSafeParent(root, target)
                    require(!Files.isSymbolicLink(target.toPath())) {
                        "Archive entry replaces an existing symlink: ${entry.name}"
                    }

                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> {
                            val linkName = safeLinkTarget(relative, entry.linkName)
                            target.parentFile?.mkdirs()
                            Files.createSymbolicLink(target.toPath(), Paths.get(linkName))
                        }
                        entry.isLink -> {
                            val sourceRelative = safeArchivePath(entry.linkName)
                            val source = safeTarget(root, sourceRelative)
                            ensureSafeParent(root, source)
                            require(source.isFile && !Files.isSymbolicLink(source.toPath())) {
                                "Hardlink source is unavailable or unsafe: ${entry.linkName}"
                            }
                            target.parentFile?.mkdirs()
                            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            extractedBytes += source.length()
                        }
                        entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO || entry.isGNUSparse -> {
                            throw IllegalArgumentException("Unsupported special rootfs entry: ${entry.name}")
                        }
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStreamCompat(target).use { output ->
                                val copied = tar.copyTo(output, DEFAULT_BUFFER_SIZE)
                                extractedBytes += copied
                            }
                            require(extractedBytes <= MAX_EXTRACTED_BYTES) {
                                "Rootfs archive expands beyond the safety limit"
                            }
                            hardlinkSources[relative] = target
                            if (entry.mode and 0b001001001 != 0) target.setExecutable(true, false)
                        }
                        else -> throw IllegalArgumentException("Unsupported rootfs entry: ${entry.name}")
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
        require(File(root, "etc/os-release").exists() || File(root, "usr/lib/os-release").exists()) {
            "Rootfs does not contain Debian release metadata"
        }
        return extractedBytes
    }

    private fun openArchive(archive: File): InputStream {
        val input = FileInputStream(archive)
        return when {
            archive.name.endsWith(".xz", ignoreCase = true) -> XZCompressorInputStream(input)
            archive.name.endsWith(".gz", ignoreCase = true) -> GZIPInputStream(input)
            else -> input
        }
    }

    private fun safeArchivePath(name: String): String {
        var normalized = name.replace('\\', '/').trimEnd('/')
        while (normalized.startsWith("./")) normalized = normalized.removePrefix("./")
        if (normalized == "." || normalized.isBlank()) return ""
        require(!normalized.startsWith('/')) { "Rootfs archive contains an absolute path" }
        val parts = normalized.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Rootfs archive contains an unsafe path: $name"
        }
        return parts.joinToString("/")
    }

    private fun safeLinkTarget(entryPath: String, linkName: String): String {
        require(linkName.isNotBlank()) { "Rootfs archive contains an empty symlink: $entryPath" }
        if (linkName.startsWith('/')) {
            // Debian absolute links are guest-root paths, but materializing one literally
            // would point at the Android host outside PRoot. Rewrite it to the equivalent
            // relative link inside the extracted root so the on-disk tree never contains an
            // escaping host link (for example /proc/mounts from etc/mtab).
            val guestTarget = safeArchivePath(linkName.removePrefix("/"))
            require(guestTarget.isNotEmpty()) {
                "Rootfs archive contains an invalid absolute symlink: $entryPath"
            }
            val parent = Paths.get(entryPath).parent ?: Paths.get("")
            return parent.relativize(Paths.get(guestTarget)).toString().ifBlank { "." }
        }
        val parent = Paths.get(entryPath).parent ?: Paths.get("")
        val resolved = parent.resolve(linkName.replace('\\', '/')).normalize()
        require(!resolved.startsWith("..")) {
            "Rootfs archive contains an escaping symlink: $entryPath"
        }
        return linkName.replace('\\', '/')
    }

    private fun safeTarget(root: File, relative: String): File {
        val target = File(root, relative).canonicalFile
        require(AgentProotEnvironmentPaths.isDescendantOrSame(target, root)) {
            "Rootfs archive path escaped extraction root"
        }
        return target
    }

    private fun ensureSafeParent(root: File, target: File) {
        var current = target.parentFile?.canonicalFile
        while (current != null && AgentProotEnvironmentPaths.isDescendantOrSame(current, root)) {
            if (current == root) return
            current = current.parentFile
        }
        throw IllegalArgumentException("Rootfs archive parent escaped extraction root")
    }

    /** Small closeable adapter to keep the extractor easy to unit test on the JVM. */
    private class FileOutputStreamCompat(file: File) : java.io.OutputStream() {
        private val output = file.outputStream()
        override fun write(b: Int) = output.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = output.write(b, off, len)
        override fun flush() = output.flush()
        override fun close() = output.close()
    }
}

/**
 * Owns extraction and activation of app-private rootfs directories. It is intentionally
 * independent from Room so migration can add metadata without creating or starting a guest.
 */
class AgentProotEnvironmentManager(private val context: Context) {
    private val locks = ConcurrentHashMap<String, Any>()

    suspend fun prepare(spec: AgentProotEnvironmentSpec, forceRefresh: Boolean = false): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val id = AgentProotEnvironmentPaths.requireSafeEnvironmentId(spec.id)
                synchronized(locks.getOrPut(id) { Any() }) {
                    prepareBlocking(spec.copy(id = id), forceRefresh)
                }
            }
        }

    suspend fun delete(environmentId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val id = AgentProotEnvironmentPaths.requireSafeEnvironmentId(environmentId)
            synchronized(locks.getOrPut(id) { Any() }) {
                val root = AgentProotEnvironmentPaths.environmentRoot(context, id)
                val storage = AgentProotEnvironmentPaths.storageRoot(context)
                require(root != storage) { "Cannot delete the PRoot storage root" }
                if (root.exists()) require(root.deleteRecursively()) { "Unable to delete PRoot environment" }
            }
        }
    }

    fun isReady(environmentId: String): Boolean {
        val rootfs = AgentProotEnvironmentPaths.rootfs(context, environmentId)
        return File(rootfs, "etc/os-release").isFile || File(rootfs, "usr/lib/os-release").isFile
    }

    fun usageBytes(environmentId: String): Long =
        AgentProotEnvironmentPaths.usageBytes(context, environmentId)

    private fun prepareBlocking(spec: AgentProotEnvironmentSpec, forceRefresh: Boolean): File {
        require(spec.imageId == DebianAssetPack.IMAGE_ID) {
            "Unsupported Debian image: ${spec.imageId}"
        }
        val environment = AgentProotEnvironmentPaths.environmentRoot(context, spec.id)
        val rootfs = AgentProotEnvironmentPaths.rootfs(context, spec.id)
        val marker = File(rootfs, ENVIRONMENT_MARKER)
        if (!forceRefresh && rootfsReady(rootfs) && marker.isFile) return rootfs
        require(DebianAssetPack.isAvailable(context)) {
            "The install-time Debian asset pack is not available"
        }

        val staging = File(
            AgentProotEnvironmentPaths.stagingRoot(context, spec.id),
            UUID.randomUUID().toString()
        ).canonicalFile
        require(AgentProotEnvironmentPaths.isDescendantOrSame(
            staging,
            AgentProotEnvironmentPaths.environmentRoot(context, spec.id)
        ))
        staging.mkdirs()
        val archive = File(staging, "rootfs.tar.xz")
        try {
            DebianAssetPack.open(context, DebianAssetPack.ROOTFS_ASSET)?.use { input ->
                archive.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Debian rootfs archive is missing from the asset pack")
            val expected = spec.imageSha256?.lowercase()
                ?: DebianAssetPack.readExpectedRootfsSha256(context)
                ?: error("The signed Debian rootfs checksum declaration is missing")
            require(AgentProotRootfsExtractor.sha256(archive) == expected) {
                "Debian rootfs checksum mismatch"
            }

            val extracted = File(staging, ROOTFS_STAGE_NAME).canonicalFile
            AgentProotRootfsExtractor.extract(archive, extracted)
            require(rootfsReady(extracted)) { "Extracted Debian rootfs is incomplete" }
            File(extracted, ENVIRONMENT_MARKER).writeText(
                JSONObject()
                    .put("environmentId", spec.id)
                    .put("displayName", spec.displayName)
                    .put("imageId", spec.imageId)
                    .put("imageSha256", expected)
                    .put("createdAt", System.currentTimeMillis())
                    .toString(),
                Charsets.UTF_8
            )
            environment.mkdirs()
            val previous = File(environment, ".rootfs.previous-${UUID.randomUUID()}").canonicalFile
            val hadPrevious = rootfs.exists()
            if (hadPrevious) {
                require(rootfs.renameTo(previous)) { "Unable to stage the previous Debian rootfs" }
            }
            try {
                require(extracted.renameTo(rootfs)) { "Unable to activate Debian rootfs atomically" }
            } catch (error: Throwable) {
                if (hadPrevious && !rootfs.exists()) previous.renameTo(rootfs)
                throw error
            }
            if (previous.exists()) {
                require(previous.deleteRecursively()) { "Unable to remove the replaced Debian rootfs" }
            }
            return rootfs
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun rootfsReady(rootfs: File): Boolean =
        rootfs.isDirectory &&
            (File(rootfs, "etc/os-release").isFile || File(rootfs, "usr/lib/os-release").isFile) &&
            (File(rootfs, "bin/sh").exists() || File(rootfs, "usr/bin/sh").exists())

    private companion object {
        const val ROOTFS_STAGE_NAME = "rootfs"
        const val ENVIRONMENT_MARKER = ".adt-environment.json"
    }
}
