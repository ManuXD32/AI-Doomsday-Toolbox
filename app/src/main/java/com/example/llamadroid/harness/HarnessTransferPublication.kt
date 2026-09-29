package com.example.llamadroid.harness

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.example.llamadroid.data.proot.AgentProotEnvironmentPaths
import com.example.llamadroid.harness.runtime.HarnessRuntimePaths
import com.example.llamadroid.harness.runtime.HarnessRuntimeScope
import com.example.llamadroid.harness.transfer.*
import com.example.llamadroid.ui.agent.harness.HarnessRuntimeImportDestination
import com.example.llamadroid.ui.agent.harness.HarnessRuntimeImportRequest
import com.google.gson.Gson
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stages all data before an idempotent filesystem/Room commit. */
internal class HarnessTransferPublication(private val context: Context, private val manager: HarnessInstallationManager) {
    private val files = HarnessInstallationFiles(context)
    private val gson = Gson()

    data class ProjectMove(val source: String, val destination: String)
    data class Plan(
        val version: Int = 1,
        val operationId: String,
        val runtimeId: String,
        val newRuntime: Boolean,
        val installRootfs: Boolean,
        val home: String,
        val projects: String,
        val projectMoves: List<ProjectMove>,
        val metadata: String,
        val sessionIds: Map<String, String>,
        val cwdMap: Map<String, String>,
        val workspaceIds: Map<String, String>,
        val harnessWorkspaceIds: Map<String, String>,
        val modelMappings: Map<String, String>,
        val settings: String?,
        val credentials: String?,
        val attachments: String?,
        val attachmentPaths: Map<String, String>,
    )

