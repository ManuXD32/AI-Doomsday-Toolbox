package com.example.llamadroid.branchy

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.modeltools.registerLocalGgufModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** Room + file-system access for Branchy runs. Run dirs live in `getExternalFilesDir(null)/branchy/runs/<id>/`. */
class BranchyRunRepository(
    private val context: Context,
    private val dao: BranchyRunDao = AppDatabase.getDatabase(context).branchyRunDao()
) {
    fun observeAll(): Flow<List<BranchyRunEntity>> = dao.observeAll()

    fun observe(id: Long): Flow<BranchyRunEntity?> = dao.observeById(id)

    suspend fun get(id: Long): BranchyRunEntity? = dao.getById(id)

    fun runRoot(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "branchy/runs")

    fun runDir(id: Long): File = File(runRoot(), id.toString())

    /** Creates a DRAFT run and its directory; returns the id. */
    suspend fun create(name: String, config: BranchyRunConfig): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val id = dao.insert(
            BranchyRunEntity(
                name = name, recipeId = config.recipeId, stage = config.currentStage, runDir = "",
                configJson = config.encode(), status = BranchyRunStatus.DRAFT.value, lastStep = 0L,
                totalSteps = 0L, lastLoss = null, lastValLoss = null, tokensSeen = 0L,
                createdAt = now, updatedAt = now, error = null
            )
        )
        val dir = runDir(id).apply { mkdirs() }
        dao.getById(id)?.let { dao.update(it.copy(runDir = dir.absolutePath)) }
        id
    }

    suspend fun config(id: Long): BranchyRunConfig? = dao.getById(id)?.let { BranchyRunConfig.decode(it.configJson) }

    suspend fun saveConfig(id: Long, config: BranchyRunConfig) =
        dao.updateConfig(id, config.encode(), System.currentTimeMillis())

    suspend fun setStatus(id: Long, status: BranchyRunStatus, error: String? = null) =
        dao.updateStatus(id, status.value, error, System.currentTimeMillis())

    suspend fun setStage(id: Long, stage: Int) {
        val row = dao.getById(id) ?: return
        dao.update(row.copy(stage = stage, updatedAt = System.currentTimeMillis()))
    }

    suspend fun saveProgress(
        id: Long, step: Long, totalSteps: Long, loss: Double?, valLoss: Double?, tokensSeen: Long
    ) = dao.updateProgress(
        id, step, totalSteps, loss?.takeIf { it.isFinite() }, valLoss?.takeIf { it.isFinite() },
        tokensSeen, System.currentTimeMillis()
    )

    /** Removes the row and its directory (checkpoints, data, exports). */
    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        val row = dao.getById(id)
        row?.runDir?.takeIf { it.isNotBlank() }?.let { File(it).deleteRecursively() }
        dao.deleteById(id)
    }

    /** Per-part byte sizes of a run directory (file-system walk; runs on IO). */
    suspend fun storage(id: Long): BranchyRunStorage = withContext(Dispatchers.IO) {
        val dir = dao.getById(id)?.runDir?.takeIf { it.isNotBlank() }?.let { File(it) }
        if (dir == null) BranchyRunStorage.EMPTY else BranchyRunStorageScan.scan(dir)
    }

    /** "Free space": removes tokenized data, mirror and DP shards; keeps checkpoints, exports and the tokenizer. Returns bytes freed. */
    suspend fun freeSpace(id: Long): Long = withContext(Dispatchers.IO) {
        val row = dao.getById(id)
        val dir = row?.runDir?.takeIf { it.isNotBlank() }?.let { File(it) }
        if (dir == null) 0L else BranchyRunStorageScan.freeSpace(dir, keepMirror = row.status == BranchyRunStatus.PAUSED.value)
    }

    /**
     * Deletes a run but keeps its exported GGUFs: each is moved into the models folder and its library row
     * repointed (or registered when it had none) before the run dir and row are removed. Each move leaves
     * file and row consistent; the first failure aborts BEFORE anything is deleted and is returned as a failure.
     */
    suspend fun deleteKeepingExports(id: Long): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val row = dao.getById(id)
            val dir = row?.runDir?.takeIf { it.isNotBlank() }?.let { File(it) }
            var moved = 0
            if (dir != null && dir.isDirectory) {
                val modelDao = AppDatabase.getDatabase(context).modelDao()
                val modelsDir = ModelRepository(context, modelDao).getModelDir(ModelType.LLM)
                modelsDir.mkdirs()
                val reserved = modelDao.getModelsByTypesSync(ModelType.entries).map { it.filename }.toSet()
                for (move in BranchyRunStorageScan.planExportMoves(dir, modelsDir, reserved)) {
                    moveFile(move.from, move.to)
                    try {
                        val old = modelDao.getModelByPath(move.from.absolutePath)
                        if (old != null) modelDao.updateFilename(old.filename, move.to.name, move.to.absolutePath)
                        else registerLocalGgufModel(context, move.to, origin = "branchy_export").getOrThrow()
                    } catch (t: Throwable) {
                        runCatching { moveFile(move.to, move.from) }
                        throw t
                    }
                    moved++
                }
            }
            row?.runDir?.takeIf { it.isNotBlank() }?.let { File(it).deleteRecursively() }
            dao.deleteById(id)
            moved
        }
    }

    private fun moveFile(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return
        try {
            from.copyTo(to, overwrite = false)
            check(to.length() == from.length()) { "size mismatch copying ${from.name}" }
        } catch (t: Throwable) {
            to.delete()
            throw t
        }
        check(from.delete()) { "cannot remove ${from.name}" }
    }

    /**
     * After process death no native process is alive: runs still marked `preparing`/`training` become
     * `paused`. Training is never restarted silently; the user resumes it. Runs the live state says are
     * active (a service in this process owns them) are left alone. Returns the ids that were changed.
     */
    suspend fun restoreInterrupted(): List<Long> = withContext(Dispatchers.IO) {
        val live = BranchyTrainingState.state.value
        val liveId = if (live.isActive) live.runId else null
        val stale = dao.getByStatuses(
            listOf(BranchyRunStatus.PREPARING.value, BranchyRunStatus.TRAINING.value)
        ).filter { it.id != liveId }
        stale.forEach { dao.updateStatus(it.id, BranchyRunStatus.PAUSED.value, null, System.currentTimeMillis()) }
        stale.map { it.id }
    }
}
