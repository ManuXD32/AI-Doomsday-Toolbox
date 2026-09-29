package com.example.llamadroid.harness

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

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
    private val directory = com.example.llamadroid.harness.runtime.HarnessRuntimeScope.dataRoot(context).absoluteFile
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

/** Replaces only the selected rootfs; canonical history and configuration are bind mounts. */
internal class HarnessRuntimeReinstaller(
    private val context: Context,
    private val database: AppDatabase = AppDatabase.getDatabase(context),
    private val journalStore: HarnessRuntimeResetJournalStore = HarnessRuntimeResetJournalStore(context),
    private val logger: HarnessRuntimeMetadataLogger = NoopHarnessRuntimeMetadataLogger,
) {
    fun confirmationState(): HarnessRuntimeReinstallState = HarnessRuntimeReinstallState(
        localProjectCount = HarnessRuntimePaths.projects(context).listFiles().orEmpty().count { it.isDirectory },
    )

    suspend fun reinstall(onState: (HarnessRuntimeReinstallState) -> Unit = {}): HarnessRuntimeReinstallResult =
        withContext(Dispatchers.IO) {
            val id = com.example.llamadroid.harness.runtime.HarnessRuntimeScope.id(context)
            val files = HarnessInstallationFiles(context)
            try {
                val receipt = files.journal.pending().firstOrNull { it.runtimeId == id && it.kind == "RECREATE" }
                    ?: files.journal.new(id, "RECREATE")
                onState(HarnessRuntimeReinstallState(HarnessRuntimeReinstallPhase.RESTORING, 10))
                val prepared = files.prepareRootfs(receipt) { }
                withContext(kotlinx.coroutines.NonCancellable) {
                    val activated = files.activateRootfs(prepared)
                    database.harnessInstallationDao().getById(id)?.let { row ->
                        database.harnessInstallationDao().update(row.copy(status = "READY", updatedAt = System.currentTimeMillis()))
                    }
                    files.finish(activated)
                    journalStore.clear()
                }
                onState(HarnessRuntimeReinstallState(HarnessRuntimeReinstallPhase.COMPLETE, 100))
                logger.record(HarnessRuntimeLogEvent("runtime_recreated", id, receipt.id, HarnessRuntimeState.STOPPED))
                HarnessRuntimeReinstallResult(true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                onState(HarnessRuntimeReinstallState(HarnessRuntimeReinstallPhase.FAILED, errorCode = "HARNESS_REINSTALL_FAILED"))
                HarnessRuntimeReinstallResult(false, "HARNESS_REINSTALL_FAILED")
            }
        }
}
