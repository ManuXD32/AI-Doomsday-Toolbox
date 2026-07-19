package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.model.LlamaScheduledTaskEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.util.DebugLog
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class LlamaScheduledTaskRunResult(
    val output: String,
    val toolActivity: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val noteToolMutatedNote: Boolean = false
)

internal data class LlamaScheduledTaskReview(
    val needsRepair: Boolean,
    val feedback: String
)

class LlamaScheduledTaskRunner(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val ollamaService: OllamaService,
    private val llamaServerChatService: LlamaServerChatService,
    private val nativeChatToolRuntime: NativeChatToolRuntime
) {
    suspend fun runTask(
        task: LlamaScheduledTaskEntity,
        server: LlamaServerEntity,
        onStatus: (String) -> Unit = {}
    ): LlamaScheduledTaskRunResult {
        val params = parseChatParams(task.apiParams)
        val thinkingEnabled = (params["enable_thinking"] as? Boolean) ?: true
        val rawToolConfig = NativeChatToolConfig.fromParams(params)
            .effectiveWithServerDefaults(NativeChatToolConfig.fromApiParams(server.defaultApiParams))
        val toolConfig = nativeChatToolRuntime.configWithOrganizerPermissions(rawToolConfig)
        val tools = nativeChatToolRuntime.availableTools(toolConfig)
        val modelName = if (server.isOllamaEngine()) {
            server.modelName?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(context.getString(R.string.llama_ollama_model_required))
        } else if (server.isLlamaSwapEngine()) {
            server.modelName?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(context.getString(R.string.llama_swap_model_required))
        } else {
            server.modelName?.takeIf { it.isNotBlank() }
        }

        if (server.isOllamaEngine()) {
            syncOllamaService(server)
        }

        val messages = buildInitialMessages(task, toolConfig).toMutableList()
        val samplingParams = LlamaServerSamplingParams.fromParams(params)
        val numCtx = task.contextSize.takeIf { it > 0 } ?: 8192
        val toolActivity = mutableListOf<String>()
        var finalOutput = ""
        var promptTokens = 0
        var completionTokens = 0
        var noteToolMutatedNote = false

        suspend fun runModelCall(
            availableTools: List<AgentTool>,
            callMessages: List<OllamaService.ChatMessage> = messages,
            useThinking: Boolean = thinkingEnabled,
            contextSize: Int = numCtx,
            sampling: LlamaServerSamplingParams = samplingParams
        ): OllamaService.ChatResponse {
            val content = StringBuilder()
            val thinking = StringBuilder()
            val response = if (server.isOllamaEngine()) {
                ollamaService.chatWithToolsStreaming(
                    model = modelName.orEmpty(),
                    messages = callMessages,
                    tools = availableTools,
                    thinkingEnabled = useThinking,
                    numCtxOverride = contextSize,
                    onChunk = { delta, thinkingDelta ->
                        delta?.let { content.append(it) }
                        thinkingDelta?.let { thinking.append(it) }
                    }
                ).getOrElse { throw it }
            } else {
                llamaServerChatService.chatWithToolsStreaming(
                    baseUrl = server.baseUrl(),
                    messages = callMessages,
                    tools = availableTools,
                    modelLabel = modelName,
                    thinkingEnabled = useThinking,
                    maxTokens = contextSize,
                    samplingParams = sampling,
                    onChunk = { delta, thinkingDelta ->
                        delta?.let { content.append(it) }
                        thinkingDelta?.let { thinking.append(it) }
                    }
                ).getOrElse { throw it }
            }
            response.usage?.promptTokens?.let { promptTokens += it }
            response.usage?.completionTokens?.let { completionTokens += it }
            val streamedContent = content.toString().ifBlank { response.message.content }
            val streamedThinking = thinking.toString().ifBlank { response.message.thinking.orEmpty() }
            return response.copy(
                message = response.message.copy(
                    content = streamedContent,
                    thinking = streamedThinking.ifBlank { null }
                )
            )
        }

        suspend fun executeToolCalls(toolCalls: List<OllamaService.ToolCall>) {
            val imageReviewMessages = mutableListOf<OllamaService.ChatMessage>()
            for (toolCall in toolCalls) {
                currentCoroutineContext().ensureActive()
                val title = toolCall.arguments["query"]
                    ?: toolCall.arguments["url"]
                    ?: toolCall.arguments["prompt"]
                    ?: toolCall.name
                onStatus(context.getString(R.string.llama_scheduler_status_tool, toolCall.name))
                toolActivity += "tool_start name=${toolCall.name} input=${title.take(160)}"
                val result = try {
                    nativeChatToolRuntime.executeToolCall(
                        toolCall = toolCall,
                        config = toolConfig,
                        onProgress = { progress ->
                            val line = buildString {
                                append("tool_progress name=${toolCall.name}")
                                progress.phase.takeIf { it.isNotBlank() }?.let { append(" phase=$it") }
                                progress.status.takeIf { it.isNotBlank() }?.let { append(" status=${it.take(80)}") }
                                progress.url?.takeIf { it.isNotBlank() }?.let { append(" url=${it.take(200)}") }
                                progress.outputPreview?.takeIf { it.isNotBlank() }?.let {
                                    append(" preview=${it.replace('\n', ' ').take(300)}")
                                }
                            }
                            toolActivity += line
                        },
                        searchSummarizer = { request ->
                            summarizeSearchPage(server, modelName, request)
                        }
                    ).getOrElse { error ->
                        NativeChatToolResult("tool_error: ${error.message ?: error::class.java.simpleName}")
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    NativeChatToolResult("tool_error: ${error.message ?: error::class.java.simpleName}")
                }
                if (toolCall.name in NOTE_MUTATION_TOOLS && !result.content.startsWith("tool_error:")) {
                    noteToolMutatedNote = true
                }
                toolActivity += "tool_done name=${toolCall.name} output=${result.content.replace('\n', ' ').take(500)}"
                messages += OllamaService.ChatMessage(
                    role = "tool",
                    content = result.content,
                    toolCallId = toolCall.id
                )
                result.generatedImagePath
                    ?.takeIf { toolConfig.imageIterationEnabled && it.isNotBlank() }
                    ?.let { imagePath ->
                        buildGeneratedImageReviewMessage(imagePath, server)?.let { imageReviewMessages += it }
                    }
            }
            messages += imageReviewMessages
        }

        suspend fun runToolLoop(
            rounds: Int,
            roundOffset: Int = 0,
            repair: Boolean = false
        ): Boolean {
            for (round in 0 until rounds) {
                currentCoroutineContext().ensureActive()
                onStatus(
                    if (repair) {
                        context.getString(R.string.llama_scheduler_status_repairing_round, round + 1)
                    } else {
                        context.getString(R.string.llama_scheduler_status_generating_round, round + 1)
                    }
                )
                val response = runModelCall(tools)
                val toolCalls = normalizeToolCalls(response.toolCalls.orEmpty(), roundOffset + round)
                if (toolCalls.isEmpty()) {
                    finalOutput = response.message.content
                    messages += response.message
                    return true
                }

                messages += response.message.copy(content = "", toolCalls = toolCalls)
                executeToolCalls(toolCalls)
            }
            return false
        }

        if (tools.isEmpty()) {
            onStatus(context.getString(R.string.llama_scheduler_status_generating))
            val response = runModelCall(tools)
            finalOutput = response.message.content
            messages += response.message
        } else if (!runToolLoop(toolConfig.maxToolRounds)) {
            messages += OllamaService.ChatMessage(
                role = "system",
                content = "The scheduled task tool round limit has been reached. Answer now using only the tool results already provided. Do not call more tools."
            )
            onStatus(context.getString(R.string.llama_scheduler_status_finalizing))
            val finalResponse = runModelCall(emptyList())
            finalOutput = finalResponse.message.content
            messages += finalResponse.message
        }

        onStatus(context.getString(R.string.llama_scheduler_status_reviewing))
        val review = reviewScheduledTaskCompletion(
            task = task,
            toolConfig = toolConfig,
            finalOutput = finalOutput,
            toolActivity = toolActivity.joinToString("\n"),
            noteToolMutatedNote = noteToolMutatedNote,
            reviewer = { reviewMessages ->
                runModelCall(
                    availableTools = emptyList(),
                    callMessages = reviewMessages,
                    useThinking = false,
                    contextSize = minOf(numCtx, SCHEDULED_REVIEW_CONTEXT),
                    sampling = LlamaServerSamplingParams(temperature = 0.1f, topP = 0.9f)
                ).message.content
            }
        )
        if (review.needsRepair) {
            toolActivity += "review_repair feedback=${review.feedback.replace('\n', ' ').take(700)}"
            messages += OllamaService.ChatMessage(
                role = "system",
                content = buildString {
                    appendLine("A scheduler reviewer checked your result against the original task and found it incomplete.")
                    appendLine("Reviewer feedback:")
                    appendLine(review.feedback)
                    appendLine()
                    appendLine("Fix the missing work now. If a note, todo, calendar event, alarm, web fetch, or other enabled tool is needed, call the tool now. Then give a corrected compact completion summary.")
                }
            )
            val repairRounds = minOf(SCHEDULED_REPAIR_TOOL_ROUNDS, toolConfig.maxToolRounds)
            if (tools.isNotEmpty() && !runToolLoop(repairRounds, roundOffset = toolConfig.maxToolRounds, repair = true)) {
                messages += OllamaService.ChatMessage(
                    role = "system",
                    content = "The scheduler repair tool round limit has been reached. Answer now using only the tool results already provided. Do not call more tools."
                )
                onStatus(context.getString(R.string.llama_scheduler_status_finalizing))
                val repairedResponse = runModelCall(emptyList())
                finalOutput = repairedResponse.message.content
                messages += repairedResponse.message
            } else if (tools.isEmpty()) {
                onStatus(context.getString(R.string.llama_scheduler_status_finalizing))
                val repairedResponse = runModelCall(emptyList())
                finalOutput = repairedResponse.message.content
                messages += repairedResponse.message
            }
        } else {
            toolActivity += "review_ok feedback=${review.feedback.replace('\n', ' ').take(300)}"
        }

        return LlamaScheduledTaskRunResult(
            output = finalOutput,
            toolActivity = toolActivity.joinToString("\n"),
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            noteToolMutatedNote = noteToolMutatedNote
        )
    }

    private fun buildInitialMessages(
        task: LlamaScheduledTaskEntity,
        toolConfig: NativeChatToolConfig
    ): List<OllamaService.ChatMessage> = buildList {
        task.systemPrompt?.takeIf { it.isNotBlank() }?.let {
            add(OllamaService.ChatMessage(role = "system", content = it))
        }
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = "You are running an autonomous scheduled native Llama task. Complete the task without assuming any previous chat history. Use enabled tools when useful, save durable notes only when the task asks for notes or when it clearly helps the user, and finish with a compact summary of what was done."
            )
        )
        addAll(nativeChatToolAwarenessMessages(toolConfig))
        add(OllamaService.ChatMessage(role = "user", content = task.taskPrompt))
    }

    private suspend fun reviewScheduledTaskCompletion(
        task: LlamaScheduledTaskEntity,
        toolConfig: NativeChatToolConfig,
        finalOutput: String,
        toolActivity: String,
        noteToolMutatedNote: Boolean,
        reviewer: suspend (List<OllamaService.ChatMessage>) -> String
    ): LlamaScheduledTaskReview {
        val localFindings = mutableListOf<String>()
        if (toolConfig.toolsEnabled &&
            toolConfig.noteToolsEnabled &&
            scheduledTaskPromptRequestsNote(task.taskPrompt) &&
            !noteToolMutatedNote
        ) {
            localFindings += "The task asked for a note, note tools were enabled, but no successful note/todo mutation tool call was recorded. Call create_note or the appropriate note tool now."
        }

        val reviewMessages = listOf(
            OllamaService.ChatMessage(
                role = "system",
                content = "You are the scheduler review LLM. Check whether the scheduled task output actually completed the original task. Return exactly one of these formats: OK: short reason, or FIX: specific missing work. If the task asked to create, save, or update a note, require evidence in the tool activity that a note/todo mutation tool succeeded."
            ),
            OllamaService.ChatMessage(
                role = "user",
                content = buildString {
                    appendLine("Original scheduled task name:")
                    appendLine(task.name)
                    appendLine()
                    appendLine("Original scheduled task prompt:")
                    appendLine(task.taskPrompt.take(SCHEDULED_REVIEW_PROMPT_CHARS))
                    appendLine()
                    appendLine("Enabled tool summary:")
                    appendLine("tools_enabled=${toolConfig.toolsEnabled}")
                    appendLine("note_tools_enabled=${toolConfig.noteToolsEnabled}")
                    appendLine("todo_tools_enabled=${toolConfig.todoToolsEnabled}")
                    appendLine("web_search_enabled=${toolConfig.webSearchEnabled}")
                    appendLine("fetch_url_enabled=${toolConfig.fetchUrlEnabled}")
                    appendLine("kiwix_search_enabled=${toolConfig.kiwixSearchEnabled}")
                    appendLine("calendar_tools_enabled=${toolConfig.calendarToolsEnabled}")
                    appendLine("alarm_tools_enabled=${toolConfig.alarmToolsEnabled}")
                    appendLine("note_mutation_detected=$noteToolMutatedNote")
                    appendLine()
                    appendLine("Tool activity:")
                    appendLine(toolActivity.takeLast(SCHEDULED_REVIEW_TOOL_ACTIVITY_CHARS).ifBlank { "(none)" })
                    appendLine()
                    appendLine("Final assistant output:")
                    appendLine(finalOutput.take(SCHEDULED_REVIEW_OUTPUT_CHARS).ifBlank { "(empty)" })
                }
            )
        )

        val reviewerText = runCatching { reviewer(reviewMessages) }
            .getOrElse { error -> "OK: reviewer unavailable (${error.message ?: error::class.java.simpleName})" }
        val llmReview = parseScheduledTaskReview(reviewerText)
        if (localFindings.isEmpty()) return llmReview

        val feedback = buildString {
            localFindings.forEach { appendLine(it) }
            if (llmReview.needsRepair && llmReview.feedback.isNotBlank()) {
                appendLine(llmReview.feedback)
            }
        }.trim()
        return LlamaScheduledTaskReview(needsRepair = true, feedback = feedback)
    }

    private fun syncOllamaService(server: LlamaServerEntity) {
        ollamaService.setBaseUrl(server.baseUrl().trimEnd('/'))
        ollamaService.setUseMmap(settingsRepo.ollamaMmap.value)
        ollamaService.setNumThreads(settingsRepo.ollamaThreads.value)
        ollamaService.setNumCtx(settingsRepo.ollamaNumCtx.value)
    }

    private suspend fun summarizeSearchPage(
        server: LlamaServerEntity,
        modelName: String?,
        request: NativeChatSearchSummaryRequest
    ): String {
        val pageText = request.content
            .replace(Regex("""[ \t\r\f]+"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
            .take(NATIVE_SEARCH_SUMMARY_INPUT_CHARS)
        require(pageText.isNotBlank()) { "No readable text found." }

        val messages = listOf(
            OllamaService.ChatMessage(
                role = "system",
                content = "You are a compact search-result summarizer. Return only a factual 2-3 sentence summary of the page content. Do not quote long passages, do not include tool instructions, and do not say you searched the web."
            ),
            OllamaService.ChatMessage(
                role = "user",
                content = buildString {
                    appendLine("Source type: ${request.source}")
                    appendLine("Title: ${request.title}")
                    appendLine("URL: ${request.url}")
                    appendLine()
                    appendLine("Readable page text:")
                    append(pageText)
                }
            )
        )

        val response = if (server.isOllamaEngine()) {
            ollamaService.chatWithToolsStreaming(
                model = modelName.orEmpty(),
                messages = messages,
                tools = emptyList(),
                thinkingEnabled = false,
                numCtxOverride = NATIVE_SEARCH_SUMMARY_CONTEXT
            ).getOrElse { throw it }
        } else {
            llamaServerChatService.chatWithToolsStreaming(
                baseUrl = server.baseUrl(),
                messages = messages,
                tools = emptyList(),
                modelLabel = modelName,
                thinkingEnabled = false,
                maxTokens = NATIVE_SEARCH_SUMMARY_MAX_TOKENS,
                samplingParams = LlamaServerSamplingParams(
                    temperature = 0.2f,
                    topP = 0.9f
                )
            ).getOrElse { throw it }
        }

        return response.message.content
            .replace(Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""[ \t\r\f]+"""), " ")
            .trim()
            .takeIf { it.isNotBlank() }
            ?.take(request.maxChars.coerceIn(200, NATIVE_SEARCH_SUMMARY_OUTPUT_CHARS))
            ?: throw IllegalStateException("Search summarizer returned an empty summary.")
    }

    private fun buildGeneratedImageReviewMessage(
        imagePath: String,
        server: LlamaServerEntity
    ): OllamaService.ChatMessage? {
        if (!server.supportsVision) return null
        val imageFile = File(imagePath)
        if (!imageFile.exists() || !imageFile.isFile) return null
        val encodedImage = runCatching { fileToBase64(imagePath) }.getOrNull() ?: return null
        return OllamaService.ChatMessage(
            role = "user",
            content = "Generated image from generate_image is attached for visual review. Compare it with the scheduled task request. If it needs improvement and tool rounds remain, call generate_image again with a better optimized prompt. If it is good enough, finish the scheduled task.",
            images = if (server.isOllamaEngine()) listOf(encodedImage) else null,
            imagePath = imagePath
        )
    }

    private fun normalizeToolCalls(
        toolCalls: List<OllamaService.ToolCall>,
        round: Int
    ): List<OllamaService.ToolCall> = toolCalls.mapIndexed { index, toolCall ->
        if (!toolCall.id.isNullOrBlank()) {
            toolCall
        } else {
            toolCall.copy(id = "scheduled_call_${round}_${index}_${System.nanoTime()}")
        }
    }

    private fun parseChatParams(apiParams: String?): Map<String, Any> {
        if (apiParams.isNullOrBlank()) return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            Gson().fromJson(apiParams, Map::class.java) as? Map<String, Any> ?: emptyMap()
        } catch (error: Exception) {
            DebugLog.log("[LlamaScheduler] apiParams parse failed: ${error.message}")
            emptyMap()
        }
    }

    private companion object {
        const val NATIVE_SEARCH_SUMMARY_CONTEXT = 4096
        const val NATIVE_SEARCH_SUMMARY_MAX_TOKENS = 512
        const val NATIVE_SEARCH_SUMMARY_INPUT_CHARS = 6_000
        const val NATIVE_SEARCH_SUMMARY_OUTPUT_CHARS = 900
        const val SCHEDULED_REVIEW_CONTEXT = 4096
        const val SCHEDULED_REVIEW_PROMPT_CHARS = 4_000
        const val SCHEDULED_REVIEW_TOOL_ACTIVITY_CHARS = 8_000
        const val SCHEDULED_REVIEW_OUTPUT_CHARS = 6_000
        const val SCHEDULED_REPAIR_TOOL_ROUNDS = 3
        val NOTE_MUTATION_TOOLS = setOf(
            NativeChatToolRuntime.TOOL_CREATE_NOTE,
            NativeChatToolRuntime.TOOL_UPDATE_NOTE,
            NativeChatToolRuntime.TOOL_REPLACE_NOTE_TEXT,
            NativeChatToolRuntime.TOOL_CREATE_TODO_LIST,
            NativeChatToolRuntime.TOOL_ADD_TODO_ITEM,
            NativeChatToolRuntime.TOOL_UPDATE_TODO_ITEM,
            NativeChatToolRuntime.TOOL_REMOVE_TODO_ITEM,
            NativeChatToolRuntime.TOOL_SET_TODO_ITEM_CHECKED
        )
    }
}

