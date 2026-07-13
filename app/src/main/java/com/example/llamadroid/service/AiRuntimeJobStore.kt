package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.db.AiRuntimeJobEntity
import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AiRuntimeJobStore {
    const val TYPE_AGENT_CHAT = "AGENT_CHAT"
    const val TYPE_DATASET_PIPELINE = "DATASET_PIPELINE"
    const val TYPE_OLLAMA_PULL = "OLLAMA_PULL"
    const val TYPE_OLLAMA_CREATE_REMOTE = "OLLAMA_CREATE_REMOTE"
    const val TYPE_OLLAMA_CREATE_LOCAL = "OLLAMA_CREATE_LOCAL"

    const val STATUS_RUNNING = "RUNNING"
    const val STATUS_RECOVERING = "RECOVERING"
    const val STATUS_COMPLETED = "COMPLETED"
    const val STATUS_FAILED = "FAILED"
    const val STATUS_CANCELLED = "CANCELLED"

    private const val STALE_LONG_RUNNING_RUNTIME_MS = 6L * 60L * 60L * 1000L
    private const val STALE_AGENT_CHAT_MS = STALE_LONG_RUNNING_RUNTIME_MS
    private const val STALE_DATASET_RUNTIME_MS = STALE_LONG_RUNNING_RUNTIME_MS
    private const val STALE_OLLAMA_RUNTIME_MS = STALE_LONG_RUNNING_RUNTIME_MS

    suspend fun upsert(context: Context, job: AiRuntimeJobEntity) = withContext(Dispatchers.IO) {
        AppDatabase.getDatabase(context).aiRuntimeJobDao().upsert(job)
        pruneOldTerminalJobs(context)
    }

    suspend fun markState(
        context: Context,
        jobId: String,
        status: String,
        checkpointJson: String? = null,
        progressText: String? = null,
        errorMessage: String? = null
    ) = withContext(Dispatchers.IO) {
        AppDatabase.getDatabase(context).aiRuntimeJobDao().updateState(
            jobId = jobId,
            status = status,
            checkpointJson = checkpointJson,
            progressText = progressText,
            errorMessage = errorMessage,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun getByJobKey(context: Context, jobKey: String): AiRuntimeJobEntity? = withContext(Dispatchers.IO) {
        AppDatabase.getDatabase(context).aiRuntimeJobDao().getByJobKey(jobKey)
    }

    suspend fun getActiveJobs(context: Context): List<AiRuntimeJobEntity> = withContext(Dispatchers.IO) {
        AppDatabase.getDatabase(context).aiRuntimeJobDao().getActiveJobs()
    }

    suspend fun markStaleActiveJobsTerminal(context: Context, now: Long = System.currentTimeMillis()): List<AiRuntimeJobEntity> =
        withContext(Dispatchers.IO) {
            val staleJobs = AppDatabase.getDatabase(context).aiRuntimeJobDao()
                .getActiveJobs()
                .filter { isJobStale(it, now) }

            staleJobs.forEach { job ->
                AppDatabase.getDatabase(context).aiRuntimeJobDao().updateState(
                    jobId = job.jobId,
                    status = STATUS_FAILED,
                    checkpointJson = job.checkpointJson,
                    progressText = job.progressText,
                    errorMessage = "Recovered as stale runtime job",
                    updatedAt = now
                )
            }
            staleJobs
        }

    suspend fun getRecoverableJobs(context: Context, now: Long = System.currentTimeMillis()): List<AiRuntimeJobEntity> =
        withContext(Dispatchers.IO) {
            markStaleActiveJobsTerminal(context, now)
            AppDatabase.getDatabase(context).aiRuntimeJobDao()
                .getActiveJobs()
                .filterNot { isJobStale(it, now) }
        }

    fun staleWindowMsForType(type: String): Long = when (type) {
        TYPE_AGENT_CHAT -> STALE_AGENT_CHAT_MS
        TYPE_DATASET_PIPELINE -> STALE_DATASET_RUNTIME_MS
        TYPE_OLLAMA_PULL, TYPE_OLLAMA_CREATE_REMOTE, TYPE_OLLAMA_CREATE_LOCAL -> STALE_OLLAMA_RUNTIME_MS
        else -> STALE_AGENT_CHAT_MS
    }

    fun isJobStale(job: AiRuntimeJobEntity, now: Long = System.currentTimeMillis()): Boolean {
        return now - job.updatedAt > staleWindowMsForType(job.type)
    }

    private suspend fun pruneOldTerminalJobs(context: Context) {
        val olderThan = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        AppDatabase.getDatabase(context).aiRuntimeJobDao().deleteTerminalJobsOlderThan(olderThan)
    }
}
