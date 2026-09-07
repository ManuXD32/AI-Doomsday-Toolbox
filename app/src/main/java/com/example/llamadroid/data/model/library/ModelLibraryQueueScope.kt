package com.example.llamadroid.data.model.library

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Process-owned scope for model-library download queues.
 *
 * A screen ViewModel can disappear while a foreground download is still
 * staging an artifact. Keeping the queue here gives the coordinator an owner
 * that outlives the screen; durable pending rows and application startup
 * recovery still cover a process death.
 */
object ModelLibraryQueueScope {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val active = mutableMapOf<String, Job>()

    /** Starts one operation per key; duplicate taps reuse the existing run. */
    fun launch(key: String, operation: suspend () -> Unit): Job? {
        val normalizedKey = key.trim().ifBlank { return null }
        synchronized(lock) {
            // Cancellation is asynchronous. Keep the key occupied until the
            // old coroutine has actually completed so a retry cannot overlap
            // its late finalizer/cleanup callbacks.
            active[normalizedKey]?.takeIf { !it.isCompleted }?.let { return null }
            lateinit var job: Job
            job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    operation()
                } finally {
                    synchronized(lock) {
                        if (active[normalizedKey] === job) active.remove(normalizedKey)
                    }
                }
            }
            active[normalizedKey] = job
            job.start()
            return job
        }
    }

    /** Cancels a screen-independent queue before its next sequential item starts. */
    fun cancel(key: String): Boolean {
        val normalizedKey = key.trim().ifBlank { return false }
        // Keep the cancelled job in the map until its finally block runs. A
        // new tap cannot race the old coroutine between remove() and cancel().
        val job = synchronized(lock) { active[normalizedKey] }
        job?.cancel()
        return job != null
    }
}
