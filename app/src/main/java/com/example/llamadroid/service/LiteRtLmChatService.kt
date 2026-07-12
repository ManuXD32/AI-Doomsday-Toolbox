package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.model.LITERT_BACKEND_AUTO
import com.example.llamadroid.data.model.LITERT_BACKEND_CPU
import com.example.llamadroid.data.model.LITERT_BACKEND_GPU
import com.example.llamadroid.data.model.LiteRtModelEntity
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.defaultLiteRtEngineMaxTokens
import com.example.llamadroid.data.model.estimateNativeChatTextTokens
import com.example.llamadroid.data.model.isLikelyLiteRtGpuPackage
import com.example.llamadroid.data.model.normalizeLiteRtBackend
import com.example.llamadroid.util.DebugLog
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class LiteRtLmChatRequest(
    val model: LiteRtModelEntity,
    val chat: LlamaChatEntity,
    val history: List<LlamaMessageEntity>,
    val backendMode: String,
    val params: Map<String, Any>,
    val promptOverride: String? = null,
    val conversationOverride: LiteRtConversationOverride? = null
)

data class LiteRtConversationOverride(
    val systemInstruction: String,
    val initialMessages: List<LiteRtConversationMessage>,
    val userMessage: String,
    val userImagePath: String? = null,
    val userAudioPath: String? = null,
    val tools: List<LiteRtToolDefinition> = emptyList()
)

data class LiteRtConversationMessage(
    val role: String,
    val content: String,
    val imagePath: String? = null,
    val audioPath: String? = null,
    val toolCalls: List<LiteRtToolCallSpec> = emptyList(),
    val toolName: String? = null
)

data class LiteRtToolDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, String>,
    val requiredParams: List<String> = emptyList()
)

data class LiteRtToolCallSpec(
    val name: String,
    val arguments: Map<String, Any?> = emptyMap()
)

data class LiteRtLmChatStats(
    val promptTokens: Int,
    val completionTokens: Int,
    val tokensPerSecond: Double,
    val toolCalls: List<OllamaService.ToolCall> = emptyList(),
    val visibleText: String = "",
    val meteredText: String = ""
)

const val LITERT_PARAM_MTP_ENABLED = "litert_mtp_enabled"
const val LITERT_PARAM_MAX_OUTPUT_TOKENS = "litert_max_output_tokens"

private const val LITERT_EXTRA_CONTEXT_ENABLE_THINKING = "enable_thinking"
private const val LITERT_DEFAULT_CONTEXT_TOKENS = 4000
private const val LITERT_GPU_SAFE_CONTEXT_TOKENS = 4096
private const val LITERT_GPU_SAFE_MAX_OUTPUT_TOKENS = 1024
private const val LITERT_PROMPT_RESERVE_TOKENS = 128
private const val LITERT_PROMPT_CONTEXT_SAFETY_PERCENT = 90
private const val LITERT_MIN_PROMPT_CONTEXT_TOKENS = 256
private const val LITERT_TRUNCATED_MARKER = "[Earlier conversation omitted to fit this LiteRT model context.]"
private const val LITERT_TRUNCATED_CONTENT_MARKER = "\n[...truncated for LiteRT context...]\n"

internal fun effectiveLiteRtEngineMaxTokens(
    model: LiteRtModelEntity,
    requestedMaxTokens: Int?
): Int? {
    val packageMaxTokens = model.defaultLiteRtEngineMaxTokens()
    return when {
        requestedMaxTokens != null && packageMaxTokens != null -> minOf(requestedMaxTokens, packageMaxTokens)
        requestedMaxTokens != null -> requestedMaxTokens
        else -> packageMaxTokens
    }
}

internal fun effectiveLiteRtEngineMaxTokensForBackend(
    model: LiteRtModelEntity,
    requestedMaxTokens: Int?,
    backendLabel: String?
): Int? {
    val base = effectiveLiteRtEngineMaxTokens(model, requestedMaxTokens)
    return if (backendLabel.equals("GPU", ignoreCase = true)) {
        base?.coerceAtMost(LITERT_GPU_SAFE_CONTEXT_TOKENS) ?: LITERT_GPU_SAFE_CONTEXT_TOKENS
    } else {
        base
    }
}

private fun effectiveLiteRtMaxOutputTokensForBackend(
    requestedMaxOutputTokens: Int?,
    backendLabel: String,
    engineMaxTokens: Int?
): Int? {
    val boundedByContext = requestedMaxOutputTokens
        ?.takeIf { it > 0 }
        ?.let { requested ->
            engineMaxTokens
                ?.let { maxTokens -> requested.coerceAtMost((maxTokens / 2).coerceAtLeast(1)) }
                ?: requested
        }
    return if (backendLabel == "GPU") {
        (boundedByContext ?: LITERT_GPU_SAFE_MAX_OUTPUT_TOKENS)
            .coerceAtMost(LITERT_GPU_SAFE_MAX_OUTPUT_TOKENS)
            .coerceAtLeast(1)
    } else {
        boundedByContext
    }
}

private data class LiteRtConversationInput(
    val promptOverride: String? = null,
    val systemInstruction: String = "",
    val initialMessages: List<LiteRtConversationMessage> = emptyList(),
    val userMessage: String = "",
    val userImagePath: String? = null,
    val userAudioPath: String? = null,
    val tools: List<LiteRtToolDefinition> = emptyList(),
    val wasTruncated: Boolean = false
)

