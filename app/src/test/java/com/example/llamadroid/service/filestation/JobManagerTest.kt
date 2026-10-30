package com.example.llamadroid.service.filestation

import com.example.llamadroid.service.filestation.http.JobInfo
import com.example.llamadroid.service.filestation.http.JobState
import com.example.llamadroid.service.filestation.jobs.JobManager
import com.example.llamadroid.service.filestation.jobs.StagingJobContext
import com.example.llamadroid.service.filestation.volume.FsErrorCode
import com.example.llamadroid.service.filestation.volume.FsException
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class JobManagerTest {
    private lateinit var staging: File
    private lateinit var jobs: JobManager

    @Before fun setUp() {
        staging = Files.createTempDirectory("fs-jobs").toFile()
        jobs = JobManager(File(staging, "file_station_jobs"))
    }

    @After fun tearDown() {
        jobs.shutdown()
        staging.deleteRecursively()
    }

    private fun await(m: JobManager, id: String, cond: (JobInfo) -> Boolean = { it.state.ordinal >= JobState.DONE.ordinal }): JobInfo {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val info = m.get(id)
            if (info != null && cond(info)) return info
            Thread.sleep(5)
        }
        throw AssertionError("timeout: ${m.get(id)}")
    }

    @Test fun progressOutputsAndResult() {
        val mid = CountDownLatch(1)
        val go = CountDownLatch(1)
        val id = jobs.submit("k", "t") { ctx ->
            ctx.progress(0.5f, "half")
            ctx.addOutput("a", "x/y.zip")
            ctx.setResult("""{"n":1}""")
            mid.countDown()
            go.await(10, TimeUnit.SECONDS)
        }
        assertTrue(mid.await(10, TimeUnit.SECONDS))
        val running = jobs.get(id)!!
        assertEquals(JobState.RUNNING, running.state)
        assertEquals(0.5f, running.progress, 0.001f)
        assertEquals("half", running.message)
        go.countDown()
        val done = await(jobs, id)
        assertEquals(JobState.DONE, done.state)
        assertEquals(1f, done.progress, 0f)
        assertEquals("x/y.zip", done.outputs.single().path)
        assertEquals("""{"n":1}""", done.resultJson)
        assertNotNull(done.finishedAt)
    }

    @Test fun cancelRunningJob() {
        val started = CountDownLatch(1)
        val id = jobs.submit("k", "t") { ctx ->
            started.countDown()
            while (true) { ctx.checkCancelled(); Thread.sleep(2) }
        }
        assertTrue(started.await(10, TimeUnit.SECONDS))
        assertTrue(jobs.cancel(id))
        assertEquals(JobState.CANCELLED, await(jobs, id).state)
        assertFalse(jobs.cancel(id))
        assertFalse(jobs.cancel("nope"))
    }

    @Test fun cancelQueuedJobNeverRuns() {
        val one = JobManager(File(staging, "one"), threads = 1)
        try {
            val block = CountDownLatch(1)
            val first = one.submit("k", "first") { block.await(10, TimeUnit.SECONDS) }
            var ran = false
            val second = one.submit("k", "second") { ran = true }
            assertEquals(JobState.QUEUED, one.get(second)!!.state)
            assertTrue(one.cancel(second))
            assertEquals(JobState.CANCELLED, one.get(second)!!.state)
            block.countDown()
            await(one, first)
            Thread.sleep(50)
            assertFalse(ran)
        } finally { one.shutdown() }
    }

    @Test fun failureCarriesErrorCode() {
        val id = jobs.submit("k", "t") { throw FsException(FsErrorCode.NO_SPACE, "full") }
        val info = await(jobs, id)
        assertEquals(JobState.FAILED, info.state)
        assertEquals("NO_SPACE", info.errorCode)
        assertEquals("full", info.message)
        val other = await(jobs, jobs.submit("k", "t") { error("boom") })
        assertEquals("IO", other.errorCode)
    }

    @Test fun retentionKeepsLastJobsNewestFirst() {
        val small = JobManager(File(staging, "small"), keep = 3)
        try {
            val ids = (1..6).map { i -> small.submit("k", "job$i") { } }
            await(small, ids.last())
            val deadline = System.currentTimeMillis() + 10_000
            while (small.list().any { it.state == JobState.QUEUED || it.state == JobState.RUNNING } && System.currentTimeMillis() < deadline) Thread.sleep(5)
            val list = small.list()
            assertEquals(3, list.size)
            assertEquals(ids.takeLast(3).reversed(), list.map { it.id })
            assertEquals(null, small.get(ids.first()))
        } finally { small.shutdown() }
    }

    @Test fun runningJobsAreNotEvicted() {
        val small = JobManager(File(staging, "small2"), threads = 1, keep = 2)
        try {
            val block = CountDownLatch(1)
            val first = small.submit("k", "first") { block.await(10, TimeUnit.SECONDS) }
            repeat(4) { small.submit("k", "q") { } }
            assertNotNull(small.get(first))
            block.countDown()
        } finally { small.shutdown() }
    }

    @Test fun stagingDirIsCleanedPerJob() {
        var dir: File? = null
        val id = jobs.submit("k", "t") { ctx ->
            dir = (ctx as StagingJobContext).stagingDir
            File(dir!!, "f.tmp").writeText("x")
            assertTrue(dir!!.exists())
        }
        await(jobs, id)
        assertFalse(dir!!.exists())
    }
}
