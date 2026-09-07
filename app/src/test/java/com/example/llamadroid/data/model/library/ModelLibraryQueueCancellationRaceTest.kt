package com.example.llamadroid.data.model.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ModelLibraryQueueCancellationRaceTest {
    @Test
    fun `retry remains blocked until cancelled queue cleanup completes`() = runBlocking {
        val key = "cancel-race:${UUID.randomUUID()}"
        val entered = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val first = ModelLibraryQueueScope.launch(key) {
            try {
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                }
            }
        }

        assertNotNull(first)
        withTimeout(2_000) { entered.await() }
        assertTrue(ModelLibraryQueueScope.cancel(key))
        withTimeout(2_000) { cleanupStarted.await() }
        assertNull(ModelLibraryQueueScope.launch(key) {})

        releaseCleanup.complete(Unit)
        withTimeout(2_000) { first!!.join() }
        assertNotNull(ModelLibraryQueueScope.launch(key) {})
    }
}