internal fun parseScheduledTaskReview(text: String): LlamaScheduledTaskReview {
    val clean = text
        .replace(Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL), "")
        .trim()
    if (clean.isBlank()) {
        return LlamaScheduledTaskReview(needsRepair = false, feedback = "Reviewer returned no feedback.")
    }
    val firstMeaningfulLine = clean.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
    val upper = firstMeaningfulLine.uppercase()
    return when {
        upper.startsWith("FIX:") -> LlamaScheduledTaskReview(
            needsRepair = true,
            feedback = firstMeaningfulLine.substringAfter(':', clean).trim().ifBlank { clean }
        )
        upper == "FIX" || upper.startsWith("FIX ") -> LlamaScheduledTaskReview(
            needsRepair = true,
            feedback = clean.removePrefix(firstMeaningfulLine).trim().ifBlank { clean }
        )
        upper.startsWith("OK:") -> LlamaScheduledTaskReview(
            needsRepair = false,
            feedback = firstMeaningfulLine.substringAfter(':', "Task completed.").trim()
        )
        upper == "OK" || upper.startsWith("OK ") -> LlamaScheduledTaskReview(
            needsRepair = false,
            feedback = clean.removePrefix(firstMeaningfulLine).trim().ifBlank { "Task completed." }
        )
        else -> LlamaScheduledTaskReview(needsRepair = false, feedback = clean.take(700))
    }
}
