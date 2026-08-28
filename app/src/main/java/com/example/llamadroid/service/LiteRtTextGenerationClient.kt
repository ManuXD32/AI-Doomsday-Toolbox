package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.defaultLiteRtChatContextTokens
import com.example.llamadroid.data.model.isLikelyLiteRtGpuPackage
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LiteRtTextGenerationResult(
    val output: String,
    val rawOutput: String,
    val stats: LiteRtLmChatStats,
    val runtimeFallbacks: Int = 0,
    val thinking: String = ""
)

private data class LiteRtSafeStreamResult(
    val stats: LiteRtLmChatStats,
    val runtimeFallbacks: Int
)

class LiteRtTextGenerationClient(private val context: Context) {
    suspend fun generate(
        model: LiteRtModelEntity,
        title: String,
        systemPrompt: String,
        messages: List<LiteRtConversationMessage>,
        userPrompt: String,
        contextSize: Int,
        maxTokens: Int?,
        temperature: Float,
        thinkingEnabled: Boolean,
        backendMode: String,
        mtpEnabled: Boolean,
        userImagePath: String? = null,
        userAudioPath: String? = null,
        onStatus: suspend (String) -> Unit = {},
        onChunk: suspend (String) -> Unit = {},
        onThinkingChunk: suspend (String) -> Unit = {}
    ): LiteRtTextGenerationResult = withContext(Dispatchers.IO) {
        val resolvedContext = contextSize
            .takeIf { it > 0 }
            ?: model.defaultLiteRtChatContextTokens()
            ?: 4_000
        val output = StringBuilder()
        val thinking = StringBuilder()
        val request = LiteRtLmChatRequest(
            model = model,
            chat = LlamaChatEntity(
                title = title,
                contextSize = resolvedContext,
                systemPrompt = systemPrompt
            ),
            history = emptyList(),
            backendMode = normalizeLiteRtBackend(backendMode),
            params = mapOf(
                "temperature" to temperature.toDouble(),
                "top_k" to 40,
                "top_p" to 0.95,
                "enable_thinking" to thinkingEnabled,
                LITERT_PARAM_MTP_ENABLED to mtpEnabled,
                LITERT_PARAM_MAX_OUTPUT_TOKENS to (maxTokens ?: resolvedContext)
            ),
            conversationOverride = LiteRtConversationOverride(
                systemInstruction = systemPrompt,
                initialMessages = messages,
                userMessage = userPrompt,
                userImagePath = userImagePath,
                userAudioPath = userAudioPath
            )
        )
        val safeResult = streamSafely(
            model = model,
            request = request,
            onStatus = onStatus,
            onChunk = { chunk ->
                output.append(chunk)
                onChunk(chunk)
            },
            onThinkingChunk = { chunk ->
                thinking.append(chunk)
                onThinkingChunk(chunk)
            }
        )
        val raw = output.toString()
        val cleaned = PDFSummaryLogic.cleanLlamaOutput(raw)
        LiteRtTextGenerationResult(
            output = cleaned,
            rawOutput = raw,
            stats = safeResult.stats.copy(
                completionTokens = safeResult.stats.completionTokens.takeIf { it > 0 }
                    ?: estimateLiteRtCompletionTokens(cleaned)
            ),
            runtimeFallbacks = safeResult.runtimeFallbacks,
            thinking = thinking.toString()
        )
    }

    private suspend fun streamSafely(
        model: LiteRtModelEntity,
        request: LiteRtLmChatRequest,
        onStatus: suspend (String) -> Unit,
        onChunk: suspend (String) -> Unit,
        onThinkingChunk: suspend (String) -> Unit
    ): LiteRtSafeStreamResult {
        val backendMode = normalizeLiteRtBackend(request.backendMode)
        val workerClient = LiteRtLmWorkerClient(context)

        suspend fun runGpuWorker(gpuRequest: LiteRtLmChatRequest = request): LiteRtLmChatStats =
            workerClient.streamGpuChat(
                request = gpuRequest.copy(backendMode = LITERT_BACKEND_GPU),
                onStatus = onStatus,
                onChunk = onChunk,
                onThinkingChunk = onThinkingChunk
            )

        suspend fun runCpuWorker(cpuRequest: LiteRtLmChatRequest = request): LiteRtLmChatStats =
            workerClient.streamCpuChat(
                request = cpuRequest.copy(backendMode = LITERT_BACKEND_CPU),
                onStatus = onStatus,
                onChunk = onChunk,
                onThinkingChunk = onThinkingChunk
            )

        if (backendMode == LITERT_BACKEND_GPU) {
            return try {
                LiteRtSafeStreamResult(runGpuWorker(), 0)
            } catch (error: Throwable) {
                val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.name
                LiteRtLmAcceleratorHealth.recordGpuCrash(context, model, detail)
                throw IllegalStateException(
                    context.getString(R.string.litert_error_explicit_backend_failed, "GPU", detail),
                    error
                )
            }
        }

        if (backendMode == LITERT_BACKEND_CPU) {
            return try {
                LiteRtSafeStreamResult(runCpuWorker(), 0)
            } catch (error: Throwable) {
                val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.name
                throw IllegalStateException(
                    context.getString(R.string.litert_error_explicit_backend_failed, "CPU", detail),
                    error
                )
            }
        }

        if (backendMode == LITERT_BACKEND_AUTO && model.supportsGpu && model.isLikelyLiteRtGpuPackage()) {
            if (!LiteRtLmAcceleratorHealth.isGpuQuarantined(context, model)) {
                try {
                    return LiteRtSafeStreamResult(runGpuWorker(), 0)
                } catch (error: Throwable) {
                    val mtpEnabled = (request.params[LITERT_PARAM_MTP_ENABLED] as? Boolean) ?: false
                    if (mtpEnabled) {
                        DebugLog.log(
                            "LiteRtTextGenerationClient: GPU/MTP generation failed; retrying the same GPU backend with MTP disabled"
                        )
                        try {
                            return LiteRtSafeStreamResult(
                                stats = runGpuWorker(
                                    request.copy(
                                        params = request.params + (LITERT_PARAM_MTP_ENABLED to false)
                                    )
                                ),
                                runtimeFallbacks = 1
                            )
                        } catch (retryError: Throwable) {
                            val detail = retryError.message?.takeIf { it.isNotBlank() }
                                ?: retryError.javaClass.name
                            LiteRtLmAcceleratorHealth.recordGpuCrash(context, model, detail)
                            DebugLog.log(
                                "LiteRtTextGenerationClient: GPU retry without MTP failed; considering CPU: $detail"
                            )
                        }
                    } else {
                        val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.name
                        LiteRtLmAcceleratorHealth.recordGpuCrash(context, model, detail)
                        DebugLog.log("LiteRtTextGenerationClient: GPU worker failed; considering CPU: $detail")
                    }
                }
            }
        }

        check(model.supportsCpu) {
            context.getString(
                R.string.litert_error_explicit_backend_failed,
                "CPU",
                context.getString(R.string.litert_error_cpu_unsupported_package)
            )
        }
        return LiteRtSafeStreamResult(
            stats = runCpuWorker(),
            runtimeFallbacks = if (backendMode == LITERT_BACKEND_AUTO && model.supportsGpu) 1 else 0
        )
    }
}
