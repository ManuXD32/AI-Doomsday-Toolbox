package com.example.llamadroid.harness

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide read/write style lock shared by every per-runtime gate. Per-runtime start, stop
 * and maintenance hold it shared (so runtimes never block each other); a whole-database backup or
 * restore holds it exclusively. Shared entry is reentrant-safe: it only waits for an *active*
 * exclusive holder, never for a merely waiting one, so a nested shared section inside an outer
 * shared section cannot deadlock against a queued exclusive request.
 */
internal class HarnessGlobalLock {
    private data class State(val readers: Int = 0, val exclusive: Boolean = false)

    private val state = MutableStateFlow(State())
    private val exclusiveQueue = Mutex()

    suspend fun <T> shared(action: suspend () -> T): T {
        while (true) {
            val current = state.value
            if (current.exclusive) { state.first { !it.exclusive }; continue }
            if (state.compareAndSet(current, current.copy(readers = current.readers + 1))) break
        }
        return try { action() } finally {
            while (true) {
                val current = state.value
                if (state.compareAndSet(current, current.copy(readers = current.readers - 1))) break
            }
        }
    }

    suspend fun <T> exclusive(action: suspend () -> T): T = exclusiveQueue.withLock {
        while (true) {
            state.first { it.readers == 0 && !it.exclusive }
            if (state.compareAndSet(State(0, false), State(0, true))) break
        }
        try { action() } finally { state.value = State(state.value.readers, false) }
    }
}

/**
 * Serializes Android lease publication/cleanup for ONE runtime while keeping native Force Stop
 * independent. Stopping runtime A never cancels runtime B's start because each runtime owns its
 * own gate; the shared [globalLock] only serializes against whole-database operations.
 */
internal class HarnessLifecycleGate(private val globalLock: HarnessGlobalLock = HarnessGlobalLock()) {
    private val publication = Mutex()
    private val destructiveMaintenance = Mutex()
    private val starts = ConcurrentHashMap.newKeySet<Job>()
    private val stopping = AtomicInteger()

    suspend fun <T> runStart(action: suspend () -> T): T = globalLock.shared {
        coroutineScope {
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
    }

    suspend fun <T> runStop(terminate: suspend () -> T, cleanup: suspend (T) -> Unit): T = globalLock.shared {
        stopping.incrementAndGet()
        try {
            starts.toList().forEach { it.cancel(CancellationException("HARNESS_STOP_REQUESTED")) }
            // Never wait for the publication lock before reaching the native supervisor.
            val result = terminate()
            publication.withLock { cleanup(result) }
            result
        } finally { stopping.decrementAndGet() }
    }

    /** A receipt retry or process-loss reconciliation must not overlap a new publication. */
    suspend fun <T> runMaintenance(action: suspend () -> T): T =
        globalLock.shared { publication.withLock { action() } }

    /**
     * Runs a destructive environment operation after all published runtime work has been
     * stopped.  The stopping lease is held across the final maintenance lock so a concurrent
     * start cannot publish a new bridge between the stop and the filesystem reset.
     */
    suspend fun <T> runExclusiveDestructive(
        terminate: suspend () -> Unit,
        action: suspend () -> T
    ): T = globalLock.shared {
        destructiveMaintenance.withLock {
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
}