    // Staging needs real free capacity while source/rollback data remain intact, without cache eviction.
    @android.annotation.SuppressLint("UsableSpace")
    suspend fun prepareAndPublish(archive: File, inspection: TransferInspection, request: HarnessRuntimeImportRequest,
                                  password: CharArray?): String {
        val newRuntime = request.destination == HarnessRuntimeImportDestination.NEW_RUNTIME
        val runtimeMetadata = JSONObject(requireNotNull(inspection.metadata["runtime"]))
        require(newRuntime || (inspection.manifest.mode == TransferArchiveMode.PORTABLE && !runtimeMetadata.optBoolean("configurationOnly"))) {
            "HARNESS_TRANSFER_NEW_RUNTIME_REQUIRED"
        }
        val targetId = if (newRuntime) manager.newInstallation(request.runtimeName?.takeIf { it.isNotBlank() }
            ?: runtimeMetadata.getString("name")).id else requireNotNull(request.destinationRuntimeId)
        val row = requireNotNull(manager.database.harnessInstallationDao().getById(targetId)) { "HARNESS_RUNTIME_REQUIRED" }
        require(newRuntime || row.status !in setOf("INSTALLING", "UPDATING", "DELETING", "BROKEN")) { "HARNESS_RUNTIME_UNAVAILABLE" }
        require(files.journal.pending().none { it.runtimeId == targetId }) { "HARNESS_OPERATION_INTERRUPTED" }
        val receipt = files.journal.new(targetId, "IMPORT")
        val stage = stageRoot(receipt).apply { mkdirs() }
        write(File(stage, "intent.json"), JSONObject().put("newRuntime", newRuntime).toString())
        if (!newRuntime) manager.database.harnessInstallationDao().update(row.copy(status = "UPDATING"))
        val requiredBytes = inspection.entries.fold(0L) { total, entry -> Math.addExact(total, entry.sizeBytes) }
        val scoped = HarnessRuntimeScope.context(context, targetId)
        val targetHome = HarnessRuntimePaths.harnessHome(scoped)
        val extraHome = if (newRuntime) 0L else treeSize(targetHome)
        val installRootfs = newRuntime || !AgentProotEnvironmentPaths.rootfs(context, targetId).exists()
        val stockAllowance = if (installRootfs && inspection.manifest.mode == TransferArchiveMode.PORTABLE) 2L * 1024 * 1024 * 1024 else 0L
        require(stage.usableSpace > requiredBytes + extraHome + stockAllowance + 32L * 1024 * 1024) { "HARNESS_TRANSFER_STORAGE_REQUIRED" }
        val document = HarnessTransferMetadata.decode(requireNotNull(inspection.metadata["room"]))
            .copy(transferId = receipt.id)
        val mappings = HarnessTransferMetadata.prepareMappings(context, targetId, document, receipt.id)
        val cwdMap = linkedMapOf<String, String>()
        val usedFolders = HarnessRuntimePaths.projects(scoped).list()?.toMutableSet() ?: mutableSetOf()
        // Canonical Harness data can be newer than Room's projection. Allocate names before
        // rewriting session headers so these projects also point to the imported copies.
        val projectFolders = inspection.entries.mapNotNull { entry ->
            entry.path.takeIf { it.startsWith("projects/") }?.removePrefix("projects/")?.substringBefore('/')
        } + inspection.manifest.sessions.mapNotNull { session ->
            session.cwd?.takeIf { it.startsWith("/workspace/projects/") }
                ?.removePrefix("/workspace/projects/")?.substringBefore('/')
        }
        projectFolders.filter { it.isNotEmpty() }.distinct().forEach { folder ->
            require(folder !in setOf(".", "..") && '\\' !in folder) { "HARNESS_TRANSFER_PROJECT_INVALID" }
            cwdMap["/workspace/projects/$folder"] = "/workspace/projects/${if (newRuntime) folder else uniqueFolder(folder, usedFolders, receipt.id)}"
        }
        document.workspaces.forEach { workspace ->
            val destination = if (workspace.backend == "REMOTE_SSH") {
                "/workspace/remote/${mappings.workspaceIdMap.getValue(workspace.id)}"
            } else if (!newRuntime) {
                cwdMap[workspace.guestPath] ?: "/workspace/projects/${uniqueFolder(workspace.projectFolder, usedFolders, receipt.id)}"
            } else cwdMap[workspace.guestPath] ?: workspace.guestPath
            cwdMap[workspace.guestPath] = destination
        }
        val operationContext = currentCoroutineContext()
        val cancellation = TransferCancellation { phase -> operationContext.ensureActive(); manager.progress(phase.name) }
        val staged = HarnessTransferArchive.stage(archive, TransferStageOptions(
            password = password, stagingRoot = stage,
            workspacePathMapper = { remapPath(it, cwdMap) },
            referenceMapper = { key, value -> request.mappingOverrides["$key:$value"] ?: request.mappingOverrides[value] },
            cancellation = cancellation,
        ))
        if (!newRuntime) HarnessTransferProjectLinks.prepare(staged.projects) { folder ->
            // Even an unselected link target gets a fresh name; it must not resolve into
            // an unrelated destination project that happens to have the source name.
            cwdMap.getOrPut("/workspace/projects/$folder") {
                "/workspace/projects/${uniqueFolder(folder, usedFolders, receipt.id)}"
            }.removePrefix("/workspace/projects/")
        }
        HarnessTransferDomains.prepare(staged.dshHome, staged.mapping.sessionIds, cwdMap,
            mappings.harnessWorkspaceIdMap) { key, value -> request.mappingOverrides["$key:$value"] ?: request.mappingOverrides[value] }
        val mergedHome = if (newRuntime) staged.dshHome else File(stage, "merged-home").also { merged ->
            copyTree(targetHome, merged)
            HarnessTransferDomains.merge(staged.dshHome, merged)
        }
        val moves = staged.projects.listFiles().orEmpty().map { source ->
            require(source.isDirectory && !Files.isSymbolicLink(source.toPath())) { "HARNESS_TRANSFER_PROJECT_INVALID" }
            val originalCwd = "/workspace/projects/${source.name}"
            val destination = cwdMap[originalCwd]?.substringAfterLast('/') ?: if (newRuntime) source.name else uniqueFolder(source.name, usedFolders, receipt.id)
            require(!File(HarnessRuntimePaths.projects(scoped), destination).exists()) { "HARNESS_TRANSFER_PROJECT_COLLISION" }
            ProjectMove(source.name, destination)
        }
        // A sessions-only selection still needs a writable working directory.
        document.workspaces.filter { it.backend != "REMOTE_SSH" }.forEach { workspace ->
            val folder = (cwdMap[workspace.guestPath] ?: workspace.guestPath).takeIf { it.startsWith("/workspace/projects/") }
                ?.removePrefix("/workspace/projects/") ?: return@forEach
            require('/' !in folder && folder !in setOf("", ".", "..")) { "HARNESS_TRANSFER_PROJECT_INVALID" }
        }
        var prepared = receipt
        if (installRootfs) {
            prepared = if (inspection.manifest.mode == TransferArchiveMode.FULL) {
                val rootfs = requireNotNull(staged.rootfs) { "HARNESS_TRANSFER_ROOTFS_MISSING" }
                val marker = File(rootfs, ".adt-environment.json")
                require(marker.isFile && marker.length() <= 65536) { "HARNESS_TRANSFER_ROOTFS_INVALID" }
                write(marker, JSONObject(marker.readText()).put("environmentId", targetId).toString())
                HarnessInstallationFiles.move(rootfs, AgentProotEnvironmentPaths.rootfs(context, receipt.stageId))
                receipt.copy(phase = "PREPARED")
            } else files.prepareRootfs(receipt) { manager.progress(it) }
        }
        val plan = Plan(operationId = receipt.id, runtimeId = targetId, newRuntime = newRuntime,
            installRootfs = installRootfs,
            home = mergedHome.relativeTo(stage).invariantSeparatorsPath,
            projects = staged.projects.relativeTo(stage).invariantSeparatorsPath, projectMoves = moves,
            metadata = document.toJson(), sessionIds = staged.mapping.sessionIds,
            cwdMap = cwdMap, workspaceIds = mappings.workspaceIdMap, harnessWorkspaceIds = mappings.harnessWorkspaceIdMap,
            modelMappings = request.mappingOverrides,
            settings = inspection.metadata["settings"].takeIf { newRuntime && document.configurationIncluded },
            credentials = inspection.metadata["credentials"]?.takeIf { newRuntime }?.let(::seal),
            attachments = File(staged.stagingDirectory, "attachments").takeIf { it.isDirectory }
                ?.relativeTo(stage)?.invariantSeparatorsPath,
            attachmentPaths = document.attachments.associate { attachment ->
                require(attachment.archivePath.startsWith("attachments/")) { "HARNESS_TRANSFER_ATTACHMENT_PATH_INVALID" }
                val relative = attachment.archivePath.removePrefix("attachments/")
                val source = child(File(staged.stagingDirectory, "attachments"), relative)
                require(source.isFile && source.length() == attachment.sizeBytes) { "HARNESS_TRANSFER_ATTACHMENT_MISSING" }
                val destination = HarnessRuntimeScope.dataFile(scoped, "agent_harness/legacy-attachments/${receipt.id}")
                attachment.key to child(destination, relative).absolutePath
            },
        )
        write(File(stage, "plan.json"), gson.toJson(plan))
        // Sensitive source metadata is not needed after sealing the recovery plan.
        HarnessInstallationFiles.deleteTree(File(staged.stagingDirectory, "metadata"))
        prepared = prepared.copy(phase = "PREPARED").also(files.journal::write)
        currentCoroutineContext().ensureActive()
        publish(prepared, plan)
        return targetId
    }

