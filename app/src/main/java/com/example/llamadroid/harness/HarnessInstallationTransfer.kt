package com.example.llamadroid.harness

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import com.example.llamadroid.data.proot.AgentProotEnvironmentPaths
import com.example.llamadroid.harness.runtime.HarnessRuntimePaths
import com.example.llamadroid.harness.runtime.HarnessRuntimeScope
import com.example.llamadroid.harness.transfer.*
import com.example.llamadroid.ui.agent.harness.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Owns SAF copies independently of the screen that opened the picker. */
internal class HarnessInstallationTransfer private constructor(private val context: Context) {
    private val manager = HarnessInstallationManager.get(context)
    private val database = manager.database
    private val inspectionLock = Mutex()
    private val mutableUi = MutableStateFlow<HarnessRuntimeTransferUiState?>(null)
    val ui = mutableUi.asStateFlow()
    private var inspected: Inspected? = null
    private var inspectionJob: Job? = null
    private var openSequence = 0L
    private val transferDirectory = File(context.filesDir, "agent_harness/transfers").apply { mkdirs() }
    init {
        // These are only private SAF copies; committed recovery uses the separate staged plan.
        transferDirectory.listFiles().orEmpty().filter {
            it.name.matches(Regex("(source|export)-[a-f0-9-]{36}\\.zip"))
        }.forEach(HarnessInstallationFiles::deleteTree)
    }
    private data class Inspected(val uri: Uri, val file: File, val inspection: TransferInspection)

    fun begin(direction: HarnessRuntimeTransferDirection, runtimeId: String? = null, selection: HarnessRuntimeTransferSelectionUi? = null) {
        if (mutableUi.value?.phase != HarnessRuntimeTransferPhase.RUNNING) {
            mutableUi.value = HarnessRuntimeTransferUiState(direction, runtimeId = runtimeId,
                openRequestToken = ++openSequence, selection = selection)
        }
    }

    fun consumeOpenRequest(token: Long) {
        mutableUi.value?.takeIf { it.openRequestToken == token }?.let {
            mutableUi.value = it.copy(openRequestToken = 0L)
        }
    }

    fun cancel() { inspectionJob?.cancel(); manager.cancel() }

    suspend fun inspect(uri: Uri, password: String?, destinationRuntimeId: String? = null) {
        val work = manager.scope.async {
            inspectionLock.withLock {
                mutableUi.value = HarnessRuntimeTransferUiState(HarnessRuntimeTransferDirection.IMPORT, HarnessRuntimeTransferPhase.RUNNING)
                val chars = password?.takeIf { it.isNotEmpty() }?.toCharArray()
                var copied: File? = null
                try {
                    val cached = inspected?.takeIf { it.uri == uri && it.file.isFile }
                    val source = cached?.file ?: copySource(uri)
                    copied = source
                    val operationContext = currentCoroutineContext()
                    val inspection = cached?.inspection ?: HarnessTransferArchive.inspect(source, chars,
                        TransferCancellation { operationContext.ensureActive() })
                    validateCompatibility(inspection)
                    val metadata = HarnessTransferMetadata.decode(requireNotNull(inspection.metadata["room"]) { "HARNESS_TRANSFER_METADATA_MISSING" })
                    val runtime = JSONObject(requireNotNull(inspection.metadata["runtime"]))
                    val mappings = HarnessTransferMappings.review(context, inspection, destinationRuntimeId)
                    inspected?.file?.takeIf { it != copied }?.let(HarnessInstallationFiles::deleteTree)
                    inspected = Inspected(uri, source, inspection)
                    copied = null
                    mutableUi.value = HarnessRuntimeTransferUiState(HarnessRuntimeTransferDirection.IMPORT,
                        importPreview = HarnessRuntimeImportPreviewUi(
                            archiveName = "${runtime.getString("name")}.zip",
                            runtimeName = runtime.getString("name"),
                            format = if (inspection.manifest.mode == TransferArchiveMode.FULL) HarnessRuntimeTransferFormat.FULL_SNAPSHOT else HarnessRuntimeTransferFormat.PORTABLE,
                            scope = if (runtime.optBoolean("configurationOnly")) HarnessRuntimeTransferScope.CONFIG_ONLY else HarnessRuntimeTransferScope.SELECTIONS_AND_CONFIG,
                            projectCount = metadata.workspaces.size,
                            sessionCount = inspection.manifest.sessions.size + metadata.conversations.count { it.runtimeSource == "LEGACY_ARCHIVE" },
                            includesConfiguration = metadata.configurationIncluded,
                            includesCredentials = "credentials" in inspection.metadata,
                            requiresPassword = inspection.manifest.encrypted,
                            missingMappings = mappings.choices.map { it.label },
                            mappingChoices = mappings.choices,
                        ))
                } catch (cancelled: CancellationException) {
                    mutableUi.value = HarnessRuntimeTransferUiState(HarnessRuntimeTransferDirection.IMPORT)
                    throw cancelled
                } catch (failure: Exception) {
                    mutableUi.value = failed(HarnessRuntimeTransferDirection.IMPORT, failure)
                } finally {
                    chars?.fill('\u0000')
                    copied?.let(HarnessInstallationFiles::deleteTree)
                }
            }
        }
        inspectionJob = work
        try { work.await() } finally { if (inspectionJob === work) inspectionJob = null }
    }

