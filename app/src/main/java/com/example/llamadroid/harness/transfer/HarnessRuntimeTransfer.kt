package com.example.llamadroid.harness.transfer

import java.io.File
import java.util.UUID

/** The two intentionally different transfer contracts exposed by the runtime manager. */
enum class TransferArchiveMode {
    /** User data, sessions, workspace files, and supplied runtime metadata. */
    PORTABLE,

    /** Portable data plus the installed rootfs snapshot supplied by [TransferSource]. */
    FULL,
}

/** Cooperative cancellation points used by archive scans, writes, and staging. */
enum class TransferWorkPhase {
    SCAN,
    WRITE,
    READ,
    VALIDATE,
    STAGE,
}

fun interface TransferCancellation {
    fun check(phase: TransferWorkPhase)

    companion object {
        val NONE = TransferCancellation { }
    }
}

/** JSON owned by the app rather than by the DSH filesystem. Secrets are opt-in. */
data class TransferMetadata(
    val name: String,
    val json: String,
    val sensitive: Boolean = false,
) {
    init {
        require(name.matches(METADATA_NAME)) { "TRANSFER_METADATA_NAME_INVALID" }
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES) {
            "TRANSFER_METADATA_TOO_LARGE"
        }
    }

    companion object {
        private val METADATA_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")
        const val MAX_METADATA_BYTES = 4 * 1024 * 1024
    }
}

/** Files and app-owned JSON supplied by the installation manager for one runtime. */
data class TransferSource(
    val runtimeId: String,
    val dshHome: File,
    val projects: File,
    /**
     * Retained for source compatibility with early callers. Runtime scratch/cache files are
     * deliberately excluded from FULL archives and this path is never traversed.
     */
    val runtimeRoot: File? = null,
    /** Optional installed Debian rootfs; included only by FULL transfers. */
    val rootfs: File? = null,
    /** App-owned selected attachment files keyed by `attachments/<safe-key>/<safe-basename>`. */
    val additionalFiles: Map<String, File> = emptyMap(),
    val metadata: List<TransferMetadata> = emptyList(),
    val compatibilityVersion: Int = 1,
    val runtimeVersion: String? = null,
    val abi: String? = null,
) {
    init {
        require(runtimeId.matches(RUNTIME_ID)) { "TRANSFER_RUNTIME_ID_INVALID" }
        require(compatibilityVersion > 0) { "TRANSFER_COMPATIBILITY_VERSION_INVALID" }
        require(runtimeVersion == null || runtimeVersion.length <= 128) { "TRANSFER_RUNTIME_VERSION_INVALID" }
        require(abi == null || abi.matches(ABI_PATTERN)) { "TRANSFER_ABI_INVALID" }
        additionalFiles.keys.forEach { path ->
            require(path.matches(ADDITIONAL_FILE_PATH)) { "TRANSFER_ATTACHMENT_PATH_INVALID" }
        }
        require(metadata.map { it.name }.distinct().size == metadata.size) {
            "TRANSFER_METADATA_DUPLICATE"
        }
    }

    companion object {
        private val RUNTIME_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")
        private val ABI_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
        private val ADDITIONAL_FILE_PATH = Regex(
            "attachments/[A-Za-z0-9][A-Za-z0-9_.-]{0,95}/[A-Za-z0-9][A-Za-z0-9_.-]{0,254}",
        )
    }
}

data class TransferExportOptions(
    val mode: TransferArchiveMode = TransferArchiveMode.PORTABLE,
    /** A non-null value opts all archive entries into WinZip AES-256 encryption. */
    val password: CharArray? = null,
    /** Null means all project folders; an empty set means no project files. */
    val projectFolders: Set<String>? = null,
    /** Null means all sessions; an empty set means no session logs. */
    val sessionIds: Set<String>? = null,
    /** Include app-owned configuration metadata supplied by [TransferSource]. */
    val includeConfiguration: Boolean = true,
    /** Include sensitive metadata (usually credentials) only when the archive is encrypted. */
    val includeCredentials: Boolean = false,
    /**
     * Compatibility alias for callers that classify all sensitive metadata themselves.
     * [includeCredentials] remains the preferred switch for the runtime transfer UI.
     */
    val includeSensitiveMetadata: Boolean = false,
    val compressionLevel: Int = 6,
    val cancellation: TransferCancellation = TransferCancellation.NONE,
) {
    init {
        require(compressionLevel in 0..9) { "TRANSFER_COMPRESSION_LEVEL_INVALID" }
        projectFolders?.forEach { folder ->
            require(folder.isNotBlank() && folder != "." && folder != ".." &&
                '/' !in folder && '\\' !in folder && '\u0000' !in folder) {
                "TRANSFER_PROJECT_SELECTION_INVALID"
            }
        }
        sessionIds?.forEach { id ->
            require(id.isNotBlank() && id.length <= 4096 && '\u0000' !in id) {
                "TRANSFER_SESSION_SELECTION_INVALID"
            }
        }
        password?.let {
            require(it.isNotEmpty() && it.size <= 4096) { "TRANSFER_PASSWORD_INVALID" }
        }
        require(!(includeCredentials || includeSensitiveMetadata) || password != null) {
            "TRANSFER_CREDENTIALS_REQUIRE_ENCRYPTION"
        }
    }
}

