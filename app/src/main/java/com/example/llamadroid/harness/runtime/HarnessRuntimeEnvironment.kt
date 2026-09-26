package com.example.llamadroid.harness.runtime

import android.content.Context
import com.example.llamadroid.data.proot.AgentProotEnvironmentManager
import com.example.llamadroid.data.proot.AgentProotEnvironmentPaths
import com.example.llamadroid.data.proot.AgentProotEnvironmentSpec
import com.example.llamadroid.data.proot.AgentProotNetworkConfig
import com.example.llamadroid.data.proot.DebianAssetPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream

/** Shared app-owned paths for the single Harness environment. */
object HarnessRuntimePaths {
    const val HARNESS_HOME_DIRECTORY = "agent_harness/dsh_home"
    const val RUNTIME_DIRECTORY = "agent_harness/runtime"
    const val PROJECTS_DIRECTORY = "agent_local_workspaces"
    const val DEFAULT_PROJECT_DIRECTORY = "default_project"
    const val DEFAULT_PROJECT_GUEST = "/workspace/projects/$DEFAULT_PROJECT_DIRECTORY"
    const val SHARED_ENVIRONMENT_ID = HarnessRuntimeRecord.DEFAULT_ENVIRONMENT_ID

    fun harnessHome(context: Context): File = File(context.filesDir, HARNESS_HOME_DIRECTORY).canonicalFile

    fun runtimeRoot(context: Context, environmentId: String): File {
        AgentProotEnvironmentPaths.requireSafeEnvironmentId(environmentId)
        val root = File(context.cacheDir, "$RUNTIME_DIRECTORY/$environmentId").canonicalFile
        require(AgentProotEnvironmentPaths.isDescendantOrSame(root, context.cacheDir.canonicalFile)) {
            "Harness runtime path escaped the cache directory"
        }
        return root
    }

    fun projects(context: Context): File = File(context.filesDir, PROJECTS_DIRECTORY).canonicalFile
}

/** Prepares the existing verified Debian environment and the persistent Harness bind paths. */
class AndroidHarnessEnvironmentProvider(
    private val context: Context,
    private val environmentManager: AgentProotEnvironmentManager = AgentProotEnvironmentManager(context)
) : HarnessEnvironmentProvider {
    override suspend fun prepare(environmentId: String): HarnessEnvironmentPaths = withContext(Dispatchers.IO) {
        AgentProotEnvironmentPaths.requireSafeEnvironmentId(environmentId)
        val rootfs = environmentManager.prepare(AgentProotEnvironmentSpec(environmentId)).getOrThrow()
        val projects = HarnessRuntimePaths.projects(context).apply { mkdirs() }.canonicalFile
        // DSH starts new WebUI sessions from this scoped project directory. Keep the parent
        // mount shared so existing project folders remain visible, while ensuring the default
        // session cwd is always present inside the guest.
        val defaultProject = File(projects, HarnessRuntimePaths.DEFAULT_PROJECT_DIRECTORY)
            .apply { mkdirs() }
            .canonicalFile
        require(AgentProotEnvironmentPaths.isDescendantOrSame(defaultProject, projects)) {
            "Harness default project escaped the shared projects directory"
        }
        val dshHome = HarnessRuntimePaths.harnessHome(context).apply { mkdirs() }.canonicalFile
        val runtime = HarnessRuntimePaths.runtimeRoot(context, environmentId).apply { mkdirs() }
        val temp = File(runtime, "tmp").apply { mkdirs() }.canonicalFile
        val run = File(runtime, "run").apply { mkdirs() }.canonicalFile
        val resolver = AgentProotNetworkConfig.write(context, environmentId).canonicalFile
        HarnessEnvironmentPaths(
            environmentId = environmentId,
            rootfs = rootfs.canonicalFile,
            projectsHost = projects,
            dshHomeHost = dshHome,
            tempHost = temp,
            runHost = run,
            resolverFile = resolver
        )
    }
}

/** Work phases exposed only so cancellation can be verified without Android assets. */
enum class HarnessPayloadWorkPhase {
    COPY,
    CHECKSUM,
    EXTRACT_ENTRY,
    EXTRACT_CHUNK,
    ACTIVATE
}

/** Cooperative cancellation seam used by the blocking archive pipeline. */
fun interface HarnessPayloadCancellation {
    fun check(phase: HarnessPayloadWorkPhase)

    companion object {
        val NONE = HarnessPayloadCancellation { }
    }
}