class LiteRtLmChatService(
    private val context: Context,
    private val allowGpuBackend: Boolean = false,
    private val onDiagnostic: ((String) -> Unit)? = null
) {
    fun isEngineLoaded(
        model: LiteRtModelEntity,
        backendMode: String,
        contextSize: Int,
        mtpEnabled: Boolean
    ): Boolean = LiteRtLmReflectionBridge(context).hasLoadedEngine(
        model = model,
        backendMode = normalizeLiteRtBackend(backendMode),
        contextSize = contextSize,
        mtpEnabled = mtpEnabled
    )

    fun unloadEngines(
        model: LiteRtModelEntity,
        backendMode: String,
        contextSize: Int,
        mtpEnabled: Boolean
    ): Int = LiteRtLmReflectionBridge(context).unloadLoadedEngines(
        model = model,
        backendMode = normalizeLiteRtBackend(backendMode),
        contextSize = contextSize,
        mtpEnabled = mtpEnabled
    )

    suspend fun streamGalleryStyleGpuChat(
        request: LiteRtLmChatRequest,
        onStatus: suspend (String) -> Unit,
        onChunk: suspend (String) -> Unit,
        onThinkingChunk: suspend (String) -> Unit = {}
    ): LiteRtLmChatStats {
        val modelPath = File(request.model.path)
        if (!modelPath.exists()) {
            throw IllegalStateException(context.getString(R.string.litert_error_model_file_missing))
        }

        val thinkingEnabled = (request.params["enable_thinking"] as? Boolean) ?: true
        val rawInput = request.conversationOverride
            ?.let { override ->
                LiteRtConversationInput(
                    systemInstruction = override.systemInstruction,
                    initialMessages = override.initialMessages,
                    userMessage = override.userMessage,
                    userImagePath = override.userImagePath,
                    userAudioPath = override.userAudioPath,
                    tools = override.tools
                )
            }
            ?: request.promptOverride
            ?.let { LiteRtConversationInput(promptOverride = it) }
            ?: buildConversationInput(
                chat = request.chat,
                history = request.history,
                thinkingEnabled = thinkingEnabled
            )
        val input = rawInput.fitLiteRtContext(request)
        if (input.wasTruncated) {
            diagnostic("LiteRT prompt trimmed to fit contextSize=${request.chat.contextSize} model=${request.model.displayName}")
        }
        val promptForTokenEstimate = input.renderLiteRtPromptForEstimate()
        val promptTokens = estimateNativeChatTextTokens(promptForTokenEstimate)
        val bridge = LiteRtLmReflectionBridge(context)

        onStatus(context.getString(R.string.litert_status_starting_backend, "GPU"))
        diagnostic("starting Gallery-style in-app GPU for ${request.model.displayName}")
        diagnostic("Gallery-style GPU raw model path=${modelPath.absolutePath}")
        diagnostic("Gallery-style GPU cacheDir=default")
        diagnostic("creating GPU Backend object")
        val backend = bridge.createBackend("GPU")
        diagnostic("GPU Backend object created")
        return runWithBackend(
            modelPath = modelPath,
            backend = backend,
            backendLabel = "GPU",
            cacheDir = null,
            input = input,
            request = request.copy(backendMode = LITERT_BACKEND_GPU),
            promptTokens = promptTokens,
            gpuMode = LiteRtGpuBridgeMode.GalleryStyle,
            onChunk = onChunk,
            onThinkingChunk = onThinkingChunk
        )
    }

    suspend fun streamChat(
        request: LiteRtLmChatRequest,
        onStatus: suspend (String) -> Unit,
        onChunk: suspend (String) -> Unit,
        onThinkingChunk: suspend (String) -> Unit = {}
    ): LiteRtLmChatStats {
        val modelPath = File(request.model.path)
        if (!modelPath.exists()) {
            throw IllegalStateException(context.getString(R.string.litert_error_model_file_missing))
        }

        val thinkingEnabled = (request.params["enable_thinking"] as? Boolean) ?: true
        val rawInput = request.conversationOverride
            ?.let { override ->
                LiteRtConversationInput(
                    systemInstruction = override.systemInstruction,
                    initialMessages = override.initialMessages,
                    userMessage = override.userMessage,
                    userImagePath = override.userImagePath,
                    userAudioPath = override.userAudioPath,
                    tools = override.tools
                )
            }
            ?: request.promptOverride
            ?.let { LiteRtConversationInput(promptOverride = it) }
            ?: buildConversationInput(
                chat = request.chat,
                history = request.history,
                thinkingEnabled = thinkingEnabled
            )
        val input = rawInput.fitLiteRtContext(request)
        if (input.wasTruncated) {
            diagnostic("LiteRT prompt trimmed to fit contextSize=${request.chat.contextSize} model=${request.model.displayName}")
        }
        val promptForTokenEstimate = input.renderLiteRtPromptForEstimate()
        val backendMode = normalizeLiteRtBackend(request.backendMode)
        val mtpEnabled = (request.params[LITERT_PARAM_MTP_ENABLED] as? Boolean) ?: false
        val requestedMaxTokens = request.chat.contextSize.takeIf { it > 0 }
        if (backendMode == LITERT_BACKEND_GPU && !allowGpuBackend) {
            DebugLog.log("LiteRtLmChatService: refusing in-process GPU backend; worker process is required")
            throw IllegalStateException(context.getString(R.string.litert_error_gpu_requires_worker))
        }
        val backendCandidates = backendCandidates(backendMode, request.model)
        if (backendCandidates.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.litert_error_runtime_unavailable))
        }
        val promptTokens = estimateNativeChatTextTokens(promptForTokenEstimate)
        var lastFailure: Throwable? = null

        backendCandidates.forEach { candidate ->
            try {
                val candidateEngineMaxTokens = effectiveLiteRtEngineMaxTokensForBackend(
                    model = request.model,
                    requestedMaxTokens = requestedMaxTokens,
                    backendLabel = candidate.label
                )
                val runtimeModelPath = prepareRuntimeModelPath(
                    modelPath = modelPath,
                    modelId = request.model.id,
                    backendLabel = candidate.label
                )
                val cacheDir = liteRtLmCacheDir(
                    modelId = request.model.id,
                    backendLabel = candidate.label,
                    mtpEnabled = mtpEnabled,
                    contextTokens = candidateEngineMaxTokens
                )
                if (candidate.label == "GPU") {
                    val startupDiagnostics = LiteRtGpuStartupDiagnostics.collect(
                        context = context,
                        sourceModelPath = modelPath,
                        stagedModelPath = runtimeModelPath,
                        cacheDir = cacheDir
                    )
                    startupDiagnostics.toLogLines().forEach(::diagnostic)
                    if (!startupDiagnostics.probe.ok) {
                        throw IllegalStateException(
                            context.getString(
                                R.string.litert_error_gpu_probe_failed,
                                startupDiagnostics.probe.error ?: "unknown"
                            )
                        )
                    }
                }
                onStatus(context.getString(R.string.litert_status_starting_backend, candidate.label))
                diagnostic("starting ${candidate.label} for ${request.model.displayName}")
                diagnostic("creating ${candidate.label} Backend object")
                val backend = candidate.backendFactory()
                diagnostic("${candidate.label} Backend object created")
                return runWithBackend(
                    modelPath = runtimeModelPath,
                    backend = backend,
                    backendLabel = candidate.label,
                    cacheDir = cacheDir,
                    input = input,
                    request = request,
                    promptTokens = promptTokens,
                    onChunk = onChunk,
                    onThinkingChunk = onThinkingChunk
                )
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                val failure = e.liteRtRootCause()
                if (failure is CancellationException) throw failure
                val detail = e.liteRtDiagnosticMessage()
                lastFailure = failure
                diagnostic("${candidate.label} failed: $detail")
                if (backendMode != LITERT_BACKEND_AUTO) {
                    throw IllegalStateException(
                        context.getString(R.string.litert_error_explicit_backend_failed, candidate.label, detail),
                        failure
                    )
                }
                onStatus(context.getString(R.string.litert_status_backend_failed, candidate.label))
            }
        }

        throw IllegalStateException(lastFailure?.message ?: context.getString(R.string.error_generic), lastFailure)
    }

    private fun backendCandidates(backendMode: String, model: LiteRtModelEntity): List<BackendCandidate> {
        val bridge = LiteRtLmReflectionBridge(context)
        val gpu = BackendCandidate("GPU") { bridge.createBackend("GPU") }
        val cpu = BackendCandidate("CPU") { bridge.createBackend("CPU") }
        return when (backendMode) {
            LITERT_BACKEND_GPU -> if (allowGpuBackend) listOf(gpu) else emptyList()
            LITERT_BACKEND_CPU -> listOf(cpu)
            else -> buildList {
                if (model.supportsGpu && model.isLikelyLiteRtGpuPackage()) {
                    if (allowGpuBackend) {
                        add(gpu)
                    } else {
                        DebugLog.log(
                            "LiteRtLmChatService: skipping in-process GPU for ${model.displayName}; worker process is required"
                        )
                    }
                }
                if (model.supportsCpu) add(cpu)
                if (isEmpty()) add(cpu)
            }
        }
    }

    private suspend fun runWithBackend(
        modelPath: File,
        backend: Any,
        backendLabel: String,
        cacheDir: File?,
        input: LiteRtConversationInput,
        request: LiteRtLmChatRequest,
        promptTokens: Int,
        gpuMode: LiteRtGpuBridgeMode = LiteRtGpuBridgeMode.WorkerSafe,
        onChunk: suspend (String) -> Unit,
        onThinkingChunk: suspend (String) -> Unit
    ): LiteRtLmChatStats {
        val bridge = LiteRtLmReflectionBridge(context)
        if (backendLabel == "GPU") {
            bridge.runtimeDiagnosticLines(backend).forEach(::diagnostic)
            when (gpuMode) {
                LiteRtGpuBridgeMode.WorkerSafe -> {
                    diagnostic("GPU native libraries left to LiteRT-LM default loader")
                    diagnostic("GPU compiled artifacts cache=${cacheDir?.absolutePath ?: "default"}")
                }
                LiteRtGpuBridgeMode.GalleryStyle -> {
                    diagnostic("Gallery-style GPU native libraries left to LiteRT-LM default loader")
                    diagnostic("Gallery-style GPU compiled artifacts cache=default")
                    diagnostic("Gallery-style GPU EGL context left to LiteRT-LM default handling")
                }
            }
        }
        val startedAt = System.currentTimeMillis()
        val requestedMaxTokens = request.chat.contextSize.takeIf { it > 0 }
        val engineMaxTokens = effectiveLiteRtEngineMaxTokensForBackend(
            model = request.model,
            requestedMaxTokens = requestedMaxTokens,
            backendLabel = backendLabel
        )
        val mtpEnabled = (request.params[LITERT_PARAM_MTP_ENABLED] as? Boolean) ?: false
        val requestedMaxOutputTokens = (request.params[LITERT_PARAM_MAX_OUTPUT_TOKENS] as? Number)
            ?.toInt()
            ?.takeIf { it > 0 }
        val maxOutputTokens = effectiveLiteRtMaxOutputTokensForBackend(
            requestedMaxOutputTokens = requestedMaxOutputTokens,
            backendLabel = backendLabel,
            engineMaxTokens = engineMaxTokens
        )
        if (backendLabel == "GPU") {
            when {
                requestedMaxTokens != null && engineMaxTokens != null && engineMaxTokens < requestedMaxTokens -> {
                    diagnostic(
                        "GPU max tokens using chat context setting $requestedMaxTokens " +
                            "clamped to safe LiteRT GPU limit $engineMaxTokens"
                    )
                }
                requestedMaxTokens != null -> {
                    diagnostic("GPU max tokens using chat context setting $requestedMaxTokens")
                }
                engineMaxTokens != null -> {
                    diagnostic("GPU max tokens using model default $engineMaxTokens")
                }
                else -> {
                    diagnostic("GPU max tokens left to LiteRT-LM default")
                }
            }
            if (requestedMaxOutputTokens != null && maxOutputTokens != null && maxOutputTokens < requestedMaxOutputTokens) {
                diagnostic(
                    "GPU max output tokens using chat setting $requestedMaxOutputTokens " +
                        "clamped to safe LiteRT GPU limit $maxOutputTokens"
                )
            }
        }
        diagnostic(
            "LiteRT generation config backend=$backendLabel maxTokens=${engineMaxTokens ?: "default"} " +
                "requestedContext=${requestedMaxTokens ?: "default"} " +
                "maxOutputTokens=${maxOutputTokens ?: "default"} " +
                "mtp=$mtpEnabled thinking=${(request.params[LITERT_EXTRA_CONTEXT_ENABLE_THINKING] as? Boolean) ?: true} " +
                "promptTokens=$promptTokens"
        )
        val generated = try {
            bridge.generate(
                modelPath = modelPath,
                backend = backend,
                backendLabel = backendLabel,
                maxTokens = engineMaxTokens,
                maxOutputTokens = maxOutputTokens,
                cacheDir = cacheDir,
                input = input,
                thinkingEnabled = (request.params["enable_thinking"] as? Boolean) ?: true,
                speculativeDecodingEnabled = mtpEnabled,
                topK = (request.params["top_k"] as? Number)?.toInt() ?: 40,
                topP = (request.params["top_p"] as? Number)?.toDouble() ?: 0.95,
                temperature = (request.params["temperature"] as? Number)?.toDouble() ?: 0.7,
                seed = (request.params["seed"] as? Number)?.toInt() ?: 0,
                holdGpuEglContext = false,
                onDiagnostic = ::diagnostic,
                onChunk = onChunk,
                onThinkingChunk = onThinkingChunk
            )
        } finally {
            // LiteRT-LM's experimental flags are process-wide. Reset after each generation so
            // one chat's speed setting does not leak into another retained engine request.
            bridge.resetSpeculativeDecoding(::diagnostic)
        }
        if (backendLabel == "GPU" && generated.visibleText.hasLiteRtCorruptOutputSignature()) {
            throw IllegalStateException(
                "LiteRT GPU produced a corrupt text stream; falling back to CPU is recommended."
            )
        }
        val completionTokens = estimateLiteRtCompletionTokens(
            generated.meteredText.ifBlank { generated.visibleText }
        )
        val elapsed = (System.currentTimeMillis() - startedAt) / 1000.0
        val tokensPerSecond = if (elapsed > 0.0) completionTokens / elapsed else 0.0
        diagnostic(
            "LiteRT generation complete backend=$backendLabel mtp=$mtpEnabled " +
                "promptTokens=$promptTokens completionTokens=$completionTokens " +
                "toolCalls=${generated.toolCalls.size} " +
                "elapsedSec=${String.format(Locale.US, "%.2f", elapsed)} " +
                "tokensPerSecond=${String.format(Locale.US, "%.2f", tokensPerSecond)}"
        )
        return LiteRtLmChatStats(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            tokensPerSecond = tokensPerSecond,
            toolCalls = generated.toolCalls,
            visibleText = generated.visibleText,
            meteredText = generated.meteredText
        )
    }

    private fun liteRtLmCacheDir(
        modelId: Long,
        backendLabel: String,
        mtpEnabled: Boolean,
        contextTokens: Int?
    ): File {
        return liteRtLmEngineCacheDir(
            cacheRoot = context.cacheDir,
            modelId = modelId,
            backendLabel = backendLabel,
            mtpEnabled = mtpEnabled,
            contextTokens = contextTokens
        )
    }

    private fun prepareRuntimeModelPath(
        modelPath: File,
        modelId: Long,
        backendLabel: String
    ): File {
        if (backendLabel != "GPU") return modelPath
        val appPrivateRoots = listOfNotNull(
            context.filesDir,
            context.noBackupFilesDir,
            context.cacheDir,
            context.getExternalFilesDir(null)
        ).map { it.absoluteFile }
        val absoluteModel = modelPath.absoluteFile
        if (appPrivateRoots.any { root -> absoluteModel.path.startsWith(root.path) }) {
            return modelPath
        }
        val stagedRoot = File(context.noBackupFilesDir, "litert_lm_runtime/$modelId").apply { mkdirs() }
        val staged = File(stagedRoot, modelPath.name)
        if (modelPath.isDirectory) {
            if (!staged.exists() || staged.lastModified() < modelPath.lastModified()) {
                staged.deleteRecursively()
                modelPath.copyRecursively(staged, overwrite = true)
            }
        } else if (!staged.exists() || staged.length() != modelPath.length() || staged.lastModified() < modelPath.lastModified()) {
            modelPath.inputStream().use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            }
            staged.setLastModified(modelPath.lastModified())
        }
        diagnostic(
            "$backendLabel runtime model staged at ${staged.absolutePath} " +
                "from ${modelPath.absolutePath}"
        )
        return staged
    }

    private fun buildConversationInput(
        chat: LlamaChatEntity,
        history: List<LlamaMessageEntity>,
        thinkingEnabled: Boolean
    ): LiteRtConversationInput {
        val systemInstruction = buildString {
            append("Answer in the user's language. Use readable Markdown with blank lines before lists. ")
            if (thinkingEnabled) {
                append("Do not write analysis, reasoning, or a Thinking Process section in the final answer. ")
            } else {
                append("Do not output a thinking block. ")
            }
            chat.systemPrompt?.takeIf { it.isNotBlank() }?.let {
                append("\n\n")
                append(it.trim())
            }
        }
        val latestUserIndex = history.indexOfLast { it.role == "user" }
        val initialMessages = history
            .take(if (latestUserIndex >= 0) latestUserIndex else history.size)
            .mapNotNull { message ->
                val content = message.content.trim()
                if (content.isBlank()) {
                    null
                } else {
                    when (message.role) {
                        "assistant" -> LiteRtConversationMessage("assistant", content)
                        "system" -> LiteRtConversationMessage("system", content)
                        else -> LiteRtConversationMessage(
                            "user",
                            content,
                            imagePath = message.imagePath,
                            audioPath = message.audioPath
                        )
                    }
                }
            }
        val latestUserImagePath = history
            .getOrNull(latestUserIndex)
            ?.imagePath
            ?.takeIf { it.isNotBlank() }
        val latestUserAudioPath = history
            .getOrNull(latestUserIndex)
            ?.audioPath
            ?.takeIf { it.isNotBlank() }
        val userMessage = history
            .getOrNull(latestUserIndex)
            ?.content
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: if (latestUserImagePath != null) {
                "Describe the attached image."
            } else if (latestUserAudioPath != null) {
                "Use the attached audio to answer."
            } else {
                "Continue."
            }
        return LiteRtConversationInput(
            systemInstruction = systemInstruction,
            initialMessages = initialMessages,
            userMessage = userMessage,
            userImagePath = latestUserImagePath,
            userAudioPath = latestUserAudioPath
        )
    }

    private fun LiteRtConversationInput.fitLiteRtContext(
        request: LiteRtLmChatRequest
    ): LiteRtConversationInput {
        val backendMode = normalizeLiteRtBackend(request.backendMode)
        val fitBackendLabel = if (backendMode == LITERT_BACKEND_CPU) "CPU" else "GPU"
        val engineMaxTokens = effectiveLiteRtEngineMaxTokensForBackend(
            model = request.model,
            requestedMaxTokens = request.chat.contextSize.takeIf { it > 0 },
            backendLabel = fitBackendLabel
        ) ?: LITERT_DEFAULT_CONTEXT_TOKENS
        val promptBudget = liteRtPromptContextBudget(engineMaxTokens)
        return fitLiteRtConversationInputToBudget(promptBudget)
    }

    private fun diagnostic(message: String) {
        val line = "LiteRtLmChatService: $message"
        DebugLog.log(line)
        onDiagnostic?.invoke(message)
    }

    private data class BackendCandidate(
        val label: String,
        val backendFactory: () -> Any
    )

    private enum class LiteRtGpuBridgeMode {
        WorkerSafe,
        GalleryStyle
    }
}

private fun liteRtPromptContextBudget(engineMaxTokens: Int): Int {
    val safeMaxTokens = engineMaxTokens.coerceAtLeast(1)
    val percentBudget = (safeMaxTokens * LITERT_PROMPT_CONTEXT_SAFETY_PERCENT) / 100
    val reservedBudget = (safeMaxTokens - LITERT_PROMPT_RESERVE_TOKENS).coerceAtLeast(1)
    val minimumBudget = LITERT_MIN_PROMPT_CONTEXT_TOKENS.coerceAtMost(safeMaxTokens)
    return minOf(percentBudget, reservedBudget, safeMaxTokens)
        .coerceAtLeast(minimumBudget)
}

internal fun LiteRtToolDefinition.toLiteRtOpenApiToolJson(): String =
    JSONObject().apply {
        put("name", name)
        put("description", description)
        put(
            "parameters",
            JSONObject().apply {
                put("type", "object")
                put(
                    "properties",
                    JSONObject().apply {
                        parameters.forEach { (parameterName, parameterDescription) ->
                            put(
                                parameterName,
                                JSONObject().apply {
                                    put("type", "string")
                                    put("description", parameterDescription)
                                }
                            )
                        }
                    }
                )
                put(
                    "required",
                    JSONArray().apply { requiredParams.forEach { put(it) } }
                )
            }
        )
    }.toString()

private fun LiteRtConversationInput.renderLiteRtPromptForEstimate(): String =
    promptOverride ?: buildString {
        append(systemInstruction)
        if (tools.isNotEmpty()) {
            append("\n\nTools:\n")
            tools.forEach { tool ->
                append(tool.name)
                append(": ")
                append(tool.description)
                append('\n')
            }
        }
        initialMessages.forEach { message ->
            append("\n\n")
            append(message.role)
            append(":\n")
            append(message.content)
        }
        append("\n\nUser:\n")
        append(userMessage)
    }

private fun LiteRtConversationInput.maxImageCount(): Int {
    var count = 0
    if (!userImagePath.isNullOrBlank()) count += 1
    initialMessages.forEach { message ->
        if (!message.imagePath.isNullOrBlank()) count += 1
    }
    return count
}

private fun LiteRtConversationInput.hasAudio(): Boolean =
    !userAudioPath.isNullOrBlank() ||
        initialMessages.any { !it.audioPath.isNullOrBlank() }

