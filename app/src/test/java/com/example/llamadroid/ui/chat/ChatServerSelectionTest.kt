package com.example.llamadroid.ui.chat

import com.example.llamadroid.ui.ai.llama.RunningLlamaChatServerUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChatServerSelectionTest {
    @Test
    fun explicitRoutePortOverrideWinsOnFreshEntry() {
        assertEquals(
            9123,
            resolveChatServerPort(
                routePortOverride = 9123,
                savedSelectedPort = null,
                configuredPort = 8080
            )
        )
    }

    @Test
    fun savedSelectionWinsAfterUserChoosesAnotherServer() {
        assertEquals(
            9234,
            resolveChatServerPort(
                routePortOverride = 9123,
                savedSelectedPort = 9234,
                configuredPort = 8080
            )
        )
    }

    @Test
    fun staleSelectedPortIsKeptWhenItsServerStops() {
        val stalePort = 9345
        val runningServers = listOf(
            RunningLlamaChatServerUi(sessionId = "other", name = "Other", port = 9456)
        )

        assertEquals(
            stalePort,
            resolveChatServerPort(
                routePortOverride = null,
                savedSelectedPort = stalePort,
                configuredPort = 8080
            )
        )
        assertNull(runningChatServerForPort(stalePort, runningServers))
    }

    @Test
    fun matchingRunningServerIsResolvedWithoutChangingItsIdentity() {
        val expected = RunningLlamaChatServerUi(sessionId = "main", name = "Main model", port = 9567)

        assertSame(expected, runningChatServerForPort(expected.port, listOf(expected)))
    }
}
