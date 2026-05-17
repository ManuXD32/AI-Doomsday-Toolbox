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
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

data class LiteRtLmChatRequest(
    val model: LiteRtModelEntity,
    val chat: LlamaChatEntity,
    val history: List<LlamaMessageEntity>,
    val backendMode: String,
    val params: Map<String, Any>,
    val promptOverride: String? = null
)

data class LiteRtLmChatStats(
    val promptTokens: Int,
    val completionTokens: Int,
    val tokensPerSecond: Double
)

private data class LiteRtConversationInput(
    val promptOverride: String? = null,
    val systemInstruction: String = "",
    val initialMessages: List<Pair<String, String>> = emptyList(),
    val userMessage: String = ""
)

class LiteRtLmChatService(
    private val context: Context,
    private val allowGpuBackend: Boolean = false,
    private val onDiagnostic: ((String) -> Unit)? = null
) {
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
        val input = request.promptOverride
            ?.let { LiteRtConversationInput(promptOverride = it) }
            ?: buildConversationInput(
                chat = request.chat,
                history = request.history,
                thinkingEnabled = thinkingEnabled
            )
        val promptForTokenEstimate = input.promptOverride
            ?: buildString {
                append(input.systemInstruction)
                input.initialMessages.forEach { (role, content) ->
                    append("\n\n")
                    append(role)
                    append(":\n")
                    append(content)
                }
                append("\n\nUser:\n")
                append(input.userMessage)
            }
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
        val input = request.promptOverride
            ?.let { LiteRtConversationInput(promptOverride = it) }
            ?: buildConversationInput(
                chat = request.chat,
                history = request.history,
                thinkingEnabled = thinkingEnabled
            )
        val promptForTokenEstimate = input.promptOverride
            ?: buildString {
                append(input.systemInstruction)
                input.initialMessages.forEach { (role, content) ->
                    append("\n\n")
                    append(role)
                    append(":\n")
                    append(content)
                }
                append("\n\nUser:\n")
                append(input.userMessage)
        }
        val backendMode = normalizeLiteRtBackend(request.backendMode)
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
                val runtimeModelPath = prepareRuntimeModelPath(
                    modelPath = modelPath,
                    modelId = request.model.id,
                    backendLabel = candidate.label
                )
                val cacheDir = liteRtLmCacheDir(
                    modelId = request.model.id,
                    backendLabel = candidate.label
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
            bridge.disableSpeculativeDecoding(::diagnostic)
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
        val engineMaxTokens = when (backendLabel) {
            "GPU" -> requestedMaxTokens ?: request.model.defaultLiteRtEngineMaxTokens()
            else -> requestedMaxTokens
        }
        if (backendLabel == "GPU") {
            when {
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
        }
        val generated = try {
            bridge.generate(
                modelPath = modelPath,
                backend = backend,
                backendLabel = backendLabel,
                maxTokens = engineMaxTokens,
                cacheDir = cacheDir,
                input = input,
                thinkingEnabled = (request.params["enable_thinking"] as? Boolean) ?: true,
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
            if (backendLabel == "GPU") {
                bridge.resetSpeculativeDecoding(::diagnostic)
            }
        }
        val completionTokens = estimateLiteRtCompletionTokens(
            generated.meteredText.ifBlank { generated.visibleText }
        )
        val elapsed = (System.currentTimeMillis() - startedAt) / 1000.0
        return LiteRtLmChatStats(
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            tokensPerSecond = if (elapsed > 0.0) completionTokens / elapsed else 0.0
        )
    }

    private fun liteRtLmCacheDir(
        modelId: Long,
        backendLabel: String
    ): File {
        return liteRtLmEngineCacheDir(
            cacheRoot = context.cacheDir,
            modelId = modelId,
            backendLabel = backendLabel
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
                append("If this model supports visible thinking, expose it through the model's thought channel or inside <think>...</think> before the final answer. ")
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
                        "assistant" -> "assistant" to content
                        "system" -> "system" to content
                        else -> "user" to content
                    }
                }
            }
        val userMessage = history
            .getOrNull(latestUserIndex)
            ?.content
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Continue."
        return LiteRtConversationInput(
            systemInstruction = systemInstruction,
            initialMessages = initialMessages,
            userMessage = userMessage
        )
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

private class LiteRtLmReflectionBridge(private val context: Context) {
    private val backendClass by lazy { loadClass("com.google.ai.edge.litertlm.Backend") }
    private val engineConfigClass by lazy { loadClass("com.google.ai.edge.litertlm.EngineConfig") }
    private val engineClass by lazy { loadClass("com.google.ai.edge.litertlm.Engine") }
    private val conversationConfigClass by lazy { loadClass("com.google.ai.edge.litertlm.ConversationConfig") }
    private val contentsClass by lazy { loadClass("com.google.ai.edge.litertlm.Contents") }
    private val messageClass by lazy { loadClass("com.google.ai.edge.litertlm.Message") }
    private val samplerConfigClass by lazy { loadClass("com.google.ai.edge.litertlm.SamplerConfig") }
    private val messageCallbackClass by lazy { loadClass("com.google.ai.edge.litertlm.MessageCallback") }

    fun createBackend(label: String): Any = when (label) {
        "GPU" -> loadClass("com.google.ai.edge.litertlm.Backend\$GPU")
            .getConstructor()
            .newInstance()
        else -> loadClass("com.google.ai.edge.litertlm.Backend\$CPU")
            .getConstructor()
            .newInstance()
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
        add("LiteRT runtime Message methods=${methodSummary(messageClass, setOf("getContents", "getChannels", "toString"))}")
        add("LiteRT runtime callback methods=${methodSummary(messageCallbackClass, setOf("onMessage", "onDone", "onError"))}")
        add("LiteRT runtime Backend nested=${backendClass.declaredClasses.joinToString(",") { it.name }.truncateLiteRtDiagnostic(600)}")
        add(
            "LiteRT runtime ExperimentalFlags=" +
                runCatching {
                    val flagsClass = loadClass("com.google.ai.edge.litertlm.ExperimentalFlags")
                    "class=${flagsClass.name} fields=${flagsClass.fields.joinToString(",") { it.name }} " +
                        "methods=${methodSummary(flagsClass, setOf("setEnableSpeculativeDecoding", "getEnableSpeculativeDecoding"))}"
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

    fun disableSpeculativeDecoding(onDiagnostic: (String) -> Unit) {
        runCatching {
            val flagsClass = loadClass("com.google.ai.edge.litertlm.ExperimentalFlags")
            val instance = flagsClass.getField("INSTANCE").get(null)
            flagsClass.getMethod("setEnableSpeculativeDecoding", java.lang.Boolean::class.java)
                .invoke(instance, java.lang.Boolean.FALSE)
            onDiagnostic("LiteRT-LM speculative decoding disabled for GPU compatibility")
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
            onDiagnostic("LiteRT-LM speculative decoding reset after GPU attempt")
        }.onFailure { error ->
            onDiagnostic("LiteRT-LM speculative decoding reset unavailable: ${error.liteRtDiagnosticMessage()}")
        }
    }

    suspend fun generate(
        modelPath: File,
        backend: Any,
        backendLabel: String,
        maxTokens: Int?,
        cacheDir: File?,
        input: LiteRtConversationInput,
        thinkingEnabled: Boolean,
        topK: Int,
        topP: Double,
        temperature: Double,
        seed: Int,
        holdGpuEglContext: Boolean,
        onDiagnostic: (String) -> Unit,
        onChunk: suspend (String) -> Unit,
        onThinkingChunk: suspend (String) -> Unit
    ): LiteRtGenerationText = coroutineScope {
        onDiagnostic(
            "building EngineConfig backend=$backendLabel maxTokens=${maxTokens ?: "default"} " +
                "cacheDir=${cacheDir?.absolutePath ?: "default"} thread=${Thread.currentThread().name}"
        )
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
                null,
                null,
                maxTokens?.let { Integer.valueOf(it) },
                null,
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
            val samplerConfig = samplerConfigClass
                .getConstructor(
                    Integer.TYPE,
                    java.lang.Double.TYPE,
                    java.lang.Double.TYPE,
                    Integer.TYPE
                )
                .newInstance(topK, topP, temperature, seed)
            onDiagnostic("creating ConversationConfig backend=$backendLabel sampler=${samplerConfig != null}")
            val systemInstruction = input.promptOverride
                ?.let { null }
                ?: input.systemInstruction.takeIf { it.isNotBlank() }?.let { createContents(it) }
            val initialMessages = if (input.promptOverride != null) {
                emptyList<Any>()
            } else {
                input.initialMessages.mapNotNull { (role, content) -> createMessage(role, content) }
            }
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
                    emptyList<Any>(),
                    samplerConfig,
                    false,
                    emptyList<Any>(),
                    emptyMap<String, Any>()
                )
            onDiagnostic("creating Conversation backend=$backendLabel")
            val conversation = engineClass
                .getMethod("createConversation", conversationConfigClass)
                .invoke(engine, conversationConfig)
                ?: error("LiteRT-LM did not create a conversation")
            try {
                onDiagnostic("sending first async message backend=$backendLabel")
                generateStreaming(
                    conversation = conversation,
                    input = input,
                    thinkingEnabled = thinkingEnabled,
                    onDiagnostic = onDiagnostic,
                    onChunk = onChunk,
                    onThinkingChunk = onThinkingChunk
                )
            } finally {
                runCatching { conversation.javaClass.getMethod("close").invoke(conversation) }
            }
        } finally {
            runCatching { engineClass.getMethod("close").invoke(engine) }
        }
    }

    private suspend fun generateStreaming(
        conversation: Any,
        input: LiteRtConversationInput,
        thinkingEnabled: Boolean,
        onDiagnostic: (String) -> Unit,
        onChunk: suspend (String) -> Unit,
        onThinkingChunk: suspend (String) -> Unit
    ): LiteRtGenerationText = coroutineScope {
        val completion = CompletableDeferred<Unit>()
        val snapshots = Channel<LiteRtMessageSnapshot>(Channel.UNLIMITED)
        val callbackError = AtomicReference<Throwable?>(null)
        val rendered = StringBuilder()
        val metered = StringBuilder()
        var lastContentSnapshot = ""
        var lastThoughtSnapshot = ""

        val collector = launch {
            for (snapshot in snapshots) {
                currentCoroutineContext().ensureActive()
                val thoughtSnapshot = repairLiteRtCompactTextForDisplay(
                    sanitizeLiteRtRenderedTextForStreaming(snapshot.thought)
                )
                val thoughtDelta = liteRtStreamingDelta(thoughtSnapshot, lastThoughtSnapshot)
                if (thoughtDelta.isNotEmpty()) {
                    metered.append(thoughtDelta)
                    onThinkingChunk(thoughtDelta)
                }
                lastThoughtSnapshot = thoughtSnapshot
                val contentSnapshot = repairLiteRtCompactTextForDisplay(
                    sanitizeLiteRtRenderedTextForStreaming(snapshot.text)
                )
                val delta = liteRtStreamingDelta(contentSnapshot, lastContentSnapshot)
                if (delta.isNotEmpty()) {
                    rendered.append(delta)
                    metered.append(delta)
                    onChunk(delta)
                }
                lastContentSnapshot = contentSnapshot
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
                        if (snapshot.text.isNotEmpty() || snapshot.thought.isNotEmpty()) {
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
            val extraContext = mapOf("enable_thinking" to thinkingEnabled)
            val messageContents = createContents(input.promptOverride ?: input.userMessage)
            conversation.javaClass
                .getMethod("sendMessageAsync", contentsClass, messageCallbackClass, Map::class.java)
                .invoke(conversation, messageContents, callback, extraContext)
            onDiagnostic("async message accepted by LiteRT-LM")
            completion.await()
            finishedNormally = true
        } finally {
            if (!finishedNormally) {
                runCatching { conversation.javaClass.getMethod("cancelProcess").invoke(conversation) }
            }
            snapshots.close(callbackError.get())
            collector.join()
        }

        LiteRtGenerationText(
            visibleText = rendered.toString(),
            meteredText = metered.toString()
        )
    }

    private fun createContents(text: String): Any {
        val companion = contentsClass.getField("Companion").get(null)
        return companion.javaClass.getMethod("of", String::class.java).invoke(companion, text)
            ?: error("LiteRT-LM did not create Contents")
    }

    private fun createMessage(role: String, content: String): Any? {
        val companion = messageClass.getField("Companion").get(null)
        val methodName = when (role.lowercase()) {
            "assistant", "model" -> "model"
            "system" -> "system"
            "tool" -> "tool"
            else -> "user"
        }
        val method = companion.javaClass.methods.firstOrNull { method ->
            method.name == methodName &&
                method.parameterTypes.size == 1 &&
                method.parameterTypes.first() == String::class.java
        } ?: return null
        return method.invoke(companion, content)
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

    private companion object {
        val coreLibrariesLoaded = AtomicBoolean(false)
        val gpuAcceleratorLoaded = AtomicBoolean(false)
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
    val thought: String
)

private data class LiteRtGenerationText(
    val visibleText: String,
    val meteredText: String
)

internal fun liteRtMessageSnapshot(message: Any, thinkingEnabled: Boolean): LiteRtMessageSnapshot {
    val contentText = message.extractLiteRtMessageContentText()
    val rendered = selectLiteRtRenderedMessageText(
        contentText = contentText,
        messageString = message.toString()
    )
    val channelThought = if (thinkingEnabled) message.extractLiteRtThoughtChannel() else ""
    val leakedChannel = if (thinkingEnabled) {
        splitLeakedLiteRtThinkingChannel(rendered)
    } else {
        null
    }
    return LiteRtMessageSnapshot(
        text = leakedChannel?.first ?: rendered,
        thought = if (thinkingEnabled) {
            channelThought.ifBlank { leakedChannel?.second.orEmpty() }
        } else {
            ""
        }
    )
}

internal fun selectLiteRtRenderedMessageText(contentText: String, messageString: String): String {
    if (contentText.isBlank()) return messageString
    val rendered = messageString.takeIf { it.isNotBlank() } ?: return contentText
    val sanitizedContent = sanitizeLiteRtRenderedTextForStreaming(contentText)
    val sanitizedRendered = sanitizeLiteRtRenderedTextForStreaming(rendered)
    if (sanitizedRendered.isBlank()) return contentText
    if (looksLikeLiteRtObjectDump(sanitizedRendered)) return contentText

    val contentScore = liteRtRenderedTextScore(sanitizedContent)
    val renderedScore = liteRtRenderedTextScore(sanitizedRendered)
    return if (renderedScore > contentScore) rendered else contentText
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
    if (!shouldUseWordBoundaryJoin(nonBlankParts)) return rawJoin

    val rendered = StringBuilder()
    nonBlankParts.forEach { part ->
        appendLiteRtTextPart(rendered, part)
    }
    return rendered.toString()
}

private fun shouldUseWordBoundaryJoin(parts: List<String>): Boolean {
    if (parts.size < 3) return false
    if (parts.any { part -> part.any { it.isWhitespace() } }) return false
    val textParts = parts.filter { part -> part.any { it.isLetter() } }
    if (textParts.size < 2) return false
    val averageLength = textParts.sumOf { it.length }.toDouble() / textParts.size
    if (averageLength < 2.0) return false
    val dictionaryHits = textParts.count { part ->
        part.lowercase(Locale.US) in LiteRtCompactWordDictionary
    }
    return dictionaryHits >= 2 || dictionaryHits >= (textParts.size * 0.5).toInt().coerceAtLeast(1)
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
        val channelText = value?.toString().orEmpty()
        channelText.takeIf {
            normalizedKey != null && normalizedKey in thoughtKeys && channelText.isNotBlank()
        }
    }.orEmpty()
}.getOrDefault("")

private fun Any.callLiteRtNoArg(name: String): Any? =
    javaClass.methods
        .firstOrNull { method -> method.name == name && method.parameterTypes.isEmpty() }
        ?.invoke(this)

private fun splitLeakedLiteRtThinkingChannel(text: String): Pair<String, String>? {
    if (!LiteRtLeakedThinkingChannelStartPattern.containsMatchIn(text)) return null
    val finalMarker = LiteRtFinalChannelTokenPattern.find(text)
        ?: LiteRtFinalAnswerMarkerPattern.find(text)
    val visible = finalMarker
        ?.let { match -> text.substring(match.range.last + 1) }
        .orEmpty()
    val thought = finalMarker
        ?.let { match -> text.substring(0, match.range.first) }
        ?: text
    return visible to thought
}

internal fun sanitizeLiteRtRenderedText(text: String): String {
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
    return cleaned.trimStart()
}

internal fun sanitizeLiteRtRenderedTextForStreaming(text: String): String {
    if (text.isBlank()) return text
    return LiteRtDanglingControlTokenTailPattern.replace(sanitizeLiteRtRenderedText(text), "")
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
    val punctuationSpaced = LiteRtCompactPunctuationBoundaryPattern.replace(text) { match ->
        "${match.value} "
    }
    val repaired = LiteRtLongLetterRunPattern.replace(punctuationSpaced) { match ->
        splitLiteRtCompactRun(match.value) ?: match.value
    }
    return if (repaired != punctuationSpaced || shouldRepairLiteRtCompactText(text)) repaired else text
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
    pattern = """<\s*\|?\s*channel\s*\|?\s*>\s*(?:assistant|model|final|thought|thinking|reasoning|analysis)?""",
    option = RegexOption.IGNORE_CASE
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
    pattern = """(?is)^\s*(?:<\s*\|?\s*channel\s*\|?\s*>\s*)?(?:thought|thinking|reasoning|analysis)?\s*(?:Thinking\s*Process|Analyze\s*the\s*(?:Request|Input)|Determine\s*the\s*(?:Goal|Intent|Appropriate\s*Response)|Review\s*Constraints\s*/?\s*Style|Self\s*[- ]?\s*Correction\s*/?\s*Refinement)\s*:"""
)
private val LiteRtFinalChannelTokenPattern = Regex(
    pattern = """(?is)<\s*\|?\s*channel\s*\|?\s*>\s*(?:final|assistant|model)\b"""
)
private val LiteRtFinalAnswerMarkerPattern = Regex(
    pattern = """(?is)(?:^|\s)(?:Final\s*Answer|Final)\s*:\s*"""
)
private val LiteRtDanglingControlTokenTailPattern = Regex(
    pattern = """(?is)<\|?(?:start(?:[_ ]of(?:[_ ]turn)?)?|end(?:[_ ]of(?:[_ ]turn|[_ ]text)?)?|begin(?:[_ ]of(?:[_ ]text)?)?|turn|eot(?:[_ ]id)?|eom(?:[_ ]id)?|im(?:[_ ]start|[_ ]end)?|[a-z_ ]{0,24})?$"""
)
private val LiteRtAnyControlTokenPattern = Regex("""(?i)<\|?|turn>|channel>""")
private val LiteRtObjectDumpPattern = Regex("""^[A-Za-z0-9_.$]+\(.*=""")
private val LiteRtCompactPunctuationBoundaryPattern = Regex("""[.!?;:](?=\p{L})""")
private val LiteRtLongLetterRunPattern = Regex("""[\p{L}]{8,}""")
private val LiteRtCompactRepairSkipPattern = Regex(
    pattern = """(?i)(<tool_call|</tool_call|```|https?://|www\.|^\s*[\[{]|[}\]]\s*$)"""
)
private val LiteRtNoSpaceBeforeChars = setOf('.', ',', '!', '?', ';', ':', '%', ')', ']', '}', '"', '\'')
private val LiteRtNoSpaceAfterChars = setOf('(', '[', '{', '/', '\n')
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
    "standard",
    "warm",
    "formulate",
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

internal fun liteRtStreamingDelta(currentSnapshot: String, lastSnapshot: String): String {
    if (currentSnapshot.isBlank() || currentSnapshot == lastSnapshot) return ""
    if (currentSnapshot.startsWith(lastSnapshot)) return currentSnapshot.substring(lastSnapshot.length)
    if (lastSnapshot.startsWith(currentSnapshot)) return ""
    val commonPrefixLength = currentSnapshot
        .zip(lastSnapshot)
        .takeWhile { (current, last) -> current == last }
        .count()
    return currentSnapshot.substring(commonPrefixLength)
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
