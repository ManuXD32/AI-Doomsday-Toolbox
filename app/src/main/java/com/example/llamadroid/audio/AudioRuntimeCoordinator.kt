package com.example.llamadroid.audio

import android.content.Context
import com.example.llamadroid.R
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Performs the process-start recovery exactly once.
 *
 * Room rows outlive the process that was running a synthesis. A repository
 * observer can therefore be the first audio entry point after a cold start;
 * recovery must not depend on the foreground service being restarted. The
 * mutex also prevents an observer, an enqueue, and the service coordinator
 * from racing one another during that first initialization.
 */
internal object AudioRuntimeCoordinator {
    private val initializationMutex = Mutex()
    @Volatile private var initialized = false

    suspend fun initialize(context: Context, dao: AudioDao): Int {
        if (initialized) return 0
        return initializationMutex.withLock {
            if (initialized) {
                0
            } else {
                val changed = dao.markRecoverableJobsInterrupted(
                    context.applicationContext.getString(R.string.audio_runtime_status_interrupted)
                )
                initialized = true
                changed
            }
        }
    }

    /** Test-only reset; production callers should never repeat startup recovery. */
    internal suspend fun resetForTests() {
        initializationMutex.withLock { initialized = false }
    }
}
