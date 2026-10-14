package com.example.llamadroid.harness

import com.example.llamadroid.ui.agent.harness.NativeHarnessCustomProviderModel
import com.example.llamadroid.ui.agent.harness.NativeHarnessCustomProviderRequest
import com.example.llamadroid.ui.agent.harness.buildNativeCustomProviderProfile
import com.example.llamadroid.ui.agent.harness.harnessTextFrom
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HarnessHeartbeatAndLabelsTest {
    @Before
    fun reset() = HarnessModelLabels.clearForTest()

    @Test
    fun heartbeatFrameIsMetadataOnlyWaitingActivity() {
        val frame = harnessWaitingHeartbeatChunk("c1", "llama:3", "req", "sess", "att")
        assertEquals(0, frame.getJSONArray("choices").length())
        val adt = frame.getJSONObject("adt")
        assertEquals("waiting", adt.getString("type"))
        assertEquals("req", adt.getString("requestId"))
        assertEquals("sess", adt.getString("sessionId"))
        assertEquals("att", adt.getString("attemptId"))
        val noSession = harnessWaitingHeartbeatChunk("c1", "m", "req", null, "att").getJSONObject("adt")
        assertFalse(noSession.has("sessionId"))
    }

    @Test
    fun modelLabelKeepsRawIdAndLeavesOtherValues() {
        HarnessModelLabels.register("llama:3", "Gemma4 E4B")
        assertEquals("Gemma4 E4B (llama:3)", HarnessModelLabels.display("llama:3"))
        assertEquals("llama:9", HarnessModelLabels.display("llama:9"))
        assertEquals("gpt-5", HarnessModelLabels.display("gpt-5"))
    }

    @Test
    fun transcriptDetailLineShowsModelName() {
        HarnessModelLabels.register("llama:3", "Gemma4 E4B")
        val data: JsonObject = buildJsonObject {
            put("provider", "adt-llama")
            put("model", "llama:3")
            put("contextWindow", JsonPrimitive(8192))
        }
        val text = harnessTextFrom(data)
        assertTrue(text, text.contains("model: Gemma4 E4B (llama:3)"))
    }

    @Test
    fun customProfileCarriesIdleTimeoutOnlyWhenRequested() {
        val base = NativeHarnessCustomProviderRequest(
            route = "adt-chat-srv-1", api = "openai-completions", baseUrl = "http://127.0.0.1:8080/v1",
            models = listOf(NativeHarnessCustomProviderModel(id = "m")),
        )
        assertFalse(buildNativeCustomProviderProfile(base).containsKey("streamIdleTimeoutMs"))
        val withTimeout = buildNativeCustomProviderProfile(base.copy(streamIdleTimeoutMs = 2147483647L))
        assertEquals("2147483647", withTimeout["streamIdleTimeoutMs"].toString())
    }
}
