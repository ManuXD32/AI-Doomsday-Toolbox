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

/** JSON schemas of the seven subagent tools (the app opens the project and thread, not the subagent). */
internal object DelegateSubagentToolDefs {
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
        tool(SEND, "Send a prompt to the harness agent. Make it complete and self-contained.",
            mapOf(
                "prompt" to prop("string", "The prompt, at most 4000 characters."),
                "mode" to prop("string", "queue (default) or steer (interrupt the running turn).", listOf("queue", "steer")),
                "attach_inputs" to prop("boolean", "List all input file paths in the prompt; images are attached as images when the harness model can see them. Files that arrived since your last prompt are always listed.")
            ), listOf("prompt")),
        tool(WAIT,
            "Wait until the harness finishes its turn, needs an approval/question/plan answer, you receive a main-agent message, an error occurs or the time runs out. Returns a compact digest.",
            mapOf("max_seconds" to prop("integer", "30 to 1800, default 600.")), emptyList()),
        tool(READ, "Read a small piece of the thread. Output is capped at 6000 characters.",
            mapOf(
                "what" to prop("string", "What to read.", listOf("transcript", "list", "deliverables")),
                "path" to prop("string", "Project-relative folder (for list)."),
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
 * Executes the seven subagent tools against one [DelegateHarnessDriver]. The app already bound the project and
 * thread ([bound], see [DelegateProjectBinder]); there is a single current thread. Every result is plain
 * English text of at most 6,000 characters; raw harness output is never returned.
 */
internal class DelegateSubagentToolbox(
    private val context: Context,
    private val task: NativeChatDelegateTaskEntity,
    private val chat: LlamaChatEntity,
    private val config: NativeChatDelegationConfig,
    private val driver: DelegateHarnessDriver,
    private val chatServer: LlamaServerEntity?,
    private val inputs: List<DelegationResolvedInput>,
    private val bound: BindResult,
    private val guards: DelegationGuards,
    private val inbox: DelegationInbox,
    private val reporter: SubagentReporter
) {
    private val db = AppDatabase.getDatabase(context.applicationContext)
    private val taskDao get() = db.nativeChatDelegateTaskDao()
    private val bridge = DelegationFileBridge(context)
    private val taskId = task.id

    private class ThreadInfo(val sessionId: String, val title: String)

    private val threads = ArrayList<ThreadInfo>()
    private var currentSession: String? = bound.thread.sessionId
    private val binding: HarnessModelBinding = bound.binding

    /** A chat file already copied into the project's `inputs/` folder. */
    private class ToolInput(val ref: String, val name: String, val path: String, val file: java.io.File)

    private val allInputs = ArrayList<ToolInput>()
    private val sentImagePaths = HashSet<String>()

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

    /** Re-attach the bound thread's watcher (after a runtime restart); the binder already attached it at bind time. */
    fun reattach() {
        val session = currentSession ?: return
        driver.attachThread(session)
        com.example.llamadroid.harness.HarnessDelegatedSessions.register(
            task.runtimeId, session, config.approvalPolicy != DelegationApprovalPolicy.ASK_ME)
        publishThreads()
    }

    /**
     * Right after bind: ensures `inputs/`, copies the first-start input files and the task's pending refs of a
     * NEW task (a resumed task leaves pending refs to the next [SEND], which lists their paths). Returns a
     * one-line note about skipped files, or null.
     */
    suspend fun afterBind(): String? {
        val session = checkNotNull(currentSession)
        var note: String? = null
        if (inputs.isNotEmpty()) {
            val paths = driver.copyInputs(session, inputs.map { it.displayName to it.file })
            inputs.zip(paths).forEach { (input, path) -> allInputs += ToolInput(input.ref, input.displayName, path, input.file) }
            // Copied once here: clear them from the pending refs so copyPendingInputs does not duplicate them.
            val done = inputs.map { it.ref }
            mutateTask { t ->
                t.copy(optionsJson = DelegationPendingRefs.cleared(DelegationTaskOptions.decode(t.optionsJson), done).encode())
            }
        }
        driver.ensureInputsDir(session)
        if (inputs.isNotEmpty()) {
            val pending = copyPendingInputs(session)
            if (pending.unknown.isNotEmpty()) note = "Skipped input files (not found): " + pending.unknown.joinToString(", ") + "."
        }
        reporter.live { it.copy(harnessBusy = false) }
        publishThreads()
        return note
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

    private fun session(): String =
        currentSession ?: throw DelegationHarnessException("NO_PROJECT", "The app has not opened a thread for this task.")

    private suspend fun mutateTask(block: (NativeChatDelegateTaskEntity) -> NativeChatDelegateTaskEntity) {
        val latest = taskDao.getById(taskId) ?: return
        taskDao.update(block(latest).copy(updatedAt = System.currentTimeMillis()))
    }

    // ---- tools -----------------------------------------------------------------------------

    private suspend fun options() = DelegationTaskOptions.decode(taskDao.getById(taskId)?.optionsJson)

    private class PendingCopyResult(val items: List<ToolInput>, val unknown: List<String>)

    /** Copies the task's pending attachment refs into `inputs/` and clears them from the options. */
    private suspend fun copyPendingInputs(sessionId: String): PendingCopyResult {
        val copy = bridge.copyPending(driver, sessionId, chat.id, options())
        if (copy.handledRefs.isEmpty()) return PendingCopyResult(emptyList(), emptyList())
        mutateTask { t ->
            t.copy(optionsJson = DelegationPendingRefs.cleared(DelegationTaskOptions.decode(t.optionsJson), copy.handledRefs).encode())
        }
        val items = copy.copied.map { ToolInput(it.ref, it.displayName, it.path, it.file) }
        allInputs += items
        return PendingCopyResult(items, copy.unknown)
    }

    /** True when the harness agent's server row is flagged as vision capable. */
    private suspend fun harnessAcceptsImages(): Boolean {
        val server = when (val choice = config.harnessServer) {
            DelegationServerChoice.SameAsChat -> chatServer
            is DelegationServerChoice.Server -> db.llamaServerDao().getServerById(choice.rowId)
        }
        return server?.supportsVision == true
    }

    private suspend fun send(args: Map<String, String>): SubagentToolOutcome {
        val prompt = DelegationToolArgs.boundedText(arg(args, "prompt", "text"), 4000) ?: return err("prompt is required.")
        val mode = DelegationToolArgs.parseEnum(arg(args, "mode"), setOf("queue", "steer"), "queue")
        val session = session()
        if (!guards.canSendPrompt()) {
            return err("The limit of ${DelegationGuards.MAX_HARNESS_PROMPTS} harness prompts was reached. Call finish_task now with what exists.")
        }
        val fresh = copyPendingInputs(session)
        val attach = DelegationToolArgs.coerceBoolean(arg(args, "attach_inputs", "attachInputs"), false)
        val listed = if (attach) allInputs.toList() else fresh.items
        val images = listed.filter { DelegateHarnessInputs.isImageName(it.name) && it.path !in sentImagePaths }
        val receipts = ArrayList<String>()
        val described = ArrayList<DelegateImageNote>()
        if (images.isNotEmpty()) {
            val vision = harnessAcceptsImages()
            images.forEachIndexed { index, image ->
                var uploaded = false
                if (vision && index < DelegateHarnessInputs.MAX_IMAGES_PER_SEND) {
                    try {
                        receipts += driver.uploadAttachment(session, image.name, image.file)
                        sentImagePaths += image.path
                        uploaded = true
                    } catch (e: DelegationHarnessException) {
                        if (e.code == "RUNTIME_NOT_RUNNING") throw e
                    }
                }
                if (!uploaded && !vision) {
                    val description = DelegateHarnessInputs.messageIdOfImageRef(image.ref)
                        ?.let { db.llamaMessageImageDescriptionDao().get(it)?.description }
                    described += DelegateImageNote(image.path, description)
                }
            }
        }
        val withInputs = prompt +
            DelegateHarnessInputs.inputsLine(if (attach) "Input files" else "New input files", listed.map { it.path }) +
            DelegateHarnessInputs.imageNotes(described)
        driver.send(session, withInputs, mode, receipts)
        guards.onPromptSent()
        reporter.saveCounters()
        reporter.live { it.copy(harnessBusy = true) }
        publishThreads()
        return ok(buildString {
            append("Prompt sent to thread ${threads.indexOfFirst { it.sessionId == session } + 1} (mode $mode).")
            if (fresh.items.isNotEmpty()) append(" New input files copied: ").append(fresh.items.joinToString(", ") { it.path }).append('.')
            if (receipts.isNotEmpty()) append(" ").append(receipts.size).append(" image(s) attached.")
            if (fresh.unknown.isNotEmpty()) append(" Skipped input files (not found): ").append(fresh.unknown.joinToString(", ")).append('.')
            append(" Call harness_wait next.")
        })
    }

    private suspend fun waitTool(args: Map<String, String>): SubagentToolOutcome {
        val session = session()
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
                failureCode, DelegationProviderFailure.contextTokensOf(currentModelJson()) ?: binding.contextTokens?.toInt(),
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
        val what = DelegationToolArgs.parseEnum(arg(args, "what"), setOf("transcript", "list", "deliverables"), "")
        if (what.isEmpty()) return err("what must be one of transcript, list, deliverables (reading a single file or the change list is not available; ask the harness agent in a prompt).")
        val session = session()
        val page = (DelegationToolArgs.coerceInt(arg(args, "page")) ?: 0).coerceAtLeast(0)
        val max = DelegateHarnessSupport.clampChars(DelegationToolArgs.coerceInt(arg(args, "max_chars", "maxChars")))
        val path = arg(args, "path")
        val text = when (what) {
            "transcript" -> driver.readTranscript(session, page, max)
            "list" -> driver.listFiles(session, path, max)
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
        return try {
            ok(driver.respond(session(), id, decision, text, options))
        } catch (e: DelegationHarnessException) {
            if (e.code != "INTERACTION_NO_LONGER_PENDING") throw e
            SubagentToolOutcome(bounded(e.toToolError()), isError = true)
        }
    }

    private suspend fun cancelTurn(): SubagentToolOutcome {
        driver.cancelTurn(session())
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

/** Pure thread-reuse rule used by [DelegateProjectBinder] (unit-tested without a database). */
object DelegateThreadReuse {
    /** The thread this chat used last in [workspaceId]: the most recent task row with a current thread. */
    fun lastThread(tasks: List<NativeChatDelegateTaskEntity>, workspaceId: String): String? =
        tasks.filter { it.workspaceId == workspaceId }
            .sortedWith(compareByDescending<NativeChatDelegateTaskEntity> { it.createdAt }.thenByDescending { it.id })
            .firstNotNullOfOrNull { t ->
                t.harnessSessionId?.takeIf { it.isNotBlank() } ?: runCatching {
                    val a = JSONArray(t.harnessThreadsJson)
                    if (a.length() > 0) a.getJSONObject(a.length() - 1).getString("id") else null
                }.getOrNull()
            }
}
