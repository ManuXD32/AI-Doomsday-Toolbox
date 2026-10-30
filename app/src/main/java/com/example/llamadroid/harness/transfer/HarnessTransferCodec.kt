package com.example.llamadroid.harness.transfer

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.FileHeader
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.EncryptionMethod
import net.lingala.zip4j.model.enums.AesKeyStrength
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

internal object HarnessTransferCodec {
    private const val MAX_ENTRIES = 250_000
    private const val MAX_ARCHIVE_ENTRY_BYTES = 128L * 1024L * 1024L * 1024L
    private const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L * 1024L
    private const val MAX_METADATA_ROWS = 512
    private const val DSH_HOME_PREFIX = "dsh_home"
    private const val PROJECTS_PREFIX = "projects"
    private const val ROOTFS_PREFIX = "rootfs"
    private const val ATTACHMENTS_PREFIX = "attachments"
    private const val METADATA_PREFIX = "metadata"
    private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val ATTACHMENT_DIGEST = Regex("[0-9a-f]{64}")
    private val ATTACHMENT_OBJECT_KINDS = setOf("objects", "file-objects", "files")
    private val NOFOLLOW = arrayOf(LinkOption.NOFOLLOW_LINKS)
    /** DSH runtime state is never a transferable canonical record, even for FULL. */
    private val DSH_TRANSIENT_TOP_LEVEL = setOf("cache", "tmp", "run", ".staging", "staging")
    /** Secret-bearing DSH paths are opt-in and then protected by the archive password. */
    private val DSH_SECRET_STORAGE_DIRECTORIES = setOf(
        ".oauth", "auth", "credential", "credentials", "oauth", "provider-store",
        "provider_store", "providers", "secret", "secrets", "token", "tokens",
    )
    private val DSH_SECRET_FILE_NAMES = setOf(
        ".env", ".credentials", "credentials.json", "provider-store.json", "auth.json", "tokens.json",
    )

    private data class SourceEntry(
        val path: String,
        val source: File? = null,
        val bytes: ByteArray? = null,
        val descriptor: TransferEntry,
    )

    private data class Collected(
        val entries: List<SourceEntry>,
        val sessions: List<TransferSession>,
        val metadataNames: List<String>,
    )

    fun export(
        destination: File,
        source: TransferSource,
        options: TransferExportOptions,
    ): TransferManifest {
        options.cancellation.check(TransferWorkPhase.SCAN)
        val collected = collect(source, options)
        val manifest = TransferManifest(
            format = TransferManifest.FORMAT,
            formatVersion = TransferManifest.CURRENT_FORMAT_VERSION,
            runtimeId = source.runtimeId,
            mode = options.mode,
            createdAtEpochMs = System.currentTimeMillis(),
            encrypted = options.password != null,
            entries = collected.entries.map { it.descriptor },
            sessions = collected.sessions,
            metadataNames = collected.metadataNames,
            compatibilityVersion = source.compatibilityVersion,
            runtimeVersion = source.runtimeVersion,
            abi = source.abi,
        )
        val manifestBytes = manifest.toJson().toString().toByteArray(Charsets.UTF_8)
        writeArchive(destination, manifestBytes, collected.entries, options)
        return manifest
    }

    fun inspect(
        archive: File,
        password: CharArray?,
        cancellation: TransferCancellation = TransferCancellation.NONE,
    ): TransferInspection {
        cancellation.check(TransferWorkPhase.VALIDATE)
        requireRegularFile(archive, "TRANSFER_ARCHIVE_MISSING")
        require(archive.length() <= MAX_ARCHIVE_BYTES) { "TRANSFER_ARCHIVE_TOO_LARGE" }
        val zip = openZip(archive, password)
        try {
            cancellation.check(TransferWorkPhase.VALIDATE)
            val headers = zip.fileHeaders
            require(headers.size in 2..MAX_ENTRIES + 1) { "TRANSFER_ARCHIVE_ENTRY_COUNT_INVALID" }
            val normalized = headers.map { header ->
                val path = normalizeArchivePath(header.fileName)
                path to header
            }
            require(normalized.map { it.first }.distinct().size == normalized.size) {
                "TRANSFER_ARCHIVE_DUPLICATE_ENTRY"
            }
            val manifestHeader = normalized.singleOrNull { it.first == TransferManifest.MANIFEST_PATH }
                ?.second ?: throw IllegalArgumentException("TRANSFER_MANIFEST_MISSING")
            require(!manifestHeader.isDirectory) { "TRANSFER_MANIFEST_INVALID" }
            val manifestBytes = readZipEntry(zip, manifestHeader, MAX_MANIFEST_BYTES, cancellation)
            val manifest = parseManifest(manifestBytes)
            validateEntrySemantics(manifest)
            val payloadHeaders = normalized.filterNot { it.first == TransferManifest.MANIFEST_PATH }
            val actualPaths = payloadHeaders.map { it.first }.toSet()
            val expectedPaths = manifest.entries.map { it.path }.toSet()
            require(actualPaths == expectedPaths) { "TRANSFER_MANIFEST_ENTRIES_MISMATCH" }
            require(manifest.entries.size <= MAX_ENTRIES) { "TRANSFER_MANIFEST_ENTRY_COUNT_EXCEEDED" }
            require(manifest.sessions.all { session -> expectedPaths.contains(session.archivePath) }) {
                "TRANSFER_MANIFEST_SESSIONS_MISMATCH"
            }
            val fileHeaders = headers.filterNot { it.isDirectory }
            val encryptedFiles = fileHeaders.map { it.isEncrypted }.distinct()
            require(encryptedFiles.size <= 1) { "TRANSFER_ARCHIVE_MIXED_ENCRYPTION" }
            require((encryptedFiles.singleOrNull() == true) == manifest.encrypted) {
                "TRANSFER_MANIFEST_ENCRYPTION_MISMATCH"
            }
            val byPath = normalized.associate { it.first to it.second }
            validateEntryTopology(manifest.entries)
            val references = linkedMapOf<String, LinkedHashSet<String>>()
            val sessionReferences = linkedMapOf<String, LinkedHashSet<String>>()
            var totalBytes = 0L
            manifest.entries.forEach { entry ->
                cancellation.check(TransferWorkPhase.VALIDATE)
                val header = requireNotNull(byPath[entry.path])
                require(header.isDirectory == entry.directory) { "TRANSFER_ENTRY_DIRECTORY_MISMATCH" }
                if (entry.directory) {
                    require(entry.sizeBytes == 0L && entry.sha256 == EMPTY_SHA256) {
                        "TRANSFER_DIRECTORY_DIGEST_INVALID"
                    }
                    require(header.uncompressedSize <= 0L) { "TRANSFER_DIRECTORY_SIZE_INVALID" }
                } else {
                    val declared = header.uncompressedSize
                    require(declared < 0L || declared == entry.sizeBytes) {
                        "TRANSFER_ENTRY_SIZE_MISMATCH"
                    }
                    require(entry.sizeBytes <= MAX_ARCHIVE_ENTRY_BYTES) { "TRANSFER_ENTRY_TOO_LARGE" }
                    totalBytes = Math.addExact(totalBytes, entry.sizeBytes)
                    require(totalBytes <= MAX_ARCHIVE_BYTES) { "TRANSFER_ARCHIVE_TOO_LARGE" }
                    val actualDigest = digestZipEntry(zip, header, entry.sizeBytes, cancellation)
                    require(actualDigest.equals(entry.sha256, ignoreCase = true)) {
                        "TRANSFER_ENTRY_DIGEST_MISMATCH"
                    }
                    if (entry.kind == "session") {
                        zip.getInputStream(header).use { input ->
                            val session = HarnessTransferSessionCodec.sessionDescriptor(
                                input,
                                entry.path,
                                requireNotNull(entry.projectKey),
                                cancellation,
                            )
                            require(session.id == entry.sessionId) { "TRANSFER_SESSION_MANIFEST_MISMATCH" }
                        }
                        zip.getInputStream(header).use { input ->
                            val found = HarnessTransferSessionCodec.collectReferences(input, cancellation)
                            mergeReferences(references, found)
                            mergeReferences(sessionReferences, found)
                        }
                    } else if (entry.kind == "file" && HarnessTransferConfiguration.isSettingsPath(entry.path) &&
                        entry.sizeBytes <= MAX_CONFIGURATION_REFERENCE_BYTES) {
                        val json = zip.getInputStream(header).use {
                            readBounded(it, MAX_CONFIGURATION_REFERENCE_BYTES, cancellation)
                        }
                            .toString(Charsets.UTF_8)
                        mergeReferences(references, HarnessTransferConfiguration.references(json))
                    } else if (entry.kind == "file" && isConfigurationPath(entry.path) &&
                        entry.sizeBytes <= MAX_CONFIGURATION_REFERENCE_BYTES) {
                        val json = zip.getInputStream(header).use {
                            readBounded(it, MAX_CONFIGURATION_REFERENCE_BYTES, cancellation)
                        }
                            .toString(Charsets.UTF_8)
                        mergeReferences(references, runCatching {
                            HarnessTransferSessionCodec.collectConfigurationReferences(json)
                        }.getOrDefault(emptyMap()))
                    }
                    if (entry.symlink) {
                        val target = zip.getInputStream(header).use {
                            readBounded(it, MAX_LINK_TARGET_BYTES, cancellation).toString(Charsets.UTF_8)
                        }
                        require(target == entry.linkTarget) { "TRANSFER_SYMLINK_TARGET_MISMATCH" }
                        validateLinkTarget(entry.path, target)
                    }
                }
            }
            val metadata = manifest.metadataNames.associateWith { name ->
                val entryPath = "$METADATA_PREFIX/$name.json"
                val entry = manifest.entries.singleOrNull { it.path == entryPath }
                    ?: throw IllegalArgumentException("TRANSFER_METADATA_MISSING")
                require(!entry.directory && entry.kind == "metadata") { "TRANSFER_METADATA_INVALID" }
                val header = requireNotNull(byPath[entryPath])
                cancellation.check(TransferWorkPhase.VALIDATE)
                val bytes = readZipEntry(zip, header, TransferMetadata.MAX_METADATA_BYTES.toLong(), cancellation)
                val text = bytes.toString(Charsets.UTF_8)
                validateJson(text)
                text
            }
            require(manifest.entries.none { it.kind == "metadata" &&
                it.path.removePrefix("$METADATA_PREFIX/").removeSuffix(".json") !in metadata }) {
                "TRANSFER_METADATA_MISMATCH"
            }
            manifest.entries.filter { it.hardlinkPath != null }.forEach { entry ->
                val target = manifest.entries.singleOrNull { it.path == entry.hardlinkPath }
                    ?: throw IllegalArgumentException("TRANSFER_HARDLINK_TARGET_MISSING")
                require(!target.directory && !target.symlink && target.kind != "session") {
                    "TRANSFER_HARDLINK_TARGET_INVALID"
                }
                require(target.sizeBytes == entry.sizeBytes && target.sha256 == entry.sha256) {
                    "TRANSFER_HARDLINK_TARGET_MISMATCH"
                }
            }
            cancellation.check(TransferWorkPhase.VALIDATE)
            return TransferInspection(
                manifest = manifest,
                entries = manifest.entries,
                metadata = metadata,
                references = references.mapValues { it.value.toSet() },
                sessionReferences = sessionReferences.mapValues { it.value.toSet() },
            )
        } finally {
            zip.close()
        }
    }