private fun LiteRtConversationInput.fitLiteRtConversationInputToBudget(
    tokenBudget: Int
): LiteRtConversationInput {
    if (estimateNativeChatTextTokens(renderLiteRtPromptForEstimate()) <= tokenBudget) {
        return this
    }
    promptOverride?.let { prompt ->
        return copy(
            promptOverride = prompt.ellipsizeLiteRtTextToTokenBudget(tokenBudget, preferTail = true),
            wasTruncated = true
        )
    }

    val systemBudget = (tokenBudget / 4).coerceIn(64, 768)
    var fittedSystem = systemInstruction.ellipsizeLiteRtTextToTokenBudget(systemBudget)
    var fittedUser = userMessage
    var fitted = copy(
        systemInstruction = fittedSystem,
        initialMessages = emptyList(),
        userMessage = fittedUser,
        wasTruncated = true
    )
    if (estimateNativeChatTextTokens(fitted.renderLiteRtPromptForEstimate()) > tokenBudget) {
        val userBudget = (tokenBudget - estimateNativeChatTextTokens(fittedSystem) - 32)
            .coerceAtLeast(64)
        fittedUser = fittedUser.ellipsizeLiteRtTextToTokenBudget(userBudget, preferTail = true)
        fitted = fitted.copy(userMessage = fittedUser)
    }
    if (estimateNativeChatTextTokens(fitted.renderLiteRtPromptForEstimate()) > tokenBudget) {
        fittedSystem = fittedSystem.ellipsizeLiteRtTextToTokenBudget(tokenBudget / 6)
        val userBudget = (tokenBudget - estimateNativeChatTextTokens(fittedSystem) - 32)
            .coerceAtLeast(32)
        fitted = fitted.copy(
            systemInstruction = fittedSystem,
            userMessage = fittedUser.ellipsizeLiteRtTextToTokenBudget(userBudget, preferTail = true)
        )
    }

    val selected = mutableListOf<LiteRtConversationMessage>()
    var omitted = initialMessages.isNotEmpty()
    for (message in initialMessages.asReversed()) {
        val candidate = fitted.copy(initialMessages = listOf(message) + selected)
        if (estimateNativeChatTextTokens(candidate.renderLiteRtPromptForEstimate()) <= tokenBudget) {
            selected.add(0, message)
            omitted = selected.size < initialMessages.size
            continue
        }

        val remainingBudget = tokenBudget -
            estimateNativeChatTextTokens(
                fitted.copy(initialMessages = selected).renderLiteRtPromptForEstimate()
            )
        if (selected.isEmpty() && remainingBudget > 80) {
            val trimmedMessage = message.copy(
                content = message.content.ellipsizeLiteRtTextToTokenBudget(remainingBudget - 16, preferTail = true)
            )
            val trimmedCandidate = fitted.copy(initialMessages = listOf(trimmedMessage))
            if (estimateNativeChatTextTokens(trimmedCandidate.renderLiteRtPromptForEstimate()) <= tokenBudget) {
                selected.add(trimmedMessage)
            }
        }
        omitted = true
        break
    }

    fitted = fitted.copy(initialMessages = selected)
    if (omitted && !fitted.systemInstruction.contains(LITERT_TRUNCATED_MARKER)) {
        val withMarker = fitted.copy(
            systemInstruction = buildString {
                append(fitted.systemInstruction)
                appendLine()
                appendLine()
                append(LITERT_TRUNCATED_MARKER)
            }
        )
        fitted = if (estimateNativeChatTextTokens(withMarker.renderLiteRtPromptForEstimate()) <= tokenBudget) {
            withMarker
        } else {
            fitted
        }
    }
    return fitted
}

