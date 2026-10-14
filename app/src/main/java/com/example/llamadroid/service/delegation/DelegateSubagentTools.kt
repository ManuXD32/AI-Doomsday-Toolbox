package com.example.llamadroid.service.delegation

import android.content.Context
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.NativeChatDelegateMessageRole
import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import com.example.llamadroid.data.model.NativeChatDelegationWakeKind
import com.example.llamadroid.harness.HarnessAppRuntime
import com.example.llamadroid.service.AgentTool
import com.example.llamadroid.service.OllamaService.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Result of one subagent tool call. */
internal data class SubagentToolOutcome(
    val text: String,
    val isError: Boolean = false,
    /** finish_task succeeded: the loop ends. */
    val finished: Boolean = false,
    /** message_main(question): the loop waits for the main agent's answer. */
    val awaitMain: Boolean = false,
    /** A guard fired while executing (for example unchanged digests). */
    val guard: GuardDecision? = null
)

/** Thrown to stop the loop into a Continue-able state. */
internal class DelegationLoopStop(val state: String, val errorCode: String?, val note: String) : Exception(note)

/** What the toolbox needs from the loop. */
internal interface SubagentReporter {
    suspend fun phase(phase: String, state: String? = null)
    fun live(transform: (DelegationLiveStatus) -> DelegationLiveStatus)
    suspend fun event(type: String, toolName: String? = null, status: String? = null, count: Int? = null,
                      durationMs: Long? = null, errorClass: String? = null)
    suspend fun appendRow(role: String, content: String, toolName: String? = null): Long
    suspend fun progress(note: String)
    suspend fun saveCounters()
    suspend fun saveDigest(json: String)
}

/** JSON schemas of the nine subagent tools. */
internal object DelegateSubagentToolDefs {
    const val CREATE_PROJECT = "harness_create_project"
    const val NEW_THREAD = "harness_new_thread"
    const val SEND = "harness_send"
    const val WAIT = "harness_wait"
    const val READ = "harness_read"
    const val RESPOND = "harness_respond"
    const val CANCEL_TURN = "harness_cancel_turn"
    const val MESSAGE_MAIN = "message_main"
    const val FINISH = "finish_task"

    private fun prop(type: String, description: String, enum: List<String>? = null): JSONObject =
        JSONObject().put("type", type).put("description", description).also { o ->
            enum?.let { o.put("enum", JSONArray(it)) }
        }

    private fun tool(name: String, description: String, props: Map<String, JSONObject>, required: List<String>): AgentTool {
        val properties = JSONObject().also { o -> props.forEach { (k, v) -> o.put(k, v) } }
        val schema = JSONObject().put("type", "object").put("properties", properties)
            .put("required", JSONArray(required)).put("additionalProperties", false).toString()
        return AgentTool(
            name = name, description = description,
            parameters = props.mapValues { it.value.optString("description") },
            requiredParams = required, schemaJson = schema
        )
    }