    suspend fun export(request: HarnessRuntimeExportRequest) {
        mutableUi.value = HarnessRuntimeTransferUiState(HarnessRuntimeTransferDirection.EXPORT, HarnessRuntimeTransferPhase.RUNNING)
        try {
            manager.execute("EXPORT", request.runtimeId) {
                val scoped = HarnessRuntimeScope.context(context, request.runtimeId)
                val row = requireNotNull(database.harnessInstallationDao().getById(request.runtimeId))
                require(!request.includeCredentials || request.protectArchive) { "TRANSFER_CREDENTIALS_REQUIRE_PASSWORD" }
                val full = request.format == HarnessRuntimeTransferFormat.FULL_SNAPSHOT
                val configOnly = request.scope == HarnessRuntimeTransferScope.CONFIG_ONLY
                require(!full || request.includeConfiguration) { "HARNESS_TRANSFER_CONFIGURATION_REQUIRED" }
                require(!configOnly || request.includeConfiguration) { "HARNESS_TRANSFER_EMPTY_SELECTION" }
                val dao = database.harnessDao(request.runtimeId)
                val workspaces = dao.workspaces()
                val allProjects = request.selectedProjectIds.containsAll(workspaces.map { it.id })
                val allSessions = request.selectedSessionIds.containsAll(dao.observeSessions().first().map { it.harnessSessionId } +
                    dao.conversations().filter { it.runtimeSource == "LEGACY_ARCHIVE" }.map { "legacy:${it.id}" })
                val selectedRoots = workspaces.filter { it.id in request.selectedProjectIds }.map { it.guestPath }.toSet()
                val selectedWorkspaces = if (full || (!configOnly && allProjects)) null else workspaces.filter { it.guestPath in selectedRoots }.map { it.id }.toSet()
                val selectedSessions = if (full || (!configOnly && allSessions)) null else if (configOnly) emptySet() else request.selectedSessionIds
                val document = HarnessTransferMetadata.snapshot(scoped, request.runtimeId,
                    if (configOnly) emptySet() else selectedWorkspaces, selectedSessions, request.includeConfiguration,
                    selectedSessions?.filter { it.startsWith("legacy:") }?.map { it.removePrefix("legacy:").toLong() }?.toSet())
                val metadata = mutableListOf(
                    TransferMetadata("room", document.toJson()),
                    TransferMetadata("catalog", HarnessTransferMappings.catalog(scoped).toString()),
                    TransferMetadata("runtime", JSONObject().put("version", 1).put("name", row.displayName)
                        .put("configurationOnly", configOnly).toString()),
                )
                if (request.includeConfiguration) metadata += TransferMetadata("settings", HarnessTransferSettings.snapshot(scoped, database).toString())
                if (request.includeCredentials) metadata += TransferMetadata("credentials",
                    HarnessCredentialStore(scoped).exportTransferRecords(request.includeConfiguration,
                        document.workspaces.map { it.id }.toSet()).toString(), sensitive = true)
                val operationContext = currentCoroutineContext()
                val cancellation = TransferCancellation { phase ->
                    operationContext.ensureActive()
                    manager.progress(phase.name)
                }
                val chars = request.password?.takeIf { request.protectArchive && it.isNotEmpty() }?.toCharArray()
                require(!request.protectArchive || chars != null) { "TRANSFER_PASSWORD_REQUIRED" }
                val archive = File(transferDirectory, "export-${UUID.randomUUID()}.zip")
                try {
                    val rootfs = AgentProotEnvironmentPaths.rootfs(scoped, request.runtimeId)
                    val manifest = HarnessTransferArchive.export(archive, TransferSource(
                        runtimeId = request.runtimeId,
                        dshHome = HarnessRuntimePaths.harnessHome(scoped).apply { mkdirs() },
                        projects = HarnessRuntimePaths.projects(scoped).apply { mkdirs() },
                        rootfs = rootfs.takeIf { full }, metadata = metadata,
                        additionalFiles = document.attachments.associate { attachment ->
                            val file = File(attachment.sourcePath).canonicalFile
                            require(listOfNotNull(context.filesDir, context.cacheDir, context.getExternalFilesDir(null))
                                .any { file.toPath().startsWith(it.canonicalFile.toPath()) }) { "HARNESS_TRANSFER_ATTACHMENT_PATH_INVALID" }
                            attachment.archivePath to file
                        },
                        runtimeVersion = PAYLOAD_VERSION, abi = ABI,
                    ), TransferExportOptions(
                        mode = if (full) TransferArchiveMode.FULL else TransferArchiveMode.PORTABLE,
                        password = chars,
                        projectFolders = if (full || (!configOnly && allProjects)) null else document.workspaces.filter { it.backend != "REMOTE_SSH" && it.id in selectedWorkspaces.orEmpty() }.map { it.projectFolder }.toSet(),
                        sessionIds = selectedSessions?.filterNot { it.startsWith("legacy:") }?.toSet(),
                        includeConfiguration = request.includeConfiguration,
                        includeSensitiveMetadata = request.includeCredentials,
                        cancellation = cancellation,
                    ))
                    manager.progress("VALIDATE")
                    HarnessTransferArchive.inspect(archive, chars, cancellation)
                    require(request.destinationUri.scheme == "content") { "HARNESS_TRANSFER_URI_INVALID" }
                    context.contentResolver.openOutputStream(request.destinationUri, "wt").use { output ->
                        requireNotNull(output) { "HARNESS_TRANSFER_DESTINATION_UNAVAILABLE" }
                        archive.inputStream().use { input ->
                            val bytes = ByteArray(256 * 1024)
                            var completed = 0L
                            while (true) {
                                operationContext.ensureActive()
                                val count = input.read(bytes)
                                if (count < 0) break
                                output.write(bytes, 0, count); completed += count
                                manager.progress("WRITE", completed, archive.length())
                            }
                            output.flush()
                        }
                    }
                    check(manifest.runtimeId == request.runtimeId)
                } finally { chars?.fill('\u0000'); HarnessInstallationFiles.deleteTree(archive) }
            }
            mutableUi.value = HarnessRuntimeTransferUiState(HarnessRuntimeTransferDirection.EXPORT, HarnessRuntimeTransferPhase.COMPLETED)
        } catch (cancelled: CancellationException) {
            mutableUi.value = HarnessRuntimeTransferUiState(HarnessRuntimeTransferDirection.EXPORT)
            throw cancelled
        } catch (failure: Exception) { mutableUi.value = failed(HarnessRuntimeTransferDirection.EXPORT, failure) }
    }