private fun String.ellipsizeLiteRtTextToTokenBudget(
    tokenBudget: Int,
    preferTail: Boolean = false
): String {
    if (isBlank() || estimateNativeChatTextTokens(this) <= tokenBudget) return this
    val safeBudget = tokenBudget.coerceAtLeast(1)
    var low = 1
    var high = length
    var best = take(1)
    while (low <= high) {
        val mid = (low + high) / 2
        val candidate = ellipsizeLiteRtTextToChars(mid, preferTail)
        if (estimateNativeChatTextTokens(candidate) <= safeBudget) {
            best = candidate
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}

private fun String.ellipsizeLiteRtTextToChars(
    maxChars: Int,
    preferTail: Boolean
): String {
    if (length <= maxChars) return this
    if (maxChars <= LITERT_TRUNCATED_CONTENT_MARKER.length + 2) {
        return if (preferTail) takeLast(maxChars) else take(maxChars)
    }
    val available = maxChars - LITERT_TRUNCATED_CONTENT_MARKER.length
    val head = if (preferTail) {
        (available * 0.35f).toInt().coerceAtLeast(1)
    } else {
        (available * 0.7f).toInt().coerceAtLeast(1)
    }
    val tail = (available - head).coerceAtLeast(1)
    return take(head).trimEnd() + LITERT_TRUNCATED_CONTENT_MARKER + takeLast(tail).trimStart()
}

internal fun fitLiteRtConversationOverrideForContext(
    conversation: LiteRtConversationOverride,
    model: LiteRtModelEntity,
    contextSize: Int
): LiteRtConversationOverride {
    val engineMaxTokens = effectiveLiteRtEngineMaxTokens(
        model = model,
        requestedMaxTokens = contextSize.takeIf { it > 0 }
    ) ?: LITERT_DEFAULT_CONTEXT_TOKENS
    val fitted = LiteRtConversationInput(
        systemInstruction = conversation.systemInstruction,
        initialMessages = conversation.initialMessages,
        userMessage = conversation.userMessage,
        userImagePath = conversation.userImagePath,
        userAudioPath = conversation.userAudioPath,
        tools = conversation.tools
    ).fitLiteRtConversationInputToBudget(liteRtPromptContextBudget(engineMaxTokens))
    return conversation.copy(
        systemInstruction = fitted.systemInstruction,
        initialMessages = fitted.initialMessages,
        userMessage = fitted.userMessage,
        userImagePath = fitted.userImagePath,
        userAudioPath = fitted.userAudioPath,
        tools = fitted.tools
    )
}

private class LiteRtLmReflectionBridge(private val context: Context) {
    private val backendClass by lazy { loadClass("com.google.ai.edge.litertlm.Backend") }
    private val engineConfigClass by lazy { loadClass("com.google.ai.edge.litertlm.EngineConfig") }
    private val engineClass by lazy { loadClass("com.google.ai.edge.litertlm.Engine") }
    private val conversationConfigClass by lazy { loadClass("com.google.ai.edge.litertlm.ConversationConfig") }
    private val contentsClass by lazy { loadClass("com.google.ai.edge.litertlm.Contents") }
    private val messageClass by lazy { loadClass("com.google.ai.edge.litertlm.Message") }
    private val samplerConfigClass by lazy { loadClass("com.google.ai.edge.litertlm.SamplerConfig") }
    private val messageCallbackClass by lazy { loadClass("com.google.ai.edge.litertlm.MessageCallback") }
    private val contentClass by lazy { loadClass("com.google.ai.edge.litertlm.Content") }
    private val contentTextClass by lazy { loadClass("com.google.ai.edge.litertlm.Content\$Text") }
    private val contentImageFileClass by lazy { loadClass("com.google.ai.edge.litertlm.Content\$ImageFile") }
    private val contentAudioFileClass by lazy { loadClass("com.google.ai.edge.litertlm.Content\$AudioFile") }
    private val toolCallClass by lazy { loadClass("com.google.ai.edge.litertlm.ToolCall") }
    private val openApiToolClass by lazy { loadClass("com.google.ai.edge.litertlm.OpenApiTool") }

    fun createBackend(label: String): Any = when (label) {
        "GPU" -> loadClass("com.google.ai.edge.litertlm.Backend\$GPU")
            .getConstructor()
            .newInstance()
        else -> loadClass("com.google.ai.edge.litertlm.Backend\$CPU")
            .getConstructor()
            .newInstance()
    }

    fun hasLoadedEngine(
        model: LiteRtModelEntity,
        backendMode: String,
        contextSize: Int,
        mtpEnabled: Boolean
    ): Boolean = synchronized(engineCacheLock) {
        engineCache.keys.any { it.matchesModelRequest(model, backendMode, contextSize, mtpEnabled) }
    }

    fun unloadLoadedEngines(
        model: LiteRtModelEntity,
        backendMode: String,
        contextSize: Int,
        mtpEnabled: Boolean
    ): Int {
        val cached = synchronized(engineCacheLock) {
            val matches = engineCache
                .filterKeys { it.matchesModelRequest(model, backendMode, contextSize, mtpEnabled) }
                .values
                .toList()
            matches.forEach { cached ->
                cached.closeJob?.cancel()
                engineCache.remove(cached.key)
            }
            matches
        }
        cached.forEach { engine ->
            runCatching { engine.engineClass.getMethod("close").invoke(engine.engine) }
                .onFailure { error ->
                    DebugLog.log("LiteRT-LM Engine manual unload failed: ${error.liteRtDiagnosticMessage()}")
                }
        }
        return cached.size
    }

    fun runtimeDiagnosticLines(backend: Any): List<String> = buildList {
        add(
            "LiteRT runtime classloaders app=${context.classLoader} " +
                "thread=${Thread.currentThread().contextClassLoader} backend=${backendClass.classLoader}"
        )
        add(
            "LiteRT runtime classes backend=${backendClass.name} engineConfig=${engineConfigClass.name} " +
                "engine=${engineClass.name} conversationConfig=${conversationConfigClass.name} " +
                "contents=${contentsClass.name} message=${messageClass.name} sampler=${samplerConfigClass.name} " +
                "callback=${messageCallbackClass.name}"
        )
        add(
            "LiteRT runtime backendObject class=${backend.javaClass.name} " +
                "classLoader=${backend.javaClass.classLoader} toString=${backend.toString().truncateLiteRtDiagnostic(220)}"
        )
        add("LiteRT runtime EngineConfig constructors=${constructorSummary(engineConfigClass)}")
        add("LiteRT runtime Engine constructors=${constructorSummary(engineClass)}")
        add("LiteRT runtime Engine methods=${methodSummary(engineClass, setOf("initialize", "createConversation", "close"))}")
        add("LiteRT runtime ConversationConfig constructors=${constructorSummary(conversationConfigClass)}")
        add("LiteRT runtime SamplerConfig constructors=${constructorSummary(samplerConfigClass)}")
        add("LiteRT runtime Contents methods=${methodSummary(contentsClass, setOf("getContents", "of", "toString"))}")
        add("LiteRT runtime Message methods=${methodSummary(messageClass, setOf("getContents", "getChannels", "getToolCalls", "toString"))}")
        add(
            "LiteRT runtime multimodal text=${constructorSummary(contentTextClass)} " +
                "imageFile=${constructorSummary(contentImageFileClass)} " +
                "audioFile=${constructorSummary(contentAudioFileClass)}"
        )
        add("LiteRT runtime callback methods=${methodSummary(messageCallbackClass, setOf("onMessage", "onDone", "onError"))}")
        add("LiteRT runtime Backend nested=${backendClass.declaredClasses.joinToString(",") { it.name }.truncateLiteRtDiagnostic(600)}")
        add(
            "LiteRT runtime tools=" +
                runCatching {
                    val toolKt = loadClass("com.google.ai.edge.litertlm.ToolKt")
                    "openApiTool=${openApiToolClass.name} toolCall=${toolCallClass.name} " +
                        "contentToolResponse=${loadClass("com.google.ai.edge.litertlm.Content\$ToolResponse").name} " +
                        "toolKt=${methodSummary(toolKt, setOf("tool"))}"
                }.getOrElse { "unavailable:${it.liteRtDiagnosticMessage()}" }
        )
        add(
            "LiteRT runtime ExperimentalFlags=" +
                runCatching {
                    val flagsClass = loadClass("com.google.ai.edge.litertlm.ExperimentalFlags")
                    "class=${flagsClass.name} fields=${flagsClass.fields.joinToString(",") { it.name }} " +
                        "methods=${methodSummary(flagsClass, setOf("setEnableSpeculativeDecoding", "getEnableSpeculativeDecoding", "setEnableMtp", "getEnableMtp", "setEnableMultiTokenPrediction", "getEnableMultiTokenPrediction"))}"
                }.getOrElse { "unavailable:${it.liteRtDiagnosticMessage()}" }
        )
    }

    fun loadCoreLibraries(onDiagnostic: (String) -> Unit) {
        if (coreLibrariesLoaded.get()) {
            onDiagnostic("LiteRT core native libraries already loaded")
            return
        }
        synchronized(coreLibrariesLoaded) {
            if (coreLibrariesLoaded.get()) {
                onDiagnostic("LiteRT core native libraries already loaded")
                return
            }
            onDiagnostic("loading LiteRT core native libraries")
            try {
                System.loadLibrary("LiteRt")
                System.loadLibrary("litertlm_jni")
                coreLibrariesLoaded.set(true)
                onDiagnostic("LiteRT core native libraries loaded")
            } catch (e: UnsatisfiedLinkError) {
                throw IllegalStateException(
                    context.getString(
                        R.string.litert_error_runtime_unavailable_detail,
                        e.message ?: e.javaClass.name
                    ),
                    e
                )
            }
        }
    }

    fun loadGpuAccelerator(onDiagnostic: (String) -> Unit) {
        if (gpuAcceleratorLoaded.get()) {
            onDiagnostic("GPU accelerator native library already loaded")
            return
        }
        synchronized(gpuAcceleratorLoaded) {
            if (gpuAcceleratorLoaded.get()) {
                onDiagnostic("GPU accelerator native library already loaded")
                return
            }
            onDiagnostic("loading GPU accelerator native library LiteRtClGlAccelerator")
            try {
                System.loadLibrary("LiteRtClGlAccelerator")
                gpuAcceleratorLoaded.set(true)
                onDiagnostic("GPU accelerator native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                throw IllegalStateException(
                    context.getString(
                        R.string.litert_error_gpu_runtime_missing,
                        e.message ?: e.javaClass.name
                    ),
                    e
                )
            }
        }
    }

    fun setSpeculativeDecodingEnabled(enabled: Boolean, onDiagnostic: (String) -> Unit) {
        runCatching {
            val flagsClass = loadClass("com.google.ai.edge.litertlm.ExperimentalFlags")
            val instance = flagsClass.getField("INSTANCE").get(null)
            flagsClass.getMethod("setEnableSpeculativeDecoding", java.lang.Boolean::class.java)
                .invoke(instance, java.lang.Boolean.valueOf(enabled))
            onDiagnostic(
                "LiteRT-LM speculative decoding/MTP ${if (enabled) "enabled" else "disabled"}"
            )
        }.onFailure { error ->
            onDiagnostic("LiteRT-LM speculative decoding flag unavailable: ${error.liteRtDiagnosticMessage()}")
        }
    }

    fun resetSpeculativeDecoding(onDiagnostic: (String) -> Unit) {
        runCatching {
            val flagsClass = loadClass("com.google.ai.edge.litertlm.ExperimentalFlags")
            val instance = flagsClass.getField("INSTANCE").get(null)
            flagsClass.getMethod("setEnableSpeculativeDecoding", java.lang.Boolean::class.java)
                .invoke(instance, java.lang.Boolean.FALSE)
            onDiagnostic("LiteRT-LM speculative decoding/MTP reset after generation")
        }.onFailure { error ->
            onDiagnostic("LiteRT-LM speculative decoding reset unavailable: ${error.liteRtDiagnosticMessage()}")
        }
    }

    suspend fun generate(
        modelPath: File,
        backend: Any,
        backendLabel: String,
        maxTokens: Int?,
        maxOutputTokens: Int?,
        cacheDir: File?,
        input: LiteRtConversationInput,
        thinkingEnabled: Boolean,
        speculativeDecodingEnabled: Boolean,
        topK: Int,
        topP: Double,
        temperature: Double,
        seed: Int,
        holdGpuEglContext: Boolean,
        onDiagnostic: (String) -> Unit,
        onChunk: suspend (String) -> Unit,
        onThinkingChunk: suspend (String) -> Unit
    ): LiteRtGenerationText = coroutineScope {
        setSpeculativeDecodingEnabled(speculativeDecodingEnabled, onDiagnostic)
        val hasAudio = input.hasAudio()
        onDiagnostic(
            "building EngineConfig backend=$backendLabel maxTokens=${maxTokens ?: "default"} " +
                "maxOutputTokens=${maxOutputTokens ?: "default"} " +
                "maxImages=${input.maxImageCount().takeIf { it > 0 } ?: "none"} " +
                "audio=${if (hasAudio) "enabled" else "none"} " +
                "cacheDir=${cacheDir?.absolutePath ?: "default"} mtp=$speculativeDecodingEnabled " +
                "thread=${Thread.currentThread().name}"
        )
        val maxImages = input.maxImageCount().takeIf { it > 0 }
        val cachedEngine = getOrCreateCachedEngine(
            modelPath = modelPath,
            backend = backend,
            backendLabel = backendLabel,
            maxTokens = maxTokens,
            maxImages = maxImages,
            audioEnabled = hasAudio,
            cacheDir = cacheDir,
            holdGpuEglContext = holdGpuEglContext,
            speculativeDecodingEnabled = speculativeDecodingEnabled,
            onDiagnostic = onDiagnostic
        )
        try {
            cachedEngine.mutex.withLock {
                cachedEngine.lastUsedAtMs = System.currentTimeMillis()
                val samplerConfig = samplerConfigClass
                    .getConstructor(
                        Integer.TYPE,
                        java.lang.Double.TYPE,
                        java.lang.Double.TYPE,
                        Integer.TYPE
                    )
                    .newInstance(topK, topP, temperature, seed)
                onDiagnostic("creating ConversationConfig backend=$backendLabel sampler=${samplerConfig != null}")
                val extraContext = liteRtExtraContext(thinkingEnabled)
                val systemInstruction = input.promptOverride
                    ?.let { null }
                    ?: input.systemInstruction.takeIf { it.isNotBlank() }?.let { createContents(it) }
                val initialMessages = if (input.promptOverride != null) {
                    emptyList<Any>()
                } else {
                    input.initialMessages.mapNotNull { message ->
                        createMessage(message, onDiagnostic)
                            ?: run {
                                onDiagnostic(
                                    "LiteRT dropped initial message role=${message.role} " +
                                        "toolCalls=${message.toolCalls.size} " +
                                        "toolName=${message.toolName.orEmpty()}"
                                )
                                null
                            }
                    }
                }
                val toolProviders = createToolProviders(input.tools, onDiagnostic)
                val conversationConfig = conversationConfigClass
                    .getConstructor(
                        contentsClass,
                        List::class.java,
                        List::class.java,
                        samplerConfigClass,
                        java.lang.Boolean.TYPE,
                        List::class.java,
                        Map::class.java
                    )
                    .newInstance(
                        systemInstruction,
                        initialMessages,
                        toolProviders,
                        samplerConfig,
                        false,
                        emptyList<Any>(),
                        extraContext
                    )
                onDiagnostic(
                    "ConversationConfig extraContext keys=${extraContext.keys.joinToString(",")} " +
                        "thinking=$thinkingEnabled tools=${toolProviders.size} automaticToolCalling=false"
                )
                onDiagnostic("creating Conversation backend=$backendLabel")
                val conversation = engineClass
                    .getMethod("createConversation", conversationConfigClass)
                    .invoke(cachedEngine.engine, conversationConfig)
                    ?: error("LiteRT-LM did not create a conversation")
                try {
                    onDiagnostic("sending first async message backend=$backendLabel")
                    generateStreaming(
                        conversation = conversation,
                        input = input,
                        thinkingEnabled = thinkingEnabled,
                        maxOutputTokens = maxOutputTokens,
                        onDiagnostic = onDiagnostic,
                        onChunk = onChunk,
                        onThinkingChunk = onThinkingChunk
                    )
                } finally {
                    runCatching { conversation.javaClass.getMethod("close").invoke(conversation) }
                }
            }
        } finally {
            scheduleCachedEngineClose(cachedEngine, onDiagnostic)
        }
    }

    private fun getOrCreateCachedEngine(
        modelPath: File,
        backend: Any,
        backendLabel: String,
        maxTokens: Int?,
        maxImages: Int?,
        audioEnabled: Boolean,
        cacheDir: File?,
        holdGpuEglContext: Boolean,
        speculativeDecodingEnabled: Boolean,
        onDiagnostic: (String) -> Unit
    ): CachedEngine {
        val key = EngineCacheKey(
            modelPath = modelPath.absolutePath,
            backendLabel = backendLabel,
            maxTokens = maxTokens,
            maxImages = maxImages,
            audioEnabled = audioEnabled,
            cacheDir = cacheDir?.absolutePath,
            speculativeDecodingEnabled = speculativeDecodingEnabled
        )
        synchronized(engineCacheLock) {
            engineCache[key]?.let { cached ->
                cached.closeJob?.cancel()
                cached.lastUsedAtMs = System.currentTimeMillis()
                onDiagnostic(
                    "reusing loaded Engine backend=$backendLabel model=${modelPath.name} " +
                        "idleTimeoutMs=$ENGINE_IDLE_TIMEOUT_MS"
                )
                return cached
            }
        }

        val audioBackend = if (audioEnabled) createBackend("CPU") else null
        val engineConfig = engineConfigClass
            .getConstructor(
                String::class.java,
                backendClass,
                backendClass,
                backendClass,
                Integer::class.java,
                Integer::class.java,
                String::class.java
            )
            .newInstance(
                modelPath.absolutePath,
                backend,
                maxImages?.let { backend },
                audioBackend,
                maxTokens?.let { Integer.valueOf(it) },
                maxImages?.let { Integer.valueOf(it) },
                cacheDir?.absolutePath
            )
        onDiagnostic("creating Engine backend=$backendLabel")
        val engine = engineClass.getConstructor(engineConfigClass).newInstance(engineConfig)
        try {
            onDiagnostic("initializing Engine backend=$backendLabel thread=${Thread.currentThread().name}")
            val gpuContext = if (backendLabel == "GPU" && holdGpuEglContext) {
                LiteRtGpuProbe.createCurrentContext().also { context ->
                    onDiagnostic(
                        "GPU EGL context held current for Engine.initialize " +
                            "renderer=${context.probe.renderer.ifBlank { "-" }}"
                    )
                }
            } else {
                null
            }
            try {
                engineClass.getMethod("initialize").invoke(engine)
            } finally {
                gpuContext?.close()
            }
            onDiagnostic("Engine initialized backend=$backendLabel")
        } catch (error: Throwable) {
            runCatching { engineClass.getMethod("close").invoke(engine) }
            throw error
        }

        val cached = CachedEngine(
            key = key,
            engine = engine,
            engineClass = engineClass,
            mutex = Mutex(),
            lastUsedAtMs = System.currentTimeMillis()
        )
        synchronized(engineCacheLock) {
            engineCache[key]?.let { existing ->
                runCatching { engineClass.getMethod("close").invoke(engine) }
                existing.closeJob?.cancel()
                existing.lastUsedAtMs = System.currentTimeMillis()
                onDiagnostic(
                    "reusing loaded Engine backend=$backendLabel model=${modelPath.name} " +
                        "idleTimeoutMs=$ENGINE_IDLE_TIMEOUT_MS"
                )
                return existing
            }
            engineCache[key] = cached
        }
        return cached
    }

    private fun scheduleCachedEngineClose(
        cached: CachedEngine,
        onDiagnostic: (String) -> Unit
    ) {
        cached.lastUsedAtMs = System.currentTimeMillis()
        cached.closeJob?.cancel()
        cached.closeJob = engineCacheScope.launch {
            delay(ENGINE_IDLE_TIMEOUT_MS)
            var shouldReschedule = false
            val shouldClose = synchronized(engineCacheLock) {
                val current = engineCache[cached.key]
                val idleForMs = System.currentTimeMillis() - cached.lastUsedAtMs
                if (current === cached && !cached.mutex.isLocked && idleForMs >= ENGINE_IDLE_TIMEOUT_MS) {
                    engineCache.remove(cached.key)
                    true
                } else {
                    shouldReschedule = current === cached
                    false
                }
            }
            if (shouldClose) {
                val message = "LiteRT-LM Engine idle timeout closing backend=${cached.key.backendLabel} " +
                    "model=${File(cached.key.modelPath).name} idleTimeoutMs=$ENGINE_IDLE_TIMEOUT_MS"
                DebugLog.log(message)
                onDiagnostic(message)
                runCatching { cached.engineClass.getMethod("close").invoke(cached.engine) }
                    .onFailure { error ->
                        DebugLog.log("LiteRT-LM Engine idle close failed: ${error.liteRtDiagnosticMessage()}")
                    }
            } else if (shouldReschedule) {
                scheduleCachedEngineClose(cached, onDiagnostic)
            }
        }
    }

    private suspend fun generateStreaming(
        conversation: Any,
        input: LiteRtConversationInput,
        thinkingEnabled: Boolean,
        maxOutputTokens: Int?,
        onDiagnostic: (String) -> Unit,
        onChunk: suspend (String) -> Unit,
        onThinkingChunk: suspend (String) -> Unit
    ): LiteRtGenerationText = coroutineScope {
        val completion = CompletableDeferred<Unit>()
        val snapshots = Channel<LiteRtMessageSnapshot>(Channel.UNLIMITED)
        val callbackError = AtomicReference<Throwable?>(null)
        val rendered = StringBuilder()
        val metered = StringBuilder()
        val structuredToolCalls = mutableListOf<OllamaService.ToolCall>()
        val contentAssembler = LiteRtStreamTextAssembler()
        val thoughtAssembler = LiteRtStreamTextAssembler()
        val leakedThinkingFilter = LiteRtLeakedThinkingStreamFilter()
        val outputLimitReached = AtomicBoolean(false)
        val callbackDiagnosticsLogged = AtomicInteger(0)

        val collector = launch {
            suspend fun appendSnapshot(snapshot: LiteRtMessageSnapshot) {
                if (snapshot.toolCalls.isNotEmpty()) {
                    snapshot.toolCalls.forEach { call ->
                        if (structuredToolCalls.none { existing ->
                                existing.name == call.name && existing.arguments == call.arguments
                            }
                        ) {
                            structuredToolCalls += call
                        }
                    }
                }
                val thoughtDelta = thoughtAssembler.appendSnapshot(
                    sanitizeLiteRtRenderedTextForStreaming(snapshot.thought)
                )
                if (thoughtDelta.isNotEmpty()) {
                    metered.append(thoughtDelta)
                    onThinkingChunk(thoughtDelta)
                }
                val delta = contentAssembler.appendSnapshot(
                    sanitizeLiteRtRenderedTextForStreaming(snapshot.text)
                )
                if (delta.isNotEmpty()) {
                    rendered.append(delta)
                    metered.append(delta)
                    onChunk(delta)
                    if (maxOutputTokens != null &&
                        estimateLiteRtCompletionTokens(rendered.toString()) >= maxOutputTokens
                    ) {
                        outputLimitReached.set(true)
                        completion.complete(Unit)
                    }
                }
            }
            for (snapshot in snapshots) {
                currentCoroutineContext().ensureActive()
                appendSnapshot(leakedThinkingFilter.filter(snapshot))
            }
            leakedThinkingFilter.finish()?.let { appendSnapshot(it) }
            val finalThoughtDelta = thoughtAssembler.finish()
            if (finalThoughtDelta.isNotEmpty()) {
                metered.append(finalThoughtDelta)
                onThinkingChunk(finalThoughtDelta)
            }
            val finalDelta = contentAssembler.finish()
            if (finalDelta.isNotEmpty()) {
                rendered.append(finalDelta)
                metered.append(finalDelta)
                onChunk(finalDelta)
            }
        }

        val callback = Proxy.newProxyInstance(
            context.classLoader,
            arrayOf(messageCallbackClass)
        ) { _, method, args ->
            when (method.name) {
                "onMessage" -> {
                    val message = args?.firstOrNull()
                    if (message != null) {
                        val snapshot = liteRtMessageSnapshot(message, thinkingEnabled)
                        val diagnosticIndex = callbackDiagnosticsLogged.getAndIncrement()
                        if (diagnosticIndex < 3) {
                            onDiagnostic(
                                "LiteRT callback snapshot#${diagnosticIndex + 1} " +
                                    "channelKeys=${message.liteRtChannelKeySummary()} " +
                                    "visibleChars=${snapshot.text.length} " +
                                    "thoughtChars=${snapshot.thought.length} " +
                                    "rawChars=${snapshot.rawText.length} " +
                                    "toolCalls=${snapshot.toolCalls.size} " +
                                    "thinkingSource=${snapshot.thinkingSource}"
                            )
                        }
                        if (
                            snapshot.text.isNotEmpty() ||
                            snapshot.thought.isNotEmpty() ||
                            snapshot.rawText.isNotEmpty() ||
                            snapshot.toolCalls.isNotEmpty()
                        ) {
                            snapshots.trySend(snapshot)
                        }
                    }
                    null
                }
                "onDone" -> {
                    completion.complete(Unit)
                    null
                }
                "onError" -> {
                    val failure = (args?.firstOrNull() as? Throwable)
                        ?.liteRtRootCause()
                        ?: IllegalStateException("LiteRT-LM streaming callback failed")
                    onDiagnostic("streaming callback error: ${failure.liteRtDiagnosticMessage()}")
                    callbackError.compareAndSet(null, failure)
                    completion.completeExceptionally(failure)
                    null
                }
                else -> null
            }
        }

        var finishedNormally = false
        try {
            val extraContext = liteRtExtraContext(thinkingEnabled)
            val messageContents = createContents(
                text = input.promptOverride ?: input.userMessage,
                imagePath = input.promptOverride?.let { null } ?: input.userImagePath,
                audioPath = input.promptOverride?.let { null } ?: input.userAudioPath
            )
            conversation.javaClass
                .getMethod("sendMessageAsync", contentsClass, messageCallbackClass, Map::class.java)
                .invoke(conversation, messageContents, callback, extraContext)
            onDiagnostic("async message accepted by LiteRT-LM extraContextKeys=${extraContext.keys.joinToString(",")}")
            completion.await()
            finishedNormally = true
        } finally {
            if (!finishedNormally || outputLimitReached.get()) {
                runCatching { conversation.javaClass.getMethod("cancelProcess").invoke(conversation) }
            }
            snapshots.close(callbackError.get())
            collector.join()
        }

        LiteRtGenerationText(
            visibleText = rendered.toString(),
            meteredText = metered.toString(),
            toolCalls = structuredToolCalls.toList()
        )
    }

    private fun createContents(
        text: String,
        imagePath: String? = null,
        audioPath: String? = null
    ): Any {
        val imageFile = imagePath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.exists() && it.isFile }
        val audioFile = audioPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.exists() && it.isFile }
        if (imageFile != null || audioFile != null) {
            val textContent = contentTextClass
                .getConstructor(String::class.java)
                .newInstance(text)
            val mediaContents = buildList {
                imageFile?.let { file ->
                    add(
                        contentImageFileClass
                            .getConstructor(String::class.java)
                            .newInstance(file.absolutePath)
                    )
                }
                audioFile?.let { file ->
                    add(
                        contentAudioFileClass
                            .getConstructor(String::class.java)
                            .newInstance(file.absolutePath)
                    )
                }
                add(textContent)
            }
            return createContentsFromContentList(mediaContents)
        }
        val companion = contentsClass.getField("Companion").get(null)
        return companion.javaClass.getMethod("of", String::class.java).invoke(companion, text)
            ?: error("LiteRT-LM did not create Contents")
    }

    private fun createMessage(
        message: LiteRtConversationMessage,
        onDiagnostic: (String) -> Unit
    ): Any? {
        val companion = messageClass.getField("Companion").get(null)
        val role = message.role.lowercase()
        if (role == "assistant" || role == "model") {
            return if (message.toolCalls.isNotEmpty()) {
                createModelMessageWithToolCalls(message, onDiagnostic)
            } else {
                companion.javaClass.methods.firstOrNull { method ->
                    method.name == "model" &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes.first() == String::class.java
                }?.invoke(companion, message.content)
            }
        }
        if (role == "tool") {
            return createToolResponseMessage(message, onDiagnostic)
        }
        val methodName = if (role == "system") "system" else "user"
        val contents = createContents(
            message.content,
            imagePath = message.imagePath,
            audioPath = message.audioPath
        )
        return createMessageFromContents(companion, methodName, contents)
    }

    private fun createMessageFromContents(
        companion: Any,
        methodName: String,
        contents: Any
    ): Any? {
        val method = companion.javaClass.methods.firstOrNull { method ->
            method.name == methodName &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes.first() == contentsClass
        } ?: return null
        return method.invoke(companion, contents)
    }

    private fun createModelMessageWithToolCalls(
        message: LiteRtConversationMessage,
        onDiagnostic: (String) -> Unit
    ): Any? = runCatching {
        val companion = messageClass.getField("Companion").get(null)
        val contents = createContents(message.content)
        val toolCalls = message.toolCalls.map { createLiteRtToolCall(it) }
        companion.javaClass.methods.firstOrNull { method ->
            method.name == "model" &&
                method.parameterTypes.size == 3 &&
                method.parameterTypes[0] == contentsClass &&
                List::class.java.isAssignableFrom(method.parameterTypes[1]) &&
                Map::class.java.isAssignableFrom(method.parameterTypes[2])
        }?.invoke(companion, contents, toolCalls, emptyMap<String, String>())
    }.getOrElse { error ->
        onDiagnostic("LiteRT model tool-call message unavailable: ${error.liteRtDiagnosticMessage()}")
        null
    } ?: runCatching {
        val companion = messageClass.getField("Companion").get(null)
        val fallback = buildString {
            appendLine(message.content.ifBlank { "Assistant requested app tool calls." })
            message.toolCalls.forEach { call ->
                appendLine("<tool_call>${JSONObject().apply {
                    put("name", call.name)
                    put("arguments", JSONObject(call.arguments))
                }}</tool_call>")
            }
        }.trim()
        companion.javaClass.methods.firstOrNull { method ->
            method.name == "model" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes.first() == String::class.java
        }?.invoke(companion, fallback)
    }.getOrElse { error ->
        onDiagnostic("LiteRT model tool-call fallback message unavailable: ${error.liteRtDiagnosticMessage()}")
        null
    }

    private fun createToolResponseMessage(
        message: LiteRtConversationMessage,
        onDiagnostic: (String) -> Unit
    ): Any? = runCatching {
        val toolName = message.toolName?.takeIf { it.isNotBlank() }
            ?: message.toolCalls.firstOrNull()?.name
            ?: "tool"
        val toolResponseClass = loadClass("com.google.ai.edge.litertlm.Content\$ToolResponse")
        val toolResponse = toolResponseClass
            .getConstructor(String::class.java, Any::class.java)
            .newInstance(toolName, message.content)
        val contents = createContentsFromContent(toolResponse)
        val companion = messageClass.getField("Companion").get(null)
        companion.javaClass.methods.firstOrNull { method ->
            method.name == "tool" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes.first() == contentsClass
        }?.invoke(companion, contents)
    }.getOrElse { error ->
        onDiagnostic("LiteRT tool-response message unavailable: ${error.liteRtDiagnosticMessage()}")
        null
    } ?: runCatching {
        val companion = messageClass.getField("Companion").get(null)
        val toolName = message.toolName?.takeIf { it.isNotBlank() }
            ?: message.toolCalls.firstOrNull()?.name
            ?: "tool"
        val fallbackContents = createContents(
            buildString {
                appendLine("Tool result for $toolName:")
                appendLine(message.content)
                appendLine()
                appendLine("Use this tool result to answer the user. Do not call $toolName again unless the result is insufficient.")
            }
        )
        createMessageFromContents(companion, "user", fallbackContents)
    }.getOrElse { error ->
        onDiagnostic("LiteRT tool-response fallback user message unavailable: ${error.liteRtDiagnosticMessage()}")
        null
    }

    private fun createContentsFromContent(content: Any): Any {
        return createContentsFromContentList(listOf(content))
    }

    private fun createContentsFromContentList(contents: List<Any>): Any {
        val companion = contentsClass.getField("Companion").get(null)
        val contentArray = java.lang.reflect.Array.newInstance(contentClass, contents.size).also { array ->
            contents.forEachIndexed { index, content ->
                java.lang.reflect.Array.set(array, index, content)
            }
        }
        return companion.javaClass.methods.firstOrNull { method ->
            method.name == "of" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes.first().isArray &&
                method.parameterTypes.first().componentType == contentClass
        }?.invoke(companion, contentArray)
            ?: companion.javaClass.methods.firstOrNull { method ->
                method.name == "of" &&
                    method.parameterTypes.size == 1 &&
                    List::class.java.isAssignableFrom(method.parameterTypes.first())
            }?.invoke(companion, contents)
            ?: error("LiteRT-LM did not create Contents")
    }

    private fun createLiteRtToolCall(call: LiteRtToolCallSpec): Any =
        toolCallClass
            .getConstructor(String::class.java, Map::class.java)
            .newInstance(call.name, call.arguments)

    private fun createToolProviders(
        tools: List<LiteRtToolDefinition>,
        onDiagnostic: (String) -> Unit
    ): List<Any> {
        if (tools.isEmpty()) return emptyList()
        val toolKt = runCatching { loadClass("com.google.ai.edge.litertlm.ToolKt") }
            .getOrElse { error ->
                onDiagnostic("LiteRT structured tools unavailable: ${error.liteRtDiagnosticMessage()}")
                return emptyList()
            }
        val toolMethod = toolKt.methods.firstOrNull { method ->
            method.name == "tool" &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes.first() == openApiToolClass
        } ?: run {
            onDiagnostic("LiteRT structured tools unavailable: ToolKt.tool(OpenApiTool) missing")
            return emptyList()
        }
        val providers = tools.mapNotNull { tool ->
            runCatching {
                val openApiTool = Proxy.newProxyInstance(
                    context.classLoader,
                    arrayOf(openApiToolClass)
                ) { _, method, args ->
                    when (method.name) {
                        "getToolDescriptionJsonString" -> tool.toLiteRtOpenApiToolJson()
                        "execute" -> {
                            val arguments = args?.firstOrNull()?.toString().orEmpty()
                            JSONObject().apply {
                                put("status", "manual_execution_required")
                                put("tool", tool.name)
                                put("arguments", arguments)
                            }.toString()
                        }
                        "toString" -> "LiteRtOpenApiTool(${tool.name})"
                        "hashCode" -> tool.name.hashCode()
                        "equals" -> false
                        else -> null
                    }
                }
                toolMethod.invoke(null, openApiTool)
            }.onFailure { error ->
                onDiagnostic("LiteRT structured tool ${tool.name} unavailable: ${error.liteRtDiagnosticMessage()}")
            }.getOrNull()
        }
        onDiagnostic("LiteRT structured tools exposed=${providers.size} requested=${tools.size}")
        return providers
    }

    private fun loadClass(name: String): Class<*> = try {
        Class.forName(name, true, context.classLoader)
    } catch (e: Throwable) {
        val detail = e.liteRtDiagnosticMessage()
        throw IllegalStateException(context.getString(R.string.litert_error_runtime_unavailable_detail, detail), e)
    }

    private fun constructorSummary(type: Class<*>): String =
        runCatching {
            type.constructors
                .sortedBy { it.parameterTypes.size }
                .joinToString("; ") { constructor ->
                    constructor.parameterTypes.joinToString(
                        prefix = "${type.simpleName}(",
                        postfix = ")"
                    ) { it.liteRtTypeName() }
                }
                .ifBlank { "none" }
                .truncateLiteRtDiagnostic(900)
        }.getOrElse { "error:${it.liteRtDiagnosticMessage()}" }

    private fun methodSummary(type: Class<*>, names: Set<String>): String =
        runCatching {
            type.methods
                .filter { it.name in names }
                .sortedWith(compareBy({ it.name }, { it.parameterTypes.size }))
                .joinToString("; ") { method ->
                    method.parameterTypes.joinToString(
                        prefix = "${method.name}(",
                        postfix = "):${method.returnType.liteRtTypeName()}"
                    ) { it.liteRtTypeName() }
                }
                .ifBlank { "none" }
                .truncateLiteRtDiagnostic(900)
        }.getOrElse { "error:${it.liteRtDiagnosticMessage()}" }

    private data class EngineCacheKey(
        val modelPath: String,
        val backendLabel: String,
        val maxTokens: Int?,
        val maxImages: Int?,
        val audioEnabled: Boolean,
        val cacheDir: String?,
        val speculativeDecodingEnabled: Boolean
    )

    private class CachedEngine(
        val key: EngineCacheKey,
        val engine: Any,
        val engineClass: Class<*>,
        val mutex: Mutex,
        @Volatile var lastUsedAtMs: Long,
        @Volatile var closeJob: Job? = null
    )

    private companion object {
        val coreLibrariesLoaded = AtomicBoolean(false)
        val gpuAcceleratorLoaded = AtomicBoolean(false)
        const val ENGINE_IDLE_TIMEOUT_MS = 5 * 60 * 1000L
        val engineCacheLock = Any()
        val engineCacheScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engineCache = mutableMapOf<EngineCacheKey, CachedEngine>()

        private fun EngineCacheKey.matchesModelRequest(
            model: LiteRtModelEntity,
            backendMode: String,
            contextSize: Int,
            mtpEnabled: Boolean
        ): Boolean {
            val requestedFile = File(model.path)
            val cachedFile = File(modelPath)
            val modelMatches = modelPath == requestedFile.absolutePath ||
                modelPath == model.path ||
                cachedFile.name == requestedFile.name
            if (!modelMatches || speculativeDecodingEnabled != mtpEnabled) return false
            val backendMatches = when (backendMode) {
                LITERT_BACKEND_CPU -> backendLabel == "CPU"
                LITERT_BACKEND_GPU -> backendLabel == "GPU"
                else -> backendLabel == "CPU" || backendLabel == "GPU"
            }
            if (!backendMatches) return false
            return contextSize <= 0 || maxTokens == null || maxTokens > 0
        }
    }
}