    fun all(): List<AgentTool> = listOf(
        tool(CREATE_PROJECT,
            "Create the harness project (workspace) and its first thread, select the model, apply the permission policy and copy the task's input files. Call once, first.",
            mapOf(
                "title" to prop("string", "Project title."),
                "continue_previous" to prop("boolean", "Reuse the chat's previous project instead of creating a new one.")
            ), listOf("title")),
        tool(NEW_THREAD, "Open another thread in the same project (an unrelated subtask).",
            mapOf("title" to prop("string", "Thread title.")), emptyList()),
        tool(SEND, "Send a prompt to the harness agent. Make it complete and self-contained.",
            mapOf(
                "prompt" to prop("string", "The prompt, at most 4000 characters."),
                "thread" to prop("integer", "Thread number (1-based). Default: the current thread."),
                "mode" to prop("string", "queue (default) or steer (interrupt the running turn).", listOf("queue", "steer")),
                "attach_inputs" to prop("boolean", "Append the list of input file paths to the prompt.")
            ), listOf("prompt")),
        tool(WAIT,
            "Wait until the harness finishes its turn, needs an approval/question/plan answer, you receive a main-agent message, an error occurs or the time runs out. Returns a compact digest.",
            mapOf("max_seconds" to prop("integer", "30 to 1800, default 600.")), emptyList()),
        tool(READ, "Read a small piece of the project. Output is capped at 6000 characters.",
            mapOf(
                "what" to prop("string", "What to read.", listOf("transcript", "file", "list", "changes", "deliverables")),
                "path" to prop("string", "Project-relative path (for file and list)."),
                "page" to prop("integer", "Page number, default 0."),
                "max_chars" to prop("integer", "At most 6000.")
            ), listOf("what")),
        tool(RESPOND, "Answer a pending approval, question or plan shown in the digest.",
            mapOf(
                "id" to prop("string", "The pending item id from the digest."),
                "decision" to prop("string", "How to answer.", listOf("approve", "deny", "answer", "plan_approve", "plan_reject")),
                "text" to prop("string", "Free-text answer (decision=answer)."),
                "options" to prop("string", "Selected option labels, comma separated or a JSON array (decision=answer).")
            ), listOf("id", "decision")),
        tool(CANCEL_TURN, "Cancel the harness agent's running turn.",
            mapOf("reason" to prop("string", "Why.")), emptyList()),
        tool(MESSAGE_MAIN, "Send a message to the main assistant.",
            mapOf(
                "kind" to prop("string", "progress (one-line status), reply (answer to a [Main agent] message) or question (needs a decision).",
                    listOf("progress", "reply", "question")),
                "text" to prop("string", "At most 2000 characters.")
            ), listOf("kind", "text")),
        tool(FINISH, "Finish the task and hand the result to the main assistant. Always end with this.",
            mapOf(
                "status" to prop("string", "Outcome.", listOf("success", "partial", "failed")),
                "summary" to prop("string", "What was done and the result, at most 3000 characters."),
                "files" to prop("string", "Up to 20 project-relative result file paths, comma separated or a JSON array. Default: the harness's presented deliverables.")
            ), listOf("status", "summary"))
    )
}

/**
 * Executes the nine subagent tools against one [DelegateHarnessDriver]. Every result is plain
 * English text of at most 6,000 characters; raw harness output is never returned.
 */
