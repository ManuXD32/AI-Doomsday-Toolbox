package com.example.llamadroid.harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessLifecycleGateTest {
    @Test fun forceReachesSupervisorBeforeWaitingForStartupTeardown() = runBlocking {
        val gate = HarnessLifecycleGate()
        val entered = CompletableDeferred<Unit>()
        val releaseStartup = CompletableDeferred<Unit>()
        val terminated = CompletableDeferred<Unit>()
        val start = async {
            gate.runStart {
                entered.complete(Unit)
                try { CompletableDeferred<Unit>().await() }
                finally { withContext(NonCancellable) { releaseStartup.await() } }
            }
        }
        entered.await()
        val stop = async { gate.runStop({ terminated.complete(Unit) }) {} }
        withTimeout(2_000) { terminated.await() }
        assertFalse(stop.isCompleted)
        releaseStartup.complete(Unit)
        withTimeout(2_000) { stop.await(); start.join() }
        assertTrue(start.isCancelled)
    }

    @Test fun aNewGenerationCannotPublishWhileOldAndroidCleanupIsActive() = runBlocking {
        val gate = HarnessLifecycleGate()
        val cleaning = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val published = CompletableDeferred<Unit>()
        val stop = async { gate.runStop({ Unit }) { cleaning.complete(Unit); releaseCleanup.await() } }
        cleaning.await()
        val start = async { gate.runStart { published.complete(Unit) } }
        delay(30)
        assertFalse(published.isCompleted)
        releaseCleanup.complete(Unit)
        withTimeout(2_000) { stop.await(); start.await() }
        assertTrue(published.isCompleted)
    }

    @Test fun forceStillTerminatesWhileGracefulAndroidCleanupIsWaiting(): Unit = runBlocking {
        val gate = HarnessLifecycleGate()
        val cleaning = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val forced = CompletableDeferred<Unit>()
        val stop = async { gate.runStop({ Unit }) { cleaning.complete(Unit); releaseCleanup.await() } }
        cleaning.await()
        val force = async { gate.runStop({ forced.complete(Unit) }) {} }
        withTimeout(2_000) { forced.await() }
        assertFalse(force.isCompleted)
        releaseCleanup.complete(Unit)
        withTimeout(2_000) { stop.await(); force.await() }
    }

    @Test fun destructiveMaintenanceQuiescesExternalProcessesBeforePublishingReset() = runBlocking {
        val gate = HarnessLifecycleGate()
        val events = mutableListOf<String>()
        val result = gate.runExclusiveDestructive(
            terminate = { events += "quiesce"; events += "terminate" },
            action = { events += "reset"; "done" },
        )
        assertEquals("done", result)
        assertEquals(listOf("quiesce", "terminate", "reset"), events)
    }

    @Test fun stoppingOneRuntimeDoesNotCancelAnotherRuntimesStart() = runBlocking {
        val global = HarnessGlobalLock()
        val a = HarnessLifecycleGate(global)
        val b = HarnessLifecycleGate(global)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val startB = async { b.runStart { entered.complete(Unit); finish.await(); "b" } }
        entered.await()
        a.runStop({ Unit }) {}
        assertFalse(startB.isCancelled)
        finish.complete(Unit)
        assertEquals("b", withTimeout(2_000) { startB.await() })
    }

    @Test fun oneRuntimesStartDoesNotBlockAnotherRuntimesMaintenance() = runBlocking {
        val global = HarnessGlobalLock()
        val a = HarnessLifecycleGate(global)
        val b = HarnessLifecycleGate(global)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val startB = async { b.runStart { entered.complete(Unit); finish.await() } }
        entered.await()
        assertEquals("files", withTimeout(2_000) { a.runMaintenance { "files" } })
        finish.complete(Unit)
        startB.await()
    }

    @Test fun globalExclusiveWaitsForEveryRuntimeAndBlocksNewOnes() = runBlocking {
        val global = HarnessGlobalLock()
        val a = HarnessLifecycleGate(global)
        val b = HarnessLifecycleGate(global)
        val inA = CompletableDeferred<Unit>()
        val inB = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        val ranExclusive = CompletableDeferred<Unit>()
        val startA = async { a.runStart { inA.complete(Unit); releaseA.await() } }
        val startB = async { b.runStart { inB.complete(Unit); releaseB.await() } }
        inA.await(); inB.await()
        val exclusive = async { global.exclusive { ranExclusive.complete(Unit) } }
        delay(30)
        assertFalse(ranExclusive.isCompleted)
        releaseA.complete(Unit); startA.await()
        delay(30)
        assertFalse(ranExclusive.isCompleted)
        releaseB.complete(Unit); startB.await()
        withTimeout(2_000) { exclusive.await() }
        assertTrue(ranExclusive.isCompleted)
        // After the exclusive section, runtimes work again.
        assertEquals(1, withTimeout(2_000) { a.runMaintenance { 1 } })
    }
}
