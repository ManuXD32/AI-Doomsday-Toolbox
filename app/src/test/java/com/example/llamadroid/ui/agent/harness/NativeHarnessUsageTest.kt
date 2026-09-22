package com.example.llamadroid.ui.agent.harness

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NativeHarnessUsageTest {
    @Test
    fun parsesSessionTokenUsageProjectionBuckets() {
        val usage = harnessParseTokenUsageProjection(buildJsonObject {
            put("uncachedInputTokens", 120)
            put("outputTokens", 30)
            put("cacheReadTokens", 40)
            put("cacheWriteTokens", 5)
        })

        assertNotNull(usage)
        assertEquals(195L, usage?.total)
        assertEquals(165L, usage?.billedInputTokens)
        assertEquals(true, usage?.cacheMetricsKnown)
        assertEquals(25, usage?.cacheHitPercent)
    }

    @Test
    fun parsesTurnUsageIncludingReasoningAndKeepsEmptyAssistantUsage() {
        val item = harnessParseEvent(buildJsonObject {
            put("type", "assistant/message")
            put("seq", 8)
            putJsonObject("data") {
                putJsonObject("message") {
                    put("id", "message-8")
                    put("content", "")
                }
                putJsonObject("usage") {
                    put("inputTokens", 20)
                    put("outputTokens", 12)
                    put("totalTokens", 32)
                    put("reasoningTokens", 4)
                }
            }
        })

        assertNotNull(item)
        assertEquals("message-8", item?.messageId)
        assertEquals(32L, item?.usage?.total)
        assertEquals(4L, item?.usage?.reasoningTokens)
    }

    @Test
    fun parsesContextPressureBreakdownAndWholeSessionStats() {
        val usage = harnessParseUsageProjections(buildJsonObject {
            putJsonObject("tokenUsage") {
                put("uncachedInputTokens", 120)
                put("outputTokens", 30)
                put("cacheReadTokens", 40)
                put("cacheWriteTokens", 5)
            }
            putJsonObject("contextPressure") {
                put("pressureTokens", 104)
                put("projectedTokens", 122)
                put("contextWindow", 128000)
            }
            putJsonObject("contextBreakdown") {
                put("systemTokens", 17)
                put("toolsTokens", 8)
                put("messageTokens", 4427)
            }
            putJsonObject("sessionStats") {
                put("turns", 75)
                put("steps", 87)
                put("llmMs", 69600)
                put("toolMs", 24800)
                put("ttftMs", 1200)
                put("ttftSteps", 4)
                put("decodeMs", 1800)
                put("decodeTokens", 708)
            }
        })

        assertEquals(122L, usage?.contextPressure?.projectedTokens)
        assertEquals(0, usage?.contextPressure?.occupancyPercent)
        assertEquals(4427L, usage?.contextBreakdown?.messageTokens)
        assertEquals(75L, usage?.sessionStats?.turns)
        assertEquals(708L, usage?.sessionStats?.decodeTokens)
        assertEquals(50, usage?.contextPressure?.copy(projectedTokens = 50, contextWindow = 100)?.occupancyPercent)
        assertEquals(25, usage?.cacheHitPercent)
        assertEquals(393.3333333333333, usage?.tokensPerSecond ?: 0.0, 0.000001)
    }

    @Test
    fun liveTokenProjectionKeepsOtherUsageProjections() {
        val state = NativeHarnessUiState(
            contextPressure = HarnessContextPressureUi(contextWindow = 128_000),
            contextBreakdown = HarnessContextBreakdownUi(1, 2, 3),
            sessionStats = HarnessSessionStatsUi(4, 5, 6, 7, 8, 9, 10, 11)
        )

        val updated = state.withHarnessProjection(
            "tokenUsage",
            buildJsonObject {
                put("uncachedInputTokens", 20)
                put("outputTokens", 10)
            }
        )

        assertEquals(20L, updated.tokenUsage?.inputTokens)
        assertEquals(128_000L, updated.contextPressure?.contextWindow)
        assertEquals(3L, updated.contextBreakdown?.messageTokens)
        assertEquals(4L, updated.sessionStats?.turns)
    }
}