data class TransferStageOptions(
    val password: CharArray? = null,
    /** Parent under which a fresh, owned staging directory is created. */
    val stagingRoot: File? = null,
    /** Allows the installation manager to allocate IDs from its canonical database policy. */
    val sessionIdFactory: (oldId: String) -> String = { UUID.randomUUID().toString() },
    /** Rewrites a stored DSH cwd to the canonical destination workspace path when known. */
    val workspacePathMapper: (oldCwd: String) -> String? = { null },
    /**
     * Rewrites structural model/provider references during staging. The codec only invokes this
     * callback for known configuration keys; session text, prompts, tool arguments, and arbitrary
     * app metadata remain byte-for-byte unchanged.
     */
    val referenceMapper: (key: String, value: String) -> String? = { _, _ -> null },
    val cancellation: TransferCancellation = TransferCancellation.NONE,
) {
    init {
        password?.let {
            require(it.isNotEmpty() && it.size <= 4096) { "TRANSFER_PASSWORD_INVALID" }
        }
    }
}

/** One archive entry, with a digest that is checked again while writing or staging. */
data class TransferEntry(
    val path: String,
    val kind: String,
    val sizeBytes: Long,
    val sha256: String,
    val directory: Boolean = false,
    val mode: Int? = null,
    val symlink: Boolean = false,
    val linkTarget: String? = null,
    val hardlinkPath: String? = null,
    val sessionId: String? = null,
    val projectKey: String? = null,
) {
    init {
        require(path.isNotBlank() && path == path.replace('\\', '/')) { "TRANSFER_ENTRY_PATH_INVALID" }
        require(kind in ENTRY_KINDS) { "TRANSFER_ENTRY_KIND_INVALID" }
        require(sizeBytes >= 0L) { "TRANSFER_ENTRY_SIZE_INVALID" }
        require(!directory || sizeBytes == 0L) { "TRANSFER_DIRECTORY_SIZE_INVALID" }
        require(!symlink || !directory) { "TRANSFER_SYMLINK_DIRECTORY_INVALID" }
        require(!symlink || linkTarget != null) { "TRANSFER_SYMLINK_TARGET_MISSING" }
        require(!symlink || linkTarget!!.isNotEmpty()) { "TRANSFER_SYMLINK_TARGET_INVALID" }
        require(!symlink || '\u0000' !in linkTarget!!) { "TRANSFER_SYMLINK_TARGET_INVALID" }
        require(!symlink || linkTarget!!.length <= 4096) { "TRANSFER_SYMLINK_TARGET_TOO_LONG" }
        require(!symlink || sizeBytes == linkTarget!!.toByteArray(Charsets.UTF_8).size.toLong()) {
            "TRANSFER_SYMLINK_SIZE_INVALID"
        }
        require(symlink || linkTarget == null) { "TRANSFER_SYMLINK_TARGET_UNEXPECTED" }
        require(!symlink || hardlinkPath == null) { "TRANSFER_LINK_KIND_INVALID" }
        require(hardlinkPath == null || !directory) { "TRANSFER_HARDLINK_DIRECTORY_INVALID" }
        require(hardlinkPath == null || hardlinkPath != path) { "TRANSFER_HARDLINK_SELF_REFERENCE" }
        require(hardlinkPath == null || '\u0000' !in hardlinkPath) { "TRANSFER_HARDLINK_PATH_INVALID" }
        require(mode == null || mode in 0..0xFFFF) { "TRANSFER_MODE_INVALID" }
        require(sha256.matches(SHA256)) { "TRANSFER_ENTRY_DIGEST_INVALID" }
    }

    companion object {
        internal val ENTRY_KINDS = setOf("file", "session", "workspace", "runtime", "rootfs", "metadata", "attachment")
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class TransferSession(
    val id: String,
    val cwd: String? = null,
    val archivePath: String,
    val projectKey: String,
) {
    init {
        require(id.isNotEmpty() && id.length <= 4096 && '\u0000' !in id) { "TRANSFER_SESSION_ID_INVALID" }
        require(archivePath.isNotBlank()) { "TRANSFER_SESSION_PATH_INVALID" }
        require(projectKey.isNotBlank()) { "TRANSFER_PROJECT_KEY_INVALID" }
    }
}

data class TransferManifest(
    val format: String,
    val formatVersion: Int,
    val runtimeId: String,
    val mode: TransferArchiveMode,
    val createdAtEpochMs: Long,
    val encrypted: Boolean,
    val entries: List<TransferEntry>,
    val sessions: List<TransferSession>,
    val metadataNames: List<String>,
    val compatibilityVersion: Int = 1,
    val runtimeVersion: String? = null,
    val abi: String? = null,
) {
    init {
        require(format == FORMAT) { "TRANSFER_FORMAT_UNSUPPORTED" }
        require(formatVersion == CURRENT_FORMAT_VERSION) { "TRANSFER_FORMAT_UNSUPPORTED" }
        require(runtimeId.matches(RUNTIME_ID)) { "TRANSFER_RUNTIME_ID_INVALID" }
        require(createdAtEpochMs >= 0L) { "TRANSFER_TIMESTAMP_INVALID" }
        require(compatibilityVersion > 0) { "TRANSFER_COMPATIBILITY_VERSION_INVALID" }
        require(runtimeVersion == null || runtimeVersion.length <= 128) { "TRANSFER_RUNTIME_VERSION_INVALID" }
        require(abi == null || abi.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) {
            "TRANSFER_ABI_INVALID"
        }
        require(entries.map { it.path }.distinct().size == entries.size) {
            "TRANSFER_ENTRY_DUPLICATE"
        }
        require(sessions.map { it.id }.distinct().size == sessions.size) {
            "TRANSFER_SESSION_DUPLICATE"
        }
        require(metadataNames.distinct().size == metadataNames.size) {
            "TRANSFER_METADATA_DUPLICATE"
        }
        require(metadataNames.all { it.matches(METADATA_NAME) }) { "TRANSFER_METADATA_NAME_INVALID" }
    }

    companion object {
        const val FORMAT = "adt-harness-runtime-transfer"
        const val CURRENT_FORMAT_VERSION = 1
        const val MANIFEST_PATH = "transfer/manifest.json"
        private val METADATA_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")
        private val RUNTIME_ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")
    }
}

data class TransferInspection(
    val manifest: TransferManifest,
    val entries: List<TransferEntry>,
    /** Validated app-owned JSON metadata, keyed by its manifest name. */
    val metadata: Map<String, String> = emptyMap(),
    /** Union of session, settings, and other validated configuration references. */
    val references: Map<String, Set<String>> = emptyMap(),
    /** References used by session streams only; destination imports can map these without importing settings. */
    val sessionReferences: Map<String, Set<String>> = emptyMap(),
)

data class TransferMapping(
    val sessionIds: Map<String, String>,
    val workspacePaths: Map<String, String>,
    val archivePaths: Map<String, String>,
)

/** Fully validated content prepared for the installation manager's activation transaction. */
data class TransferStagedContent(
    val stagingDirectory: File,
    val dshHome: File,
    val projects: File,
    val runtimeRoot: File?,
    val rootfs: File?,
    val manifest: TransferManifest,
    val mapping: TransferMapping,
    val entries: List<TransferEntry>,
    val attachments: File? = null,
)

/** Public archive seam. Activation is deliberately owned by HarnessInstallationManager. */
object HarnessTransferArchive {
    fun export(destination: File, source: TransferSource, options: TransferExportOptions = TransferExportOptions()): TransferManifest =
        HarnessTransferCodec.export(destination, source, options)

    fun inspect(
        archive: File,
        password: CharArray? = null,
        cancellation: TransferCancellation = TransferCancellation.NONE,
    ): TransferInspection = HarnessTransferCodec.inspect(archive, password, cancellation)

    /** Reads and authenticates the app-owned JSON metadata before any staging occurs. */
    fun readMetadata(
        archive: File,
        password: CharArray? = null,
        cancellation: TransferCancellation = TransferCancellation.NONE,
    ): Map<String, String> = inspect(archive, password, cancellation).metadata

    fun stage(
        archive: File,
        password: CharArray? = null,
        stagingRoot: File? = null,
    ): TransferStagedContent = stage(
        archive,
        TransferStageOptions(password = password, stagingRoot = stagingRoot),
    )

    fun stage(archive: File, options: TransferStageOptions): TransferStagedContent =
        HarnessTransferCodec.stage(archive, options)
}
