package com.example.llamadroid.harness

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.proot.AgentProotEnvironmentManager
import com.example.llamadroid.harness.runtime.AndroidHarnessEnvironmentProvider
import com.example.llamadroid.harness.runtime.AssetHarnessPayloadProvider
import com.example.llamadroid.harness.runtime.HarnessEnvironmentProvider
import com.example.llamadroid.harness.runtime.HarnessPayloadProvider
import com.example.llamadroid.harness.runtime.HarnessRuntimeException
import com.example.llamadroid.harness.runtime.HarnessRuntimeLogEvent
import com.example.llamadroid.harness.runtime.HarnessRuntimeMetadataLogger
import com.example.llamadroid.harness.runtime.HarnessRuntimePaths
import com.example.llamadroid.harness.runtime.HarnessRuntimeState
import com.example.llamadroid.harness.runtime.NoopHarnessRuntimeMetadataLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/** UI-visible phases for the destructive, app-managed runtime reset. */
enum class HarnessRuntimeReinstallPhase {
    IDLE,
    STOPPING,
    RESETTING,
    RESTORING,
    COMPLETE,
    FAILED,
}

data class HarnessRuntimeReinstallState(
    val phase: HarnessRuntimeReinstallPhase = HarnessRuntimeReinstallPhase.IDLE,
    val progressPercent: Int = 0,
    val errorCode: String? = null,
    /** Counts are metadata only and make the destructive confirmation concrete. */
    val localProjectCount: Int = 0,
    val localRecordCount: Int = 0,
)

data class HarnessRuntimeReinstallResult(
    val success: Boolean,
    val errorCode: String? = null,
)

/** Metadata needed to resume a reset after process death. It never contains user content. */
internal data class HarnessRuntimeResetJournal(
    val generation: String,
    val phase: String,
    val progressPercent: Int,
    val localProjectCount: Int,
    val localRecordCount: Int,
    val errorCode: String? = null,
)

/**
 * Crash-resumable marker for the destructive reset transaction.
 *
 * The marker lives outside the DSH home so deleting a broken DSH tree cannot erase the recovery
 * record. Writes are atomic and contain only phase/count/error metadata; prompts, file names and
 * credentials are intentionally absent.
 */
internal class HarnessRuntimeResetJournalStore(private val context: Context) {
    private val directory = File(context.filesDir, "agent_harness").absoluteFile
    private val marker = File(directory, "runtime-reset.json")

    @Synchronized
    fun read(): HarnessRuntimeResetJournal? = runCatching {
        if (!Files.exists(marker.toPath(), LinkOption.NOFOLLOW_LINKS) || !marker.isFile) return null
        val json = JSONObject(marker.readText(Charsets.UTF_8))
        val generation = json.optString("generation").takeIf { it.matches(GENERATION_PATTERN) } ?: return null
        HarnessRuntimeResetJournal(
            generation = generation,
            phase = json.optString("phase", "unknown").take(MAX_PHASE_LENGTH),
            progressPercent = json.optInt("progressPercent", 0).coerceIn(0, 100),
            localProjectCount = json.optInt("localProjectCount", 0).coerceAtLeast(0),
            localRecordCount = json.optInt("localRecordCount", 0).coerceAtLeast(0),
            errorCode = json.optString("errorCode").takeIf { it.matches(ERROR_PATTERN) },
        )
    }.getOrNull()

