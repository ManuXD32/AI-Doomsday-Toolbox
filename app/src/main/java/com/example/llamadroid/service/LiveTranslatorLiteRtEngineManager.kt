package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.isLikelyLiteRtGpuPackage
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LiveTranslatorLiteRtEngineManager {
    suspend fun preload(
        context: Context,
        model: LiteRtModelEntity,
        backendMode: String,
        contextSize: Int,
        maxTokens: Int,
        temperature: Float,
        thinkingEnabled: Boolean,
        mtpEnabled: Boolean
    ) = withContext(Dispatchers.IO) {
        LiteRtTextGenerationClient(context.applicationContext).generate(
            model = model,
            title = "Live Translator LiteRT preload",
            systemPrompt = "You are warming up the Live Translator LiteRT engine. Reply with OK.",
            messages = emptyList(),
            userPrompt = "Reply with exactly: OK",
            contextSize = contextSize,
            maxTokens = maxTokens.coerceAtLeast(1),
            temperature = temperature,
            thinkingEnabled = thinkingEnabled,
            backendMode = backendMode,
            mtpEnabled = mtpEnabled
        )
    }

    suspend fun isLoaded(
        context: Context,
        model: LiteRtModelEntity,
        backendMode: String,
        contextSize: Int,
        mtpEnabled: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val normalizedBackend = normalizeLiteRtBackend(backendMode)
        val appContext = context.applicationContext
        val request = statusRequest(model, normalizedBackend, contextSize, mtpEnabled)
        val cpuLoaded = normalizedBackend != LITERT_BACKEND_GPU &&
            LiteRtLmChatService(appContext).isEngineLoaded(
                model = model,
                backendMode = LITERT_BACKEND_CPU,
                contextSize = contextSize,
                mtpEnabled = mtpEnabled
            )
        val gpuLoaded = normalizedBackend != LITERT_BACKEND_CPU &&
            model.supportsGpu &&
            model.isLikelyLiteRtGpuPackage() &&
            !LiteRtLmAcceleratorHealth.isGpuQuarantined(appContext, model) &&
            runCatching { LiteRtLmWorkerClient(appContext).isGpuEngineLoaded(request) }.getOrDefault(false)
        cpuLoaded || gpuLoaded
    }

    suspend fun unload(
        context: Context,
        model: LiteRtModelEntity,
        backendMode: String,
        contextSize: Int,
        mtpEnabled: Boolean
    ): Int = withContext(Dispatchers.IO) {
        val normalizedBackend = normalizeLiteRtBackend(backendMode)
        val appContext = context.applicationContext
        val request = statusRequest(model, normalizedBackend, contextSize, mtpEnabled)
        val cpuUnloaded = if (normalizedBackend != LITERT_BACKEND_GPU) {
            LiteRtLmChatService(appContext).unloadEngines(
                model = model,
                backendMode = LITERT_BACKEND_CPU,
                contextSize = contextSize,
                mtpEnabled = mtpEnabled
            )
        } else {
            0
        }
        val gpuUnloaded = if (normalizedBackend != LITERT_BACKEND_CPU && model.supportsGpu && model.isLikelyLiteRtGpuPackage()) {
            runCatching { LiteRtLmWorkerClient(appContext).unloadGpuEngines(request) }.getOrDefault(0)
        } else {
            0
        }
        cpuUnloaded + gpuUnloaded
    }

    private fun statusRequest(
        model: LiteRtModelEntity,
        backendMode: String,
        contextSize: Int,
        mtpEnabled: Boolean
    ): LiteRtLmChatRequest = LiteRtLmChatRequest(
        model = model,
        chat = LlamaChatEntity(
            title = "Live Translator LiteRT cache status",
            contextSize = contextSize.coerceAtLeast(512),
            systemPrompt = ""
        ),
        history = emptyList(),
        backendMode = if (backendMode == LITERT_BACKEND_AUTO) LITERT_BACKEND_GPU else backendMode,
        params = mapOf(
            LITERT_PARAM_MTP_ENABLED to mtpEnabled,
            "enable_thinking" to false
        ),
        promptOverride = "OK"
    )
}