    suspend fun recover(receipt: HarnessInstallationReceipt) {
        if (receipt.phase == "COMMITTED") { files.finish(receipt); return }
        val stage = stageRoot(receipt)
        val planFile = File(stage, "plan.json")
        if (!planFile.isFile) {
            require(receipt.phase in setOf("PREPARING", "PREPARED")) { "HARNESS_TRANSFER_RECOVERY_INVALID" }
            val intentFile = File(stage, "intent.json")
            // A crash immediately after the receipt is durable may precede intent.json.
            // No target files have changed at that point; keep the catalog row available
            // for explicit recreation instead of guessing whether an existing row is owned.
            if (intentFile.isFile && JSONObject(intentFile.readText()).getBoolean("newRuntime")) {
                manager.database.harnessInstallationDao().deleteRuntimeRecords(receipt.runtimeId)
                if (manager.selectedId.value == receipt.runtimeId) manager.persistSelection(null)
            } else if (!intentFile.isFile) {
                val dao = manager.database.harnessInstallationDao()
                dao.getById(receipt.runtimeId)?.takeIf { it.status == "INSTALLING" }?.let {
                    dao.update(it.copy(status = "BROKEN"))
                }
            }
            val existingRuntime = intentFile.isFile && !JSONObject(intentFile.readText()).getBoolean("newRuntime")
            files.finish(receipt)
            if (existingRuntime) {
                manager.markReady(receipt.runtimeId, select = false)
            }
            return
        }
        require(planFile.length() <= 32L * 1024 * 1024) { "HARNESS_TRANSFER_RECOVERY_INVALID" }
        val plan = gson.fromJson(AtomicFile(planFile).openRead().use { it.reader().readText() }, Plan::class.java)
        require(plan.version == 1 && plan.operationId == receipt.id && plan.runtimeId == receipt.runtimeId) { "HARNESS_TRANSFER_RECOVERY_INVALID" }
        publish(receipt, plan)
    }

