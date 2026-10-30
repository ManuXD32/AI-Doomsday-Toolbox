package com.example.llamadroid.service.filestation.jobs

import com.example.llamadroid.service.filestation.http.Clock
import com.example.llamadroid.service.filestation.http.JobContext
import com.example.llamadroid.service.filestation.http.JobInfo
import com.example.llamadroid.service.filestation.http.JobOutput
import com.example.llamadroid.service.filestation.http.JobRunner
import com.example.llamadroid.service.filestation.http.JobState
import com.example.llamadroid.service.filestation.volume.FsErrorCode
import com.example.llamadroid.service.filestation.volume.FsException
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** Job context that also hands out a per-job staging folder (created lazily, deleted after the job). */
interface StagingJobContext : JobContext {
    val stagingDir: File
}

/**
 * [JobRunner] on a small fixed thread pool (default 2). Keeps the last [keep] jobs (finished ones beyond the newest [keep] by submission order are
 * evicted first-in first-out; queued/running jobs never are). Staging folders live under [stagingRoot]
 * (`cacheDir/file_station_jobs`) and are wiped on construction and after each job.
 */
class JobManager(
    private val stagingRoot: File,
    threads: Int = 2,
    private val keep: Int = 50,
    private val clock: Clock = Clock { System.currentTimeMillis() },
    private val idGenerator: () -> String = { java.util.UUID.randomUUID().toString().replace("-", "").take(12) },
) : JobRunner {
    private class Job(val id: String, val kind: String, val title: String, val createdAt: Long) {
        @Volatile var state = JobState.QUEUED
        @Volatile var progress = 0f
        @Volatile var message: String? = null
        val outputs = CopyOnWriteArrayList<JobOutput>()
        @Volatile var errorCode: String? = null
        @Volatile var startedAt = createdAt
        @Volatile var finishedAt: Long? = null
        @Volatile var resultJson: String? = null
        @Volatile var cancelRequested = false

        val finished: Boolean get() = state != JobState.QUEUED && state != JobState.RUNNING
    }

    private val lock = Any()
    private val jobs = LinkedHashMap<String, Job>()
    private val threadCounter = AtomicInteger()
    private val executor: ExecutorService = Executors.newFixedThreadPool(
        threads.coerceAtLeast(1),
        ThreadFactory { r -> Thread(r, "fs-job-${threadCounter.incrementAndGet()}").also { it.isDaemon = true } },
    )

    init {
        stagingRoot.deleteRecursively()
    }

    override fun submit(kind: String, title: String, body: (JobContext) -> Unit): String {
        val job = Job(idGenerator(), kind, title, clock.nowMillis())
        synchronized(lock) {
            jobs[job.id] = job
            trim()
        }
        try {
            executor.execute { run(job, body) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            finish(job, JobState.FAILED, "IO", "job runner is shut down")
        }
        return job.id
    }

    override fun cancel(id: String): Boolean = synchronized(lock) {
        val job = jobs[id] ?: return false
        if (job.finished) return false
        job.cancelRequested = true
        if (job.state == JobState.QUEUED) {
            job.state = JobState.CANCELLED
            job.finishedAt = clock.nowMillis()
        }
        true
    }

    override fun list(): List<JobInfo> = synchronized(lock) { jobs.values.reversed().map { snapshot(it) } }

    override fun get(id: String): JobInfo? = synchronized(lock) { jobs[id]?.let { snapshot(it) } }

    fun shutdown() {
        executor.shutdownNow()
        stagingRoot.deleteRecursively()
    }

    private fun snapshot(j: Job) = JobInfo(
        id = j.id, kind = j.kind, title = j.title, state = j.state, progress = j.progress,
        message = j.message, outputs = j.outputs.toList(), errorCode = j.errorCode,
        startedAt = j.startedAt, finishedAt = j.finishedAt, resultJson = j.resultJson,
    )

    /**
     * Keeps the newest [keep] jobs by submission order; older finished jobs are evicted regardless of
     * the order in which they finished (so retention does not depend on thread timing). Queued/running
     * jobs are never evicted.
     */
    private fun trim() {
        val extra = jobs.size - keep
        if (extra <= 0) return
        val evict = jobs.values.take(extra).filter { it.finished }
        for (j in evict) jobs.remove(j.id)
    }

    private fun finish(job: Job, state: JobState, errorCode: String?, message: String?) = synchronized(lock) {
        if (job.finished) return@synchronized
        job.state = state
        job.errorCode = errorCode
        if (message != null) job.message = message
        if (state == JobState.DONE) job.progress = 1f
        job.finishedAt = clock.nowMillis()
        trim()
    }

    private inner class Ctx(val job: Job) : StagingJobContext {
        override val stagingDir: File by lazy { File(stagingRoot, job.id).also { it.mkdirs() } }
        override val isCancelled: Boolean get() = job.cancelRequested
        override fun progress(fraction: Float, message: String?) {
            if (job.finished) return
            job.progress = if (fraction < 0f) -1f else fraction.coerceIn(0f, 1f)
            if (message != null) job.message = message
        }
        override fun addOutput(vol: String, path: String) { if (!job.finished) job.outputs.add(JobOutput(vol, path)) }
        override fun setResult(json: String) { if (!job.finished) job.resultJson = json }
        override fun checkCancelled() {
            if (job.cancelRequested) throw FsException(FsErrorCode.IO, "cancelled")
        }
        fun cleanup() {
            if (File(stagingRoot, job.id).exists()) File(stagingRoot, job.id).deleteRecursively()
        }
    }

    private fun run(job: Job, body: (JobContext) -> Unit) {
        val started = synchronized(lock) {
            if (job.state != JobState.QUEUED) false else {
                job.state = JobState.RUNNING
                job.startedAt = clock.nowMillis()
                true
            }
        }
        if (!started) return
        val ctx = Ctx(job)
        try {
            body(ctx)
            if (job.cancelRequested) finish(job, JobState.CANCELLED, null, null)
            else finish(job, JobState.DONE, null, null)
        } catch (t: Throwable) {
            val cancelled = job.cancelRequested || (t is FsException && t.message == "cancelled")
            when {
                cancelled -> finish(job, JobState.CANCELLED, null, null)
                t is FsException -> finish(job, JobState.FAILED, t.code.name, t.message)
                else -> finish(job, JobState.FAILED, "IO", t.message ?: t.javaClass.simpleName)
            }
        } finally {
            ctx.cleanup()
        }
    }
}
