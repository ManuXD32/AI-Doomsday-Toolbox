package com.example.llamadroid.ui.agent.harness

import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessRpcResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class HarnessAttentionStoreTest {
    @Test fun questionsStayWithTheirSessionAndShareOneWaterfallReply() = runBlocking {
        val frames = Channel<JsonElement>(Channel.UNLIMITED)
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = mockk<HarnessClient>()
        every { client.remoteEvents(any()) } returns frames.receiveAsFlow()
        var replies = 0
        coEvery { client.call("\$events", "result", any(), any(), any()) } answers { replies++; HarnessRpcResult.Success(buildJsonObject {}) }
        val store = NativeHarnessAttentionStore(owner, { client })
        try {
            store.attach(client)
            frames.send(Json.parseToJsonElement("""{"type":"ready","clientId":"native-1"}"""))
            frames.send(Json.parseToJsonElement("""{"type":"waterfall","agentId":"session-a","eventId":"q1","event":"user-questions/request","request":{"questions":[{"id":"one","question":"A?","options":[{"label":"Yes"}]},{"id":"two","question":"B?","options":[{"label":"No"}]}]}}"""))
            withTimeout(2_000) { while (store.entries.value.size != 2) yield() }
            assertTrue(store.answer("session-b", "q1:one", "Yes", false, emptyList()) is HarnessRpcResult.Failure)
            assertEquals(0, replies)
            assertTrue(store.answer("session-a", "q1:one", "Yes", false, emptyList()) is HarnessRpcResult.Success)
            assertEquals(1, store.entries.value.size)
            assertEquals(0, replies)
            assertTrue(store.answer("session-a", "q1:two", "No", false, emptyList()) is HarnessRpcResult.Success)
            assertEquals(1, replies)
            assertTrue(store.entries.value.isEmpty())
        } finally { store.attach(null); owner.cancel(); frames.close() }
    }

    @Test fun modelSelectionRestoresTheWireIdAndDefaultEffort() {
        val previous = HarnessProviderUiState(selectedProviderId = "other", selectedModel = "other-model", selectedReasoningEffort = "high")
        val next = previous.withSessionSelection(Json.parseToJsonElement("""{"provider":"llama-swap","model":"/models/Qwen.gguf"}"""))
        assertEquals("llama-swap", next.selectedProviderId)
        assertEquals("/models/Qwen.gguf", next.selectedModel)
        assertNull(next.selectedReasoningEffort)
    }
}