internal class DelegateSubagentToolbox(
    private val context: Context,
    private val task: NativeChatDelegateTaskEntity,
    private val chat: LlamaChatEntity,
    private val config: NativeChatDelegationConfig,
    private val driver: DelegateHarnessDriver,
    private val chatServer: LlamaServerEntity?,
    private val server: ResolvedDelegationServer,
    private val inputs: List<DelegationResolvedInput>,
    private val guards: DelegationGuards,
    private val inbox: DelegationInbox,
    private val reporter: SubagentReporter,
    private val chatMaxOutput: Int?
) {
    private val db = AppDatabase.getDatabase(context.applicationContext)
    private val taskDao get() = db.nativeChatDelegateTaskDao()
    private val bridge = DelegationFileBridge(context)
    private val taskId = task.id

    private class ThreadInfo(val sessionId: String, val title: String)

    private val threads = ArrayList<ThreadInfo>()
    private var currentSession: String? = task.harnessSessionId
    private var workspaceId: String? = task.workspaceId
    private var project: DelegateHarnessProject? = null
    private var conversationId: Long? = task.harnessConversationId
    private var binding: HarnessModelBinding? = null
    private var inputPaths: List<String> = emptyList()

    init {
        runCatching {
            val array = JSONArray(task.harnessThreadsJson)
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                threads += ThreadInfo(o.getString("id"), o.optString("title"))
            }
        }
        if (currentSession != null && threads.none { it.sessionId == currentSession }) {
            threads += ThreadInfo(currentSession!!, task.title)
        }
    }

    /** Re-attach the watchers of existing threads after a restart or Continue. */
    fun reattach() {
        threads.forEach { thread ->
            driver.attachThread(thread.sessionId)
            com.example.llamadroid.harness.HarnessDelegatedSessions.register(
                task.runtimeId, thread.sessionId, config.approvalPolicy != DelegationApprovalPolicy.ASK_ME)
        }
        if (threads.isNotEmpty()) publishThreads()
    }

    suspend fun execute(call: ToolCall): SubagentToolOutcome {
        val started = System.currentTimeMillis()
        reporter.phase("tool_call")
        val outcome = try {
            runWithRuntimeRetry(call)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DelegationLoopStop) {
            throw e
        } catch (e: DelegationHarnessException) {
            SubagentToolOutcome(bounded(e.toToolError()), isError = true)
        } catch (e: Exception) {
            SubagentToolOutcome("tool_error: unexpected failure (${e.javaClass.simpleName}).", isError = true)
        }
        reporter.event("tool_call", toolName = call.name, status = if (outcome.isError) "error" else "ok",
            durationMs = System.currentTimeMillis() - started)
        return outcome
    }

    private suspend fun runWithRuntimeRetry(call: ToolCall): SubagentToolOutcome {
        try {
            return dispatch(call)
        } catch (e: DelegationHarnessException) {
            if (e.code != "RUNTIME_NOT_RUNNING") throw e
        }
        try {
            driver.ensureRuntime()
            reattach()
        } catch (e: DelegationHarnessException) {
            throw DelegationLoopStop(NativeChatDelegateTaskState.INTERRUPTED, "runtime_stopped", e.message.orEmpty())
        }
        return dispatch(call)
    }

    private suspend fun dispatch(call: ToolCall): SubagentToolOutcome {
        val args = call.arguments
        return when (call.name) {
            DelegateSubagentToolDefs.CREATE_PROJECT -> createProject(args)
            DelegateSubagentToolDefs.NEW_THREAD -> newThreadTool(args)
            DelegateSubagentToolDefs.SEND -> send(args)
            DelegateSubagentToolDefs.WAIT -> waitTool(args)
            DelegateSubagentToolDefs.READ -> read(args)
            DelegateSubagentToolDefs.RESPOND -> respond(args)
            DelegateSubagentToolDefs.CANCEL_TURN -> cancelTurn()
            DelegateSubagentToolDefs.MESSAGE_MAIN -> messageMain(args)
            DelegateSubagentToolDefs.FINISH -> finish(args)
            else -> err("Unknown tool '${call.name}'. Available: ${DelegateSubagentToolDefs.all().joinToString { it.name }}.")
        }
    }

    // ---- helpers ---------------------------------------------------------------------------

    private suspend fun currentModelJson(): String? = taskDao.getById(taskId)?.harnessModelJson

    private fun bounded(text: String) = DelegateSubagentMessages.bound(text, DelegateSubagentMessages.MAX_TOOL_ROW_CHARS)
    private fun ok(text: String) = SubagentToolOutcome(bounded(text))
    private fun err(text: String) = SubagentToolOutcome("tool_error: $text", isError = true)
    private fun arg(args: Map<String, String>, vararg names: String) =
        DelegationToolArgs.arg(args, *names)?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseList(raw: String?): List<String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        val items = if (text.startsWith("[")) runCatching {
            val a = JSONArray(text); (0 until a.length()).map { a.opt(it)?.toString().orEmpty() }
        }.getOrNull() else null
        return (items ?: text.split(',', '\n')).map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
    }

    private fun policySuppresses() = config.approvalPolicy != DelegationApprovalPolicy.ASK_ME

    private fun sessionFor(threadArg: String?): String {
        val idx = DelegationToolArgs.coerceInt(threadArg)
        if (idx != null) {
            return threads.getOrNull(idx - 1)?.sessionId
                ?: throw DelegationHarnessException("THREAD_NOT_FOUND", "There is no thread $idx. Threads: 1..${threads.size}.")
        }
        return currentSession ?: throw DelegationHarnessException("NO_PROJECT", "No project yet. Call harness_create_project first.")
    }

    private suspend fun mutateTask(block: (NativeChatDelegateTaskEntity) -> NativeChatDelegateTaskEntity) {
        val latest = taskDao.getById(taskId) ?: return
        taskDao.update(block(latest).copy(updatedAt = System.currentTimeMillis()))
    }

    private fun threadsJson(): String = JSONArray().also { a ->
        threads.forEach { a.put(JSONObject().put("id", it.sessionId).put("title", it.title)) }
    }.toString()

    private suspend fun bindModel(): HarnessModelBinding {
        binding?.let { return it }
        val client = driver.ensureRuntime()
        return DelegateHarnessModelBinder.bind(
            context, HarnessAppRuntime.forRuntime(context, task.runtimeId), client, config.harnessServer, chatServer,
            fallbackContextTokens = chat.contextSize,
            fallbackMaxOutputTokens = chatMaxOutput ?: server.maxOutputTokens
        ).also { binding = it }
    }

    private suspend fun openThread(project: DelegateHarnessProject, title: String): Pair<DelegateHarnessThread, String?> {
        val thread = driver.createThread(project, title, suppressApprovals = policySuppresses())
        driver.selectModel(thread.sessionId, bindModel())
        val approval = driver.applyApprovalPolicy(thread.sessionId, config.approvalPolicy)
        threads += ThreadInfo(thread.sessionId, thread.title)
        currentSession = thread.sessionId
        conversationId = thread.conversationId
        return thread to approval.note
    }

    private suspend fun ensureProject(): DelegateHarnessProject {
        project?.let { return it }
        val ws = workspaceId ?: throw DelegationHarnessException("NO_PROJECT", "No project yet. Call harness_create_project first.")
        return driver.openProject(ws).also { project = it }
    }

    // ---- tools -----------------------------------------------------------------------------

    private suspend fun createProject(args: Map<String, String>): SubagentToolOutcome {
        if (workspaceId != null) return err("A project already exists for this task. Use harness_send, or harness_new_thread for a new thread.")
        val title = DelegationToolArgs.boundedText(arg(args, "title"), 80) ?: task.title
        val wantContinue = DelegationToolArgs.coerceBoolean(arg(args, "continue_previous", "continuePrevious"), false)
        var note: String? = null
        var created: DelegateHarnessProject? = null
        if (wantContinue) {
            val previous = taskDao.getLatestWithWorkspace(chat.id)?.takeIf { it.id != taskId }?.workspaceId
            if (previous != null) {
                created = try { driver.openProject(previous) } catch (e: DelegationHarnessException) {
                    note = "The previous project could not be opened (${e.code}); a new project was created."; null
                }
            } else note = "There is no previous project in this chat; a new project was created."
        }
        val proj = created ?: driver.createProject(title)
        project = proj
        workspaceId = proj.workspaceId
        reporter.event("project_created")
        val (thread, approvalNote) = openThread(proj, title)
        val copied = if (inputs.isEmpty()) emptyList() else driver.copyInputs(thread.sessionId, inputs.map { it.displayName to it.file })
        inputPaths = copied
        val b = bindModel()
        mutateTask { t ->
            t.copy(
                workspaceId = proj.workspaceId, harnessSessionId = thread.sessionId, harnessThreadsJson = threadsJson(),
                harnessConversationId = thread.conversationId,
                harnessModelJson = JSONObject().put("provider", b.provider).put("model", b.model)
                    .put("contextTokens", b.contextTokens ?: JSONObject.NULL).put("maxOutputTokens", b.maxOutputTokens ?: JSONObject.NULL)
                    .put("route", b.routeKind.name).toString()
            )
        }
        reporter.live { it.copy(harnessBusy = false) }
        publishThreads()
        return ok(buildString {
            append("Project \"").append(proj.title).append("\" created with thread 1.\n")
            append("Harness model: ").append(b.model)
            b.contextTokens?.let { append(" (context ").append(it).append(" tokens)") }
            append('\n')
            approvalNote?.let { append("Note: ").append(it).append('\n') }
            note?.let { append("Note: ").append(it).append('\n') }
            if (copied.isNotEmpty()) append("Input files: ").append(copied.joinToString(", ")).append('\n')
            append("Next: harness_send a complete prompt.")
        })
    }

    private suspend fun newThreadTool(args: Map<String, String>): SubagentToolOutcome {
        val proj = ensureProject()
        val title = DelegationToolArgs.boundedText(arg(args, "title"), 80) ?: "Thread ${threads.size + 1}"
        val (thread, approvalNote) = openThread(proj, title)
        mutateTask { it.copy(harnessSessionId = thread.sessionId, harnessThreadsJson = threadsJson(), harnessConversationId = thread.conversationId) }
        publishThreads()
        return ok("Thread ${threads.size} \"${thread.title}\" created and selected." + (approvalNote?.let { "\nNote: $it" } ?: ""))
    }

    private suspend fun send(args: Map<String, String>): SubagentToolOutcome {
        val prompt = DelegationToolArgs.boundedText(arg(args, "prompt", "text"), 4000) ?: return err("prompt is required.")
        val mode = DelegationToolArgs.parseEnum(arg(args, "mode"), setOf("queue", "steer"), "queue")
        val session = sessionFor(arg(args, "thread"))
        if (!guards.canSendPrompt()) {
            return err("The limit of ${DelegationGuards.MAX_HARNESS_PROMPTS} harness prompts was reached. Call finish_task now with what exists.")
        }
        val withInputs = if (DelegationToolArgs.coerceBoolean(arg(args, "attach_inputs", "attachInputs"), false) && inputPaths.isNotEmpty()) {
            prompt + "\n\nInput files: " + inputPaths.joinToString(", ")
        } else prompt
        driver.send(session, withInputs, mode)
        guards.onPromptSent()
        reporter.saveCounters()
        reporter.live { it.copy(harnessBusy = true) }
        publishThreads()
        return ok("Prompt sent to thread ${threads.indexOfFirst { it.sessionId == session } + 1} (mode $mode). Call harness_wait next.")
    }

    private suspend fun waitTool(args: Map<String, String>): SubagentToolOutcome {
        val session = sessionFor(arg(args, "thread"))
        val seconds = (DelegationToolArgs.coerceInt(arg(args, "max_seconds", "maxSeconds")) ?: 600).coerceIn(30, 1800)
        reporter.phase("harness_wait", NativeChatDelegateTaskState.WAITING_HARNESS)
        reporter.live { it.copy(harnessBusy = true) }
        val ask = config.approvalPolicy == DelegationApprovalPolicy.ASK_ME
        val started = System.currentTimeMillis()
        val result = try {
            withHarnessProgress(session) {
                if (ask) driver.waitFor(session, seconds * 1000L, interrupt = { inbox.hasPending() }, stopOnPending = { it.kind != "approval" })
                else driver.waitFor(session, seconds * 1000L, interrupt = { inbox.hasPending() })
            }
        } finally {
            reporter.phase("tool_call", NativeChatDelegateTaskState.RUNNING)
        }
        val pending = result.digest.pending
        val userOwnedOnly = ask && pending.isNotEmpty() && pending.all { it.kind == "approval" }
        reporter.live { it.copy(harnessBusy = result.digest.running, pendingAttention = pending.size) }
        publishThreads()
        reporter.saveDigest(result.digest.toJson())
        reporter.event("wait_result", status = result.reason.name.lowercase(), durationMs = System.currentTimeMillis() - started)
        val failureCode = result.digest.providerFailureCode()
        val providerFailures = guards.onProviderTurn(
            failureCode, result.digest.turnCount, result.reason == DelegateWaitReason.TURN_ENDED)
        if (failureCode != null) {
            reporter.event("provider_failure", status = failureCode, count = providerFailures, errorClass = failureCode)
        }
        // The real cause (server died, OOM, rejected body) from the metadata-only failure log.
        val failureCause = if (failureCode == null) null else runCatching {
            com.example.llamadroid.harness.HarnessModelFailureLog.get(context, task.runtimeId).latest()
                ?.let(com.example.llamadroid.harness.HarnessModelFailureExplain::english)
        }.getOrNull()
        if (failureCode != null && providerFailures >= DelegationGuards.MAX_PROVIDER_FAILURES) {
            val reason = DelegationProviderFailure.needsDirectionReason(
                failureCode, DelegationProviderFailure.contextTokensOf(currentModelJson()) ?: binding?.contextTokens?.toInt(),
                failureCause)
            return SubagentToolOutcome(bounded("wait_result: error\n$reason"), isError = true,
                guard = GuardDecision.NeedsDirection(reason, DelegationProviderFailure.ERROR_CODE))
        }
        val guard = guards.onWaitResult(result.digest.hash(), userOwnedOnly)
        val text = buildString {
            append("wait_result: ").append(result.reason.name.lowercase()).append('\n').append(result.text)
            if (failureCode != null) append(DelegationProviderFailure.firstFailureHint(failureCode, failureCause))
            else if (result.followLost) append(DelegationProviderFailure.FOLLOW_LOST_HINT)
            if (result.reason == DelegateWaitReason.INTERRUPTED) append("\n[Main agent] message waiting; it is delivered next.")
            if (userOwnedOnly) append("\nThe pending approvals are answered by the user, not by you.")
        }
        return SubagentToolOutcome(bounded(text), guard = guard.takeIf { it !is GuardDecision.Continue })
    }

    /** Publishes every thread's title, running state, context use and [progress] into the live status. */
    private fun publishThreads(progress: Map<String, Pair<Int, Int>> = emptyMap(), current: Pair<Int, Int> = 0 to 0, session: String? = null) {
        val digests = threads.associate { it.sessionId to driver.digest(it.sessionId) }
        val list = DelegationThreadLives.assemble(threads.map { it.sessionId to it.title }, digests, progress)
        reporter.live {
            val base = it.copy(threads = list)
            if (session == null) base else base.copy(harnessPromptProcessed = current.first, harnessPromptTotal = current.second)
        }
    }

    /**
     * Publishes the prompt-processing progress of every thread (and the waited-on [session] into the
     * legacy harness fields) while [block] runs, throttled to one update per 250 ms.
     */
    private suspend fun <T> withHarnessProgress(session: String, block: suspend () -> T): T = coroutineScope {
        val collector = launch(Dispatchers.Default) {
            val activity = runCatching { HarnessAppRuntime.forRuntime(context, task.runtimeId).models.generationActivity }
                .getOrNull() ?: return@launch
            var last = 0L
            var lastProgress: Map<String, Pair<Int, Int>> = emptyMap()
            activity.collect { map ->
                val progress = LinkedHashMap<String, Pair<Int, Int>>()
                threads.forEach { thread ->
                    val entry = map.values.filter { it.sessionId == thread.sessionId }.maxByOrNull { it.updatedAtMs }
                        ?: map.values.filter { it.sessionId == null }.singleOrNull().takeIf { thread.sessionId == session }
                    val total = entry?.total ?: 0
                    if (entry != null && total > 0) progress[thread.sessionId] = (entry.processed ?: 0) to total
                }
                val now = System.currentTimeMillis()
                val finished = progress.values.any { it.first >= it.second }
                if (progress.keys != lastProgress.keys || finished || now - last >= 250L) {
                    last = now
                    lastProgress = progress
                    publishThreads(progress, progress[session] ?: (0 to 0), session)
                }
            }
        }
        try {
            block()
        } finally {
            collector.cancel()
            publishThreads(emptyMap(), 0 to 0, session)
        }
    }

    private suspend fun read(args: Map<String, String>): SubagentToolOutcome {
        val what = DelegationToolArgs.parseEnum(arg(args, "what"), setOf("transcript", "file", "list", "changes", "deliverables"), "")
        if (what.isEmpty()) return err("what must be one of transcript, file, list, changes, deliverables.")
        val session = sessionFor(arg(args, "thread"))
        val page = (DelegationToolArgs.coerceInt(arg(args, "page")) ?: 0).coerceAtLeast(0)
        val max = DelegateHarnessSupport.clampChars(DelegationToolArgs.coerceInt(arg(args, "max_chars", "maxChars")))
        val path = arg(args, "path")
        val text = when (what) {
            "transcript" -> driver.readTranscript(session, page, max)
            "file" -> driver.readFileText(session, path ?: return err("path is required for what=file."), page, max)
            "list" -> driver.listFiles(session, path, max)
            "changes" -> driver.readChanges(session, max)
            else -> driver.readDeliverables(session, max)
        }
        return ok(text)
    }

    private suspend fun respond(args: Map<String, String>): SubagentToolOutcome {
        val id = arg(args, "id") ?: return err("id is required.")
        val decision = when (arg(args, "decision")?.lowercase()) {
            "approve" -> DelegateDecision.APPROVE
            "deny" -> DelegateDecision.DENY
            "answer" -> DelegateDecision.ANSWER
            "plan_approve" -> DelegateDecision.PLAN_APPROVE
            "plan_reject" -> DelegateDecision.PLAN_REJECT
            else -> return err("decision must be approve, deny, answer, plan_approve or plan_reject.")
        }
        val text = arg(args, "text")
        val options = parseList(arg(args, "options")).takeIf { it.isNotEmpty() }
        val order = (listOfNotNull(currentSession) + threads.map { it.sessionId }).distinct()
        if (order.isEmpty()) return err("No project yet. Call harness_create_project first.")
        var last: DelegationHarnessException? = null
        for (session in order) {
            try {
                return ok(driver.respond(session, id, decision, text, options))
            } catch (e: DelegationHarnessException) {
                if (e.code != "INTERACTION_NO_LONGER_PENDING") throw e
                last = e
            }
        }
        return SubagentToolOutcome(bounded(last?.toToolError() ?: "tool_error: item not found"), isError = true)
    }

    private suspend fun cancelTurn(): SubagentToolOutcome {
        driver.cancelTurn(sessionFor(null))
        return ok("Cancel requested for the running turn.")
    }

    private suspend fun messageMain(args: Map<String, String>): SubagentToolOutcome {
        val kind = DelegationToolArgs.parseEnum(arg(args, "kind"), setOf("progress", "reply", "question"), "")
        if (kind.isEmpty()) return err("kind must be progress, reply or question.")
        val text = DelegationToolArgs.boundedText(arg(args, "text", "message"), 2000) ?: return err("text is required.")
        reporter.appendRow(NativeChatDelegateMessageRole.SUB_TO_MAIN, text, toolName = kind)
        return when (kind) {
            "progress" -> { reporter.progress(text.take(300)); ok("Progress noted.") }
            "reply" -> { inbox.emitReply("reply", text); ok("Reply delivered to the main agent.") }
            else -> {
                inbox.emitReply("question", text)
                reporter.phase("waiting_main", NativeChatDelegateTaskState.WAITING_MAIN)
                DelegationWakeDispatcher.enqueue(context, chat.id, taskId, NativeChatDelegationWakeKind.SUBAGENT_QUESTION,
                    DelegationResultNotice.question(text))
                SubagentToolOutcome(ok("Question sent. Waiting for the main agent's answer.").text, awaitMain = true)
            }
        }
    }

    private suspend fun finish(args: Map<String, String>): SubagentToolOutcome {
        val status = DelegationToolArgs.parseEnum(arg(args, "status"), setOf("success", "partial", "failed"), "")
        if (status.isEmpty()) return err("status must be success, partial or failed.")
        val summary = DelegationToolArgs.boundedText(arg(args, "summary"), 3000) ?: return err("summary is required.")
        reporter.phase("finishing")
        var paths = parseList(arg(args, "files")).take(DelegationFileRefs.MAX_RESULT_FILES)
        if (paths.isEmpty()) {
            paths = currentSession?.let { s -> driver.digest(s).deliverables.map { it.path } }.orEmpty()
                .take(DelegationFileRefs.MAX_RESULT_FILES)
        }
        val valid = paths.mapNotNull { runCatching { DelegateHarnessSupport.requireProjectRelative(it) }.getOrNull() }
            .filter { it != "." }
        val export = currentSession?.takeIf { valid.isNotEmpty() }?.let {
            bridge.exportResults(driver, it, chat.id, taskId, valid)
        } ?: DelegationExportResult(emptyList(), emptyList())
        val finalState = if (status == "failed") NativeChatDelegateTaskState.FAILED else NativeChatDelegateTaskState.COMPLETED
        val now = System.currentTimeMillis()
        mutateTask { t ->
            t.copy(
                state = finalState, resultStatus = status, resultSummary = summary,
                resultFilesJson = bridge.resultFilesJson(export.files), finishedAt = now,
                lastErrorCode = null, nextCheckAt = null, checkNote = null
            )
        }
        reporter.appendRow(NativeChatDelegateMessageRole.SUB_TO_MAIN, summary, toolName = "result")
        reporter.event("finished", status = status, count = export.files.size)
        inbox.emitReply("result", summary)
        DelegationAlarmScheduler.cancel(context, taskId)
        val refs = export.files.map { DelegationResultFileRef(it.name, it.attachmentId) }
        DelegationWakeDispatcher.enqueue(context, chat.id, taskId, NativeChatDelegationWakeKind.TASK_FINISHED,
            DelegationResultNotice.finished(task.title, status, summary, refs))
        return SubagentToolOutcome(
            bounded("Task finished ($status)." + if (export.notes.isNotEmpty()) " Skipped files: " + export.notes.joinToString("; ") else ""),
            finished = true
        )
    }
}