private fun Class<*>.liteRtTypeName(): String =
    canonicalName ?: name

private fun String.truncateLiteRtDiagnostic(maxChars: Int): String {
    if (length <= maxChars) return replace('\n', ' ')
    val head = (maxChars / 2).coerceAtLeast(1)
    val tail = (maxChars - head - 3).coerceAtLeast(1)
    return "${take(head)}...${takeLast(tail)}".replace('\n', ' ')
}

internal data class LiteRtMessageSnapshot(
    val text: String,
    val thought: String,
    val rawText: String = text,
    val toolCalls: List<OllamaService.ToolCall> = emptyList(),
    val thinkingSource: String = "none"
)

private data class LiteRtGenerationText(
    val visibleText: String,
    val meteredText: String,
    val toolCalls: List<OllamaService.ToolCall> = emptyList()
)

internal class LiteRtStreamTextAssembler {
    private val rawText = StringBuilder()
    private var lastRawSnapshot = ""
    private var lastEmittedText = ""

    fun appendSnapshot(snapshot: String): String {
        val rawDelta = liteRtStreamingDelta(
            currentSnapshot = snapshot,
            lastSnapshot = lastRawSnapshot
        )
        lastRawSnapshot = snapshot
        if (rawDelta.isEmpty()) return ""
        rawText.append(rawDelta)
        return emitStable(final = false)
    }

    fun finish(): String = emitStable(final = true)

