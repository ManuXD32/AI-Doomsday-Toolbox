package com.example.llamadroid.harness

import com.example.llamadroid.data.db.AgentConversationEntity
import com.example.llamadroid.data.db.HarnessSessionEntity
import com.example.llamadroid.data.db.HarnessWorkspaceEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessWorkspaceActionsTest {
    @Test fun guestPathsRemainWithinTheCapturedSessionWorkspace() {
        assertEquals("out/a.txt", relativeWorkspaceActionPath("/workspace/projects/a", "/workspace/projects/a/out/a.txt"))
        assertEquals(".", relativeWorkspaceActionPath("/workspace/projects/a", "/workspace/projects/a"))
        for (path in listOf("/workspace/projects/b/f", "/workspace/projects/ab/f", "../secret", "out/../../secret", "/tmp/file")) {
            assertThrows(IllegalArgumentException::class.java) { relativeWorkspaceActionPath("/workspace/projects/a", path) }
        }
    }

    @Test fun openWaitsForUiAcceptanceAndForceCancellationInterruptsPendingActions() = runBlocking {
        val actions = HarnessWorkspaceActions()
        val captured = HarnessSessionScope(HarnessSessionEntity("session-a", 1, "a"),
            HarnessWorkspaceEntity("a", "LOCAL_PROOT", "a", title = "A", guestPath = "/workspace/projects/a"),
            AgentConversationEntity(id = 1, title = "A", projectFolder = "a"), null)
        val request = CompletableDeferred<HarnessWorkspaceActions.Request>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { actions.requests.collect { request.complete(it) } }
        try {
            val opening = async { actions.open(captured, "out/a.txt", reveal = true, directory = false) }
            val accepted = withTimeout(1000) { request.await() }
            assertEquals("session-a", accepted.scope.session.harnessSessionId)
            assertTrue(!opening.isCompleted)
            actions.cancelPending()
            withTimeout(1000) { opening.join() }
            assertTrue(opening.isCancelled)
        } finally { collector.cancelAndJoin() }
    }
}
