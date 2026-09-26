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
}