    suspend fun importArchive(request: HarnessRuntimeImportRequest) {
        mutableUi.value = HarnessRuntimeTransferUiState(HarnessRuntimeTransferDirection.IMPORT, HarnessRuntimeTransferPhase.RUNNING)
        try {
            manager.execute("IMPORT", request.destinationRuntimeId) {
                inspectionLock.withLock {
                    val chars = request.password?.takeIf { it.isNotEmpty() }?.toCharArray()
                    val source = inspected?.takeIf { it.uri == request.sourceUri }?.file ?: copySource(request.sourceUri)
                    try {
                        // Re-authenticate the private copy; a preview never authorizes unchecked content.
                        val operationContext = currentCoroutineContext()
                        val inspection = HarnessTransferArchive.inspect(source, chars,
                            TransferCancellation { phase -> operationContext.ensureActive(); manager.progress(phase.name) })
                        validateCompatibility(inspection)
                        val mappings = HarnessTransferMappings.resolve(context, inspection,
                            request.destinationRuntimeId.takeIf { request.destination == HarnessRuntimeImportDestination.COPY_STOPPED_RUNTIME },
                            request.mappingOverrides)
                        HarnessTransferPublication(context, manager).prepareAndPublish(source, inspection, request.copy(mappingOverrides = mappings), chars)
                    } finally {
                        chars?.fill('\u0000')
                        HarnessInstallationFiles.deleteTree(source)
                        inspected = null
                    }
                }
            }
            mutableUi.value = HarnessRuntimeTransferUiState(HarnessRuntimeTransferDirection.IMPORT, HarnessRuntimeTransferPhase.COMPLETED)
        } catch (cancelled: CancellationException) {
            mutableUi.value = HarnessRuntimeTransferUiState(HarnessRuntimeTransferDirection.IMPORT)
            throw cancelled
        } catch (failure: Exception) { mutableUi.value = failed(HarnessRuntimeTransferDirection.IMPORT, failure) }
    }