/** Asset-backed provider for the pinned, real DeepSeek Harness payload. */
class AssetHarnessPayloadProvider(
    private val context: Context
) : HarnessPayloadProvider {
    override suspend fun prepare(paths: HarnessEnvironmentPaths): HarnessPayload = withContext(Dispatchers.IO) {
        val coroutineContext = currentCoroutineContext()
        val cancellation = HarnessPayloadCancellation { coroutineContext.ensureActive() }
        val manifest = readManifest()
        val payload = parseManifest(manifest)
        installPayload(paths.rootfs, payload, cancellation)
        val entrypoint = File(paths.rootfs, payload.command.first().removePrefix("/")).canonicalFile
        require(AgentProotEnvironmentPaths.isDescendantOrSame(
            entrypoint,
            File(paths.rootfs, payload.installGuestPath.removePrefix("/")).canonicalFile
        ) && entrypoint.isFile) {
            "Installed DeepSeek Harness entrypoint is missing: ${payload.command.first()}"
        }
        payload
    }

    private fun readManifest(): JSONObject {
        val input = DebianAssetPack.open(context, MANIFEST_ASSET)
            ?: throw HarnessRuntimeException("HARNESS_PAYLOAD_MISSING", "Pinned Harness manifest is unavailable")
        return input.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    }

    private fun parseManifest(manifest: JSONObject): HarnessPayload {
        val command = manifest.optJSONArray("command")?.toStringList()
            ?: throw HarnessRuntimeException("HARNESS_PAYLOAD_INVALID", "Harness manifest command is missing")
        return HarnessPayload(
            version = manifest.optString("version"),
            commit = manifest.optString("commit"),
            archiveAsset = manifest.optString("archiveAsset"),
            archiveSha256 = manifest.optString("archiveSha256"),
            command = command,
            healthPath = manifest.optString("healthPath")
        )
    }

    private fun installPayload(
        rootfs: File,
        payload: HarnessPayload,
        cancellation: HarnessPayloadCancellation
    ) {
        installHarnessPayload(
            rootfs = rootfs,
            payload = payload,
            archiveSource = {
                DebianAssetPack.open(context, payload.archiveAsset)
                    ?: throw HarnessRuntimeException(
                        "HARNESS_PAYLOAD_MISSING",
                        "Pinned Harness archive is unavailable"
                    )
            },
            cancellation = cancellation
        )
    }

    private fun JSONArray.toStringList(): List<String> = buildList(length()) {
        for (index in 0 until length()) add(getString(index))
    }

    private companion object {
        const val MANIFEST_ASSET = "harness/manifest.json"
    }
}

