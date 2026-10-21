package com.example.llamadroid.service.delegation

import java.util.concurrent.ConcurrentHashMap

/**
 * Generation guard for task loops: every launch registers its own instance (compared by identity),
 * and a loop that ends may only clean up when its instance is still the registered one. A loop that
 * was replaced (reopen, restart) must not drop the new loop's registration or mark the task INTERRUPTED.
 */
internal object DelegationRunGuard {
    /** True (and unregisters) only when [self] is still the instance registered for [taskId]. */
    fun <T : Any> endIfCurrent(running: ConcurrentHashMap<Long, T>, taskId: Long, self: T?): Boolean =
        self != null && running[taskId] === self && running.remove(taskId, self)
}