    internal suspend fun recover(receipt: HarnessInstallationReceipt) = HarnessTransferPublication(context, manager).recover(receipt)

    // Require space already free without relying on cache eviction during an active transfer.
    @SuppressLint("UsableSpace")
    private suspend fun copySource(uri: Uri): File = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "HARNESS_TRANSFER_URI_INVALID" }
        val target = File(transferDirectory, "source-${UUID.randomUUID()}.zip")
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "HARNESS_TRANSFER_SOURCE_UNAVAILABLE" }
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= 256L * 1024 * 1024 * 1024) { "TRANSFER_ARCHIVE_TOO_LARGE" }
                        require(transferDirectory.usableSpace >= count + 16L * 1024 * 1024) { "HARNESS_TRANSFER_STORAGE_REQUIRED" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            target
        } catch (failure: Throwable) { HarnessInstallationFiles.deleteTree(target); throw failure }
    }

    private fun validateCompatibility(inspection: TransferInspection) {
        val runtime = JSONObject(requireNotNull(inspection.metadata["runtime"]) { "HARNESS_TRANSFER_METADATA_MISSING" })
        require(runtime.getInt("version") == 1) { "HARNESS_TRANSFER_METADATA_UNSUPPORTED" }
        val name = runtime.getString("name")
        require(name == HarnessInstallationManager.normalizeName(name)) { "HARNESS_RUNTIME_NAME_INVALID" }
        require(inspection.manifest.compatibilityVersion == 1) { "HARNESS_TRANSFER_VERSION_UNSUPPORTED" }
        if (inspection.manifest.mode == TransferArchiveMode.FULL) {
            require(inspection.manifest.abi == ABI && inspection.manifest.runtimeVersion == PAYLOAD_VERSION) { "HARNESS_TRANSFER_RUNTIME_INCOMPATIBLE" }
            require(inspection.entries.any { it.kind == "rootfs" }) { "HARNESS_TRANSFER_ROOTFS_MISSING" }
        }
        require("credentials" !in inspection.metadata || inspection.manifest.encrypted) { "TRANSFER_CREDENTIALS_REQUIRE_PASSWORD" }
    }

    private fun failed(direction: HarnessRuntimeTransferDirection, failure: Exception) = HarnessRuntimeTransferUiState(
        direction, HarnessRuntimeTransferPhase.FAILED,
        errorCode = failure.message?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{2,96}")) } ?: "HARNESS_TRANSFER_FAILED",
        canRetry = false,
    )

    companion object {
        private const val ABI = "arm64-v8a"
        private const val PAYLOAD_VERSION = "0.1.6-alpha.2"
        @SuppressLint("StaticFieldLeak") @Volatile private var instance: HarnessInstallationTransfer? = null
        fun get(context: Context): HarnessInstallationTransfer = instance ?: synchronized(this) {
            instance ?: HarnessInstallationTransfer(HarnessRuntimeScope.host(context)).also { instance = it }
        }
    }
}