/** Installs a verified payload through a testable archive source and cooperative checkpoints. */
internal fun installHarnessPayload(
    rootfs: File,
    payload: HarnessPayload,
    archiveSource: () -> InputStream,
    cancellation: HarnessPayloadCancellation = HarnessPayloadCancellation.NONE
) {
    val install = File(rootfs, payload.installGuestPath.removePrefix("/")).canonicalFile
    require(AgentProotEnvironmentPaths.isDescendantOrSame(install, rootfs.canonicalFile)) {
        "Harness install path escaped the Debian rootfs"
    }
    val marker = File(install, HARNESS_PAYLOAD_MARKER)
    val existingEntrypoint = File(rootfs, payload.command.first().removePrefix("/")).canonicalFile
    if (!Files.isSymbolicLink(marker.toPath()) &&
        marker.isFile &&
        marker.readText(Charsets.UTF_8).contains(payload.archiveSha256, ignoreCase = true) &&
        AgentProotEnvironmentPaths.isDescendantOrSame(existingEntrypoint, install) &&
        existingEntrypoint.isFile
    ) {
        return
    }

    val stage = File(rootfs, ".adt-harness-staging/${UUID.randomUUID()}").canonicalFile
    require(AgentProotEnvironmentPaths.isDescendantOrSame(stage, rootfs.canonicalFile)) {
        "Harness staging path escaped the Debian rootfs"
    }
    val archive = File(stage, "payload.tar.xz")
    val extracted = File(stage, "extracted")
    stage.mkdirs()
    try {
        cancellation.check(HarnessPayloadWorkPhase.COPY)
        archiveSource().use { input ->
            archive.outputStream().use { output ->
                copyCancellable(input, output, cancellation, HarnessPayloadWorkPhase.COPY)
            }
        }
        require(sha256Cancellable(archive, cancellation).equals(payload.archiveSha256, ignoreCase = true)) {
            "Pinned Harness archive checksum mismatch"
        }
        HarnessSecureArchiveExtractor.extract(
            archive,
            extracted,
            payload.installGuestPath,
            cancellation
        )
        val stagedInstall = File(extracted, payload.installGuestPath.removePrefix("/")).canonicalFile
        require(stagedInstall.isDirectory) { "Harness archive has no ${payload.installGuestPath} directory" }
        require(File(stagedInstall, HARNESS_PAYLOAD_MARKER).let { !it.exists() || !Files.isSymbolicLink(it.toPath()) }) {
            "Harness payload marker is not a regular path"
        }

        install.parentFile?.mkdirs()
        val previous = File(rootfs, ".adt-harness-previous-${UUID.randomUUID()}").canonicalFile
        try {
            // Both checks are before activation. The second one also covers the window after the
            // previous tree was moved; its failure restores that tree before propagating cancel.
            cancellation.check(HarnessPayloadWorkPhase.ACTIVATE)
            if (install.exists()) {
                require(!Files.isSymbolicLink(install.toPath())) { "Harness install path is a symlink" }
                require(install.renameTo(previous)) { "Unable to stage the previous Harness payload" }
            }
            cancellation.check(HarnessPayloadWorkPhase.ACTIVATE)
            require(stagedInstall.renameTo(install)) { "Unable to activate the Harness payload" }
        } catch (error: Throwable) {
            if (previous.exists() && !install.exists()) previous.renameTo(install)
            throw error
        }
        File(install, HARNESS_PAYLOAD_MARKER).writeText(
            JSONObject()
                .put("version", payload.version)
                .put("commit", payload.commit)
                .put("archiveSha256", payload.archiveSha256)
                .toString(),
            Charsets.UTF_8
        )
        if (previous.exists()) require(previous.deleteRecursively()) {
            "Unable to remove the previous Harness payload"
        }
    } finally {
        stage.deleteRecursively()
    }
}

private const val HARNESS_PAYLOAD_MARKER = ".adt-harness-payload.json"

private fun copyCancellable(
    input: InputStream,
    output: java.io.OutputStream,
    cancellation: HarnessPayloadCancellation,
    phase: HarnessPayloadWorkPhase
): Long {
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        cancellation.check(phase)
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        cancellation.check(phase)
        output.write(buffer, 0, count)
        copied += count
    }
    return copied
}

private fun sha256Cancellable(file: File, cancellation: HarnessPayloadCancellation): String =
    MessageDigest.getInstance("SHA-256").let { digest ->
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                cancellation.check(HarnessPayloadWorkPhase.CHECKSUM)
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) {
                    cancellation.check(HarnessPayloadWorkPhase.CHECKSUM)
                    digest.update(buffer, 0, count)
                }
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

/**
 * Secure partial-rootfs extractor for the signed Harness archive. It only accepts entries under
 * /opt/adt-harness and rejects links or special files that could escape the guest root.
 */
object HarnessSecureArchiveExtractor {
    private const val MAX_ENTRIES = 250_000
    private const val MAX_EXTRACTED_BYTES = 4L * 1024L * 1024L * 1024L