    private fun emitStable(final: Boolean): String {
        val rendered = rawText.toString()
        val target = if (final) {
            rendered
        } else {
            val stablePrefix = rendered.stableLiteRtStreamingPrefix()
            if (stablePrefix.isNotEmpty()) stablePrefix else rendered
        }
        if (target.isEmpty() || target == lastEmittedText) return ""
        val delta = liteRtStreamingDelta(
            currentSnapshot = target,
            lastSnapshot = lastEmittedText
        )
        if (delta.isNotEmpty()) {
            lastEmittedText = target
        }
        return delta
    }
}

internal class LiteRtLeakedThinkingStreamFilter {
    private val buffered = StringBuilder()
    private var bufferingLeakedThinking = false

    fun filter(snapshot: LiteRtMessageSnapshot): LiteRtMessageSnapshot {
        if (snapshot.rawText.isBlank()) return snapshot
        if (!bufferingLeakedThinking) {
            val rawSplit = splitLeakedLiteRtThinkingChannel(snapshot.rawText)
            if (rawSplit != null) {
                if (rawSplit.first.isBlank() && rawSplit.second.isBlank()) {
                    buffered.append(snapshot.rawText)
                    bufferingLeakedThinking = true
                    return snapshot.copy(text = "", thought = "")
                }
                return snapshot.copy(
                    text = rawSplit.first,
                    thought = snapshot.thought.ifBlank { rawSplit.second },
                    thinkingSource = if (snapshot.thought.isBlank() && rawSplit.second.isNotBlank()) {
                        "leaked"
                    } else {
                        snapshot.thinkingSource
                    }
                )
            }
            if (isPotentialLeakedLiteRtThinkingPrefix(snapshot.rawText)) {
                buffered.append(snapshot.rawText)
                bufferingLeakedThinking = true
                return snapshot.copy(text = "", thought = "")
            }
            return snapshot
        }

        buffered.append(snapshot.rawText)
        val accumulated = buffered.toString()
        val accumulatedSplit = splitLeakedLiteRtThinkingChannel(accumulated)
        if (accumulatedSplit != null && accumulatedSplit.first.isNotBlank()) {
            buffered.clear()
            bufferingLeakedThinking = false
            return snapshot.copy(
                text = accumulatedSplit.first,
                thought = snapshot.thought.ifBlank { accumulatedSplit.second },
                rawText = accumulatedSplit.first,
                thinkingSource = if (snapshot.thought.isBlank() && accumulatedSplit.second.isNotBlank()) {
                    "leaked"
                } else {
                    snapshot.thinkingSource
                }
            )
        }
        if (!isPotentialOrActiveLeakedLiteRtThinking(accumulated)) {
            buffered.clear()
            bufferingLeakedThinking = false
            return snapshot.copy(
                text = accumulated,
                rawText = accumulated
            )
        }
        return snapshot.copy(text = "", thought = "")
    }

    fun finish(): LiteRtMessageSnapshot? {
        if (!bufferingLeakedThinking || buffered.isBlank()) return null
        val accumulated = buffered.toString()
        buffered.clear()
        bufferingLeakedThinking = false
        return if (isPotentialOrActiveLeakedLiteRtThinking(accumulated)) {
            LiteRtMessageSnapshot(
                text = "",
                thought = accumulated,
                rawText = accumulated,
                thinkingSource = "leaked"
            )
        } else {
            LiteRtMessageSnapshot(text = accumulated, thought = "", rawText = accumulated)
        }
    }
}

private fun String.stableLiteRtStreamingPrefix(): String {
    val boundary = indexOfLast { char ->
        char.isWhitespace() || char == '.' || char == '!' || char == '?' || char == ';' || char == ':'
    }
    if (boundary < 0) return ""
    return substring(0, boundary + 1)
}

internal fun liteRtMessageSnapshot(message: Any, thinkingEnabled: Boolean): LiteRtMessageSnapshot {
    val contentText = message.extractLiteRtMessageContentText()
    val messageString = message.toString()
    val rendered = selectLiteRtRenderedMessageText(
        contentText = contentText,
        messageString = messageString
    )
    val channelThought = if (thinkingEnabled) message.extractLiteRtThoughtChannel() else ""
    val tokenChannels = if (thinkingEnabled) {
        splitLiteRtDocumentedChannelTextFromCandidates(messageString, contentText, rendered)
    } else {
        null
    }
    val leakedChannel = if (thinkingEnabled) {
        tokenChannels ?: if (channelThought.isBlank() || isPotentialOrActiveLeakedLiteRtThinking(rendered)) {
            splitLeakedLiteRtThinkingChannel(rendered)
        } else {
            null
        }
    } else {
        null
    }
    val thought = if (thinkingEnabled) {
        channelThought.ifBlank { leakedChannel?.second.orEmpty() }
    } else {
        ""
    }
    val thinkingSource = when {
        !thinkingEnabled -> "disabled"
        channelThought.isNotBlank() -> "channel"
        tokenChannels?.second?.isNotBlank() == true -> "sentinel"
        leakedChannel?.second?.isNotBlank() == true -> "leaked"
        else -> "none"
    }
    return LiteRtMessageSnapshot(
        text = leakedChannel?.first ?: rendered,
        thought = thought,
        rawText = rendered,
        toolCalls = message.extractLiteRtToolCalls(),
        thinkingSource = thinkingSource
    )
}

internal fun splitLiteRtRecoveredOutputText(text: String): Pair<String, String> {
    val repaired = repairLiteRtCompactTextForDisplay(
        sanitizeLiteRtRenderedText(text)
    ).trim()
    if (repaired.isBlank()) return "" to ""
    splitLiteRtDocumentedChannelTextFromCandidates(repaired)?.let { return it }
    splitLiteRtTaggedThinkingChannel(repaired)?.let { return it }
    splitLeakedLiteRtThinkingChannel(repaired)?.let { leaked ->
        if (leaked.first.isNotBlank() || leaked.second.isNotBlank()) {
            return leaked
        }
    }
    return extractLiteRtFinalVisibleText(repaired) to ""
}

internal fun selectLiteRtRenderedMessageText(contentText: String, messageString: String): String {
    val sanitizedContent = sanitizeLiteRtRenderedTextForStreaming(contentText)
    val rendered = messageString.takeIf { it.isNotBlank() } ?: return sanitizedContent
    val sanitizedRendered = sanitizeLiteRtRenderedTextForStreaming(rendered)
    if (sanitizedRendered.isBlank() || looksLikeLiteRtObjectDump(sanitizedRendered)) {
        return sanitizedContent
    }
    if (sanitizedContent.isBlank()) return sanitizedRendered
    if (shouldPreferLiteRtReflectedContent(sanitizedContent, sanitizedRendered)) {
        return sanitizedContent
    }
    return sanitizedRendered
}

private fun shouldPreferLiteRtReflectedContent(contentText: String, renderedText: String): Boolean {
    if (contentText.isBlank()) return false
    if (looksLikeLiteRtObjectDump(contentText)) return false
    if (shouldRepairLiteRtCompactText(renderedText)) {
        val contentLooksReadable = !shouldRepairLiteRtCompactText(contentText) &&
            contentText.any { it.isWhitespace() } &&
            liteRtRenderedTextScore(contentText) >= liteRtRenderedTextScore(renderedText)
        if (contentLooksReadable) return true
    }
    return false
}

private fun Any.extractLiteRtMessageContentText(): String = runCatching {
    val contents = callLiteRtNoArg("getContents") ?: return@runCatching ""
    val parts = contents.callLiteRtNoArg("getContents") as? Iterable<*> ?: return@runCatching ""
    val textParts = parts.mapNotNull { part ->
        when (part) {
            null -> null
            is String -> part
            else -> part.callLiteRtNoArg("getText") as? String
        }
    }
    joinLiteRtTextParts(textParts)
}.getOrDefault("")

internal fun joinLiteRtTextParts(parts: List<String>): String {
    val nonBlankParts = parts.filter { it.isNotEmpty() }
    if (nonBlankParts.isEmpty()) return ""
    val rawJoin = nonBlankParts.joinToString(separator = "")
    if (!shouldUseLiteRtBoundaryJoin(nonBlankParts, rawJoin)) return rawJoin

    val rendered = StringBuilder()
    nonBlankParts.forEach { part ->
        appendLiteRtTextPart(rendered, part)
    }
    return rendered.toString()
}

private fun shouldUseLiteRtBoundaryJoin(parts: List<String>, rawJoin: String): Boolean {
    if (parts.size < 3) return false
    if (parts.any { part -> part.any { it.isWhitespace() } }) return false
    val textParts = parts.filter { part -> part.any { it.isLetter() } }
    if (textParts.size < 2) return false
    val averageLength = textParts.sumOf { it.length }.toDouble() / textParts.size
    if (averageLength < 2.0) return false
    if (shouldRepairLiteRtCompactText(rawJoin)) return true
    return textParts.size >= 4 && rawJoin.count { it.isWhitespace() } <= rawJoin.count { it.isLetter() } / 24
}

private fun appendLiteRtTextPart(rendered: StringBuilder, part: String) {
    if (rendered.isEmpty()) {
        rendered.append(part)
        return
    }
    val previous = rendered.last()
    val first = part.first()
    val needsSpace = !previous.isWhitespace() &&
        !first.isWhitespace() &&
        !LiteRtNoSpaceBeforeChars.contains(first) &&
        !LiteRtNoSpaceAfterChars.contains(previous)
    if (needsSpace) rendered.append(' ')
    rendered.append(part)
}

private fun Any.extractLiteRtThoughtChannel(): String = runCatching {
    val channels = callLiteRtNoArg("getChannels") as? Map<*, *> ?: return@runCatching ""
    val thoughtKeys = setOf("thought", "thinking", "reasoning", "analysis")
    channels.entries.firstNotNullOfOrNull { (key, value) ->
        val normalizedKey = key?.toString()?.lowercase(Locale.US)
        val channelText = value?.extractLiteRtTextLike().orEmpty()
        channelText.takeIf {
            normalizedKey != null && normalizedKey in thoughtKeys && channelText.isNotBlank()
        }
    }.orEmpty()
}.getOrDefault("")

internal fun Any.extractLiteRtToolCalls(): List<OllamaService.ToolCall> = runCatching {
    val calls = callLiteRtNoArg("getToolCalls") as? Iterable<*>
        ?: return@runCatching emptyList<OllamaService.ToolCall>()
    calls.toList().mapIndexedNotNull { index, call ->
        call ?: return@mapIndexedNotNull null
        val name = (call.callLiteRtNoArg("getName") as? String)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return@mapIndexedNotNull null
        val arguments = AgentRuntimeSupport.normalizeToolArguments(
            call.callLiteRtNoArg("getArguments")
        )
        OllamaService.ToolCall(
            name = name,
            arguments = arguments,
            id = "litert_call_${index + 1}"
        )
    }
}.getOrDefault(emptyList())

private fun liteRtExtraContext(thinkingEnabled: Boolean): Map<String, Any> =
    mapOf(LITERT_EXTRA_CONTEXT_ENABLE_THINKING to java.lang.Boolean.valueOf(thinkingEnabled))

private fun Any.liteRtChannelKeySummary(): String = runCatching {
    val channels = callLiteRtNoArg("getChannels") as? Map<*, *> ?: return@runCatching "none"
    channels.entries
        .take(8)
        .joinToString(",") { (key, value) ->
            val type = value?.javaClass?.simpleName ?: "null"
            val chars = value?.extractLiteRtTextLike()?.length ?: 0
            "${key ?: "null"}:$type:$chars"
        }
        .ifBlank { "empty" }
}.getOrDefault("unavailable")

private fun Any.extractLiteRtTextLike(depth: Int = 0): String {
    if (this is String) return this
    if (depth > 4) return toString()
    (callLiteRtNoArg("getText") as? String)?.let { return it }
    val contents = callLiteRtNoArg("getContents") ?: return toString()
    if (contents is String) return contents
    if (contents is Iterable<*>) {
        return joinLiteRtTextParts(
            contents.mapNotNull { part ->
                when (part) {
                    null -> null
                    is String -> part
                    else -> part.extractLiteRtTextLike(depth + 1)
                }
            }
        )
    }
    return contents.extractLiteRtTextLike(depth + 1)
}

private fun Any.callLiteRtNoArg(name: String): Any? =
    javaClass.methods
        .firstOrNull { method -> method.name == name && method.parameterTypes.isEmpty() }
        ?.invoke(this)

private fun splitLiteRtDocumentedChannelTextFromCandidates(vararg candidates: String): Pair<String, String>? =
    candidates.firstNotNullOfOrNull { candidate ->
        val sanitized = sanitizeLiteRtRenderedTextForStreaming(candidate)
        if (candidate.isBlank() || looksLikeLiteRtObjectDump(sanitized)) {
            null
        } else {
            splitLiteRtDocumentedChannelText(candidate)
        }
    }

private fun splitLiteRtDocumentedChannelText(text: String): Pair<String, String>? {
    val markers = LiteRtDocumentedChannelMarkerPattern.findAll(text).toList()
    if (markers.isEmpty()) return null

    val visible = StringBuilder()
    val thought = StringBuilder()
    var currentChannel: LiteRtRenderedChannel? = null
    var sawNamedChannel = false

    markers.forEachIndexed { index, marker ->
        val segmentStart = marker.range.last + 1
        val nextMarkerStart = markers.getOrNull(index + 1)?.range?.first ?: text.length
        val channelName = marker.groupValues.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.lowercase(Locale.US)
        currentChannel = when (channelName) {
            "thought", "thinking", "reasoning", "analysis" -> {
                sawNamedChannel = true
                LiteRtRenderedChannel.Thought
            }
            "final", "assistant", "model" -> {
                sawNamedChannel = true
                LiteRtRenderedChannel.Visible
            }
            else -> {
                if (currentChannel == LiteRtRenderedChannel.Thought) {
                    LiteRtRenderedChannel.Visible
                } else {
                    currentChannel
                }
            }
        }

        val segment = text.substring(segmentStart, nextMarkerStart)
        when (currentChannel) {
            LiteRtRenderedChannel.Thought -> thought.append(segment)
            LiteRtRenderedChannel.Visible -> visible.append(segment)
            null -> Unit
        }
    }

    if (!sawNamedChannel) return null
    val cleanVisible = extractLiteRtFinalVisibleText(visible.toString())
    val cleanThought = sanitizeLiteRtRenderedText(thought.toString()).trim()
    if (cleanVisible.isBlank() && cleanThought.isBlank()) return null
    return cleanVisible to cleanThought
}