    private suspend fun publish(receipt: HarnessInstallationReceipt, plan: Plan) = withContext(NonCancellable) {
        manager.progress("ACTIVATING", cancellable = false)
        val stage = stageRoot(receipt)
        val scoped = HarnessRuntimeScope.context(context, receipt.runtimeId)
        var current = receipt.copy(phase = "ACTIVATING").also(files.journal::write)
        if (plan.installRootfs) current = files.activateRootfs(current)
        val home = child(stage, plan.home)
        val targetHome = HarnessRuntimePaths.harnessHome(scoped)
        val previous = File(stage, "previous-home")
        if (home.exists()) {
            if (targetHome.exists() && !previous.exists()) HarnessInstallationFiles.move(targetHome, previous)
            require(!targetHome.exists()) { "HARNESS_TRANSFER_HOME_COLLISION" }
            HarnessInstallationFiles.move(home, targetHome)
        }
        require(targetHome.isDirectory) { "HARNESS_TRANSFER_HOME_MISSING" }
        val projects = child(stage, plan.projects)
        val targetProjects = HarnessRuntimePaths.projects(scoped).apply { mkdirs() }
        plan.projectMoves.forEach { move ->
            val source = child(projects, move.source)
            val target = child(targetProjects, move.destination)
            if (source.exists()) {
                require(!target.exists()) { "HARNESS_TRANSFER_PROJECT_COLLISION" }
                HarnessInstallationFiles.move(source, target)
            }
            require(target.isDirectory) { "HARNESS_TRANSFER_PROJECT_MISSING" }
        }
        val document = HarnessTransferMetadata.decode(plan.metadata)
        plan.attachments?.let { path ->
            val source = child(stage, path)
            val destination = HarnessRuntimeScope.dataFile(scoped, "agent_harness/legacy-attachments/${receipt.id}")
            if (source.exists()) {
                require(!destination.exists()) { "HARNESS_TRANSFER_ATTACHMENT_COLLISION" }
                HarnessInstallationFiles.move(source, destination)
            }
            require(destination.isDirectory) { "HARNESS_TRANSFER_ATTACHMENT_MISSING" }
        }
        document.workspaces.filter { it.backend != "REMOTE_SSH" }.forEach { workspace ->
            val cwd = plan.cwdMap[workspace.guestPath] ?: workspace.guestPath
            if (cwd.startsWith("/workspace/projects/")) child(targetProjects, cwd.removePrefix("/workspace/projects/")).mkdirs()
        }
        plan.cwdMap.values.filter { it.startsWith("/workspace/projects/") }.forEach { cwd ->
            child(targetProjects, cwd.removePrefix("/workspace/projects/")).mkdirs()
        }
        current = current.copy(phase = "FILES_READY").also(files.journal::write)
        val imported = HarnessTransferMetadata.apply(context, receipt.runtimeId, document, plan.sessionIds,
            plan.cwdMap, plan.newRuntime, plan.workspaceIds, plan.harnessWorkspaceIds, plan.attachmentPaths)
        plan.settings?.let { HarnessTransferSettings.restore(scoped, JSONObject(it), plan.modelMappings) }
        plan.credentials?.let { HarnessCredentialStore(scoped).importTransferRecords(JSONObject(unseal(it)), imported.workspaceIdMap) }
        manager.markReady(receipt.runtimeId)
        HarnessAppRuntime.discard(receipt.runtimeId)
        files.finish(current)
    }

