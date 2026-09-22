package com.example.llamadroid.harness

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes Android lease publication/cleanup while keeping native Force Stop independent. */
internal class HarnessLifecycleGate {
    private val publication = Mutex()
    private val destructiveMaintenance = Mutex()
    private val starts = ConcurrentHashMap.newKeySet<Job>()
    private val stopping = AtomicInteger()

    suspend fun runStart(action: suspend () -> Unit) = coroutineScope {
        val job = requireNotNull(currentCoroutineContext()[Job])
        starts.add(job)
        try {
            publication.withLock {
                check(stopping.get() == 0) { "HARNESS_CLEANUP_IN_PROGRESS" }
                currentCoroutineContext().ensureActive()
                action()
            }
        } finally { starts.remove(job) }
    }

    suspend fun <T> runStop(terminate: suspend () -> T, cleanup: suspend (T) -> Unit): T {
        stopping.incrementAndGet()
        try {
            starts.toList().forEach { it.cancel(CancellationException("HARNESS_STOP_REQUESTED")) }
            // Never wait for the publication lock before reaching the native supervisor.
            val result = terminate()
            publication.withLock { cleanup(result) }
            return result
        } finally { stopping.decrementAndGet() }
    }

    /** A receipt retry or process-loss reconciliation must not overlap a new publication. */
    suspend fun <T> runMaintenance(action: suspend () -> T): T = publication.withLock { action() }

    /**
     * Runs a destructive environment operation after all published runtime work has been
     * stopped.  The stopping lease is held across the final maintenance lock so a concurrent
     * start cannot publish a new bridge between the stop and the filesystem reset.
     */
    suspend fun <T> runExclusiveDestructive(
        terminate: suspend () -> Unit,
        action: suspend () -> T
    ): T = destructiveMaintenance.withLock {
        stopping.incrementAndGet()
        try {
            starts.toList().forEach { it.cancel(CancellationException("HARNESS_DESTRUCTIVE_MAINTENANCE")) }
            terminate()
            publication.withLock { action() }
        } finally {
            stopping.decrementAndGet()
        }
    }
}
