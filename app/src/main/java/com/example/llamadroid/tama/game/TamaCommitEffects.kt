package com.example.llamadroid.tama.game

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** External notifications run once after a successful living transaction, never after rollback. */
internal object TamaCommitEffects {
    private class Pending : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Pending>
        val effects = linkedMapOf<String, suspend () -> Unit>()
    }

    suspend fun deferOrRun(key: String, effect: suspend () -> Unit) {
        val pending = currentCoroutineContext()[Pending]
        if (pending == null) effect() else pending.effects[key] = effect
    }

    suspend fun <T> afterCommit(transaction: suspend () -> T): T {
        if (currentCoroutineContext()[Pending] != null) return transaction()
        val pending = Pending()
        val result = withContext(pending) { transaction() }
        pending.effects.values.forEach { effect ->
            try {
                effect()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The data is committed. The normal notification refresh retries external delivery.
            }
        }
        return result
    }
}