    private fun stageRoot(row: HarnessInstallationReceipt) = HarnessRuntimeScope.dataRoot(HarnessRuntimeScope.context(context, row.stageId))
    private fun child(root: File, path: String): File {
        require(!path.startsWith('/') && path.split('/').none { it in setOf("", ".", "..") }) { "HARNESS_TRANSFER_PATH_INVALID" }
        val file = File(root, path)
        require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "HARNESS_TRANSFER_PATH_INVALID" }
        return file
    }

    private fun write(file: File, content: String) {
        file.parentFile!!.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(content.toByteArray()); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }

    private suspend fun copyTree(source: File, destination: File) {
        require(destination.mkdirs()) { "HARNESS_TRANSFER_STAGE_INVALID" }
        if (!source.exists()) return
        val operationContext = currentCoroutineContext()
        Files.walkFileTree(source.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun preVisitDirectory(path: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                operationContext.ensureActive()
                if (path != source.toPath()) Files.copy(path, destination.toPath().resolve(source.toPath().relativize(path)), StandardCopyOption.COPY_ATTRIBUTES)
                return java.nio.file.FileVisitResult.CONTINUE
            }
            override fun visitFile(path: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                operationContext.ensureActive()
                Files.copy(path, destination.toPath().resolve(source.toPath().relativize(path)), LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }

    private fun treeSize(root: File): Long = if (!root.exists()) 0L else root.walkTopDown()
        .onEnter { !Files.isSymbolicLink(it.toPath()) }.filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) }.sumOf { it.length() }

    private fun uniqueFolder(original: String, used: MutableSet<String>, operationId: String): String {
        val base = com.example.llamadroid.service.AgentLocalWorkspaceSupport.sanitizeProjectFolder(original).take(64)
        var candidate = "$base-import-${operationId.take(8)}"
        var index = 2
        while (!used.add(candidate)) candidate = "$base-import-${operationId.take(8)}-${index++}"
        return candidate
    }

    private fun remapPath(path: String, mappings: Map<String, String>): String? = mappings.keys
        .filter { path == it || path.startsWith(it.trimEnd('/') + "/") }.maxByOrNull { it.length }
        ?.let { mappings.getValue(it) + path.removePrefix(it) }

    private fun seal(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)).toString()
    }
    private fun unseal(envelope: String): String {
        val json = JSONObject(envelope)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(json.getString("iv"), Base64.NO_WRAP)))
            doFinal(Base64.decode(json.getString("ciphertext"), Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }
    }
    private fun key(): SecretKey {
        val alias = "harness-transfer-staging-v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}
