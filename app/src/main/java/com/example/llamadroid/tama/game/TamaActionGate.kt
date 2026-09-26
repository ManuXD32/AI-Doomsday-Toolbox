package com.example.llamadroid.tama.game

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One mutation lane for phone, watch, offline updates and living-world actions.
 * A coroutine may call another canonical action without acquiring the gate again.
 * Training has no dependency on this package or access to this gate.
 */
object TamaActionGate {
    private val mutex = Mutex()
    private class Lease : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Lease>
    }

    suspend fun <T> run(block: suspend () -> T): T {
        if (currentCoroutineContext()[Lease] != null) return block()
        return mutex.withLock { withContext(Lease()) { block() } }
    }
}
