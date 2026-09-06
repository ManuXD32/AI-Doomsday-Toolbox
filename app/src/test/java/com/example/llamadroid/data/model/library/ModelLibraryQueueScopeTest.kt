package com.example.llamadroid.data.model.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ModelLibraryQueueScopeTest {
    @Test
    fun `duplicate queue key is rejected and cancellation releases the key`() = runBlocking {
        val key = "test-bundle:${UUID.randomUUID()}"
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = ModelLibraryQueueScope.launch(key) {
            entered.complete(Unit)
            release.await()
        }
        assertNotNull(first)
        withTimeout(2_000) { entered.await() }
        assertNull(ModelLibraryQueueScope.launch(key) {})
        assertTrue(ModelLibraryQueueScope.cancel(key))
        withTimeout(2_000) { first!!.join() }
        assertFalse(ModelLibraryQueueScope.cancel(key))

        val completed = CompletableDeferred<Unit>()
        val retry = ModelLibraryQueueScope.launch(key) { completed.complete(Unit) }
        assertNotNull(retry)
        withTimeout(2_000) { completed.await() }
        withTimeout(2_000) { retry!!.join() }
    }
}