    fun extract(
        archive: File,
        destination: File,
        requiredPrefix: String,
        cancellation: HarnessPayloadCancellation = HarnessPayloadCancellation.NONE
    ): Long {
        require(archive.isFile) { "Harness archive is missing" }
        val prefix = normalizePrefix(requiredPrefix)
        cancellation.check(HarnessPayloadWorkPhase.EXTRACT_ENTRY)
        destination.mkdirs()
        val root = destination.canonicalFile
        var entries = 0
        var extractedBytes = 0L
        cancellation.check(HarnessPayloadWorkPhase.EXTRACT_ENTRY)
        openArchive(archive).use { compressed ->
            TarArchiveInputStream(BufferedInputStream(compressed)).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    cancellation.check(HarnessPayloadWorkPhase.EXTRACT_ENTRY)
                    entries += 1
                    require(entries <= MAX_ENTRIES) { "Harness archive contains too many entries" }
                    val relative = safeArchivePath(entry.name)
                    require(relative == prefix || relative.startsWith("$prefix/")) {
                        "Harness archive entry escapes $requiredPrefix: ${entry.name}"
                    }
                    val target = safeTarget(root, relative)
                    ensureSafeParent(root, target)
                    require(!Files.isSymbolicLink(target.toPath())) {
                        "Harness archive entry replaces an existing symlink: ${entry.name}"
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
                            require(sourceRelative == prefix || sourceRelative.startsWith("$prefix/")) {
                                "Harness hardlink escapes $requiredPrefix"
                            }
                            val source = safeTarget(root, sourceRelative)
                            ensureSafeParent(root, source)
                            require(source.isFile && !Files.isSymbolicLink(source.toPath())) {
                                "Harness hardlink source is unavailable: ${entry.linkName}"
                            }
                            require(source != target) {
                                "Harness hardlink source and target are identical: ${entry.linkName}"
                            }
                            target.parentFile?.mkdirs()
                            FileInputStream(source).use { input ->
                                FileOutputStream(target).use { output ->
                                    extractedBytes += copyCancellable(
                                        input,
                                        output,
                                        cancellation,
                                        HarnessPayloadWorkPhase.EXTRACT_CHUNK
                                    )
                                }
                            }
                            require(extractedBytes <= MAX_EXTRACTED_BYTES) {
                                "Harness archive expands beyond the safety limit"
                            }
                            target.setExecutable(source.canExecute(), false)
                        }
                        entry.isCharacterDevice || entry.isBlockDevice || entry.isFIFO || entry.isGNUSparse -> {
                            throw IllegalArgumentException("Unsupported Harness archive entry: ${entry.name}")
                        }
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output ->
                                extractedBytes += copyCancellable(
                                    tar,
                                    output,
                                    cancellation,
                                    HarnessPayloadWorkPhase.EXTRACT_CHUNK
                                )
                            }
                            require(extractedBytes <= MAX_EXTRACTED_BYTES) {
                                "Harness archive expands beyond the safety limit"
                            }
                            if (entry.mode and 0b001001001 != 0) target.setExecutable(true, false)
                        }
                        else -> throw IllegalArgumentException("Unsupported Harness archive entry: ${entry.name}")
                    }
                    cancellation.check(HarnessPayloadWorkPhase.EXTRACT_ENTRY)
                    entry = tar.nextEntry
                }
            }
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

    private fun normalizePrefix(raw: String): String {
        val normalized = raw.replace('\\', '/').trim('/').trim()
        require(normalized.isNotBlank() && !normalized.contains("..")) {
            "Harness archive prefix is invalid"
        }
        return normalized
    }

    private fun safeArchivePath(name: String): String {
        var normalized = name.replace('\\', '/').trimEnd('/')
        while (normalized.startsWith("./")) normalized = normalized.removePrefix("./")
        require(normalized.isNotBlank() && !normalized.startsWith('/')) {
            "Harness archive contains an absolute path"
        }
        val parts = normalized.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Harness archive contains an unsafe path: $name"
        }
        return parts.joinToString("/")
    }

    private fun safeLinkTarget(entryPath: String, linkName: String): String {
        require(linkName.isNotBlank()) { "Harness archive contains an empty symlink" }
        if (linkName.startsWith('/')) {
            val guestTarget = safeArchivePath(linkName.removePrefix("/"))
            val parent = Paths.get(entryPath).parent ?: Paths.get("")
            return parent.relativize(Paths.get(guestTarget)).toString().ifBlank { "." }
        }
        val parent = Paths.get(entryPath).parent ?: Paths.get("")
        val resolved = parent.resolve(linkName.replace('\\', '/')).normalize()
        require(!resolved.startsWith("..")) { "Harness archive contains an escaping symlink" }
        return linkName.replace('\\', '/')
    }

    private fun safeTarget(root: File, relative: String): File {
        val target = File(root, relative).canonicalFile
        require(AgentProotEnvironmentPaths.isDescendantOrSame(target, root)) {
            "Harness archive path escaped extraction root"
        }
        return target
    }

    private fun ensureSafeParent(root: File, target: File) {
        var current = target.parentFile?.canonicalFile
        while (current != null && AgentProotEnvironmentPaths.isDescendantOrSame(current, root)) {
            if (current == root) return
            current = current.parentFile
        }
        throw IllegalArgumentException("Harness archive parent escaped extraction root")
    }
}
