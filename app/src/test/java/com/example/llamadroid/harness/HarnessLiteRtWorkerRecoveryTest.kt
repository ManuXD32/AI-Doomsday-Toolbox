package com.example.llamadroid.harness

import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.service.LITERT_PARAM_MAX_OUTPUT_TOKENS
import com.example.llamadroid.service.LITERT_PARAM_MTP_ENABLED
import com.example.llamadroid.service.LiteRtConversationMessage
import com.example.llamadroid.service.LiteRtConversationOverride
import com.example.llamadroid.service.LiteRtLmChatRequest
import com.example.llamadroid.service.LiteRtLmWorkerCrashedException
import com.example.llamadroid.service.LiteRtPromptOverLimitException
import com.example.llamadroid.service.LiteRtToolDefinition
import com.example.llamadroid.service.estimateLiteRtPromptTokens
import com.example.llamadroid.service.liteRtPromptContextBudget
import com.example.llamadroid.service.renderLiteRtPromptForEstimate
import com.example.llamadroid.service.toLiteRtOpenApiToolJson
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessLiteRtWorkerRecoveryTest {
    @Test
    fun boundedRetryPreservesConversationAndAllToolsWhileChangingOnlyRuntimeLimits() {
        val original = request()

        val retry = requireNotNull(boundedLiteRtWorkerRecoveryRequest(original))

        assertEquals(HARNESS_LITERT_RECOVERY_CONTEXT_TOKENS, retry.chat.contextSize)
        assertEquals(LITERT_BACKEND_CPU, retry.backendMode)
        assertEquals(HARNESS_LITERT_RECOVERY_OUTPUT_TOKENS, retry.params[LITERT_PARAM_MAX_OUTPUT_TOKENS])
        assertEquals(false, retry.params[LITERT_PARAM_MTP_ENABLED])
        assertEquals(original.params["enable_thinking"], retry.params["enable_thinking"])
        assertEquals(original.params["custom_setting"], retry.params["custom_setting"])
        assertEquals(original.params[LITERT_PARAM_MAX_OUTPUT_TOKENS], 8_096)
        assertEquals(original.params[LITERT_PARAM_MTP_ENABLED], true)
        assertEquals(original.history, retry.history)
        assertEquals(original.promptOverride, retry.promptOverride)
        assertEquals(original.conversationOverride, retry.conversationOverride)
        assertEquals(51, retry.conversationOverride?.tools?.size)
    }

    @Test
    fun safeCpuRequestDoesNotCreateAnIdenticalRetry() {
        val original = request().copy(
            chat = request().chat.copy(contextSize = 16_384),
            backendMode = LITERT_BACKEND_CPU,
            params = request().params + mapOf(
                LITERT_PARAM_MAX_OUTPUT_TOKENS to 2_048,
                LITERT_PARAM_MTP_ENABLED to false,
            ),
        )

        assertNull(boundedLiteRtWorkerRecoveryRequest(original))
    }

    @Test
    fun crashBeforeVisibleOutputRetriesExactlyOnceAndUsesTheBoundedRequest() = runBlocking {
        val original = request()
        val attempts = mutableListOf<LiteRtLmChatRequest>()
        var retries = 0

        val result = runLiteRtWorkerWithOneBoundedRecovery(
            request = original,
            hasVisibleOutput = { false },
            onRetry = { retry ->
                retries += 1
                assertEquals(HARNESS_LITERT_RECOVERY_CONTEXT_TOKENS, retry.chat.contextSize)
                assertEquals(HARNESS_LITERT_RECOVERY_OUTPUT_TOKENS, retry.params[LITERT_PARAM_MAX_OUTPUT_TOKENS])
                assertEquals(false, retry.params[LITERT_PARAM_MTP_ENABLED])
            },
            execute = { attempt ->
                attempts += attempt
                if (attempts.size == 1) throw IllegalStateException("wrapped", workerCrash())
                "recovered"
            },
        )

        assertEquals("recovered", result)
        assertEquals(1, retries)
        assertEquals(2, attempts.size)
        assertEquals(51, attempts.last().conversationOverride?.tools?.size)
    }

    @Test
    fun crashAfterVisibleTextDoesNotRetry() = runBlocking {
        val attempts = mutableListOf<LiteRtLmChatRequest>()
        var visibleOutput = false
        var retries = 0

        val failure = runCatching {
            runLiteRtWorkerWithOneBoundedRecovery(
                request = request(),
                hasVisibleOutput = { visibleOutput },
                onRetry = { retries += 1 },
                execute = { attempt ->
                    attempts += attempt
                    visibleOutput = true
                    throw workerCrash()
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is LiteRtLmWorkerCrashedException)
        assertEquals(1, attempts.size)
        assertEquals(0, retries)
    }

    @Test
    fun retryFailureIsReturnedWithoutAnotherAttemptAndPromptOverLimitStaysActionable() = runBlocking {
        val attempts = mutableListOf<LiteRtLmChatRequest>()
        val promptError = LiteRtPromptOverLimitException(requiredInputTokens = 15_000, availableInputTokens = 14_000)

        val failure = runCatching {
            runLiteRtWorkerWithOneBoundedRecovery(
                request = request(),
                hasVisibleOutput = { false },
                onRetry = {},
                execute = { attempt ->
                    attempts += attempt
                    if (attempts.size == 1) throw workerCrash()
                    throw promptError
                },
            )
        }.exceptionOrNull()

        assertSame(promptError, failure)
        assertEquals(2, attempts.size)
    }

    @Test
    fun modelsWithoutCpuSupportDoNotClaimACpuRecovery() {
        val original = request().copy(model = request().model.copy(supportsCpu = false))

        assertNull(boundedLiteRtWorkerRecoveryRequest(original))
    }

    @Test
    fun largeToolSetUsesDescriptionCompactionBeforeRejectingTheBoundedRetry() {
        val tools = List(52) { index ->
            LiteRtToolDefinition(
                name = "tool_$index",
                description = "Tool $index: ${"long operational guidance ".repeat(5)}",
                parameters = mapOf("query" to "Query text"),
                requiredParams = listOf("query"),
                parameterSchemaJson = """{"type":"object","properties":{"query":{"type":"string","description":"${"query guidance ".repeat(16)}"}},"required":["query"]}""",
            )
        }
        val conversation = LiteRtConversationOverride(
            systemInstruction = "Use enabled tools and return concise results. ".repeat(148).take(6_803),
            initialMessages = emptyList(),
            userMessage = "Continue with the requested operation.",
            tools = tools,
        )
        val original = request().copy(
            chat = request().chat.copy(contextSize = 32_768),
            conversationOverride = conversation,
        )

        assertTrue(
            estimateLiteRtPromptTokens(renderLiteRtPromptForEstimate(conversation)) >
                liteRtPromptContextBudget(HARNESS_LITERT_RECOVERY_CONTEXT_TOKENS, HARNESS_LITERT_RECOVERY_OUTPUT_TOKENS)
        )

        val retry = requireNotNull(boundedLiteRtWorkerRecoveryRequest(original))
        assertEquals(HARNESS_LITERT_RECOVERY_CONTEXT_TOKENS, retry.chat.contextSize)
        assertEquals(false, retry.params[LITERT_PARAM_MTP_ENABLED])
        assertEquals(52, retry.conversationOverride?.tools?.size)
        assertEquals(tools.map { it.name }, retry.conversationOverride?.tools?.map { it.name })

        val compactSchema = JSONObject(
            retry.conversationOverride!!.tools.first().toLiteRtOpenApiToolJson()
        ).getJSONObject("parameters")
        assertTrue(compactSchema.getJSONObject("properties").getJSONObject("query").has("type"))
        assertFalse(compactSchema.getJSONObject("properties").getJSONObject("query").has("description"))
        assertEquals(listOf("query"), compactSchema.getJSONArray("required").let { array ->
            (0 until array.length()).map { array.getString(it) }
        })
        retry.conversationOverride!!.tools.forEach { tool ->
            val schema = JSONObject(tool.toLiteRtOpenApiToolJson()).getJSONObject("parameters")
            assertEquals("string", schema.getJSONObject("properties").getJSONObject("query").getString("type"))
            assertEquals(listOf("query"), schema.getJSONArray("required").let { array ->
                (0 until array.length()).map { array.getString(it) }
            })
        }
        preflightLiteRtHarnessRequest(retry)
    }

    @Test
    fun boundedRecoveryNeverReturnsToTheCrashedLargeContextForAnImpossiblePrompt() {
        val original = request().copy(
            conversationOverride = request().conversationOverride!!.copy(
                systemInstruction = "essential ".repeat(24_000),
                tools = emptyList(),
            ),
        )

        val retry = requireNotNull(boundedLiteRtWorkerRecoveryRequest(original))
        assertEquals(HARNESS_LITERT_RECOVERY_CONTEXT_TOKENS, retry.chat.contextSize)
        val failure = runCatching { preflightLiteRtHarnessRequest(retry) }.exceptionOrNull()
        assertTrue(failure is LiteRtPromptOverLimitException)
    }

    @Test
    fun requestConfigurationChangesAfterAUserRuntimeChange() {
        val original = harnessLiteRtRequestConfiguration(request())
        val changed = harnessLiteRtRequestConfiguration(
            request().copy(
                chat = request().chat.copy(contextSize = 16_384),
                params = request().params + mapOf(LITERT_PARAM_MTP_ENABLED to false),
            )
        )
        val savedOutputChanged = harnessLiteRtRequestConfiguration(
            request(),
            stableOutputTokens = 4_096,
        )

        assertFalse(original == changed)
        assertFalse(original == savedOutputChanged)
    }

    @Test
    fun degradedModeLatchAppliesToTheSameModelAndClearsOnConfigurationChange() {
        val latch = HarnessLiteRtDegradedModeLatch()
        val original = request()
        latch.recordWorkerCrash(42L, harnessLiteRtRequestConfiguration(original))

        val sameConfiguration = latch.requestFor(42L, original)
        assertEquals(HARNESS_LITERT_RECOVERY_CONTEXT_TOKENS, sameConfiguration.chat.contextSize)
        assertEquals(false, sameConfiguration.params[LITERT_PARAM_MTP_ENABLED])

        val changedConfiguration = original.copy(
            chat = original.chat.copy(contextSize = HARNESS_LITERT_RECOVERY_CONTEXT_TOKENS),
            params = original.params + mapOf(LITERT_PARAM_MTP_ENABLED to false),
        )
        assertSame(changedConfiguration, latch.requestFor(42L, changedConfiguration))

        // Once the user changes settings, returning to the old profile is a
        // deliberate new request and does not inherit the stale latch.
        assertSame(original, latch.requestFor(42L, original))
        assertSame(original, latch.requestFor(99L, original))

        val outputSettingLatch = HarnessLiteRtDegradedModeLatch()
        outputSettingLatch.recordWorkerCrash(
            42L,
            harnessLiteRtRequestConfiguration(original, stableOutputTokens = 8_096),
        )
        assertSame(
            original,
            outputSettingLatch.requestFor(42L, original, stableOutputTokens = 4_096),
        )
    }

    @Test
    fun probeOutputCapDoesNotClearDegradedModeForTheRealTurn() {
        val latch = HarnessLiteRtDegradedModeLatch()
        val probe = request().copy(
            params = request().params + mapOf(LITERT_PARAM_MAX_OUTPUT_TOKENS to 128),
        )
        val realTurn = request().copy(
            params = request().params + mapOf(LITERT_PARAM_MAX_OUTPUT_TOKENS to 2_048),
        )

        val savedOutputCeiling = 8_096
        latch.recordWorkerCrash(
            42L,
            harnessLiteRtRequestConfiguration(probe, stableOutputTokens = savedOutputCeiling),
        )

        val boundedTurn = latch.requestFor(
            modelId = 42L,
            configuredRequest = realTurn,
            stableOutputTokens = savedOutputCeiling,
        )
        assertEquals(HARNESS_LITERT_RECOVERY_CONTEXT_TOKENS, boundedTurn.chat.contextSize)
        assertEquals(HARNESS_LITERT_RECOVERY_OUTPUT_TOKENS, boundedTurn.params[LITERT_PARAM_MAX_OUTPUT_TOKENS])
        assertEquals(false, boundedTurn.params[LITERT_PARAM_MTP_ENABLED])
        assertEquals(LITERT_BACKEND_CPU, boundedTurn.backendMode)
    }

    @Test
    fun workerCrashLatchesEvenWhenAVisiblePartialAnswerCannotBeRetried() = runBlocking {
        var crashCallbacks = 0
        var visibleOutput = false
        val failure = runCatching {
            runLiteRtWorkerWithOneBoundedRecovery(
                request = request(),
                hasVisibleOutput = { visibleOutput },
                onRetry = {},
                onWorkerCrash = { crashCallbacks += 1 },
                execute = {
                    visibleOutput = true
                    throw workerCrash()
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is LiteRtLmWorkerCrashedException)
        assertEquals(1, crashCallbacks)
    }

    private fun request(): LiteRtLmChatRequest {
        val tools = List(51) { index ->
            LiteRtToolDefinition(
                name = "tool_$index",
                description = "Tool $index",
                parameters = mapOf("query" to "query"),
                requiredParams = listOf("query"),
                parameterSchemaJson = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""",
            )
        }
        return LiteRtLmChatRequest(
            model = LiteRtModelEntity(
                id = 42,
                displayName = "Gemma 4 E2B",
                path = "/models/gemma.litertlm",
                filename = "gemma-4-E2B-it.litertlm",
                supportsCpu = true,
                supportsGpu = true,
                maxContextTokens = 32_768,
            ),
            chat = LlamaChatEntity(title = "Harness", contextSize = 32_768, systemPrompt = "system"),
            history = listOf(LlamaMessageEntity(chatId = -1, role = "user", content = "prior message")),
            backendMode = "gpu",
            params = mapOf(
                "enable_thinking" to true,
                "custom_setting" to "preserved",
                LITERT_PARAM_MAX_OUTPUT_TOKENS to 8_096,
                LITERT_PARAM_MTP_ENABLED to true,
            ),
            promptOverride = "original prompt",
            conversationOverride = LiteRtConversationOverride(
                systemInstruction = "system",
                initialMessages = listOf(LiteRtConversationMessage("user", "prior message")),
                userMessage = "current prompt",
                userImagePath = "/image.png",
                userAudioPath = "/audio.wav",
                tools = tools,
            ),
        )
    }

    private fun workerCrash() = LiteRtLmWorkerCrashedException(
        message = "worker stopped before generation output",
        requestId = "test-request",
        workerLabel = "CPU",
        backendMode = "cpu",
        contextSize = 32_768,
        mtpEnabled = true,
        lastPhase = "async message accepted by LiteRT-LM",
        recentExit = null,
        elapsedMs = 4_500L,
    )
}