    fun stage(archive: File, options: TransferStageOptions): TransferStagedContent {
        val inspection = inspect(archive, options.password, options.cancellation)
        val manifest = inspection.manifest
        options.cancellation.check(TransferWorkPhase.STAGE)
        val stageParent = options.stagingRoot?.let { canonicalDirectory(it, "TRANSFER_STAGE_PARENT_INVALID") }
            ?: archive.canonicalFile.parentFile?.let { canonicalDirectory(it, "TRANSFER_STAGE_PARENT_INVALID") }
            ?: throw IllegalArgumentException("TRANSFER_STAGE_PARENT_INVALID")
        val stage = File(stageParent, ".adt-transfer-stage-${UUID.randomUUID()}").canonicalFile
        require(stage.mkdirs()) { "TRANSFER_STAGE_CREATE_FAILED" }
        try {
            val mapping = buildMapping(manifest, options)
            options.cancellation.check(TransferWorkPhase.STAGE)
            val zip = openZip(archive, options.password)
            try {
                val headers = zip.fileHeaders.associateBy { normalizeArchivePath(it.fileName) }
                val directoryModes = ArrayList<Pair<File, Int?>>()
                manifest.entries.sortedWith(
                    compareBy<TransferEntry> {
                        when {
                            it.directory -> 0
                            it.hardlinkPath == null -> 1
                            else -> 2
                        }
                    }.thenBy { it.path },
                ).forEach { entry ->
                    options.cancellation.check(TransferWorkPhase.STAGE)
                    val targetPath = mapping.archivePaths[entry.path] ?: entry.path
                    val target = safeStagePath(stage, targetPath)
                    if (entry.directory) {
                        require(!pathExistsNoFollow(target) || Files.isDirectory(target.toPath(), *NOFOLLOW)) {
                            "TRANSFER_STAGE_PATH_COLLISION"
                        }
                        require(target.mkdirs() || target.isDirectory) { "TRANSFER_STAGE_CREATE_FAILED" }
                        directoryModes += target to entry.mode
                        return@forEach
                    }
                    require(!pathExistsNoFollow(target)) { "TRANSFER_STAGE_PATH_COLLISION" }
                    require(target.parentFile?.let { it.isDirectory || it.mkdirs() } != false) {
                        "TRANSFER_STAGE_PARENT_INVALID"
                    }
                    require(target.parentFile?.let { Files.isDirectory(it.toPath(), *NOFOLLOW) } == true) {
                        "TRANSFER_STAGE_PARENT_INVALID"
                    }
                    if (entry.hardlinkPath != null) {
                        val linkedPath = mapping.archivePaths[entry.hardlinkPath]
                            ?: throw IllegalArgumentException("TRANSFER_HARDLINK_TARGET_MISSING")
                        val linked = safeStagePath(stage, linkedPath)
                        require(Files.isRegularFile(linked.toPath(), *NOFOLLOW)) {
                            "TRANSFER_HARDLINK_TARGET_INVALID"
                        }
                        Files.createLink(target.toPath(), linked.toPath())
                        return@forEach
                    }
                    val header = requireNotNull(headers[entry.path])
                    if (entry.symlink) {
                        val targetText = zip.getInputStream(header).use {
                            readBounded(it, MAX_LINK_TARGET_BYTES, options.cancellation).toString(Charsets.UTF_8)
                        }
                        require(targetText == entry.linkTarget) { "TRANSFER_SYMLINK_TARGET_MISMATCH" }
                        validateLinkTarget(entry.path, targetText)
                        val temporaryLink = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.link")
                        try {
                            Files.createSymbolicLink(
                                temporaryLink.toPath(),
                                stagedLinkTarget(stage, targetPath, targetText),
                            )
                            Files.move(temporaryLink.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        } finally {
                            if (temporaryLink.exists() || Files.isSymbolicLink(temporaryLink.toPath())) {
                                temporaryLink.delete()
                            }
                        }
                        return@forEach
                    }
                    val temporary = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.part")
                    try {
                        zip.getInputStream(header).use { input ->
                            FileOutputStream(temporary).use { output ->
                                if (entry.kind == "session") {
                                    HarnessTransferSessionCodec.rewrite(
                                        input = input,
                                        output = output,
                                        sessionIds = mapping.sessionIds,
                                        workspacePathMapper = options.workspacePathMapper,
                                        referenceMapper = options.referenceMapper,
                                        cancellation = options.cancellation,
                                    )
                                } else if (entry.kind == "metadata") {
                                    val raw = readBounded(
                                        input,
                                        TransferMetadata.MAX_METADATA_BYTES.toLong(),
                                        options.cancellation,
                                    )
                                    // App-owned records are intentionally byte exact. The
                                    // installation manager applies their schema-aware mappings.
                                    output.write(raw)
                                } else if (entry.kind == "file" && HarnessTransferConfiguration.isSettingsPath(entry.path)) {
                                    val raw = readBounded(input, MAX_CONFIGURATION_REFERENCE_BYTES, options.cancellation)
                                    output.write(
                                        HarnessTransferConfiguration.rewriteSettings(
                                            raw.toString(Charsets.UTF_8),
                                            options.referenceMapper,
                                            jsonOutput = entry.path.endsWith(".json"),
                                        ).toByteArray(Charsets.UTF_8),
                                    )
                                } else {
                                    copyBounded(input, output, entry.sizeBytes, options.cancellation, TransferWorkPhase.STAGE)
                                }
                            }
                        }
                        moveIntoPlace(temporary, target)
                    if (entry.kind != "session" && entry.kind != "metadata" &&
                        !HarnessTransferConfiguration.isSettingsPath(entry.path)) {
                        require(sha256(target, options.cancellation) == entry.sha256) {
                            "TRANSFER_STAGE_DIGEST_MISMATCH"
                        }
                        }
                        if (entry.kind == "session") {
                            val mappedParts = targetPath.split('/')
                            require(mappedParts.size == 5 && mappedParts[0] == DSH_HOME_PREFIX &&
                                    mappedParts[1] == "sessions") { "TRANSFER_SESSION_PATH_INVALID" }
                            FileInputStream(target).use { input ->
                                val rewritten = HarnessTransferSessionCodec.sessionDescriptor(
                                    input,
                                    targetPath,
                                    mappedParts[2],
                                    options.cancellation,
                                )
                                require(rewritten.id == mapping.sessionIds[entry.sessionId]) {
                                    "TRANSFER_SESSION_MAPPING_INVALID"
                                }
                            }
                        } else if (entry.kind == "metadata") {
                            options.cancellation.check(TransferWorkPhase.VALIDATE)
                            validateJson(target.readText(Charsets.UTF_8))
                        } else if (entry.kind == "file" && HarnessTransferConfiguration.isSettingsPath(entry.path)) {
                            options.cancellation.check(TransferWorkPhase.VALIDATE)
                            validateSettings(target.readText(Charsets.UTF_8))
                        }
                        applyMode(target, entry.mode)
                    } finally {
                        if (temporary.exists()) temporary.delete()
                    }
                }
                directoryModes.asReversed().forEach { (directory, mode) -> applyMode(directory, mode) }
            } finally {
                zip.close()
            }
            val dshHome = File(stage, DSH_HOME_PREFIX).apply { mkdirs() }
            val projects = File(stage, PROJECTS_PREFIX).apply { mkdirs() }
            val rootfs = File(stage, ROOTFS_PREFIX).takeIf {
                manifest.mode == TransferArchiveMode.FULL && manifest.entries.any { entry -> entry.path == ROOTFS_PREFIX || entry.path.startsWith("$ROOTFS_PREFIX/") }
            }
            rootfs?.mkdirs()
            val attachments = File(stage, ATTACHMENTS_PREFIX).takeIf {
                manifest.entries.any { entry -> entry.path == ATTACHMENTS_PREFIX || entry.path.startsWith("$ATTACHMENTS_PREFIX/") }
            }
            attachments?.mkdirs()
            return TransferStagedContent(
                stagingDirectory = stage,
                dshHome = dshHome,
                projects = projects,
                runtimeRoot = null,
                rootfs = rootfs,
                manifest = manifest,
                mapping = mapping,
                entries = manifest.entries,
                attachments = attachments,
            )
        } catch (error: Throwable) {
            stage.deleteRecursively()
            throw error
        }
    }

    private fun collect(source: TransferSource, options: TransferExportOptions): Collected {
        val dshHome = canonicalDirectory(source.dshHome, "TRANSFER_DSH_HOME_INVALID")
        val projects = canonicalDirectory(source.projects, "TRANSFER_PROJECTS_INVALID")
        require(!sameOrDescendant(projects, dshHome) && !sameOrDescendant(dshHome, projects)) {
            "TRANSFER_SOURCE_ROOTS_OVERLAP"
        }
        // Runtime scratch/cache/pid trees are intentionally never part of a transfer. The source
        // field remains accepted for callers that still populate the pre-v1 model.
        val rootfs = source.rootfs?.let { canonicalDirectory(it, "TRANSFER_ROOTFS_INVALID") }
        if (rootfs != null) require(!sameOrDescendant(rootfs, dshHome) && !sameOrDescendant(dshHome, rootfs) &&
            !sameOrDescendant(rootfs, projects) && !sameOrDescendant(projects, rootfs)) {
            "TRANSFER_SOURCE_ROOTS_OVERLAP"
        }

        val entries = LinkedHashMap<String, SourceEntry>()
        val sessions = LinkedHashMap<String, TransferSession>()
        val selectedSessionIds = options.sessionIds?.let {
            sessionSelectionClosure(dshHome, it, options.cancellation)
        }
        // Attachment bytes are content-addressed below dsh_home/attachments/v1. Resolve the
        // digests from the selected canonical session messages before walking that store so an
        // export never includes unrelated uploads or request-image cache data.
        val attachmentDigests = collectSessionAttachmentDigests(dshHome, selectedSessionIds, options.cancellation)
        scanTree(
            root = dshHome,
            prefix = DSH_HOME_PREFIX,
            kind = { path, directory -> if (!directory && isSessionArchivePath(path)) "session" else "file" },
            excludedTopLevel = DSH_TRANSIENT_TOP_LEVEL,
            entries = entries,
            sessions = sessions,
            cancellation = options.cancellation,
            selectedTopLevel = null,
            selectedSessionIds = selectedSessionIds,
            attachmentDigests = attachmentDigests,
            excludeSensitive = !options.includeCredentials && !options.includeSensitiveMetadata,
            includeConfiguration = options.includeConfiguration,
        )
        scanTree(
            root = projects,
            prefix = PROJECTS_PREFIX,
            kind = { _, _ -> "workspace" },
            excludedTopLevel = emptySet(),
            entries = entries,
            sessions = sessions,
            cancellation = options.cancellation,
            selectedTopLevel = options.projectFolders,
            selectedSessionIds = null,
            attachmentDigests = null,
            excludeSensitive = false,
            includeConfiguration = true,
        )
        if (options.mode == TransferArchiveMode.FULL && rootfs != null) {
            scanTree(
                root = rootfs,
                prefix = ROOTFS_PREFIX,
                kind = { _, _ -> "rootfs" },
                excludedTopLevel = emptySet(),
                entries = entries,
                sessions = sessions,
                cancellation = options.cancellation,
                selectedTopLevel = null,
                selectedSessionIds = null,
                attachmentDigests = null,
                // FULL rootfs is a guest snapshot. Filtering names such as "providers" or
                // "auth" there can delete legitimate packages; the UI warns about user files.
                excludeSensitive = false,
                includeConfiguration = true,
            )
        }
        collectAdditionalFiles(source.additionalFiles, entries, options.cancellation)
        val metadataNames = ArrayList<String>()
        require(source.metadata.size <= MAX_METADATA_ROWS) { "TRANSFER_METADATA_COUNT_EXCEEDED" }
        source.metadata.sortedBy { it.name }.forEach { metadata ->
            if (metadata.sensitive && !(options.includeSensitiveMetadata || options.includeCredentials)) return@forEach
            validateJson(metadata.json)
            val path = "$METADATA_PREFIX/${metadata.name}.json"
            val bytes = metadata.json.toByteArray(Charsets.UTF_8)
            val descriptor = TransferEntry(path, "metadata", bytes.size.toLong(), sha256(bytes))
            addEntry(entries, SourceEntry(path, bytes = bytes, descriptor = descriptor))
            metadataNames += metadata.name
        }
        return Collected(
            entries = entries.values.sortedBy { it.path },
            sessions = sessions.values.sortedBy { it.archivePath },
            metadataNames = metadataNames,
        )
    }

    private fun scanTree(
        root: File,
        prefix: String,
        kind: (path: String, directory: Boolean) -> String,
        excludedTopLevel: Set<String>,
        entries: MutableMap<String, SourceEntry>,
        sessions: MutableMap<String, TransferSession>,
        cancellation: TransferCancellation,
        selectedTopLevel: Set<String>?,
        selectedSessionIds: Set<String>?,
        attachmentDigests: Set<String>?,
        excludeSensitive: Boolean,
        includeConfiguration: Boolean,
    ) {
        val hardlinks = HashMap<Any, String>()
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun preVisitDirectory(dir: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                cancellation.check(TransferWorkPhase.SCAN)
                if (dir != root.toPath()) {
                    val relative = root.toPath().relativize(dir).toString().replace(File.separatorChar, '/')
                    val topLevel = relative.substringBefore('/')
                    if (topLevel.let(excludedTopLevel::contains) ||
                        (selectedTopLevel != null && topLevel !in selectedTopLevel) ||
                        isTransientDshPath(relative, prefix) ||
                        (excludeSensitive && isSensitiveDshPath(relative, prefix)) ||
                        !includeDshPath(relative, prefix, includeConfiguration, selectedSessionIds, attachmentDigests)) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    val path = "$prefix/$relative"
                    addEntry(entries, SourceEntry(
                        path,
                        source = dir.toFile(),
                        descriptor = directoryDescriptor(path, kind(path, true), readMode(dir, directory = true)),
                    ))
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                cancellation.check(TransferWorkPhase.SCAN)
                val relative = root.toPath().relativize(file).toString().replace(File.separatorChar, '/')
                val topLevel = relative.substringBefore('/')
                if (topLevel.let(excludedTopLevel::contains) ||
                    (selectedTopLevel != null && topLevel !in selectedTopLevel) ||
                    isTransientDshPath(relative, prefix) ||
                    (excludeSensitive && isSensitiveDshPath(relative, prefix)) ||
                    !includeDshPath(relative, prefix, includeConfiguration, selectedSessionIds, attachmentDigests)) {
                    return FileVisitResult.CONTINUE
                }
                val path = "$prefix/$relative"
                val entryKind = kind(path, false)
                val symlink = Files.isSymbolicLink(file)
                if (!symlink) require(Files.isRegularFile(file, *NOFOLLOW)) { "TRANSFER_SPECIAL_FILE_FORBIDDEN" }
                if (entryKind == "session") require(!symlink) { "TRANSFER_SESSION_SYMLINK_FORBIDDEN" }
                val linkTarget = if (symlink) Files.readSymbolicLink(file).toString() else null
                if (symlink) validateLinkTarget(path, requireNotNull(linkTarget))
                val linkBytes = linkTarget?.toByteArray(Charsets.UTF_8)
                val size = linkBytes?.size?.toLong() ?: Files.size(file)
                require(size <= MAX_ARCHIVE_ENTRY_BYTES) { "TRANSFER_ENTRY_TOO_LARGE" }
                val sanitizedSettings = if (!symlink && prefix == DSH_HOME_PREFIX &&
                    HarnessTransferConfiguration.isSettingsPath(path) && excludeSensitive) {
                    require(Files.size(file) <= MAX_CONFIGURATION_REFERENCE_BYTES) {
                        "TRANSFER_SETTINGS_TOO_LARGE"
                    }
                    HarnessTransferConfiguration.sanitizeSettings(
                        file.toFile().readText(Charsets.UTF_8),
                        jsonOutput = path.endsWith(".json"),
                    )
                        .toByteArray(Charsets.UTF_8)
                } else null
                val digest = linkBytes?.let(::sha256) ?: sanitizedSettings?.let(::sha256) ?: sha256(file.toFile(), cancellation)
                val session: TransferSession? = if (entryKind == "session") {
                    val parts = path.split('/')
                    require(parts.size == 5 && parts[1] == "sessions" && parts[4] == "session.v3.jsonl.zstd") {
                        "TRANSFER_SESSION_PATH_INVALID"
                    }
                    FileInputStream(file.toFile()).use {
                        HarnessTransferSessionCodec.sessionDescriptor(it, path, parts[2], cancellation)
                    }
                } else null
                if (session != null && selectedSessionIds != null && session.id !in selectedSessionIds) {
                    return FileVisitResult.CONTINUE
                }
                val fileKey = attrs.fileKey()
                val hardlinkPath = if (!symlink && sanitizedSettings == null && entryKind != "session") {
                    fileKey?.let { key ->
                        val previous = hardlinks[key]
                        when {
                            previous == null -> {
                                hardlinks[key] = path
                                null
                            }
                            path < previous -> {
                                entries[previous]?.let { prior ->
                                    entries[previous] = prior.copy(
                                        descriptor = prior.descriptor.copy(hardlinkPath = path),
                                    )
                                }
                                hardlinks[key] = path
                                null
                            }
                            else -> previous
                        }
                    }
                } else null
                val descriptor = TransferEntry(
                    path = path,
                    kind = entryKind,
                    sizeBytes = sanitizedSettings?.size?.toLong() ?: size,
                    sha256 = digest,
                    mode = readMode(file, directory = false),
                    symlink = symlink,
                    linkTarget = linkTarget,
                    hardlinkPath = hardlinkPath,
                    sessionId = session?.id,
                    projectKey = session?.projectKey,
                )
                addEntry(entries, SourceEntry(
                    path,
                    source = sanitizedSettings?.let { null } ?: file.toFile(),
                    bytes = sanitizedSettings,
                    descriptor = descriptor,
                ))
                if (session != null) {
                    require(sessions.put(session.id, session) == null) { "TRANSFER_SESSION_DUPLICATE" }
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun includeDshPath(
        relative: String,
        prefix: String,
        includeConfiguration: Boolean,
        selectedSessionIds: Set<String>?,
        attachmentDigests: Set<String>?,
    ): Boolean {
        if (prefix != DSH_HOME_PREFIX) return true
        val parts = relative.split('/').filter { it.isNotEmpty() }
        if (parts.firstOrNull() == "sessions" && selectedSessionIds != null && parts.size >= 3) {
            val sessionId = runCatching { HarnessTransferSessionCodec.decodeSegment(parts[2]) }.getOrNull()
                ?: return false
            if (sessionId !in selectedSessionIds) return false
        }
        if (parts.firstOrNull() == "attachments") {
            return includeAttachmentPath(parts, attachmentDigests.orEmpty())
        }
        if (includeConfiguration) return true
        return when (parts.firstOrNull()) {
            "sessions", "workspace" -> true
            "workspace.json" -> parts.size == 1
            "storages" -> parts.size == 1 ||
                (parts.getOrNull(1) == "workspace" || parts.getOrNull(1) == "workspace.json")
            else -> false
        }
    }

    /** Keep only immutable image/file objects named by selected session attachment references. */
    private fun includeAttachmentPath(parts: List<String>, digests: Set<String>): Boolean {
        if (digests.isEmpty()) return false
        if (parts.size <= 2) return true // attachments and its versioned root
        if (parts[1] != "v1") return false
        if (parts.size <= 3) return true // the object-kind directory
        val kind = parts[2]
        if (kind !in setOf("objects", "file-objects", "files")) return false
        if (parts.size == 4) return true // the digest-prefix directory
        val digest = parts[4]
        if (digest.length != 64 || digest !in digests) return false
        // `files/<prefix>/<digest>/<name>` contains the sanitized display-name aliases. They
        // all address the same immutable bytes, so retaining that one digest subtree is safe.
        return true
    }

    /** Include the parent/child session family so a selected fork remains attachable. */
    private fun sessionSelectionClosure(
        dshHome: File,
        requested: Set<String>,
        cancellation: TransferCancellation,
    ): Set<String> {
        if (requested.isEmpty()) return emptySet()
        val parents = linkedMapOf<String, String>()
        Files.walkFileTree(dshHome.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                cancellation.check(TransferWorkPhase.SCAN)
                val relative = dshHome.toPath().relativize(file).toString().replace(File.separatorChar, '/')
                val path = "$DSH_HOME_PREFIX/$relative"
                if (isSessionArchivePath(path)) {
                    require(!Files.isSymbolicLink(file) && Files.isRegularFile(file, *NOFOLLOW)) {
                        "TRANSFER_SESSION_SYMLINK_FORBIDDEN"
                    }
                    FileInputStream(file.toFile()).use { input ->
                        val header = HarnessTransferSessionCodec.readHeader(input, cancellation)
                        val parent = header.json.optString("parentSession", "").takeIf { it.isNotEmpty() }
                        if (parent != null) {
                            require(parents.put(header.id, parent) == null) { "TRANSFER_SESSION_DUPLICATE" }
                        } else {
                            require(!parents.containsKey(header.id)) { "TRANSFER_SESSION_DUPLICATE" }
                            parents.putIfAbsent(header.id, "")
                        }
                    }
                }
                return FileVisitResult.CONTINUE
            }
        })
        val children = linkedMapOf<String, MutableSet<String>>()
        parents.forEach { (child, parent) ->
            if (parent.isNotEmpty()) children.getOrPut(parent) { linkedSetOf() }.add(child)
        }
        val selected = linkedSetOf<String>()
        val queue = java.util.ArrayDeque<String>()
        requested.forEach { if (selected.add(it)) queue.addLast(it) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val parent = parents[current].orEmpty()
            if (parent.isNotEmpty() && selected.add(parent)) queue.addLast(parent)
            children[current].orEmpty().forEach { child -> if (selected.add(child)) queue.addLast(child) }
        }
        return selected
    }

    /** Resolve selected session message attachments before scanning the content-addressed store. */
    private fun collectSessionAttachmentDigests(
        dshHome: File,
        selectedSessionIds: Set<String>?,
        cancellation: TransferCancellation,
    ): Set<String> {
        val digests = linkedSetOf<String>()
        val sessionsRoot = File(dshHome, "sessions")
        if (!sessionsRoot.isDirectory || Files.isSymbolicLink(sessionsRoot.toPath())) return digests
        Files.walkFileTree(sessionsRoot.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                cancellation.check(TransferWorkPhase.SCAN)
                val relative = dshHome.toPath().relativize(file).toString().replace(File.separatorChar, '/')
                val path = "$DSH_HOME_PREFIX/$relative"
                if (!isSessionArchivePath(path) || Files.isSymbolicLink(file) || !Files.isRegularFile(file, *NOFOLLOW)) {
                    return FileVisitResult.CONTINUE
                }
                FileInputStream(file.toFile()).use { input ->
                    val header = HarnessTransferSessionCodec.readHeader(input, cancellation)
                    if (selectedSessionIds == null || header.id in selectedSessionIds) {
                        FileInputStream(file.toFile()).use { sessionInput ->
                            digests += HarnessTransferSessionCodec.collectAttachmentDigests(sessionInput, cancellation)
                        }
                    }
                }
                return FileVisitResult.CONTINUE
            }
        })
        return digests
    }

    private fun collectAdditionalFiles(
        additionalFiles: Map<String, File>,
        entries: MutableMap<String, SourceEntry>,
        cancellation: TransferCancellation,
    ) {
        if (additionalFiles.isEmpty()) return
        val rootPath = ATTACHMENTS_PREFIX
        if (entries[rootPath] == null) {
            addEntry(entries, SourceEntry(
                rootPath,
                descriptor = directoryDescriptor(rootPath, "attachment", 0x1C0),
            ))
        }
        additionalFiles.toSortedMap().forEach { (path, file) ->
            require(file.exists() && file.isFile && !Files.isSymbolicLink(file.toPath())) {
                "TRANSFER_ATTACHMENT_INVALID"
            }
            val parts = path.split('/')
            require(parts.size == 3 && parts[0] == rootPath) { "TRANSFER_ATTACHMENT_PATH_INVALID" }
            val keyPath = "${parts[0]}/${parts[1]}"
            if (entries[keyPath] == null) {
                addEntry(entries, SourceEntry(
                    keyPath,
                    descriptor = directoryDescriptor(keyPath, "attachment", 0x1C0),
                ))
            }
            addEntry(entries, SourceEntry(
                path,
                source = file.canonicalFile,
                descriptor = TransferEntry(
                    path = path,
                    kind = "attachment",
                    sizeBytes = Files.size(file.toPath()),
                    sha256 = sha256(file, cancellation),
                    mode = readMode(file.toPath(), directory = false),
                ),
            ))
        }
    }

    private fun buildMapping(manifest: TransferManifest, options: TransferStageOptions): TransferMapping {
        val sessionIds = LinkedHashMap<String, String>()
        val usedIds = HashSet<String>()
        manifest.sessions.forEach { session ->
            val mapped = options.sessionIdFactory(session.id)
            require(mapped.isNotBlank() && mapped.length <= 4096 && '\u0000' !in mapped) {
                "TRANSFER_SESSION_ID_INVALID"
            }
            require(usedIds.add(mapped)) { "TRANSFER_SESSION_ID_COLLISION" }
            sessionIds[session.id] = mapped
        }
        val workspacePaths = LinkedHashMap<String, String>()
        manifest.sessions.mapNotNull { it.cwd }.distinct().forEach { oldCwd ->
            val mapped = options.workspacePathMapper(oldCwd) ?: oldCwd
            require(mapped.startsWith('/') && '\u0000' !in mapped) { "TRANSFER_SESSION_CWD_INVALID" }
            val prior = workspacePaths.put(oldCwd, mapped)
            require(prior == null || prior == mapped) { "TRANSFER_WORKSPACE_MAPPING_COLLISION" }
        }
        val archivePaths = LinkedHashMap<String, String>()
        manifest.entries.forEach { entry ->
            archivePaths[entry.path] = mapArchivePath(entry.path, manifest.sessions, sessionIds, workspacePaths)
        }
        require(archivePaths.values.distinct().size == archivePaths.size) { "TRANSFER_STAGE_PATH_COLLISION" }
        return TransferMapping(sessionIds, workspacePaths, archivePaths)
    }

    private fun mapArchivePath(
        path: String,
        sessions: List<TransferSession>,
        sessionIds: Map<String, String>,
        workspacePaths: Map<String, String>,
    ): String {
        val session = sessions.firstOrNull { path == it.archivePath || path.startsWith(it.archivePath.substringBeforeLast('/') + "/") }
            ?: return path
        val oldPrefix = session.archivePath.substringBeforeLast('/')
        val newCwd = session.cwd?.let(workspacePaths::get)
        val newProject = newCwd?.let(HarnessTransferSessionCodec::projectKey) ?: session.projectKey
        val newPrefix = "$DSH_HOME_PREFIX/sessions/$newProject/${HarnessTransferSessionCodec.encodeSegment(requireNotNull(sessionIds[session.id]))}"
        return newPrefix + path.removePrefix(oldPrefix)
    }

    private fun writeArchive(
        destination: File,
        manifestBytes: ByteArray,
        entries: List<SourceEntry>,
        options: TransferExportOptions,
    ) {
        val destinationPath = destination.canonicalFile.toPath()
        require(destination.parentFile?.let { it.exists() || it.mkdirs() } != false) {
            "TRANSFER_DESTINATION_PARENT_INVALID"
        }
        val temporary = File(destination.parentFile, ".${destination.name}.${UUID.randomUUID()}.part")
        try {
            BufferedOutputStream(FileOutputStream(temporary)).use { raw ->
                val zip = if (options.password == null) {
                    ZipOutputStream(raw)
                } else {
                    ZipOutputStream(raw, options.password.copyOf())
                }
                try {
                    writeZipEntry(zip, TransferManifest.MANIFEST_PATH, manifestBytes, options)
                    entries.forEach { entry -> writeSourceEntry(zip, entry, options) }
                } finally {
                    zip.close()
                }
            }
            require(temporary.renameTo(destination)) {
                // A replace is required for re-exporting an existing destination on Android.
                try {
                    Files.move(
                        temporary.toPath(), destinationPath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                    true
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), destinationPath, StandardCopyOption.REPLACE_EXISTING)
                    true
                }
            }
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun writeSourceEntry(zip: ZipOutputStream, entry: SourceEntry, options: TransferExportOptions) {
        options.cancellation.check(TransferWorkPhase.WRITE)
        val path = if (entry.descriptor.directory) "${entry.path}/" else entry.path
        if (entry.descriptor.directory) {
            writeZipEntry(zip, path, ByteArray(0), options, directory = true)
            return
        }
        val source = entry.source
        if (source != null) {
            if (entry.descriptor.symlink) {
                require(Files.isSymbolicLink(source.toPath())) { "TRANSFER_SOURCE_CHANGED" }
                val target = Files.readSymbolicLink(source.toPath()).toString()
                require(target == entry.descriptor.linkTarget) { "TRANSFER_SOURCE_CHANGED" }
                val bytes = target.toByteArray(Charsets.UTF_8)
                require(bytes.size.toLong() == entry.descriptor.sizeBytes) { "TRANSFER_SOURCE_CHANGED" }
                require(sha256(bytes).equals(entry.descriptor.sha256, ignoreCase = true)) {
                    "TRANSFER_SOURCE_CHANGED"
                }
                writeZipEntry(zip, path, bytes, options)
                return
            }
            requireRegularFile(source, "TRANSFER_SOURCE_CHANGED")
            val actualSize = Files.size(source.toPath())
            require(actualSize == entry.descriptor.sizeBytes) { "TRANSFER_SOURCE_CHANGED" }
            val digest = MessageDigest.getInstance("SHA-256")
            val params = zipParameters(path, options, directory = false)
            zip.putNextEntry(params)
            FileInputStream(source).use { input ->
                copyAndDigest(input, zip, digest, actualSize, options.cancellation, TransferWorkPhase.WRITE)
            }
            zip.closeEntry()
            require(hex(digest.digest()).equals(entry.descriptor.sha256, ignoreCase = true)) {
                "TRANSFER_SOURCE_CHANGED"
            }
        } else {
            writeZipEntry(zip, path, requireNotNull(entry.bytes), options)
        }
    }

    private fun writeZipEntry(
        zip: ZipOutputStream,
        path: String,
        bytes: ByteArray,
        options: TransferExportOptions,
        directory: Boolean = false,
    ) {
        val params = zipParameters(path, options, directory)
        zip.putNextEntry(params)
        if (!directory) zip.write(bytes)
        zip.closeEntry()
    }

    private fun zipParameters(path: String, options: TransferExportOptions, directory: Boolean): ZipParameters =
        ZipParameters().apply {
            fileNameInZip = path
            compressionMethod = if (directory) CompressionMethod.STORE else CompressionMethod.DEFLATE
            if (!directory) {
                compressionLevel = CompressionLevel.values().minByOrNull {
                    abs(it.getLevel() - options.compressionLevel)
                } ?: CompressionLevel.NORMAL
            }
            if (!directory && options.password != null) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }

    private fun openZip(archive: File, password: CharArray?): ZipFile {
        val zip = if (password == null) ZipFile(archive) else ZipFile(archive, password.copyOf())
        try {
            require(zip.isValidZipFile) { "TRANSFER_ARCHIVE_INVALID" }
        } catch (error: Throwable) {
            zip.close()
            if (password != null) throw IllegalArgumentException("TRANSFER_PASSWORD_OR_ARCHIVE_INVALID", error)
            throw error
        }
        return zip
    }

    private fun readZipEntry(
        zip: ZipFile,
        header: FileHeader,
        maxBytes: Long,
        cancellation: TransferCancellation = TransferCancellation.NONE,
    ): ByteArray = zip.getInputStream(header).use { readBounded(it, maxBytes, cancellation) }

    private fun digestZipEntry(
        zip: ZipFile,
        header: FileHeader,
        expectedSize: Long,
        cancellation: TransferCancellation = TransferCancellation.NONE,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        zip.getInputStream(header).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                cancellation.check(TransferWorkPhase.VALIDATE)
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= MAX_ARCHIVE_ENTRY_BYTES && total <= expectedSize) {
                    "TRANSFER_ENTRY_SIZE_MISMATCH"
                }
                digest.update(buffer, 0, count)
            }
        }
        require(total == expectedSize) { "TRANSFER_ENTRY_SIZE_MISMATCH" }
        return hex(digest.digest())
    }

    private fun readBounded(
        input: InputStream,
        maxBytes: Long,
        cancellation: TransferCancellation = TransferCancellation.NONE,
        phase: TransferWorkPhase = TransferWorkPhase.READ,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            cancellation.check(phase)
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= maxBytes) { "TRANSFER_ENTRY_TOO_LARGE" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun copyBounded(
        input: InputStream,
        output: OutputStream,
        expectedSize: Long,
        cancellation: TransferCancellation,
        phase: TransferWorkPhase,
    ) {
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            cancellation.check(phase)
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= expectedSize && total <= MAX_ARCHIVE_ENTRY_BYTES) { "TRANSFER_ENTRY_SIZE_MISMATCH" }
            output.write(buffer, 0, count)
        }
        require(total == expectedSize) { "TRANSFER_ENTRY_SIZE_MISMATCH" }
    }

    private fun copyAndDigest(
        input: InputStream,
        output: OutputStream,
        digest: MessageDigest,
        expectedSize: Long,
        cancellation: TransferCancellation,
        phase: TransferWorkPhase,
    ) {
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            cancellation.check(phase)
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= expectedSize) { "TRANSFER_SOURCE_CHANGED" }
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        require(total == expectedSize) { "TRANSFER_SOURCE_CHANGED" }
    }

    private fun sha256(file: File, cancellation: TransferCancellation = TransferCancellation.NONE): String {
        FileInputStream(file).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                cancellation.check(TransferWorkPhase.SCAN)
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
            return hex(digest.digest())
        }
    }

    private fun sha256(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun directoryDescriptor(path: String, kind: String, mode: Int): TransferEntry =
        TransferEntry(path, kind, 0L, EMPTY_SHA256, directory = true, mode = mode)

    private fun addEntry(entries: MutableMap<String, SourceEntry>, entry: SourceEntry) {
        validateArchivePath(entry.path)
        require(entries.put(entry.path, entry) == null) { "TRANSFER_ENTRY_DUPLICATE" }
    }

    private fun isSessionArchivePath(path: String): Boolean =
        Regex("^dsh_home/sessions/[^/]+/[^/]+/session\\.v3\\.jsonl\\.zstd$").matches(path)

    private fun isConfigurationPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".json") && listOf("config", "setting", "provider", "model", "profile", "llm")
            .any { lower.contains(it) }
    }

    private fun mergeReferences(
        destination: MutableMap<String, LinkedHashSet<String>>,
        source: Map<String, Set<String>>,
    ) {
        source.forEach { (key, values) -> destination.getOrPut(key) { linkedSetOf() }.addAll(values) }
    }

    private fun normalizeArchivePath(raw: String): String {
        val path = raw.removeSuffix("/")
        validateArchivePath(path)
        return path
    }

    private fun isSensitiveDshPath(relative: String, prefix: String): Boolean {
        if (prefix != DSH_HOME_PREFIX) return false
        val components = relative.split('/').filter { it.isNotEmpty() }
        val lower = components.map(String::lowercase)
        // Package/plugin trees are executable user data. A dependency can legitimately contain
        // directories named `providers` or `auth`; only app-managed home storage is filtered.
        if (lower.firstOrNull() in setOf("plugins", "node_modules", "packages")) return false
        if (lower.size == 1 && (lower[0] in DSH_SECRET_STORAGE_DIRECTORIES ||
                lower[0] in DSH_SECRET_FILE_NAMES || lower[0] == ".env")) return true
        if (lower.size <= 2 && lower.lastOrNull() in DSH_SECRET_FILE_NAMES) return true
        return lower.firstOrNull() == "storages" &&
            lower.drop(1).any { it in DSH_SECRET_STORAGE_DIRECTORIES }
    }

    private fun isTransientDshPath(relative: String, prefix: String): Boolean {
        if (prefix != DSH_HOME_PREFIX) return false
        return relative.split('/').filter { it.isNotEmpty() }.any { component ->
            val lower = component.lowercase()
            lower == "pendingmutation" || lower == "pending_mutation" || lower == "pending-mutation"
        }
    }

    private fun validateLinkTarget(entryPath: String, target: String) {
        require(target.isNotEmpty() && target.length <= 4096 && '\u0000' !in target && '\\' !in target) {
            "TRANSFER_SYMLINK_TARGET_INVALID"
        }
        val root = entryPath.substringBefore('/')
        if (target.startsWith('/')) {
            require(root == ROOTFS_PREFIX) { "TRANSFER_SYMLINK_TARGET_EXTERNAL" }
            val guest = Paths.get(target.removePrefix("/")).normalize()
            require(!guest.startsWith("..")) { "TRANSFER_SYMLINK_TARGET_ESCAPE" }
            return
        }
        val parent = entryPath.substringBeforeLast('/', "")
        val resolved = Paths.get(parent).resolve(target).normalize().toString().replace(File.separatorChar, '/')
        require(resolved == root || resolved.startsWith("$root/")) { "TRANSFER_SYMLINK_TARGET_ESCAPE" }
    }

    private fun stagedLinkTarget(stage: File, targetPath: String, original: String): Path {
        if (!original.startsWith('/')) return Paths.get(original)
        require(targetPath.startsWith("$ROOTFS_PREFIX/")) { "TRANSFER_SYMLINK_TARGET_EXTERNAL" }
        val guest = Paths.get(original.removePrefix("/")).normalize()
        require(!guest.startsWith("..")) { "TRANSFER_SYMLINK_TARGET_ESCAPE" }
        val rootfs = safeStagePath(stage, ROOTFS_PREFIX)
        val parent = safeStagePath(stage, targetPath.substringBeforeLast('/'))
        val destination = rootfs.toPath().resolve(guest).normalize()
        require(destination.startsWith(rootfs.toPath())) { "TRANSFER_SYMLINK_TARGET_ESCAPE" }
        return parent.toPath().relativize(destination)
    }

    private fun validateEntryTopology(entries: List<TransferEntry>) {
        val byPath = entries.associateBy { it.path }
        entries.forEach { entry ->
            var parent = entry.path.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                val parentEntry = byPath[parent]
                require(parentEntry == null || (parentEntry.directory && !parentEntry.symlink && parentEntry.hardlinkPath == null)) {
                    "TRANSFER_ENTRY_PARENT_INVALID"
                }
                parent = parent.substringBeforeLast('/', "")
            }
            if (entry.symlink) {
                require(entries.none { it.path != entry.path && it.path.startsWith("${entry.path}/") }) {
                    "TRANSFER_SYMLINK_PARENT_INVALID"
                }
            }
        }
    }

    private fun validateEntrySemantics(manifest: TransferManifest) {
        manifest.entries.forEach { entry ->
            val root = entry.path.substringBefore('/')
            when (entry.kind) {
                "file", "session" -> require(root == DSH_HOME_PREFIX) { "TRANSFER_ENTRY_KIND_PATH_MISMATCH" }
                "workspace" -> require(root == PROJECTS_PREFIX) { "TRANSFER_ENTRY_KIND_PATH_MISMATCH" }
                "attachment" -> {
                    require(root == ATTACHMENTS_PREFIX) { "TRANSFER_ENTRY_KIND_PATH_MISMATCH" }
                    require(!entry.symlink && entry.hardlinkPath == null) { "TRANSFER_ATTACHMENT_LINK_FORBIDDEN" }
                }
                "rootfs" -> require(manifest.mode == TransferArchiveMode.FULL && root == ROOTFS_PREFIX) {
                    "TRANSFER_ROOTFS_MODE_MISMATCH"
                }
                "metadata" -> {
                    require(root == METADATA_PREFIX && entry.path.matches(Regex("^metadata/[A-Za-z0-9][A-Za-z0-9_.-]{0,95}\\.json$"))) {
                        "TRANSFER_METADATA_PATH_INVALID"
                    }
                    require(!entry.directory && !entry.symlink && entry.hardlinkPath == null) {
                        "TRANSFER_METADATA_INVALID"
                    }
                }
                "runtime" -> throw IllegalArgumentException("TRANSFER_RUNTIME_SCRATCH_FORBIDDEN")
                else -> throw IllegalArgumentException("TRANSFER_ENTRY_KIND_INVALID")
            }
            if (entry.kind == "file" && entry.path.startsWith("$DSH_HOME_PREFIX/attachments/")) {
                validateContentAddressedAttachment(entry)
            }
            if (entry.kind == "session") {
                require(!entry.directory && !entry.symlink && entry.sessionId != null && entry.projectKey != null) {
                    "TRANSFER_SESSION_MANIFEST_INVALID"
                }
            }
        }
        val metadataPaths = manifest.entries.filter { it.kind == "metadata" }
            .map { it.path.removePrefix("$METADATA_PREFIX/").removeSuffix(".json") }
        require(metadataPaths.toSet() == manifest.metadataNames.toSet()) { "TRANSFER_METADATA_MISMATCH" }
    }

    /** Validate the pinned dsh-attachment-local v1 layout before a staged home is published. */
    private fun validateContentAddressedAttachment(entry: TransferEntry) {
        require(!entry.symlink) { "TRANSFER_ATTACHMENT_LINK_FORBIDDEN" }
        val relative = entry.path.removePrefix("$DSH_HOME_PREFIX/attachments/v1/")
        if (relative == entry.path) return
        val parts = relative.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return
        require(parts[0] in ATTACHMENT_OBJECT_KINDS) { "TRANSFER_ATTACHMENT_PATH_INVALID" }
        if (parts.size == 1) {
            require(entry.directory) { "TRANSFER_ATTACHMENT_PATH_INVALID" }
            return
        }
        require(parts[1].matches(Regex("[0-9a-f]{2}"))) { "TRANSFER_ATTACHMENT_PATH_INVALID" }
        if (entry.directory) {
            if (parts.size == 2) return
            require(parts[0] == "files" && parts.size == 3 && parts[2].matches(ATTACHMENT_DIGEST)) {
                "TRANSFER_ATTACHMENT_PATH_INVALID"
            }
            require(parts[2].startsWith(parts[1])) { "TRANSFER_ATTACHMENT_PATH_INVALID" }
            return
        }
        val digest = parts.getOrNull(2)
        require(digest?.matches(ATTACHMENT_DIGEST) == true && digest.startsWith(parts[1])) {
            "TRANSFER_ATTACHMENT_PATH_INVALID"
        }
        require(
            (parts[0] in setOf("objects", "file-objects") && parts.size == 3) ||
                (parts[0] == "files" && parts.size == 4),
        ) { "TRANSFER_ATTACHMENT_PATH_INVALID" }
        require(entry.sha256 == digest) { "TRANSFER_ATTACHMENT_DIGEST_MISMATCH" }
    }

    private fun pathExistsNoFollow(file: File): Boolean = Files.exists(file.toPath(), *NOFOLLOW)

    private fun readMode(path: Path, directory: Boolean): Int {
        return try {
            (Files.getAttribute(path, "unix:mode", *NOFOLLOW) as Number).toInt() and 0xFFF
        } catch (_: Throwable) {
            val executable = path.toFile().canExecute()
            when {
                directory && executable -> 0x1ED // 0755
                directory -> 0x1C0 // 0700
                executable -> 0x1ED // 0755
                else -> 0x180 // 0600
            }
        }
    }

    private fun applyMode(file: File, mode: Int?) {
        if (mode == null || Files.isSymbolicLink(file.toPath())) return
        val permissions = mode and 0xFFF
        try {
            Files.setAttribute(file.toPath(), "unix:mode", permissions, *NOFOLLOW)
        } catch (_: Throwable) {
            file.setReadable((permissions and 0x124) != 0, false)
            file.setWritable((permissions and 0x092) != 0, false)
            file.setExecutable((permissions and 0x049) != 0, false)
        }
    }

    private fun moveIntoPlace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
    }

    private fun validateArchivePath(path: String) {
        require(path.isNotBlank() && '\u0000' !in path && '\\' !in path && !path.startsWith('/')) {
            "TRANSFER_ENTRY_PATH_INVALID"
        }
        val parts = path.split('/')
        require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "TRANSFER_ENTRY_PATH_INVALID" }
        require(path.length <= 4096) { "TRANSFER_ENTRY_PATH_TOO_LONG" }
    }

    private fun safeStagePath(stage: File, archivePath: String): File {
        validateArchivePath(archivePath)
        val root = stage.canonicalFile
        val target = File(root, archivePath).canonicalFile
        require(isDescendant(target, root)) { "TRANSFER_ENTRY_PATH_INVALID" }
        return target
    }

    private fun canonicalDirectory(file: File, error: String): File {
        require(file.exists() && file.isDirectory && !Files.isSymbolicLink(file.toPath())) { error }
        return file.canonicalFile
    }

    private fun requireRegularFile(file: File, error: String) {
        require(file.exists() && file.isFile && !Files.isSymbolicLink(file.toPath())) { error }
    }

    private fun isDescendant(candidate: File, root: File): Boolean {
        val rootPath = root.canonicalFile.toPath()
        val candidatePath = candidate.canonicalFile.toPath()
        return candidatePath.startsWith(rootPath) && candidatePath != rootPath
    }

    private fun sameOrDescendant(candidate: File, root: File): Boolean =
        candidate.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())

    private fun validateJson(json: String) {
        require(json.toByteArray(Charsets.UTF_8).size <= TransferMetadata.MAX_METADATA_BYTES) {
            "TRANSFER_METADATA_TOO_LARGE"
        }
        require('\u0000' !in json) { "TRANSFER_METADATA_JSON_INVALID" }
        try {
            val tokener = JSONTokener(json)
            val value = tokener.nextValue()
            require(value is JSONObject || value is JSONArray) { "TRANSFER_METADATA_JSON_INVALID" }
            require(tokener.nextClean() == '\u0000') { "TRANSFER_METADATA_JSON_INVALID" }
        } catch (error: org.json.JSONException) {
            throw IllegalArgumentException("TRANSFER_METADATA_JSON_INVALID", error)
        }
    }

    private fun validateSettings(text: String) {
        HarnessTransferConfiguration.references(text)
    }

    private fun parseManifest(bytes: ByteArray): TransferManifest {
        val json = try { JSONObject(bytes.toString(Charsets.UTF_8)) }
        catch (error: Throwable) { throw IllegalArgumentException("TRANSFER_MANIFEST_INVALID", error) }
        require(json.optString("format") == TransferManifest.FORMAT) { "TRANSFER_FORMAT_UNSUPPORTED" }
        require(json.optInt("formatVersion", -1) == TransferManifest.CURRENT_FORMAT_VERSION) {
            "TRANSFER_FORMAT_UNSUPPORTED"
        }
        val entriesJson = json.optJSONArray("entries") ?: throw IllegalArgumentException("TRANSFER_MANIFEST_INVALID")
        val entries = buildList(entriesJson.length()) {
            for (index in 0 until entriesJson.length()) {
                val entry = entriesJson.getJSONObject(index)
                add(
                    TransferEntry(
                        path = entry.getString("path").also(::validateArchivePath),
                        kind = entry.getString("kind"),
                        sizeBytes = entry.getLong("sizeBytes"),
                        sha256 = entry.getString("sha256").lowercase(),
                        directory = entry.optBoolean("directory", false),
                        mode = entry.optInt("mode", -1).takeIf { it >= 0 },
                        symlink = entry.optBoolean("symlink", false),
                        linkTarget = entry.optString("linkTarget", "").takeIf { it.isNotEmpty() },
                        hardlinkPath = entry.optString("hardlinkPath", "").takeIf { it.isNotEmpty() }
                            ?.also(::validateArchivePath),
                        sessionId = entry.optString("sessionId", "").takeIf { it.isNotEmpty() },
                        projectKey = entry.optString("projectKey", "").takeIf { it.isNotEmpty() },
                    )
                )
            }
        }
        val sessionsJson = json.optJSONArray("sessions") ?: JSONArray()
        val sessions = buildList(sessionsJson.length()) {
            for (index in 0 until sessionsJson.length()) {
                val session = sessionsJson.getJSONObject(index)
                add(
                    TransferSession(
                        id = session.getString("id"),
                        cwd = session.optString("cwd", "").takeIf { it.isNotEmpty() },
                        archivePath = session.getString("archivePath").also(::validateArchivePath),
                        projectKey = session.getString("projectKey"),
                    )
                )
            }
        }
        val metadataJson = json.optJSONArray("metadataNames") ?: JSONArray()
        val metadataNames = buildList(metadataJson.length()) {
            for (index in 0 until metadataJson.length()) add(metadataJson.getString(index))
        }
        return TransferManifest(
            format = json.getString("format"),
            formatVersion = json.getInt("formatVersion"),
            runtimeId = json.getString("runtimeId"),
            mode = try { TransferArchiveMode.valueOf(json.getString("mode")) }
            catch (error: Throwable) { throw IllegalArgumentException("TRANSFER_MODE_INVALID", error) },
            createdAtEpochMs = json.getLong("createdAtEpochMs"),
            encrypted = json.getBoolean("encrypted"),
            entries = entries,
            sessions = sessions,
            metadataNames = metadataNames,
            compatibilityVersion = json.optInt("compatibilityVersion", 1),
            runtimeVersion = json.optString("runtimeVersion", "").takeIf { it.isNotEmpty() },
            abi = json.optString("abi", "").takeIf { it.isNotEmpty() },
        )
    }

    private fun TransferManifest.toJson(): JSONObject = JSONObject().apply {
        put("format", format)
        put("formatVersion", formatVersion)
        put("runtimeId", runtimeId)
        put("mode", mode.name)
        put("createdAtEpochMs", createdAtEpochMs)
        put("encrypted", encrypted)
        put("compatibilityVersion", compatibilityVersion)
        runtimeVersion?.let { put("runtimeVersion", it) }
        abi?.let { put("abi", it) }
        put("entries", JSONArray().also { array -> entries.forEach { array.put(it.toJson()) } })
        put("sessions", JSONArray().also { array -> sessions.forEach { session ->
            array.put(JSONObject().apply {
                put("id", session.id)
                session.cwd?.let { put("cwd", it) }
                put("archivePath", session.archivePath)
                put("projectKey", session.projectKey)
            })
        } })
        put("metadataNames", JSONArray(metadataNames))
    }

    private fun TransferEntry.toJson(): JSONObject = JSONObject().apply {
        put("path", path)
        put("kind", kind)
        put("sizeBytes", sizeBytes)
        put("sha256", sha256)
        put("directory", directory)
        mode?.let { put("mode", it) }
        if (symlink) {
            put("symlink", true)
            put("linkTarget", linkTarget)
        }
        hardlinkPath?.let { put("hardlinkPath", it) }
        sessionId?.let { put("sessionId", it) }
        projectKey?.let { put("projectKey", it) }
    }

    private const val MAX_MANIFEST_BYTES = 32L * 1024L * 1024L
    private const val MAX_LINK_TARGET_BYTES = 16L * 1024L
    private const val MAX_CONFIGURATION_REFERENCE_BYTES = 4L * 1024L * 1024L
}