private fun splitLiteRtTaggedThinkingChannel(text: String): Pair<String, String>? {
    val startTags = listOf("<think>", "<|think|>", "<thought>", "<Thought>", "<Think>")
    val endTags = listOf("</think>", "</|think|>", "</thought>", "</Thought>", "</Think>")
    var startTag: String? = null
    var startIndex = -1
    for (tag in startTags) {
        val index = text.indexOf(tag, ignoreCase = true)
        if (index != -1 && (startIndex == -1 || index < startIndex)) {
            startIndex = index
            startTag = tag
        }
    }
    if (startIndex == -1) return null
    var endTag: String? = null
    var endIndex = -1
    for (tag in endTags) {
        val index = text.indexOf(tag, startIndex + (startTag?.length ?: 0), ignoreCase = true)
        if (index != -1 && (endIndex == -1 || index < endIndex)) {
            endIndex = index
            endTag = tag
        }
    }
    return if (endIndex != -1) {
        val thought = text.substring(startIndex + (startTag?.length ?: 0), endIndex)
        val visible = text.substring(0, startIndex) + text.substring(endIndex + (endTag?.length ?: 0))
        extractLiteRtFinalVisibleText(visible) to sanitizeLiteRtRenderedText(thought).trim()
    } else {
        val thought = text.substring(startIndex + (startTag?.length ?: 0))
        val visible = text.substring(0, startIndex)
        extractLiteRtFinalVisibleText(visible) to sanitizeLiteRtRenderedText(thought).trim()
    }
}

private fun splitLeakedLiteRtThinkingChannel(text: String): Pair<String, String>? {
    val hasThinkingPreamble = LiteRtLeakedThinkingChannelStartPattern.containsMatchIn(text)
    val finalMarker = LiteRtFinalChannelTokenPattern.find(text)
        ?: LiteRtFinalAnswerMarkerPattern.find(text)
        ?: LiteRtKnownFinalGenerationMarkerPattern.find(text)
        ?: LiteRtFinalDecisionMarkerPattern.find(text)
        ?: if (hasThinkingPreamble) LiteRtGenericFinalGenerationMarkerPattern.find(text) else null
    if (!hasThinkingPreamble && finalMarker == null) {
        return if (isPotentialLeakedLiteRtThinkingPrefix(text)) "" to "" else null
    }
    if (finalMarker == null) return "" to ""
    val visible = finalMarker
        .let { match ->
            stripLiteRtFinalDecisionMetaLead(
                extractLiteRtFinalVisibleText(
                text.substring(match.range.last + 1)
                    .trimStart { char -> char.isWhitespace() || char == '.' || char == ':' || char == '*' }
                )
            )
        }
    val thought = text.substring(0, finalMarker.range.first)
    return visible to thought
}

private fun isPotentialLeakedLiteRtThinkingPrefix(text: String): Boolean {
    val normalized = text
        .asSequence()
        .filter { it.isLetter() }
        .joinToString("")
        .lowercase(Locale.US)
    if (normalized.isBlank()) return false
    return LiteRtLeakedThinkingPrefixCandidates.any { candidate ->
        candidate.startsWith(normalized)
    }
}

private fun isPotentialOrActiveLeakedLiteRtThinking(text: String): Boolean =
    LiteRtLeakedThinkingChannelStartPattern.containsMatchIn(text) ||
        isPotentialLeakedLiteRtThinkingPrefix(text)

private fun extractLiteRtFinalVisibleText(text: String): String {
    val cleaned = repairLiteRtCompactTextForDisplay(text).trim()
    if (cleaned.isBlank()) return cleaned
    LiteRtNestedFinalAnswerMarkerPattern.find(cleaned)?.let { marker ->
        return cleaned.substring(marker.range.last + 1)
            .trimStart { char -> char.isWhitespace() || char == '.' || char == ':' || char == '*' }
    }
    LiteRtFinalGenerationDirectivePattern.matchEntire(cleaned)?.let { match ->
        val answer = match.groupValues.getOrNull(1).orEmpty().trim()
        if (answer.isNotBlank()) return answer
    }
    return cleaned
}

private fun stripLiteRtFinalDecisionMetaLead(text: String): String {
    LiteRtFinalDecisionMetaLeadPattern.matchEntire(text)?.let { match ->
        val answer = match.groupValues.getOrNull(1).orEmpty().trim()
        if (answer.isNotBlank()) return answer
    }
    return text
}

internal fun sanitizeLiteRtRenderedText(text: String): String =
    sanitizeLiteRtRenderedText(text = text, trimLeading = true)

private fun sanitizeLiteRtRenderedText(text: String, trimLeading: Boolean): String {
    if (text.isBlank()) return text
    var cleaned = text
    cleaned = LiteRtHeaderTokenPattern.replace(cleaned, "")
    cleaned = LiteRtChannelTokenPattern.replace(cleaned, "")
    cleaned = LiteRtTurnTokenWithRolePattern.replace(cleaned, "")
    cleaned = LiteRtCompactTurnTokenPattern.replace(cleaned, "")
    cleaned = LiteRtSplitPipeTurnTokenPattern.replace(cleaned, "")
    cleaned = LiteRtStandaloneControlTokenPattern.replace(cleaned, "")
    cleaned = LiteRtPlainSpecialTokenPattern.replace(cleaned, "")
    cleaned = LiteRtRoleOnlyLinePattern.replace(cleaned, "")
    cleaned = LiteRtTrailingWhitespacePattern.replace(cleaned, "\n")
    cleaned = LiteRtExcessBlankLinePattern.replace(cleaned, "\n\n")
    return if (trimLeading) cleaned.trimStart() else cleaned
}

internal fun sanitizeLiteRtRenderedTextForStreaming(text: String): String {
    if (text.isBlank()) return text
    return LiteRtDanglingControlTokenTailPattern.replace(
        sanitizeLiteRtRenderedText(text = text, trimLeading = false),
        ""
    )
}

internal fun repairLiteRtCompactTextForDisplay(text: String): String {
    if (text.contains('\n')) {
        return text.lineSequence()
            .joinToString("\n") { line -> repairLiteRtCompactTextLine(line) }
    }
    return repairLiteRtCompactTextLine(text)
}

private fun repairLiteRtCompactTextLine(text: String): String {
    val hasLongRun = LiteRtLongLetterRunPattern.containsMatchIn(text)
    if (!hasLongRun || LiteRtCompactRepairSkipPattern.containsMatchIn(text.trim())) return text
    val punctuationSpaced = addLiteRtCompactDisplaySpacing(text)
    return if (punctuationSpaced != text) punctuationSpaced else text
}

private fun addLiteRtCompactDisplaySpacing(text: String): String {
    var spaced = LiteRtCompactMarkdownHeadingInlinePattern.replace(text) { match ->
        "\n\n${match.value}"
    }
    spaced = LiteRtCompactHeadingNumberPattern.replace(spaced) { match ->
        "${match.groupValues[1]} ${match.groupValues[2]}."
    }
    spaced = LiteRtCompactMarkdownBulletBoldPattern.replace(spaced, "\n* **")
    spaced = LiteRtCompactCamelBoundaryPattern.replace(spaced, " ")
    spaced = LiteRtCompactStepBoundaryPattern.replace(spaced) { match ->
        "${match.groupValues[1]}\n${match.groupValues[2]}. "
    }
    spaced = LiteRtCompactBoldLabelValueBoundaryPattern.replace(spaced, "** ")
    spaced = LiteRtCompactItalicLabelValueBoundaryPattern.replace(spaced, "* ")
    spaced = LiteRtCompactQuoteParenBoundaryPattern.replace(spaced) { match ->
        "${match.groupValues[1]} ${match.groupValues[2]}"
    }
    spaced = LiteRtCompactCommaBoundaryPattern.replace(spaced, ", ")
    spaced = LiteRtCompactContractionBoundaryPattern.replace(spaced) { match ->
        "${match.value} "
    }
    spaced = LiteRtCompactPunctuationBoundaryPattern.replace(spaced) { match ->
        "${match.value} "
    }
    return spaced
}

internal fun estimateLiteRtCompletionTokens(text: String): Int {
    val repaired = repairLiteRtCompactTextForDisplay(sanitizeLiteRtRenderedText(text))
    val base = estimateNativeChatTextTokens(repaired)
    val letters = repaired.count { it.isLetter() }
    val compactFallback = if (
        letters >= 12 &&
        repaired.count { it.isWhitespace() } <= letters / 14
    ) {
        (letters / 4.2).toInt().coerceAtLeast(1)
    } else {
        0
    }
    return maxOf(base, compactFallback)
}

internal fun String.hasLiteRtCorruptOutputSignature(): Boolean {
    if (isBlank()) return false
    val invalid = count { char ->
        char == '\uFFFD' ||
            Character.getType(char) == Character.PRIVATE_USE.toInt() ||
            (char.code < 0x20 && char != '\n' && char != '\r' && char != '\t')
    }
    if (invalid >= 3) return true
    val sampled = take(512)
    if (sampled.length < 24) return false
    val oddSymbols = sampled.count { char ->
        val type = Character.getType(char)
        type == Character.OTHER_SYMBOL.toInt() ||
            type == Character.FORMAT.toInt() ||
            type == Character.SURROGATE.toInt() ||
            type == Character.UNASSIGNED.toInt()
    }
    return oddSymbols >= 8 && oddSymbols > sampled.length / 12
}

private fun liteRtRenderedTextScore(text: String): Int {
    if (text.isBlank()) return Int.MIN_VALUE
    var score = 0
    score += text.count { it.isWhitespace() } * 2
    score += text.count { it == '.' || it == ',' || it == '?' || it == '!' || it == '\n' }
    if (looksLikeLiteRtObjectDump(text)) score -= 100
    if (shouldRepairLiteRtCompactText(text)) score -= 12
    if (LiteRtAnyControlTokenPattern.containsMatchIn(text)) score -= 8
    return score
}

private fun looksLikeLiteRtObjectDump(text: String): Boolean {
    val compact = text.trimStart().take(96)
    if (compact.startsWith("FakeLiteRt")) return true
    return LiteRtObjectDumpPattern.containsMatchIn(compact)
}

private fun shouldRepairLiteRtCompactText(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return false
    if (trimmed.contains('\n')) return false
    if (LiteRtCompactRepairSkipPattern.containsMatchIn(trimmed)) return false
    val letters = trimmed.count { it.isLetter() }
    if (letters < 12) return false
    val whitespace = trimmed.count { it.isWhitespace() }
    if (whitespace > letters / 14) return false
    return LiteRtLongLetterRunPattern.containsMatchIn(trimmed)
}

private fun splitLiteRtCompactRun(run: String): String? {
    val normalized = run.lowercase(Locale.US)
    val bestWords = arrayOfNulls<List<String>>(normalized.length + 1)
    val bestScores = IntArray(normalized.length + 1) { Int.MIN_VALUE }
    bestWords[0] = emptyList()
    bestScores[0] = 0

    for (index in normalized.indices) {
        val currentWords = bestWords[index] ?: continue
        for (word in LiteRtCompactWordDictionary) {
            if (!normalized.startsWith(word, index)) continue
            val next = index + word.length
            val candidateScore = bestScores[index] + (word.length * word.length) - 1
            if (candidateScore > bestScores[next]) {
                bestScores[next] = candidateScore
                bestWords[next] = currentWords + word
            }
        }
    }

    val words = bestWords[normalized.length]?.takeIf { it.size >= 2 } ?: return null
    var offset = 0
    return words.joinToString(" ") { word ->
        val original = run.substring(offset, offset + word.length)
        offset += word.length
        original
    }
}

