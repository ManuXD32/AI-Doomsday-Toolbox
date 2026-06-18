package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.RemoteBackendUrlSupport
import com.example.llamadroid.data.RemoteSummarySettingsSnapshot
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.LIVE_TRANSLATOR_ENGINE_LITERT
import com.example.llamadroid.data.db.LIVE_TRANSLATOR_ENGINE_LLAMA_SERVER
import com.example.llamadroid.data.db.LIVE_TRANSLATOR_ENGINE_LLAMA_SWAP
import com.example.llamadroid.data.db.LIVE_TRANSLATOR_ENGINE_OLLAMA
import com.example.llamadroid.data.db.LiveTranslatorTemplateEntity
import com.example.llamadroid.data.model.LlamaChatEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.llamadroid.util.DebugLog

private const val LITERT_BACKEND_AUTO = "auto"
private const val LITERT_BACKEND_CPU = "cpu"
private const val LITERT_BACKEND_GPU = "gpu"

private fun normalizeLiteRtBackend(value: String?): String {
    val normalized = value?.trim()?.lowercase().orEmpty()
    return when (normalized) {
        LITERT_BACKEND_CPU, "cpu-only" -> LITERT_BACKEND_CPU
        LITERT_BACKEND_GPU, "gpu-only", "opencl", "vulkan" -> LITERT_BACKEND_GPU
        else -> LITERT_BACKEND_AUTO
    }
}

private fun com.example.llamadroid.data.model.LiteRtModelEntity.isLikelyLiteRtGpuPackage(): Boolean {
    val lower = filename.lowercase()
    return !lower.contains(".qualcomm.") &&
        !lower.contains("_qualcomm_") &&
        !lower.contains(".mediatek.") &&
        !lower.contains("_mediatek_")
}

internal fun buildLiveTranslatorRemoteSnapshot(template: LiveTranslatorTemplateEntity): RemoteSummarySettingsSnapshot {
    val ollamaUrl = RemoteBackendUrlSupport.resolveStoredUrl(
        storedUrl = template.ollamaUrl,
        legacyHost = template.ollamaHost,
        legacyPort = template.ollamaPort,
        defaultPort = 11434
    )
    val llamaServerUrl = RemoteBackendUrlSupport.resolveStoredUrl(
        storedUrl = template.llamaServerUrl,
        legacyHost = template.llamaHost,
        legacyPort = template.llamaPort,
        defaultPort = 8080
    )
    val llamaSwapUrl = RemoteBackendUrlSupport.resolveStoredUrl(
        storedUrl = template.llamaSwapUrl,
        legacyHost = template.llamaHost,
        legacyPort = template.llamaPort,
        defaultPort = 9292
    )

    return RemoteSummarySettingsSnapshot(
        backend = when (template.backendEngine) {
            LIVE_TRANSLATOR_ENGINE_LLAMA_SWAP -> SettingsRepository.PDF_BACKEND_LLAMA_SWAP
            LIVE_TRANSLATOR_ENGINE_OLLAMA -> SettingsRepository.PDF_BACKEND_OLLAMA
            else -> SettingsRepository.PDF_BACKEND_LLAMA_SERVER
        },
        ollamaUrl = ollamaUrl,
        llamaServerUrl = llamaServerUrl,
        llamaSwapUrl = llamaSwapUrl,
        ollamaModel = template.ollamaModelName,
        llamaSwapModel = template.llamaModelName,
        thinkingEnabled = false,
        llamaServerModelLabel = template.llamaModelName,
        llamaServerContextTokens = template.contextSize,
        llamaServerContextLabel = "${template.contextSize} tokens",
        chunkContext = template.contextSize,
        chunkMaxTokens = template.maxTokens,
        mergeContext = template.contextSize,
        mergeMaxTokens = template.maxTokens,
        temperature = template.temperature,
        timeoutMinutes = ((template.timeoutSeconds + 59) / 60).coerceAtLeast(1),
        targetLanguage = "",
        summaryPrompt = null,
        mergePrompt = null
    )
}