    @Synchronized
    fun write(journal: HarnessRuntimeResetJournal) {
        require(journal.generation.matches(GENERATION_PATTERN))
        directory.mkdirs()
        val temporary = File(directory, "runtime-reset.${journal.generation}.tmp")
        val payload = JSONObject()
            .put("generation", journal.generation)
            .put("phase", journal.phase.take(MAX_PHASE_LENGTH))
            .put("progressPercent", journal.progressPercent.coerceIn(0, 100))
            .put("localProjectCount", journal.localProjectCount.coerceAtLeast(0))
            .put("localRecordCount", journal.localRecordCount.coerceAtLeast(0))
            .apply { journal.errorCode?.takeIf { it.matches(ERROR_PATTERN) }?.let { put("errorCode", it) } }
        temporary.writeText(payload.toString(), Charsets.UTF_8)
        try {
            Files.move(
                temporary.toPath(),
                marker.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                marker.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    @Synchronized
    fun clear() {
        if (Files.exists(marker.toPath(), LinkOption.NOFOLLOW_LINKS) && !marker.delete()) {
            throw HarnessRuntimeException("HARNESS_REINSTALL_MARKER_DELETE_FAILED", "Reset marker could not be removed")
        }
    }

    private companion object {
        val GENERATION_PATTERN = Regex("reinstall-[A-Za-z0-9_.:-]{1,128}")
        val ERROR_PATTERN = Regex("[A-Z][A-Z0-9_]{2,96}")
        const val MAX_PHASE_LENGTH = 48
    }
}

/**
 * Deletes only Harness-owned records while leaving unrelated app data and remote files alone.
 * The list is intentionally explicit so a future database table cannot be erased by accident.
 */
internal object HarnessRuntimeRecordReset {
    /** Child tables precede their conversation/workspace parents when FK enforcement is enabled. */
    val tablesInDeleteOrder: List<String> = listOf(
        "agent_message_parts",
        "agent_turn_contexts",
        "agent_skill_assignments",
        "agent_pending_questions",
        "agent_pending_plans",
        "agent_todos",
        "agent_compactions",
        "agent_project_states",
        "agent_plan_versions",
        "agent_work_reports",
        "agent_invocations",
        "agent_pending_inputs",
        "agent_project_contracts",
        "agent_decisions",
        "agent_continuation_outbox",
        "agent_sleep_wakes",
        "agent_project_events",
        "agent_project_runs",
        "agent_proot_runs",
        "agent_messages",
        "agent_harness_sessions",
        "ai_runtime_jobs",
        "agent_conversations",
        "agent_harness_workspaces",
        "agent_project_folders",
        "agent_proot_environments",
        "agent_harness_runtime",
        "agent_skills",
        "agent_runtime_profiles",
        "agent_runtime_endpoint_configs",
    )

    fun countExistingRecords(database: AppDatabase): Int {
        val sqlite = database.openHelper.readableDatabase
        val existing = existingTables(sqlite)
        return tablesInDeleteOrder.sumOf { table ->
            if (table !in existing) 0 else sqlite.query("SELECT COUNT(*) FROM $table").use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }
    }

    fun purge(database: AppDatabase) {
        val sqlite = database.openHelper.writableDatabase
        val existing = existingTables(sqlite)
        sqlite.beginTransaction()
        try {
            tablesInDeleteOrder.forEach { table ->
                if (table in existing) sqlite.execSQL("DELETE FROM $table")
            }
            sqlite.setTransactionSuccessful()
        } finally {
            sqlite.endTransaction()
        }
    }

    private fun existingTables(database: SupportSQLiteDatabase): Set<String> = buildSet {
        database.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }
}

/**
 * Restores the managed Debian/Harness installation and clears all local agent state.
 *
 * Local projects and their Room records are part of this reset. Remote project files are never
 * reached by the filesystem deletion; only their local workspace/session records are detached.
 */
internal class HarnessRuntimeReinstaller(
    private val context: Context,
    private val environmentManager: AgentProotEnvironmentManager = AgentProotEnvironmentManager(context),
    private val environmentProvider: HarnessEnvironmentProvider = AndroidHarnessEnvironmentProvider(
        context,
        environmentManager,
    ),
    private val payloadProvider: HarnessPayloadProvider = AssetHarnessPayloadProvider(context),
    private val credentials: HarnessCredentialStore = HarnessCredentialStore(context),
    private val database: AppDatabase = AppDatabase.getDatabase(context),
    private val journalStore: HarnessRuntimeResetJournalStore = HarnessRuntimeResetJournalStore(context),
    private val logger: HarnessRuntimeMetadataLogger = NoopHarnessRuntimeMetadataLogger,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    fun confirmationState(): HarnessRuntimeReinstallState {
        val previous = journalStore.read()
        return HarnessRuntimeReinstallState(
            phase = HarnessRuntimeReinstallPhase.IDLE,
            localProjectCount = previous?.localProjectCount ?: countLocalProjects(),
            localRecordCount = previous?.localRecordCount ?: runCatching {
                HarnessRuntimeRecordReset.countExistingRecords(database)
            }.getOrDefault(0),
        )
    }

    suspend fun reinstall(
        onState: (HarnessRuntimeReinstallState) -> Unit = {},
    ): HarnessRuntimeReinstallResult = withContext(Dispatchers.IO) {
        val previous = journalStore.read()
        val generation = previous?.generation ?: "reinstall-${now()}"
        val projectCount = previous?.localProjectCount ?: countLocalProjects()
        val recordCount = previous?.localRecordCount ?: runCatching {
            HarnessRuntimeRecordReset.countExistingRecords(database)
        }.getOrDefault(0)
        fun publish(state: HarnessRuntimeReinstallState) {
            onState(state)
            logger.record(
                HarnessRuntimeLogEvent(
                    event = "runtime_reinstall",
                    environmentId = HarnessRuntimePaths.SHARED_ENVIRONMENT_ID,
                    generation = generation,
                    state = when (state.phase) {
                        HarnessRuntimeReinstallPhase.COMPLETE -> HarnessRuntimeState.STOPPED
                        HarnessRuntimeReinstallPhase.FAILED -> HarnessRuntimeState.FAILED
                        else -> HarnessRuntimeState.STOP_REQUESTED
                    },
                    phase = state.phase.name.lowercase(),
                    errorCode = state.errorCode,
                )
            )
        }
        fun journal(phase: String, progress: Int, errorCode: String? = null) {
            journalStore.write(
                HarnessRuntimeResetJournal(
                    generation = generation,
                    phase = phase,
                    progressPercent = progress,
                    localProjectCount = projectCount,
                    localRecordCount = recordCount,
                    errorCode = errorCode,
                )
            )
        }

        publish(HarnessRuntimeReinstallState(
            phase = HarnessRuntimeReinstallPhase.STOPPING,
            progressPercent = 2,
            localProjectCount = projectCount,
            localRecordCount = recordCount,
        ))
        journal("stopping", 2)
        publish(HarnessRuntimeReinstallState(
            phase = HarnessRuntimeReinstallPhase.RESETTING,
            progressPercent = 10,
            localProjectCount = projectCount,
            localRecordCount = recordCount,
        ))
        try {
            val environmentId = HarnessRuntimePaths.SHARED_ENVIRONMENT_ID
            val projectRoot = File(context.filesDir, HarnessRuntimePaths.PROJECTS_DIRECTORY)
            val quarantineRoot = File(
                context.filesDir,
                "agent_harness/reset-quarantine/$generation",
            )
            journal("purging_projects", 15)
            // Moving the project tree first makes the destructive boundary durable: a crash can
            // resume the database purge without exposing the old workspace to legacy import.
            quarantineManagedTree(projectRoot, File(quarantineRoot, "projects"))
            journal("purging_records", 28)
            HarnessRuntimeRecordReset.purge(database)
            journal("deleting_project_quarantine", 35)
            deleteManagedTree(quarantineRoot)
            credentials.clearAll()
            HarnessWorkspaceTitleSyncStore(context).clearAll()
            journal("removing_environment", 42)
            deleteManagedTree(File(context.filesDir, HarnessRuntimePaths.HARNESS_HOME_DIRECTORY))
            deleteManagedTree(File(context.cacheDir, "${HarnessRuntimePaths.RUNTIME_DIRECTORY}/$environmentId"))
            environmentManager.delete(environmentId).getOrThrow()

            publish(HarnessRuntimeReinstallState(
                phase = HarnessRuntimeReinstallPhase.RESTORING,
                progressPercent = 50,
                localProjectCount = projectCount,
                localRecordCount = recordCount,
            ))
            journal("restoring_environment", 50)
            val paths = environmentProvider.prepare(environmentId)
            journal("restoring_payload", 72)
            payloadProvider.prepare(paths)
            journal("complete", 100)
            journalStore.clear()
            publish(HarnessRuntimeReinstallState(
                phase = HarnessRuntimeReinstallPhase.COMPLETE,
                progressPercent = 100,
                localProjectCount = projectCount,
                localRecordCount = recordCount,
            ))
            HarnessRuntimeReinstallResult(success = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val code = failure.message
                ?.takeIf { it.matches(ERROR_PATTERN) }
                ?: when (failure) {
                    is HarnessRuntimeException -> failure.code
                    else -> "HARNESS_REINSTALL_FAILED"
                }
            // Preserve the original failure when storage is too damaged to
            // update the recovery marker. Earlier successful phase writes
            // still cause the next launch to resume cleanup.
            runCatching { journal("failed", 0, code) }
            publish(HarnessRuntimeReinstallState(
                phase = HarnessRuntimeReinstallPhase.FAILED,
                progressPercent = 0,
                errorCode = code,
                localProjectCount = projectCount,
                localRecordCount = recordCount,
            ))
            HarnessRuntimeReinstallResult(success = false, errorCode = code)
        }
    }

    private fun countLocalProjects(): Int = runCatching {
        val root = File(context.filesDir, HarnessRuntimePaths.PROJECTS_DIRECTORY)
        root.listFiles()?.count { Files.exists(it.toPath(), LinkOption.NOFOLLOW_LINKS) } ?: 0
    }.getOrDefault(0)

    /** Atomically hides a managed tree when possible, without resolving a symlink root. */
    private fun quarantineManagedTree(source: File, destination: File) {
        val sourcePath = source.absoluteFile.toPath().normalize()
        val destinationPath = destination.absoluteFile.toPath().normalize()
        val filesRoot = context.filesDir.absoluteFile.toPath().normalize()
        require(sourcePath.startsWith(filesRoot) && destinationPath.startsWith(filesRoot)) {
            "HARNESS_REINSTALL_PATH_INVALID"
        }
        if (!Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(sourcePath)) return
        if (Files.exists(destinationPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(destinationPath)) {
            // A previous reset already owns the authoritative quarantine. Anything recreated at
            // the old path is stale and must not replace it or be imported.
            deleteManagedTree(source)
            return
        }
        Files.createDirectories(destinationPath.parent)
        try {
            Files.move(sourcePath, destinationPath, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(sourcePath, destinationPath)
        }
    }

    /** Delete without following any directory symlink, including dangling links. */
    private fun deleteManagedTree(target: File) {
        val path = target.absoluteFile.toPath().normalize()
        val filesRoot = context.filesDir.absoluteFile.toPath().normalize()
        val cacheRoot = context.cacheDir.absoluteFile.toPath().normalize()
        require(path.startsWith(filesRoot) || path.startsWith(cacheRoot)) {
            "HARNESS_REINSTALL_PATH_INVALID"
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
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
        })
    }

    private companion object {
        val ERROR_PATTERN = Regex("[A-Z][A-Z0-9_]{2,96}")
    }
}