private val LiteRtHeaderTokenPattern = Regex(
    pattern = """<\|start_header_id\|>\s*(?:assistant|model|user|system|tool|thought)?\s*<\|end_header_id\|>""",
    option = RegexOption.IGNORE_CASE
)
private val LiteRtChannelTokenPattern = Regex(
    pattern = """<\s*\|?\s*channel\s*\|?\s*>\s*(?:(?:assistant|model|final|thought|thinking|reasoning|analysis)\b)?""",
    option = RegexOption.IGNORE_CASE
)
private val LiteRtDocumentedChannelMarkerPattern = Regex(
    pattern = """(?is)<\s*(?:\|?\s*channel\s*\|?|channel\s*\|)\s*>\s*(?:(thought|thinking|reasoning|analysis|final|assistant|model)\b)?"""
)
private val LiteRtTurnTokenWithRolePattern = Regex(
    pattern = """<\|?start[_ ]of[_ ]turn\|?>\s*(?:assistant|model|user|system|tool|thought)?""",
    option = RegexOption.IGNORE_CASE
)
private val LiteRtCompactTurnTokenPattern = Regex(
    pattern = """<\|?(?:start[_ ]of[_ ]turn|end[_ ]of[_ ]turn|turn)\|?>\s*(?:assistant|model|user|system|tool|thought)?""",
    option = RegexOption.IGNORE_CASE
)
private val LiteRtSplitPipeTurnTokenPattern = Regex(
    pattern = """<\s*\|?\s*(?:start[_ ]of[_ ]turn|end[_ ]of[_ ]turn|turn)\s*\|?\s*>\s*(?:assistant|model|user|system|tool|thought)?""",
    option = RegexOption.IGNORE_CASE
)
private val LiteRtStandaloneControlTokenPattern = Regex(
    pattern = """<\|?(?:end[_ ]of[_ ]turn|begin[_ ]of[_ ]text|end[_ ]of[_ ]text|eot[_ ]id|eom[_ ]id|im[_ ]start|im[_ ]end)\|?>""",
    option = RegexOption.IGNORE_CASE
)
private val LiteRtPlainSpecialTokenPattern = Regex(
    pattern = """</?(?:s|bos|eos|pad|unk)>""",
    option = RegexOption.IGNORE_CASE
)
private val LiteRtRoleOnlyLinePattern = Regex(
    pattern = """(?im)^[ \t]*(?:assistant|model|user|system|tool|thought)[ \t]*:?[ \t]*$[\r\n]*"""
)
private val LiteRtTrailingWhitespacePattern = Regex("""[ \t]+\r?\n""")
private val LiteRtExcessBlankLinePattern = Regex("""(?:\r?\n){3,}""")
private val LiteRtLeakedThinkingChannelStartPattern = Regex(
    pattern = """(?is)^\s*(?:<\s*\|?\s*channel\s*\|?\s*>\s*)?(?:thought|thinking|reasoning|analysis)?\s*(?:Thinking\s*Process|Analyze\s*the\s*(?:Request|Input)|Determine\s*the\s*(?:Goal|Intent|Appropriate\s*Response)|Review\s*Constraints\s*/?\s*Style|Self\s*[- ]?\s*Correction\s*/?\s*Refinement)\s*:|^\s*(?:The\s+(?:user\s+)?wants|This\s+request\s+|Since\s+the\s+request\s+|I\s+should\s+)"""
)
private val LiteRtFinalChannelTokenPattern = Regex(
    pattern = """(?is)<\s*\|?\s*channel\s*\|?\s*>\s*(?:final|assistant|model)\b"""
)
private val LiteRtFinalAnswerMarkerPattern = Regex(
    pattern = """(?is)(?:^|\s)(?:Final\s*Answer|Final)\s*:\s*"""
)
private val LiteRtFinalDecisionMarkerPattern = Regex(
    pattern = """(?is)(?:^|[.\n]\s*|\*\s*)(?:\*\*)?\s*Final\s*(?:Decision|Answer|Response|Output)\s*(?:[.:]\s*)?(?:\*\*)?\s*\*?"""
)
private val LiteRtNestedFinalAnswerMarkerPattern = Regex(
    pattern = """(?is)(?:^|\s)(?:Final\s*Answer|Answer|Response|Output)\s*:\s*"""
)
private val LiteRtFinalDecisionMetaLeadPattern = Regex(
    pattern = """(?is)^\s*(?:I\s+(?:will|would|should|need\s+to)|I'll|I\s+am\s+going\s+to|I'm\s+going\s+to|Voy\s+a|Necesito|Responder[ée]|Informar[ée]|Le\s+dir[ée])\b.+?[.!?]\s+(.+?)\s*$"""
)
private val LiteRtFinalGenerationDirectivePattern = Regex(
    pattern = """(?is)^\s*(?:generate|write|produce|create|return|output|respond|say)(?:\b|(?=\p{L})).+?[.!?]\s+(.+?)\s*$"""
)
private val LiteRtKnownFinalGenerationMarkerPattern = Regex(
    pattern = """(?is)(?:^|[.\n]\s*|\d+\s*\.\s*)(?:\*\*)?\s*Final\s*(?:Output|Response|Answer)\s*Generation\s*(?:[.:]\s*)?(?:\*\*)?"""
)
private val LiteRtGenericFinalGenerationMarkerPattern = Regex(
    pattern = """(?is)(?:^|[.\n]\s*|\d+\s*\.\s*)(?:\*\*)?\s*Final\s*(?:[\p{L}\p{M}][\p{L}\p{M}\s/_-]{0,48}?)?\s*Generation\s*(?:[.:]\s*)?(?:\*\*)?"""
)
private val LiteRtDanglingControlTokenTailPattern = Regex(
    pattern = """(?is)<\|?(?:start(?:[_ ]of(?:[_ ]turn)?)?|end(?:[_ ]of(?:[_ ]turn|[_ ]text)?)?|begin(?:[_ ]of(?:[_ ]text)?)?|turn|eot(?:[_ ]id)?|eom(?:[_ ]id)?|im(?:[_ ]start|[_ ]end)?|[a-z_ ]{0,24})?$"""
)
private val LiteRtAnyControlTokenPattern = Regex("""(?i)<\|?|turn>|channel>""")
private val LiteRtObjectDumpPattern = Regex("""^[A-Za-z0-9_.$]+\(.*=""")
private val LiteRtCompactPunctuationBoundaryPattern = Regex("""[.!?;:](?=\p{L})""")
private val LiteRtCompactCommaBoundaryPattern = Regex(""",(?=\p{L})""")
private val LiteRtCompactContractionBoundaryPattern = Regex("""(?i)\b(?:don|doesn|didn|isn|aren|wasn|weren|can|couldn|shouldn|wouldn|won|haven|hasn|hadn|mustn|needn|shan)'t(?=\p{L})""")
private val LiteRtCompactStepBoundaryPattern = Regex("""([.:;!?])(\d+)\.(?=\*{0,2}\p{L})""")
private val LiteRtCompactBoldLabelValueBoundaryPattern = Regex("""(?<=[.:])\*\*(?=\p{Lu})""")
private val LiteRtCompactItalicLabelValueBoundaryPattern = Regex("""(?<=[.:])\*(?=\p{Lu})""")
private val LiteRtCompactMarkdownHeadingInlinePattern = Regex("""(?<!^)(?<!\n)#{2,6}(?=\d|\p{L})""")
private val LiteRtCompactHeadingNumberPattern = Regex("""(#{1,6})(\d+)\.""")
private val LiteRtCompactMarkdownBulletBoldPattern = Regex("""(?<!\n)\*\*\*(?=\p{Lu})""")
private val LiteRtCompactCamelBoundaryPattern = Regex("""(?<=[\p{Ll}])(?=\p{Lu})""")
private val LiteRtCompactQuoteParenBoundaryPattern = Regex("""(["”?!])(\()""")
private val LiteRtLongLetterRunPattern = Regex("""[\p{L}]{5,}""")
private val LiteRtCompactRepairSkipPattern = Regex(
    pattern = """(?i)(<tool_call|</tool_call|```|https?://|www\.|^\s*[\[{]|[}\]]\s*$)"""
)
private val LiteRtNoSpaceBeforeChars = setOf('.', ',', '!', '?', ';', ':', '%', ')', ']', '}', '"', '\'')
private val LiteRtNoSpaceAfterChars = setOf('(', '[', '{', '/', '\n')
private val LiteRtLeakedThinkingPrefixCandidates = listOf(
    "thoughtthinkingprocess",
    "thinkingthinkingprocess",
    "reasoningthinkingprocess",
    "analysisthinkingprocess",
    "thinkingprocess",
    "analyzetherequest",
    "analyzetheinput",
    "determinethegoal",
    "determinetheintent",
    "determinetheappropriateresponse",
    "reviewconstraintsstyle",
    "selfcorrectionrefinement",
    "theuserwants",
    "thewants",
    "thisrequest",
    "sincetherequest",
    "ishould"
)

private enum class LiteRtRenderedChannel {
    Visible,
    Thought
}

private val LiteRtCompactWordDictionary = listOf(
    "information",
    "informacion",
    "assistant",
    "thinking",
    "process",
    "analyze",
    "input",
    "inputs",
    "request",
    "determine",
    "intent",
    "goal",
    "appropriate",
    "response",
    "reciprocated",
    "friendly",
    "polite",
    "manner",
    "reciprocal",
    "standard",
    "warm",
    "formulate",
    "potential",
    "responses",
    "select",
    "best",
    "welcoming",
    "open",
    "ended",
    "usually",
    "safest",
    "most",
    "effective",
    "starting",
    "point",
    "review",
    "constraints",
    "style",
    "previous",
    "instructions",
    "readable",
    "tone",
    "self",
    "correction",
    "refinement",
    "since",
    "brief",
    "very",
    "simple",
    "greeting",
    "answer",
    "final",
    "entertaining",
    "helpful",
    "casual",
    "acknowledgment",
    "acknowledgement",
    "invitation",
    "state",
    "user",
    "need",
    "equally",
    "inviting",
    "their",
    "actual",
    "output",
    "generation",
    "mply",
    "hello",
    "thanks",
    "thank",
    "there",
    "today",
    "please",
    "provide",
    "about",
    "could",
    "would",
    "should",
    "okay",
    "help",
    "can",
    "you",
    "your",
    "how",
    "what",
    "why",
    "when",
    "where",
    "tell",
    "joke",
    "with",
    "this",
    "that",
    "sound",
    "sounds",
    "like",
    "wonderful",
    "project",
    "singing",
    "rich",
    "expressive",
    "subject",
    "started",
    "start",
    "started",
    "get",
    "break",
    "down",
    "process",
    "here",
    "some",
    "initial",
    "steps",
    "areas",
    "reas",
    "area",
    "consider",
    "writing",
    "book",
    "booke",
    "define",
    "niche",
    "audience",
    "who",
    "knowing",
    "target",
    "will",
    "shape",
    "tone",
    "depth",
    "style",
    "beginners",
    "beginner",
    "focus",
    "basic",
    "breath",
    "breathing",
    "control",
    "posture",
    "warm",
    "ups",
    "simple",
    "vocal",
    "exercises",
    "intermediate",
    "singers",
    "singer",
    "explore",
    "technique",
    "techniques",
    "range",
    "expansion",
    "understanding",
    "music",
    "theory",
    "repertoire",
    "building",
    "advanced",
    "professional",
    "professionals",
    "dive",
    "into",
    "nuanced",
    "performance",
    "performances",
    "psychology",
    "acrobatics",
    "specific",
    "genre",
    "mastery",
    "career",
    "advice",
    "health",
    "songwriting",
    "musical",
    "theater",
    "theatre",
    "choir",
    "classical",
    "rock",
    "pop",
    "determine",
    "core",
    "content",
    "pillars",
    "main",
    "topics",
    "want",
    "cover",
    "good",
    "usually",
    "distinct",
    "sections",
    "physicality",
    "anatomy",
    "voice",
    "management",
    "diaphragmatic",
    "diaphragm",
    "resonance",
    "injury",
    "prevention",
    "scales",
    "arpeggios",
    "pitch",
    "accuracy",
    "intonation",
    "agility",
    "read",
    "harmony",
    "different",
    "styles",
    "legato",
    "staccato",
    "stage",
    "presence",
    "stagecraft",
    "fright",
    "management",
    "interpreting",
    "lyrics",
    "connecting",
    "audience",
    "business",
    "marketing",
    "finding",
    "collaborators",
    "maintaining",
    "sustainable",
    "mindset",
    "overcoming",
    "anxiety",
    "confidence",
    "mental",
    "discipline",
    "required",
    "long",
    "term",
    "training",
    "structure",
    "logical",
    "flow",
    "keep",
    "reader",
    "engaged",
    "possible",
    "outline",
    "foundation",
    "absolute",
    "introduction",
    "setting",
    "realistic",
    "expectations",
    "instrument",
    "physiology",
    "essential",
    "daily",
    "routines",
    "healthy",
    "breathwork",
    "mastering",
    "mechanics",
    "placement",
    "tone",
    "production",
    "developing",
    "flexibility",
    "application",
    "expression",
    "putting",
    "together",
    "choose",
    "songs",
    "fit",
    "moving",
    "beyond",
    "notes",
    "meaning",
    "exploration",
    "adapting",
    "jazz",
    "life",
    "wellness",
    "maintenance",
    "hydration",
    "nutrition",
    "recovery",
    "side",
    "contracts",
    "booking",
    "agents",
    "self",
    "promotion",
    "toughness",
    "dealing",
    "criticism",
    "plateaus",
    "personal",
    "reflection",
    "artistic",
    "identity",
    "develop",
    "voice",
    "empathic",
    "empathetic",
    "acknowledge",
    "struggle",
    "authoritative",
    "clear",
    "actionable",
    "scientifically",
    "sound",
    "appropriate",
    "inspirational",
    "motivate",
    "practice",
    "practicing",
    "pushing",
    "limits",
    "accessible",
    "avoid",
    "overly",
    "dense",
    "jargon",
    "unless",
    "immediately",
    "define",
    "use",
    "analogies",
    "real",
    "world",
    "examples",
    "next",
    "assist",
    "further",
    "bit",
    "more",
    "goal",
    "teach",
    "chnique",
    "inspire",
    "artists",
    "guide",
    "changers",
    "hangers",
    "ideal",
    "reader",
    "once",
    "have",
    "clearer",
    "direction",
    "brainstorming",
    "chapter",
    "titles",
    "detailed",
    "any",
    "these",
    "is",
    "it",
    "does",
    "doesn",
    "not",
    "specify",
    "format",
    "but",
    "be",
    "as",
    "reply",
    "question",
    "asked",
    "selected",
    "generate",
    "provide",
    "sure",
    "lets",
    "let",
    "talk",
    "the",
    "and",
    "for",
    "are",
    "was",
    "now",
    "may",
    "hi",
    "hey",
    "ok",
    "i",
    "we",
    "me",
    "my",
    "to",
    "of",
    "in",
    "on",
    "or",
    "a",
    "an",
    "hola",
    "como",
    "puedo",
    "puede",
    "puedes",
    "ayudarte",
    "ayudar",
    "gracias",
    "sobre",
    "otitis",
    "hoy",
    "hay",
    "que",
    "por",
    "favor",
    "dime",
    "chiste",
    "una",
    "uno",
    "un",
    "el",
    "la",
    "los",
    "las",
    "de",
    "del",
    "es",
    "son"
)

internal fun liteRtStreamingDelta(
    currentSnapshot: String,
    lastSnapshot: String
): String {
    if (currentSnapshot.isBlank() || currentSnapshot == lastSnapshot) return ""
    if (currentSnapshot.startsWith(lastSnapshot)) return currentSnapshot.substring(lastSnapshot.length)
    if (lastSnapshot.startsWith(currentSnapshot)) return ""
    val commonPrefixLength = currentSnapshot
        .zip(lastSnapshot)
        .takeWhile { (current, last) -> current == last }
        .count()
    return if (commonPrefixLength > 1) currentSnapshot.substring(commonPrefixLength) else currentSnapshot
}

private fun Throwable.liteRtRootCause(): Throwable {
    var current: Throwable = this
    val seen = mutableSetOf<Throwable>()
    while (seen.add(current)) {
        val next = when (current) {
            is InvocationTargetException -> current.targetException ?: current.cause
            is ExceptionInInitializerError -> current.exception ?: current.cause
            else -> current.cause
        } ?: return current
        if (next === current) return current
        current = next
    }
    return current
}

private fun Throwable.liteRtDiagnosticMessage(): String {
    val root = liteRtRootCause()
    val message = root.message?.takeIf { it.isNotBlank() }
    return if (message == null) {
        root.javaClass.name
    } else {
        "${root.javaClass.name}: $message"
    }
}