class LiveTranslatorNativeRunner(
    private val context: Context,
    private val database: AppDatabase
) {
    suspend fun translate(
        template: LiveTranslatorTemplateEntity,
        sourceLanguage: String,
        targetLanguage: String,
        transcript: String
    ): String = withContext(Dispatchers.IO) {
        val systemPrompt = LiveTranslatorLogic.buildSystemPrompt()
        val userPrompt = LiveTranslatorLogic.buildUserPrompt(
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            transcript = transcript
        )
        val firstOutput = runTranslationRequest(template, systemPrompt, userPrompt).trim().ifBlank {
            throw IllegalStateException(context.getString(R.string.live_translator_error_blank_translation))
        }
        if (!LiveTranslatorLogic.shouldRetryTranslation(sourceLanguage, targetLanguage, transcript, firstOutput)) {
            return@withContext firstOutput
        }
        DebugLog.log("[LIVE-TRANSLATOR] Translation still looked like $sourceLanguage; retrying toward $targetLanguage")
        val retryPrompt = LiveTranslatorLogic.buildRetryUserPrompt(
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            transcript = transcript,
            previousOutput = firstOutput
        )
        val retryOutput = runTranslationRequest(template, systemPrompt, retryPrompt).trim()
        retryOutput.takeIf { it.isNotBlank() } ?: firstOutput
    }

    private suspend fun runTranslationRequest(
        template: LiveTranslatorTemplateEntity,
        systemPrompt: String,
        userPrompt: String
    ): String =
        when (template.backendEngine) {
            LIVE_TRANSLATOR_ENGINE_LITERT -> translateWithLiteRt(template, systemPrompt, userPrompt)
            else -> translateWithRemote(template, systemPrompt, userPrompt)
        }

    private suspend fun translateWithRemote(
        template: LiveTranslatorTemplateEntity,
        systemPrompt: String,
        userPrompt: String
    ): String {
        val snapshot = buildLiveTranslatorRemoteSnapshot(template)
        return RemoteSummaryClientFactory.fromSnapshot(context, snapshot).summarize(
            RemoteSummaryRequest(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                contextSize = template.contextSize,
                maxTokens = template.maxTokens,
                temperature = template.temperature,
                thinkingEnabled = false
            )
        ).output
    }

    private suspend fun translateWithLiteRt(
        template: LiveTranslatorTemplateEntity,
        systemPrompt: String,
        userPrompt: String
    ): String {
        val modelId = template.liteRtModelId
            ?: throw IllegalStateException(context.getString(R.string.litert_error_model_missing))
        val model = database.liteRtModelDao().getById(modelId)
            ?: throw IllegalStateException(context.getString(R.string.litert_error_model_missing))
        val output = StringBuilder()
        streamLiteRtSafely(
            model = model,
            request = LiteRtLmChatRequest(
                model = model,
                chat = LlamaChatEntity(
                    title = "Live Translator",
                    contextSize = template.contextSize,
                    systemPrompt = systemPrompt
                ),
                history = emptyList(),
                backendMode = normalizeLiteRtBackend(template.liteRtBackend),
                params = mapOf(
                    "temperature" to template.temperature.toDouble(),
                    "top_k" to 40,
                    "top_p" to 0.95,
                    "enable_thinking" to template.liteRtThinkingEnabled,
                    LITERT_PARAM_MTP_ENABLED to template.liteRtMtpEnabled
                ),
                conversationOverride = LiteRtConversationOverride(
                    systemInstruction = systemPrompt,
                    initialMessages = emptyList(),
                    userMessage = userPrompt
                )
            ),
            onStatus = {},
            onChunk = { output.append(it) },
            onThinkingChunk = {}
        )
        return PDFSummaryLogic.cleanLlamaOutput(output.toString())
    }

    private suspend fun streamLiteRtSafely(
        model: com.example.llamadroid.data.model.LiteRtModelEntity,
        request: LiteRtLmChatRequest,
        onStatus: suspend (String) -> Unit,
        onChunk: suspend (String) -> Unit,
        onThinkingChunk: suspend (String) -> Unit
    ): LiteRtLmChatStats {
        val backendMode = normalizeLiteRtBackend(request.backendMode)

        suspend fun runGpuWorker(): LiteRtLmChatStats =
            LiteRtLmWorkerClient(context).streamGpuChat(
                request = request.copy(backendMode = LITERT_BACKEND_GPU),
                onStatus = onStatus,
                onChunk = onChunk,
                onThinkingChunk = onThinkingChunk
            )

        if (backendMode == LITERT_BACKEND_GPU) {
            return try {
                runGpuWorker()
            } catch (error: Throwable) {
                val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.name
                LiteRtLmAcceleratorHealth.recordGpuCrash(context, model, detail)
                throw IllegalStateException(
                    context.getString(R.string.litert_error_explicit_backend_failed, "GPU", detail),
                    error
                )
            }
        }

        if (backendMode == LITERT_BACKEND_AUTO && model.supportsGpu && model.isLikelyLiteRtGpuPackage()) {
            if (!LiteRtLmAcceleratorHealth.isGpuQuarantined(context, model)) {
                try {
                    return runGpuWorker()
                } catch (error: Throwable) {
                    val detail = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.name
                    LiteRtLmAcceleratorHealth.recordGpuCrash(context, model, detail)
                    DebugLog.log("LiveTranslatorNativeRunner: LiteRT GPU worker failed, falling back to CPU: $detail")
                }
            }
        }

        return LiteRtLmChatService(context).streamChat(
            request = request.copy(backendMode = LITERT_BACKEND_CPU),
            onStatus = onStatus,
            onChunk = onChunk,
            onThinkingChunk = onThinkingChunk
        )
    }
}
