package com.example.llamadroid.service.delegation

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaServerCardEntity
import com.example.llamadroid.data.model.NativeChatDelegateEventEntity
import com.example.llamadroid.data.model.NativeChatDelegateMessageEntity
import com.example.llamadroid.data.model.NativeChatDelegateMessageRole
import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import com.example.llamadroid.data.model.NativeChatDelegationWakeKind
import com.example.llamadroid.service.KvCacheKey
import com.example.llamadroid.service.KvSlotOwnerSpec
import com.example.llamadroid.service.NativeChatGenerationStates
import com.example.llamadroid.service.LlamaServerChatService
import com.example.llamadroid.service.LlamaServerRequestOptions
import com.example.llamadroid.service.LlamaServerSamplingParams
import com.example.llamadroid.service.LlamaServerUsageLease
import com.example.llamadroid.service.NativeChatKvCacheCoordinator
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.OllamaService.ChatMessage
import com.example.llamadroid.service.UnifiedNotificationManager
import com.example.llamadroid.service.compaction.CompactionProfile
import com.example.llamadroid.service.compaction.ConversationCompactor
import com.example.llamadroid.service.compaction.TokenEstimator
import com.example.llamadroid.util.DebugLog
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.coroutines.coroutineContext

/** Parses an `apiParams` JSON object the same way `LlamaClientService` does. */
internal fun parseDelegationParams(json: String?): Map<String, Any> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        @Suppress("UNCHECKED_CAST")
        Gson().fromJson(json, Map::class.java) as? Map<String, Any> ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }
}

private const val PROGRESS_THROTTLE_MS = 250L

/** Per-run inputs that exist only when the task is first started (not on Continue). */
internal class DelegationLoopStart(
    val inputs: List<DelegationResolvedInput>,
    val continuePrevious: Boolean
)

/**
 * The iterative model -> tool loop of one delegated task. One instance runs once; Continue
 * creates a new instance that rebuilds its conversation from the durable rows. Never recursive.
 */
