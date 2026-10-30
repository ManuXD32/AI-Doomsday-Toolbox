package com.example.llamadroid.harness

import android.content.Context
import android.util.AtomicFile
import com.example.llamadroid.data.proot.AgentProotEnvironmentManager
import com.example.llamadroid.data.proot.AgentProotEnvironmentPaths
import com.example.llamadroid.harness.runtime.AndroidHarnessEnvironmentProvider
import com.example.llamadroid.harness.runtime.AssetHarnessPayloadProvider
import com.example.llamadroid.harness.runtime.HarnessRuntimePaths
import com.example.llamadroid.harness.runtime.HarnessRuntimeScope
import com.example.llamadroid.harness.runtime.HarnessEnvironmentPaths
import com.example.llamadroid.harness.runtime.HarnessPayloadWorkPhase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal data class HarnessInstallationReceipt(
    val id: String,
    val runtimeId: String,
    val kind: String,
    val phase: String,
    val stageId: String,
    val failureCode: String? = null,
    val failurePhase: String? = null,
)

/** Receipts live outside any installation. Only opaque IDs and phases are durable. */
internal class HarnessInstallationJournal(context: Context) {
    val directory = File(HarnessRuntimeScope.host(context).filesDir, "agent_harness/installation-operations")
    fun new(runtimeId: String, kind: String): HarnessInstallationReceipt {
        val token = UUID.randomUUID().toString()
        return HarnessInstallationReceipt(token, runtimeId, kind, "PREPARING", "stage-$token").also(::write)
    }
    fun pending(): List<HarnessInstallationReceipt> = directory.listFiles().orEmpty()
        .filter { it.name.matches(Regex("[a-f0-9-]{36}\\.json")) }
        .map { file ->
            val bytes = AtomicFile(file).openRead().use { input ->
                require(file.length() <= 16_384) { "HARNESS_OPERATION_INVALID" }
                input.readBytes()
            }
            val row = JSONObject(bytes.toString(Charsets.UTF_8))
            HarnessInstallationReceipt(row.getString("id"), row.getString("runtimeId"),
                row.getString("kind"), row.getString("phase"), row.getString("stageId"),
                row.optString("failureCode").takeIf { it.isNotEmpty() },
                row.optString("failurePhase").takeIf { it.isNotEmpty() }).also(::validate)
        }
    fun write(receipt: HarnessInstallationReceipt) {
        validate(receipt)
        check(directory.isDirectory || directory.mkdirs())
        val file = AtomicFile(File(directory, "${receipt.id}.json"))
        val stream = file.startWrite()
        try {
            stream.write(JSONObject().put("version", 1).put("id", receipt.id)
                .put("runtimeId", receipt.runtimeId).put("kind", receipt.kind)
                .put("phase", receipt.phase).put("stageId", receipt.stageId)
                .put("failureCode", receipt.failureCode).put("failurePhase", receipt.failurePhase).toString().toByteArray())
            file.finishWrite(stream)
        } catch (error: Throwable) { file.failWrite(stream); throw error }
    }
    fun clear(receipt: HarnessInstallationReceipt) { AtomicFile(File(directory, "${receipt.id}.json")).delete() }
    fun recordFailure(runtimeId: String?, failure: Throwable, phase: String) {
        if (runtimeId == null) return
        pending().filter { it.runtimeId == runtimeId }.forEach { receipt ->
            write(receipt.copy(failureCode = harnessLifecycleErrorCode(failure),
                failurePhase = phase.takeIf { it in FAILURE_PHASES }))
        }
    }
    private fun validate(row: HarnessInstallationReceipt) {
        require(row.id.matches(Regex("[a-f0-9-]{36}"))) { "HARNESS_OPERATION_INVALID" }
        AgentProotEnvironmentPaths.requireSafeEnvironmentId(row.runtimeId)
        require(row.stageId == "stage-${row.id}" && row.kind in setOf("CREATE", "RECREATE", "DELETE", "IMPORT")) { "HARNESS_OPERATION_INVALID" }
        require(row.phase in setOf("PREPARING", "PREPARED", "ACTIVATING", "FILES_READY", "COMMITTED")) { "HARNESS_OPERATION_INVALID" }
        require(row.failureCode == null || harnessDiagnosticErrorClass(row.failureCode) != "RemoteError") { "HARNESS_OPERATION_INVALID" }
        require(row.failurePhase == null || row.failurePhase in FAILURE_PHASES) { "HARNESS_OPERATION_INVALID" }
    }

    private companion object {
        val FAILURE_PHASES = setOf("STOPPING", "PREPARING", "PREPARED", "ACTIVATING", "FILES_READY", "COMMITTED",
            "ROOTFS_COPY", "ROOTFS_CHECKSUM", "ROOTFS_EXTRACT", "ROOTFS_ACTIVATE",
            "PAYLOAD_COPY", "PAYLOAD_CHECKSUM", "PAYLOAD_EXTRACT", "PAYLOAD_ACTIVATE",
            "SCAN", "READ", "STAGE", "VALIDATE", "DELETING", "WRITE")
    }
}

