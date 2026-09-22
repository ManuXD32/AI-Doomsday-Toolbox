package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeHarnessStreamingTest {
    @Test
    fun compactAssistantBaselineKeepsTextAndReasoningOnly() {
        val stream = listOf(
            buildJsonObject {
                put("type", "text-chunks")
                putJsonArray("texts") { add(JsonPrimitive("Hello ")); add(JsonPrimitive("world")) }
            },
            buildJsonObject {
                put("type", "reasoning-chunks")
                putJsonArray("texts") { add(JsonPrimitive("checking")) }
            },
            buildJsonObject {
                put("type", "tool-call-chunks")
                put("name", "terminal")
                putJsonArray("args") { add(JsonPrimitive("{\"command\":\"pwd\"}")) }
            }
        )

        assertEquals("Hello worldchecking", harnessCompactedStreamText(stream))
    }

    @Test
    fun assistantStreamTrackerRequiresDenseRevisionAndIndex() {
        val tracker = HarnessAssistantStreamTracker()
        tracker.reset(buildJsonObject {
            put("revision", 4)
            putJsonObject("activeAttempt") {
                put("attemptId", "attempt-1")
                put("nextIndex", 2)
            }
        })

        assertEquals(
            HarnessAssistantFrameResult.CHUNK,
            tracker.accept(buildJsonObject {
                put("type", "chunk")
                put("attemptId", "attempt-1")
                put("revision", 5)
                put("index", 2)
            })
        )
        assertEquals(
            HarnessAssistantFrameResult.INVALID,
            tracker.accept(buildJsonObject {
                put("type", "chunk")
                put("attemptId", "attempt-1")
                put("revision", 7)
                put("index", 3)
            })
        )
    }

    @Test
    fun liveChunkTextPreservesWhitespaceAndDoesNotRepeatBlockEnd() {
        assertEquals(
            " ",
            harnessAssistantChunkText(buildJsonObject {
                put("type", "text-delta")
                put("text", " ")
            })
        )
        assertEquals(
            "",
            harnessAssistantChunkText(buildJsonObject {
                put("type", "block-end")
                putJsonObject("block") {
                    put("type", "text")
                    put("text", "already streamed")
                }
            })
        )
    }
}