internal class DelegateSubagentLoop(
    context: Context,
    private val taskId: Long,
    private val chatId: Long,
    private val inbox: DelegationInbox,
    private val liveFlow: MutableStateFlow<Map<Long, DelegationLiveStatus>>,
    private val scope: CoroutineScope,
    private val start: DelegationLoopStart?
) : SubagentReporter {
    private val context = context.applicationContext
    private val db = AppDatabase.getDatabase(this.context)
    private val taskDao get() = db.nativeChatDelegateTaskDao()
    private val msgDao get() = db.nativeChatDelegateMessageDao()
    private val eventDao get() = db.nativeChatDelegateEventDao()

    private class TaskGone : Exception()

    private lateinit var chat: LlamaChatEntity
    private lateinit var config: NativeChatDelegationConfig
    private lateinit var server: ResolvedDelegationServer
    private lateinit var guards: DelegationGuards
    private lateinit var toolbox: DelegateSubagentToolbox
    private var driver: DelegateHarnessDriver? = null
    private val chatService = LlamaServerChatService()
    private var title = ""
    private var compactionCount = 0
    private var ctxUsed = 0
    private var eventCount = 0
    private var thinkingEnabled = true
    private var sampling = LlamaServerSamplingParams()

    /** Cancels the harness turn the subagent is driving (used by Cancel). */
    suspend fun cancelHarnessTurn() {
        val d = driver ?: return
        val session = taskDao.getById(taskId)?.harnessSessionId ?: return
        runCatching { d.cancelTurn(session) }
    }

    /** Stops the in-flight model request. */
    fun stopModelRequest() = chatService.stopGeneration()

    // ---- entry ------------------------------------------------------------------------------

    suspend fun run() {
        var usage: LlamaServerUsageLease? = null
        try {
            val task = taskDao.getById(taskId) ?: return
            val loadedChat = db.llamaChatDao().getChatById(task.chatId) ?: return
            chat = loadedChat
            title = task.title
            config = NativeChatDelegationConfig.fromParams(parseDelegationParams(chat.apiParams))
            guards = DelegationGuards(task.subagentModelCalls, task.harnessPrompts)
            compactionCount = task.compactionCount
            ctxUsed = task.ctxUsedTokens
            driver = DelegateHarnessDriver(context, task.runtimeId)
            usage = body(task) { usage = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: TaskGone) {
            // Chat or task deleted: stop quietly.
        } catch (e: DelegationLoopStop) {
            interrupt(e.state, e.errorCode, e.note)
        } catch (e: DelegationServerException) {
            interrupt(NativeChatDelegateTaskState.INTERRUPTED, "server_down", e.message.orEmpty())
        } catch (e: DelegationHarnessException) {
            interrupt(NativeChatDelegateTaskState.INTERRUPTED, mapStartCode(e.code), e.message.orEmpty())
        } catch (e: Exception) {
            DebugLog.log("[DelegationLoop] failed: ${e.javaClass.simpleName}")
            interrupt(NativeChatDelegateTaskState.INTERRUPTED, "model_error", e.javaClass.simpleName)
        } finally {
            runCatching { usage?.close() }
            runCatching { driver?.close() }
        }
    }

    private fun mapStartCode(code: String): String = when (code) {
        "HARNESS_RUNTIME_REQUIRED", "HARNESS_RUNTIME_UNAVAILABLE" -> "runtime_missing"
        else -> "runtime_stopped"
    }

    private suspend fun body(task: NativeChatDelegateTaskEntity, onUsage: (LlamaServerUsageLease?) -> Unit): LlamaServerUsageLease? {
        val drv = checkNotNull(driver)
        phase("starting_runtime", NativeChatDelegateTaskState.STARTING_RUNTIME)
        val chatParams = parseDelegationParams(chat.apiParams)
        val resolver = DelegateServerResolver(context)
        server = resolver.resolve(chat, config.subagentServer, DelegateServerResolver.chatMaxOutput(chatParams)) { note ->
            progressSync(note)
        }
        val usage = server.cardId?.let { LlamaServerUsageLease(context, LlamaServerCardEntity.sessionIdForCard(it), scope) }
        onUsage(usage)
        val srvParams = parseDelegationParams(server.row.defaultApiParams)
        thinkingEnabled = (srvParams["enable_thinking"] as? Boolean) ?: true
        sampling = LlamaServerSamplingParams.fromParams(srvParams)
        live { it.copy(subagentContextWindow = server.contextTokens, subagentContextUsed = ctxUsed) }
        drv.ensureRuntime { progressSync(it) }
        event("runtime_ready")
        ensureTask()
        // Drop the transient "Starting the DeepSeek runtime..." line (live and persisted) now that it is ready.
        taskDao.setProgressNote(taskId, null)
        live { it.copy(progressNote = null) }
        val chatServer = resolver.rowFor(chat, DelegationServerChoice.SameAsChat).first
        toolbox = DelegateSubagentToolbox(
            context, taskDao.getById(taskId) ?: throw TaskGone(), chat, config, drv, chatServer, server,
            start?.inputs.orEmpty(), guards, inbox, this, DelegateServerResolver.chatMaxOutput(chatParams)
        )
        if (task.harnessSessionId != null) toolbox.reattach()
        repairDanglingToolCalls()
        seedFirstMessage(task)
        guards.resetStreaks()
        phase("model_call", NativeChatDelegateTaskState.RUNNING)
        mainLoop()
        return usage
    }

    // ---- main loop --------------------------------------------------------------------------

    private suspend fun mainLoop() {
        val system = ChatMessage(role = "system", content = DelegateSubagentPrompt.systemPrompt(config.approvalPolicy))
        val tools = DelegateSubagentToolDefs.all()
        while (true) {
            coroutineContext.ensureActive()
            ensureTask()
            injectInbox()
            val gate = guards.beforeModelCall()
            if (gate is GuardDecision.Fail) { fail(gate.code, gate.reason); return }
            waitForMainChatIdle()

            var rows = msgDao.getActiveForTask(taskId)
            if (shouldCompact(system, DelegateSubagentMessages.modelRows(rows), tools)) {
                if (compact(system, DelegateSubagentMessages.modelRows(rows), tools)) rows = msgDao.getActiveForTask(taskId)
            }
            phase("model_call", NativeChatDelegateTaskState.RUNNING)
            val started = System.currentTimeMillis()
            val response = try {
                callModel(listOf(system) + messagesFor(rows), tools)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!looksLikeContextOverflow(e)) throw e
                val modelRows = DelegateSubagentMessages.modelRows(rows)
                if (!compact(system, modelRows, tools)) throw e
                rows = msgDao.getActiveForTask(taskId)
                callModel(listOf(system) + messagesFor(rows), tools)
            }
            guards.onModelCallDone()
            event("model_call", count = guards.modelCalls, durationMs = System.currentTimeMillis() - started)
            val calls = response.toolCalls.orEmpty().mapIndexed { i, c ->
                if (c.id.isNullOrBlank()) c.copy(id = "sub_${guards.modelCalls}_$i") else c
            }
            val promptTokens = response.usage?.promptTokens ?: 0
            val completionTokens = response.usage?.completionTokens ?: 0
            if (promptTokens > 0) {
                ctxUsed = promptTokens + completionTokens
                live { it.copy(subagentContextUsed = ctxUsed, subagentContextWindow = server.contextTokens) }
            }
            msgDao.append(
                NativeChatDelegateMessageEntity(
                    taskId = taskId, seq = 0, role = NativeChatDelegateMessageRole.ASSISTANT,
                    content = DelegateSubagentMessages.bound(response.message.content, DelegateSubagentMessages.MAX_CONTENT_CHARS),
                    toolCallsJson = calls.takeIf { it.isNotEmpty() }?.let(DelegateSubagentMessages::toolCallsJson),
                    thinking = response.message.thinking?.takeIf { it.isNotBlank() }
                        ?.let { DelegateSubagentMessages.bound(it, DelegateSubagentMessages.MAX_THINKING_CHARS) },
                    promptTokens = promptTokens, completionTokens = completionTokens
                )
            )
            saveCounters()
            if (calls.isEmpty()) {
                when (val g = guards.onTextOnlyReply()) {
                    is GuardDecision.Nudge -> appendRow(NativeChatDelegateMessageRole.USER, g.text)
                    is GuardDecision.NeedsDirection -> { needsDirection(g.reason, g.errorCode); return }
                    else -> Unit
                }
                continue
            }
            guards.onToolCallsReceived()
            val followUps = mutableListOf<String>()
            var awaitMain = false
            for (call in calls) {
                coroutineContext.ensureActive()
                val outcome = toolbox.execute(call)
                appendTool(call, outcome.text)
                if (outcome.finished) { phase("finishing"); return }
                if (outcome.awaitMain) awaitMain = true
                val decision = outcome.guard?.takeIf { it !is GuardDecision.Continue }
                    ?: guards.onToolResult(DelegateSubagentMessages.signature(call), outcome.text.hashCode(), outcome.isError)
                when (decision) {
                    is GuardDecision.Fail -> { fail(decision.code, decision.reason); return }
                    is GuardDecision.NeedsDirection -> { needsDirection(decision.reason, decision.errorCode); return }
                    is GuardDecision.Warn -> followUps += decision.text
                    else -> Unit
                }
            }
            followUps.forEach { appendRow(NativeChatDelegateMessageRole.USER, it) }
            if (awaitMain && !waitForMainAnswer()) return
        }
    }

    private suspend fun waitForMainAnswer(): Boolean {
        phase("waiting_main", NativeChatDelegateTaskState.WAITING_MAIN)
        while (!inbox.hasPending()) {
            coroutineContext.ensureActive()
            ensureTask()
            val gate = guards.beforeModelCall()
            if (gate is GuardDecision.Fail) { fail(gate.code, gate.reason); return false }
            inbox.await(60_000L)
        }
        return true
    }

    private suspend fun waitForMainChatIdle() {
        if (config.parallelRequests) return
        var waited = false
        while (mainChatHoldsEndpoint()) {
            if (!waited) { phase("queued"); waited = true }
            coroutineContext.ensureActive()
            delay(1_000L)
        }
    }

    /** True while the main chat is busy AND runs on the same physical endpoint as this subagent. */
    private suspend fun mainChatHoldsEndpoint(): Boolean {
        if (!NativeChatGenerationStates.isBusy(chatId)) return false
        val mainServer = chat.serverId?.let { db.llamaServerDao().getServerById(it) }
        return DelegateEndpointSharing.sameEndpoint(
            mainServerId = mainServer?.id,
            mainCardId = mainServer?.managedServerCardId,
            subServerId = server.row.id,
            subCardId = server.cardId
        )
    }

    private suspend fun injectInbox() {
        val messages = inbox.drain()
        if (messages.isEmpty()) return
        messages.forEach { appendRow(NativeChatDelegateMessageRole.USER, DelegateSubagentPrompt.inboxMessage(it)) }
        phase("model_call", NativeChatDelegateTaskState.RUNNING)
    }

    private suspend fun seedFirstMessage(task: NativeChatDelegateTaskEntity) {
        val rows = msgDao.getActiveForTask(taskId)
        if (DelegateSubagentMessages.modelRows(rows).isNotEmpty()) return
        appendRow(
            NativeChatDelegateMessageRole.USER,
            DelegateSubagentPrompt.firstUserMessage(
                brief = task.request,
                inputNames = start?.inputs?.map { it.displayName }.orEmpty(),
                modelLabel = server.row.modelName?.takeIf { it.isNotBlank() } ?: server.row.name,
                contextTokens = server.contextTokens,
                maxOutputTokens = server.maxOutputTokens,
                policy = config.approvalPolicy,
                continuePrevious = start?.continuePrevious == true
            )
        )
    }

    /** After a crash mid-tool-batch, give every unanswered call a result so strict templates accept the history. */
    private suspend fun repairDanglingToolCalls() {
        val missing = DelegateSubagentMessages.missingToolResults(msgDao.getActiveForTask(taskId))
        missing.forEach {
            appendTool(it, "tool_error: interrupted. This call was not executed because the app restarted. Check the current state and retry if needed.")
        }
    }

    // ---- model ------------------------------------------------------------------------------

    private fun messagesFor(rows: List<NativeChatDelegateMessageEntity>): List<ChatMessage> =
        DelegateSubagentMessages.mergeConsecutiveUsers(
            DelegateSubagentMessages.toChatMessages(DelegateSubagentMessages.modelRows(rows))
        )

    private suspend fun callModel(
        messages: List<ChatMessage>,
        tools: List<com.example.llamadroid.service.AgentTool>,
        toolChoice: String? = null,
        maxTokens: Int = server.maxOutputTokens
    ): OllamaService.ChatResponse {
        val content = StringBuilder()
        val thinking = StringBuilder()
        val estimator = TokenEstimator.calibrated(server.baseUrl)
        val estimated = estimator.estimateMessages(messages) + estimator.estimateTools(tools)
        val spec = KvSlotOwnerSpec(
            key = KvCacheKey.Delegate(taskId),
            persist = config.subagentKvCacheEnabled && server.isLoopback,
            conversationId = "delegate:$taskId"
        )
        var lastProgressMs = 0L
        val response = try { NativeChatKvCacheCoordinator(context).withSlot(server.baseUrl, server.row, spec) { lease ->
            lease.request { slot ->
                chatService.chatWithToolsStreaming(
                    baseUrl = server.baseUrl,
                    messages = messages,
                    tools = tools,
                    modelLabel = server.row.modelName?.takeIf { it.isNotBlank() },
                    thinkingEnabled = thinkingEnabled,
                    maxTokens = maxTokens,
                    samplingParams = sampling,
                    // 0 = no read deadline: slow phones may take minutes to process the prompt.
                    requestOptions = LlamaServerRequestOptions(slotId = slot, toolChoice = toolChoice, streamReadTimeoutMs = 0),
                    slotOwner = null,
                    onPromptProgress = { p ->
                        val now = System.currentTimeMillis()
                        if (p.total > 0 && (now - lastProgressMs >= PROGRESS_THROTTLE_MS || p.processed >= p.total)) {
                            lastProgressMs = now
                            live { it.copy(subagentPromptProcessed = p.processed, subagentPromptTotal = p.total) }
                        }
                    },
                    onChunk = { delta, thinkingDelta ->
                        delta?.let { content.append(it) }
                        thinkingDelta?.let { thinking.append(it) }
                        if (!delta.isNullOrEmpty() || !thinkingDelta.isNullOrEmpty()) {
                            if (liveFlow.value[taskId]?.subagentGenerating != true) {
                                live { it.copy(subagentGenerating = true, subagentPromptProcessed = 0, subagentPromptTotal = 0) }
                            }
                        }
                    }
                ).getOrThrow()
            }
        } } finally {
            live { it.copy(subagentPromptProcessed = 0, subagentPromptTotal = 0, subagentGenerating = false) }
        }
        response.usage?.promptTokens?.takeIf { it > 0 }?.let { TokenEstimator.recordExact(server.baseUrl, estimated, it) }
        return response.copy(
            message = response.message.copy(
                content = content.toString().ifBlank { response.message.content },
                thinking = thinking.toString().ifBlank { response.message.thinking.orEmpty() }.ifBlank { null }
            )
        )
    }

    private fun looksLikeContextOverflow(e: Exception): Boolean {
        val m = e.message.orEmpty().lowercase()
        return m.contains("context") && (m.contains("exceed") || m.contains("too long") || m.contains("larger") || m.contains("n_ctx")) ||
            m.contains("n_ctx")
    }

    // ---- compaction -------------------------------------------------------------------------

    private fun shouldCompact(system: ChatMessage, modelRows: List<NativeChatDelegateMessageEntity>,
                              tools: List<com.example.llamadroid.service.AgentTool>): Boolean {
        val estimator = TokenEstimator.calibrated(server.baseUrl)
        val estimate = estimator.estimateMessages(listOf(system) + DelegateSubagentMessages.toChatMessages(modelRows)) +
            estimator.estimateTools(tools)
        return ConversationCompactor.shouldCompact(
            estimate, server.contextTokens, ConversationCompactor.outputReserve(server.contextTokens, server.maxOutputTokens)
        )
    }

    /** Summarizes older rows into one summary row. Returns false when there is nothing to compact. */
    private suspend fun compact(
        system: ChatMessage,
        modelRows: List<NativeChatDelegateMessageEntity>,
        tools: List<com.example.llamadroid.service.AgentTool>
    ): Boolean {
        val estimator = TokenEstimator.calibrated(server.baseUrl)
        val msgs = DelegateSubagentMessages.toChatMessages(modelRows)
        val cut = DelegateSubagentCompaction.planCut(msgs, estimator) ?: return false
        val through = DelegateSubagentCompaction.throughSeq(modelRows, cut) ?: return false
        phase("compacting")
        val started = System.currentTimeMillis()
        val plan = DelegateSubagentCompaction.plan(system, msgs, cut, estimator)
        val request = DelegateSubagentMessages.mergeConsecutiveUsers(
            ConversationCompactor.buildSummaryRequest(plan, CompactionProfile.SUBAGENT)
        )
        val generated = try {
            // llama-server only accepts auto/required here; same tools keep the warm prefix and the
            // instruction forbids tool calls (an invalid reply falls back to the deterministic summary).
            callModel(request, tools, toolChoice = null, maxTokens = ConversationCompactor.summaryMaxTokens(server.contextTokens))
                .message.content.trim()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        val summary = if (ConversationCompactor.isValidSummary(generated, CompactionProfile.SUBAGENT)) generated
        else ConversationCompactor.deterministicSummary(plan.toSummarize, CompactionProfile.SUBAGENT)
        val newId = msgDao.append(
            NativeChatDelegateMessageEntity(
                taskId = taskId, seq = 0, role = NativeChatDelegateMessageRole.USER,
                content = DelegateSubagentMessages.bound(summary, DelegateSubagentMessages.MAX_CONTENT_CHARS), isSummary = true
            )
        )
        msgDao.markCompactedThrough(taskId, through)
        msgDao.retireOlderSummaries(taskId, newId)
        val after = estimator.estimateMessages(ConversationCompactor.applySummary(plan, summary))
        val saved = (plan.estimatedTokensBefore - after).coerceAtLeast(0)
        appendRow(NativeChatDelegateMessageRole.NOTICE, "Context compacted (~$saved tokens saved)")
        compactionCount++
        saveCounters()
        event("compaction", count = saved, durationMs = System.currentTimeMillis() - started)
        return true
    }

    // ---- terminal states --------------------------------------------------------------------

    private suspend fun fail(code: String, reason: String) {
        val now = System.currentTimeMillis()
        val latest = taskDao.getById(taskId) ?: return
        taskDao.update(
            latest.copy(
                state = NativeChatDelegateTaskState.FAILED, lastErrorCode = code, progressNote = reason.take(300),
                resultStatus = "failed", resultSummary = reason.take(1_000), finishedAt = now,
                nextCheckAt = null, checkNote = null, updatedAt = now
            )
        )
        appendRow(NativeChatDelegateMessageRole.SUB_TO_MAIN, reason, toolName = "result")
        event("finished", status = "failed", errorClass = code)
        inbox.emitReply("result", reason)
        live { it.copy(state = NativeChatDelegateTaskState.FAILED, phase = "finishing") }
        DelegationAlarmScheduler.cancel(context, taskId)
        DelegationWakeDispatcher.enqueue(
            context, chatId, taskId, NativeChatDelegationWakeKind.TASK_FINISHED,
            DelegationResultNotice.finished(title, "failed", reason, emptyList())
        )
    }

    private suspend fun needsDirection(reason: String, errorCode: String? = null) {
        taskDao.setStateWithError(taskId, NativeChatDelegateTaskState.NEEDS_DIRECTION, errorCode, reason.take(300))
        event("runaway_continuation_suspected", errorClass = errorCode ?: "guard")
        event("needs_direction")
        live { it.copy(state = NativeChatDelegateTaskState.NEEDS_DIRECTION, progressNote = reason.take(300)) }
        DelegationWakeDispatcher.enqueue(
            context, chatId, taskId, NativeChatDelegationWakeKind.NEEDS_DIRECTION,
            DelegationResultNotice.needsDirection(reason)
        )
    }

    private suspend fun interrupt(state: String, code: String?, note: String) {
        if (taskDao.getById(taskId) == null) return
        taskDao.setStateWithError(taskId, state, code, note.take(300))
        event("interrupted", errorClass = code)
        live { it.copy(state = state) }
        runCatching {
            UnifiedNotificationManager.showDeepSeekTaskNotice(
                chatId, chatOrNull()?.serverId ?: 0L, chatOrNull()?.title ?: title,
                context.getString(R.string.native_chat_delegation_service_task_interrupted)
            )
        }
    }

    private fun chatOrNull(): LlamaChatEntity? = if (this::chat.isInitialized) chat else null

    private suspend fun ensureTask() {
        if (taskDao.getById(taskId) == null) throw TaskGone()
    }

    // ---- reporter ---------------------------------------------------------------------------

    override suspend fun phase(phase: String, state: String?) {
        if (state != null) {
            ensureTask()
            taskDao.setState(taskId, state)
        }
        live { it.copy(phase = phase, state = state ?: it.state) }
    }

    override fun live(transform: (DelegationLiveStatus) -> DelegationLiveStatus) {
        liveFlow.update { map ->
            val base = map[taskId] ?: DelegationLiveStatus(taskId, chatId, NativeChatDelegateTaskState.QUEUED, "queued")
            map + (taskId to transform(base).copy(updatedAt = System.currentTimeMillis()))
        }
    }

    private fun progressSync(note: String) {
        live { it.copy(progressNote = note.take(300)) }
    }

    override suspend fun event(type: String, toolName: String?, status: String?, count: Int?, durationMs: Long?, errorClass: String?) {
        runCatching {
            eventDao.insert(
                NativeChatDelegateEventEntity(
                    taskId = taskId, type = type, phase = liveFlow.value[taskId]?.phase, toolName = toolName,
                    status = status, count = count, durationMs = durationMs, errorClass = errorClass
                )
            )
            if (++eventCount % 100 == 0) eventDao.prune(taskId)
        }
    }

    override suspend fun appendRow(role: String, content: String, toolName: String?): Long =
        msgDao.append(
            NativeChatDelegateMessageEntity(
                taskId = taskId, seq = 0, role = role,
                content = DelegateSubagentMessages.bound(content, DelegateSubagentMessages.MAX_CONTENT_CHARS), toolName = toolName
            )
        )

    private suspend fun appendTool(call: OllamaService.ToolCall, text: String) {
        msgDao.append(
            NativeChatDelegateMessageEntity(
                taskId = taskId, seq = 0, role = NativeChatDelegateMessageRole.TOOL,
                content = DelegateSubagentMessages.bound(text, DelegateSubagentMessages.MAX_TOOL_ROW_CHARS),
                toolName = call.name, toolCallId = call.id
            )
        )
    }

    override suspend fun progress(note: String) {
        taskDao.setProgressNote(taskId, note)
        live { it.copy(progressNote = note) }
    }

    override suspend fun saveCounters() {
        taskDao.updateCounters(taskId, guards.modelCalls, guards.harnessPrompts, compactionCount, ctxUsed, server.contextTokens)
    }

    override suspend fun saveDigest(json: String) {
        taskDao.setDigest(taskId, json)
    }
}