/** Filesystem phases are idempotent and never follow directory links during cleanup. */
internal class HarnessInstallationFiles(
    private val context: Context,
    private val prepareStage: suspend (Context, String, (String) -> Unit) -> HarnessEnvironmentPaths = ::prepareInstallationStage,
) {
    val journal = HarnessInstallationJournal(context)
    private val environments = AgentProotEnvironmentManager(HarnessRuntimeScope.host(context))

    suspend fun prepareRootfs(row: HarnessInstallationReceipt, progress: (String) -> Unit): HarnessInstallationReceipt {
        if (row.phase != "PREPARING") return row
        progress("PREPARING")
        val scoped = HarnessRuntimeScope.context(context, row.runtimeId)
        val paths = prepareStage(scoped, row.stageId, progress)
        // The stage's marker must describe its final identity when activated.
        val marker = File(paths.rootfs, ".adt-environment.json")
        val metadata = JSONObject(marker.readText()).put("environmentId", row.runtimeId)
        FileOutputStream(marker).use { output -> output.write(metadata.toString().toByteArray()); output.fd.sync() }
        currentCoroutineContext().ensureActive()
        return row.copy(phase = "PREPARED").also(journal::write)
    }

    fun activateRootfs(row: HarnessInstallationReceipt): HarnessInstallationReceipt {
        if (row.phase in setOf("FILES_READY", "COMMITTED")) return row
        require(row.phase in setOf("PREPARED", "ACTIVATING"))
        val target = AgentProotEnvironmentPaths.rootfs(context, row.runtimeId)
        val staged = AgentProotEnvironmentPaths.rootfs(context, row.stageId)
        val previous = File(AgentProotEnvironmentPaths.environmentRoot(context, row.runtimeId), ".previous-${row.id}")
        val activating = row.copy(phase = "ACTIVATING").also(journal::write)
        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
        if (Files.exists(staged.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) && !previous.exists()) move(target, previous)
            check(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "HARNESS_ACTIVATION_COLLISION" }
            move(staged, target)
        }
        check(environments.isReady(row.runtimeId) && File(target, ".adt-environment.json").isFile) { "HARNESS_ACTIVATION_INCOMPLETE" }
        return activating.copy(phase = "FILES_READY").also(journal::write)
    }

    fun finish(row: HarnessInstallationReceipt) {
        val committed = row.copy(phase = "COMMITTED").also(journal::write)
        val previous = File(AgentProotEnvironmentPaths.environmentRoot(context, row.runtimeId), ".previous-${row.id}")
        deleteTree(previous)
        deleteTree(AgentProotEnvironmentPaths.environmentRoot(context, row.stageId))
        deleteTree(HarnessRuntimeScope.dataRoot(HarnessRuntimeScope.context(context, row.stageId)))
        deleteTree(HarnessRuntimePaths.runtimeRoot(context, row.stageId))
        deleteTree(quarantine(row))
        journal.clear(committed)
    }

    fun quarantineDeletion(row: HarnessInstallationReceipt): HarnessInstallationReceipt {
        if (row.phase in setOf("FILES_READY", "COMMITTED")) return row
        val scoped = HarnessRuntimeScope.context(context, row.runtimeId)
        val roots = ownedRoots(scoped) + AgentProotEnvironmentPaths.environmentRoot(context, row.runtimeId) +
            HarnessRuntimePaths.runtimeRoot(scoped, row.runtimeId)
        val quarantine = quarantine(row)
        check(quarantine.isDirectory || quarantine.mkdirs())
        journal.write(row.copy(phase = "ACTIVATING"))
        roots.forEachIndexed { index, file ->
            if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                val destination = File(quarantine, index.toString())
                check(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) { "HARNESS_DELETE_COLLISION" }
                move(file, destination)
            }
        }
        return row.copy(phase = "FILES_READY").also(journal::write)
    }

    private fun quarantine(row: HarnessInstallationReceipt) = File(journal.directory, "quarantine-${row.id}")

    companion object {
        fun ownedRoots(context: Context): List<File> = if (HarnessRuntimeScope.id(context) != HarnessRuntimeScope.LEGACY_RUNTIME_ID)
            listOf(HarnessRuntimeScope.dataRoot(context))
        else listOf(HarnessRuntimePaths.projects(context)) + listOf(
            "dsh_home", "legacy-attachments", "project-management", "deleted-projects", "project-deletions", "session-deletions",
            "runtime-diagnostics.json", "runtime-diagnostics.json.segments", "attention-notifications.json", "recovery", "runtime-reset.json"
        ).map { HarnessRuntimeScope.dataFile(context, "agent_harness/$it") }

        fun move(source: File, target: File) {
            check(!Files.isSymbolicLink(source.toPath())) { "HARNESS_RUNTIME_PATH_INVALID" }
            check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(source.parentFile!!)
            syncDirectory(target.parentFile!!)
        }

        fun syncDirectory(directory: File) {
            require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "HARNESS_RUNTIME_PATH_INVALID" }
            val descriptor = android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
        }

        fun deleteTree(file: File) {
            if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
            Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                    Files.delete(path); return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(path: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(path); return FileVisitResult.CONTINUE
                }
            })
        }
    }
}

private suspend fun prepareInstallationStage(
    context: Context,
    stageId: String,
    progress: (String) -> Unit,
): HarnessEnvironmentPaths {
    val paths = AndroidHarnessEnvironmentProvider(context, progress = { progress("ROOTFS_${it.name}") }).prepare(stageId)
    currentCoroutineContext().ensureActive()
    AssetHarnessPayloadProvider(context) { phase ->
        progress(when (phase) {
            HarnessPayloadWorkPhase.COPY -> "PAYLOAD_COPY"
            HarnessPayloadWorkPhase.CHECKSUM -> "PAYLOAD_CHECKSUM"
            HarnessPayloadWorkPhase.EXTRACT_ENTRY, HarnessPayloadWorkPhase.EXTRACT_CHUNK -> "PAYLOAD_EXTRACT"
            HarnessPayloadWorkPhase.ACTIVATE -> "PAYLOAD_ACTIVATE"
        })
    }.prepare(paths)
    return paths
}
