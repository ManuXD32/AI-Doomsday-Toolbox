package com.example.llamadroid.branchy

import android.content.Context
import com.example.llamadroid.data.binary.BinaryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.ArrayDeque

/** Short-lived native calls (`bench`, `plan`). Long jobs run in `BranchyTrainingService`. */
class BranchyNative(private val context: Context) {

    /** Local matmul GFLOP/s measured with [threads] threads. */
    suspend fun bench(threads: Int): Double {
        val event = runOneShot { exe -> BranchyCommandBuilder.bench(exe, threads) }
            .filterIsInstance<BranchyEvent.Bench>().lastOrNull()
            ?: throw IOException("branchy bench produced no result")
        return event.gflops
    }

    /** `bench --sweep`: one [BranchyEvent.BenchPointEvent] per candidate through [onPoint], then the summary. */
    suspend fun benchSweep(
        seconds: Double = SWEEP_SECONDS,
        onPoint: (BranchyEvent.BenchPoint) -> Unit = {}
    ): BranchyEvent.BenchSweep {
        val events = runOneShot(SWEEP_TIMEOUT_MS, { e -> if (e is BranchyEvent.BenchPointEvent) onPoint(e.point) }) { exe ->
            BranchyCommandBuilder.benchSweep(exe, seconds)
        }
        return events.filterIsInstance<BranchyEvent.BenchSweep>().lastOrNull()
            ?: throw IOException("branchy bench --sweep produced no result")
    }

    /** `cluster-bench --apply`: [onDevice] gets each helper as it finishes; returns all of them. */
    suspend fun clusterBench(
        configFile: File,
        tokenFile: File,
        apply: Boolean = true,
        onDevice: (BranchyEvent.BenchDevice) -> Unit = {}
    ): List<BranchyEvent.BenchDevice> =
        runOneShot(CLUSTER_BENCH_TIMEOUT_MS, { e -> if (e is BranchyEvent.BenchDevice) onDevice(e) }) { exe ->
            BranchyCommandBuilder.clusterBench(exe, configFile.absolutePath, tokenFile.absolutePath, apply)
        }.filterIsInstance<BranchyEvent.BenchDevice>()

    suspend fun plan(input: BranchyPlanInput): BranchyPlan {
        val inFile = File(context.cacheDir, "branchy_plan_in.json")
        withContext(Dispatchers.IO) {
            inFile.writeText(BranchyJson.encodePlanInput(input))
        }
        val plan = runOneShot { exe -> BranchyCommandBuilder.plan(exe, inFile.absolutePath) }
            .filterIsInstance<BranchyEvent.Plan>().lastOrNull()?.plan
        return plan ?: throw IOException("branchy plan produced no result")
    }

    /**
     * `consolidate`: pulls the remote checkpoint units of [runDir] (cluster layout [clusterFile], the OLD one) to
     * this phone. [onProgress] sees each unit's byte counts; returns the checkpoint step. A native error (for
     * example an offline helper without a mirror) is thrown as an [IOException] with its message.
     */
    suspend fun consolidate(
        runDir: File,
        clusterFile: File,
        tokenFile: File,
        onProgress: (BranchyEvent.Consolidate) -> Unit = {}
    ): Long {
        val events = runOneShot(CONSOLIDATE_TIMEOUT_MS, { e -> if (e is BranchyEvent.Consolidate) onProgress(e) }) { exe ->
            BranchyCommandBuilder.consolidate(exe, runDir.absolutePath, clusterFile.absolutePath, tokenFile.absolutePath)
        }
        return events.filterIsInstance<BranchyEvent.ConsolidateDone>().lastOrNull()?.step
            ?: throw IOException("branchy consolidate produced no result")
    }

    /**
     * Runs the binary and collects its events. [onEvent] sees each event as it is read. Cancelling the caller kills
     * the process (the blocking read itself is not cancellable).
     */
    private suspend fun runOneShot(
        timeoutMs: Long = TIMEOUT_MS,
        onEvent: (BranchyEvent) -> Unit = {},
        argv: (String) -> List<String>
    ): List<BranchyEvent> = withContext(Dispatchers.IO) {
        val exe = BinaryRepository(context).getTieredBinary("branchy_trainer")
            ?: throw IOException("Branchy trainer binary was not found.")
        val events = mutableListOf<BranchyEvent>()
        val errTail = ArrayDeque<String>()
        val proc = ProcessBuilder(argv(exe.absolutePath)).start()
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) proc.destroyForcibly()
        }
        val errThread = Thread {
            runCatching {
                proc.errorStream.bufferedReader().forEachLine { line ->
                    synchronized(errTail) {
                        errTail.addLast(line)
                        while (errTail.size > 8) errTail.removeFirst()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs)
                timedOut.set(true)
                proc.destroyForcibly()
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }
        try {
            // Reading blocks and is not cancellable; the watchdog kills the process on timeout, which ends the read.
            proc.inputStream.bufferedReader().forEachLine { line ->
                BranchyEventParser.parse(line)?.let { events += it; onEvent(it) }
            }
            val finished = proc.waitFor()
            errThread.join(1_000)
            if (timedOut.get()) throw IOException("branchy timed out")
            events.filterIsInstance<BranchyEvent.Error>().firstOrNull()?.let { throw IOException(it.message) }
            if (finished != 0) {
                val tail = synchronized(errTail) { errTail.joinToString("\n") }
                throw IOException("branchy exited with code $finished${if (tail.isNotBlank()) ": $tail" else ""}")
            }
            events
        } finally {
            watchdog.interrupt()
            cancelHandle?.dispose()
            if (proc.isAlive) proc.destroyForcibly()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 120_000L
        const val SWEEP_SECONDS = 1.5
        const val SWEEP_TIMEOUT_MS = 300_000L
        const val CLUSTER_BENCH_TIMEOUT_MS = 600_000L
        const val CONSOLIDATE_TIMEOUT_MS = 3_600_000L
    }
}
