package com.example.llamadroid.service.delegation

import android.content.Context
import com.example.llamadroid.harness.HarnessAppRuntime
import com.example.llamadroid.harness.HarnessDelegatedSessions
import com.example.llamadroid.harness.HarnessSessionScope
import com.example.llamadroid.harness.client.HarnessClient
import com.example.llamadroid.harness.client.HarnessConnectionState
import com.example.llamadroid.harness.client.HarnessRpcResult
import com.example.llamadroid.harness.harnessLifecycleErrorCode
import com.example.llamadroid.ui.agent.harness.HarnessAttentionEntry
import com.example.llamadroid.ui.agent.harness.NativeHarnessProjectReviewCoordinates
import com.example.llamadroid.ui.agent.harness.NativeHarnessProjectReviewWire
import com.example.llamadroid.ui.agent.harness.executeNativeHarnessCommand
import com.example.llamadroid.ui.agent.harness.harnessParseRecord
import com.example.llamadroid.ui.agent.harness.long
import com.example.llamadroid.ui.agent.harness.objectArray
import com.example.llamadroid.ui.agent.harness.ordinaryHarnessSessionAddress
import com.example.llamadroid.ui.agent.harness.parseNativeHarnessChangesSummary
import com.example.llamadroid.ui.agent.harness.promptNativeHarnessSession
import com.example.llamadroid.ui.agent.harness.string
import com.example.llamadroid.ui.agent.harness.boolean
import com.example.llamadroid.ui.agent.harness.jsonObjectOrNull
import com.example.llamadroid.harness.client.HarnessCallPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A harness project (workspace) the subagent works in. */
data class DelegateHarnessProject(
    val workspaceId: String,
    val title: String,
    val guestPath: String,
    /** Harness-side workspace group id once the project has been prepared. */
    val harnessWorkspaceId: String?,
)

/** One harness chat thread. [conversationId] is the app agent conversation (deep link "open live"). */
data class DelegateHarnessThread(
    val sessionId: String,
    val conversationId: Long,
    val project: DelegateHarnessProject,
    val title: String,
)

enum class DelegateWaitReason { TURN_ENDED, NEEDS_RESPONSE, INTERRUPTED, ERROR, TIMEOUT }

/** Result of [DelegateHarnessDriver.waitFor]: [text] is the rendered digest (<= 2,400 chars). */
data class DelegateWaitResult(
    val reason: DelegateWaitReason,
    val digest: HarnessTaskDigest,
    val text: String,
    /** The follow stream to the harness was lost (the reason is ERROR but no model failure is known). */
    val followLost: Boolean = false,
)

enum class DelegateDecision { APPROVE, DENY, ANSWER, PLAN_APPROVE, PLAN_REJECT }

/** [applied] is false when the preset could not be set; [note] explains it (English, model-facing). */
data class DelegateApprovalOutcome(val preset: String?, val applied: Boolean, val note: String?)

/**
 * Headless driver of one DeepSeek Harness runtime for one delegated task. It never selects a
 * runtime for the UI and never touches other runtimes. Operations throw
 * [DelegationHarnessException] (render with [DelegationHarnessException.toToolError]); the caller
 * must [close] the driver when the task ends so no coroutine leaks.
 */
class DelegateHarnessDriver(context: Context, val runtimeId: String) : AutoCloseable {
    private val appContext = context.applicationContext
    private val runtime: HarnessAppRuntime get() = HarnessAppRuntime.forRuntime(appContext, runtimeId)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watches = ConcurrentHashMap<String, Watch>()
    private val threadOrder = CopyOnWriteArrayList<String>()

    private class Watch(val sessionId: String) {
        val digest = MutableStateFlow(HarnessTaskDigest())
        val startedAtMs = System.currentTimeMillis()
        @Volatile var expectStartUntil = 0L
        @Volatile var changesFetchedSeq: Long? = null
        @Volatile var followLost = false
        val jobs = CopyOnWriteArrayList<Job>()
    }

    // ---- runtime ---------------------------------------------------------------------------

    /** Start (or reuse) the runtime without selecting it. [onStatus] receives short English progress lines. */
    suspend fun ensureRuntime(onStatus: (String) -> Unit = {}): HarnessClient {
        readyClient()?.let { return it }
        onStatus("Starting the DeepSeek runtime...")
        try {
            runtime.start()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw DelegateHarnessSupport.mapStartFailure(harnessLifecycleErrorCode(failure), failure.javaClass.name, failure.message)
        }
        val client = runtime.client ?: throw DelegateHarnessSupport.mapStartFailure(runtime.errorCode.value, null, null)
        withTimeoutOrNull(CONNECT_TIMEOUT_MS) { client.state.first { it == HarnessConnectionState.READY } }
            ?: throw DelegationHarnessException("HARNESS_NOT_READY",
                "The DeepSeek runtime started but is not accepting connections yet. Try again in a minute.")
        return client
    }

    private fun readyClient(): HarnessClient? =
        runtime.client?.takeIf { it.state.value == HarnessConnectionState.READY }

    private fun requireClient(): HarnessClient = readyClient() ?: throw DelegationHarnessException(
        "RUNTIME_NOT_RUNNING", "The DeepSeek runtime is not running. It will be started again on the next task step.")

    // ---- projects and threads -----------------------------------------------------------------

    suspend fun createProject(title: String): DelegateHarnessProject = guarded("PROJECT_CREATE_FAILED") {
        requireClient()
        val workspace = runtime.workspaces.createWorkspace(title.trim().ifBlank { "DeepSeek task" }.take(80))
        val location = com.example.llamadroid.harness.prepareHarnessProject(runtime, workspace)
        DelegateHarnessProject(workspace.id, workspace.title, workspace.guestPath, location.string("workspaceId"))
    }

    /** Find an existing project without `openWorkspace` side effects (used by `continue`). */
    suspend fun openProject(workspaceId: String): DelegateHarnessProject = guarded("PROJECT_NOT_FOUND") {
        val workspace = runtime.dao.workspace(workspaceId) ?: throw DelegationHarnessException(
            "PROJECT_NOT_FOUND", "The previous project no longer exists. Create a new project instead.")
        DelegateHarnessProject(workspace.id, workspace.title, workspace.guestPath, workspace.harnessWorkspaceId)
    }

    /**
     * Create a harness session in [project], index it for the app UI and start watching it.
     * [suppressApprovals] false keeps approval notifications for the user ("Ask me" policy).
     */
    suspend fun createThread(
        project: DelegateHarnessProject,
        title: String,
        suppressApprovals: Boolean = true,
    ): DelegateHarnessThread = guarded("THREAD_CREATE_FAILED") {
        val client = requireClient()
        val workspace = runtime.dao.workspace(project.workspaceId) ?: throw DelegationHarnessException(
            "PROJECT_NOT_FOUND", "The project no longer exists.")
        val location = com.example.llamadroid.harness.prepareHarnessProject(runtime, workspace)
        val created = when (val result = client.createSession(location)) {
            is HarnessRpcResult.Failure -> throw DelegationHarnessException(result.error.code,
                "The harness could not create a thread (${result.error.code}).")
            is HarnessRpcResult.Success -> result.value
        }
        val sessionId = created.string("sessionId")?.takeIf { it.isNotBlank() } ?: throw DelegationHarnessException(
            "SESSION_ID_MISSING", "The harness did not return a thread id.")
        val cleanTitle = title.trim().ifBlank { workspace.title }.take(80)
        // Register and watch before anything can be prompted so no event is missed.
        HarnessDelegatedSessions.register(runtimeId, sessionId, suppressApprovals)
        attachThread(sessionId)
        val indexed = runtime.workspaces.importSession(
            sessionId, cleanTitle, workspace.guestPath,
            preferredWorkspaceId = workspace.id,
            harnessWorkspaceId = location.string("workspaceId"),
            workspaceTitle = workspace.title,
        )
        runCatching {
            client.call("session", "rename", buildJsonObject {
                put("request", buildJsonObject { put("sessionId", sessionId); put("title", cleanTitle) })
            }, HarnessCallPolicy.NoRetry)
        }
        DelegateHarnessThread(sessionId, indexed.conversationId,
            project.copy(harnessWorkspaceId = location.string("workspaceId") ?: project.harnessWorkspaceId), cleanTitle)
    }

    /** Start watching an existing session (e.g. after the app process was restarted). Idempotent. */
    fun attachThread(sessionId: String) {
        if (watches.containsKey(sessionId)) return
        val watch = Watch(sessionId)
        if (watches.putIfAbsent(sessionId, watch) != null) return
        if (!threadOrder.contains(sessionId)) threadOrder.add(sessionId)
        refreshThreadNumbers()
        // UNDISPATCHED so the collectors are subscribed before attachThread returns (emits have no replay).
        watch.jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) { collectEmits(watch) }
        watch.jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) { collectAttention(watch) }
        watch.jobs += scope.launch { followLoop(watch) }
    }

    private fun refreshThreadNumbers() {
        val count = threadOrder.size
        watches.values.forEach { w ->
            val index = threadOrder.indexOf(w.sessionId) + 1
            w.digest.update { it.withThread(index, count) }
        }
    }

    // ---- model, permissions, inputs --------------------------------------------------------------

    suspend fun selectModel(sessionId: String, binding: HarnessModelBinding, reasoningEffort: String? = null) {
        val client = requireClient()
        val result = client.selectSessionModel(buildJsonObject {
            put("sessionId", sessionId)
            put("provider", binding.provider)
            put("model", binding.model)
            reasoningEffort?.let { put("reasoningEffort", it) }
        })
        if (result is HarnessRpcResult.Failure) throw DelegationHarnessException(result.error.code,
            "The harness rejected the model selection (${result.error.code}).")
    }

    /** `/permission workspace-write` (subagent decides, ask me) or `/permission danger-full-access`. */
    suspend fun applyApprovalPolicy(sessionId: String, policy: DelegationApprovalPolicy): DelegateApprovalOutcome {
        val client = requireClient()
        HarnessDelegatedSessions.register(runtimeId, sessionId, suppressApprovals = policy != DelegationApprovalPolicy.ASK_ME)
        val wanted = if (policy == DelegationApprovalPolicy.FULL_ACCESS) "danger-full-access" else "workspace-write"
        val available = (client.call("permissionPresets", "catalog", policy = HarnessCallPolicy.SafeRead) as? HarnessRpcResult.Success)
            ?.value?.objectArray("options")?.mapNotNull { it.string("value") }.orEmpty()
        val preset = pickPermissionPreset(wanted, available)
        if (preset == null) {
            return DelegateApprovalOutcome(null, false,
                "Permission preset '$wanted' is not available in this harness; the harness default applies.")
        }
        val reply = executeNativeHarnessCommand(client, sessionId, "/permission $preset", emptyList())
        return if (reply.accepted) DelegateApprovalOutcome(preset, true, null)
        else DelegateApprovalOutcome(preset, false, "The harness refused '/permission $preset'; the harness default applies.")
    }

    /**
     * Copy [files] (display name to local file) into the project's `inputs/` folder through the
     * session's workspace scope. Returns project-relative paths, in order. Names are sanitized
     * and made unique.
     */
    suspend fun copyInputs(sessionId: String, files: List<Pair<String, File>>): List<String> = guarded("INPUT_COPY_FAILED") {
        if (files.isEmpty()) return@guarded emptyList()
        withContext(Dispatchers.IO) {
            val scopeInfo = runtime.workspaces.scope(sessionId)
            val taken = existingInputNames(scopeInfo).toMutableSet()
            files.map { (display, source) ->
                if (!source.isFile) throw DelegationHarnessException("INPUT_MISSING", "Attachment '$display' is no longer available.")
                val name = DelegateHarnessSupport.uniqueName(DelegateHarnessSupport.sanitizeInputName(display), taken)
                taken += name
                val relative = "${DelegateHarnessSupport.INPUTS_DIR}/$name"
                if (scopeInfo.localRoot != null) {
                    val target = scopeInfo.localFile(relative)
                    target.parentFile?.mkdirs()
                    source.inputStream().use { input -> target.outputStream().use { out -> input.copyTo(out) } }
                } else {
                    if (source.length() > MAX_REMOTE_INPUT_BYTES) throw DelegationHarnessException("INPUT_TOO_LARGE",
                        "Attachment '$display' is larger than 64 MiB and cannot be copied to a remote project.")
                    runtime.files.writeBytes(scopeInfo, relative, source.readBytes())
                }
                relative
            }
        }
    }

    private fun existingInputNames(scopeInfo: HarnessSessionScope): Set<String> {
        if (scopeInfo.localRoot == null) return emptySet()
        return scopeInfo.localFile(DelegateHarnessSupport.INPUTS_DIR).list()?.toSet().orEmpty()
    }

    // ---- prompting ---------------------------------------------------------------------------------

    /** Send [prompt] with delivery [mode] `queue` (default) or `steer`. */
    suspend fun send(sessionId: String, prompt: String, mode: String = "queue") {
        val client = requireClient()
        val text = prompt.trim()
        if (text.isEmpty()) throw DelegationHarnessException("PROMPT_EMPTY", "The prompt is empty.")
        attachThread(sessionId)
        val delivery = if (mode == "steer") "steer" else "queue"
        val content = buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) }
        val result = promptNativeHarnessSession(client, ordinaryHarnessSessionAddress(sessionId), content, delivery,
            TimeZone.getDefault().id)
        if (result is HarnessRpcResult.Failure) throw DelegationHarnessException(result.error.code,
            "The harness rejected the prompt (${result.error.code}).")
        watches[sessionId]?.expectStartUntil = System.currentTimeMillis() + START_GRACE_MS
    }

    suspend fun cancelTurn(sessionId: String) {
        val client = requireClient()
        runtime.attention.noteStop(sessionId)
        val result = client.cancelSession(buildJsonObject { put("sessionId", sessionId) })
        if (result is HarnessRpcResult.Failure) throw DelegationHarnessException(result.error.code,
            "The harness could not cancel the turn (${result.error.code}).")
    }

    // ---- waiting ----------------------------------------------------------------------------------

    /** Latest digest with elapsed time filled in. */
    fun digest(sessionId: String): HarnessTaskDigest {
        val watch = watches[sessionId] ?: return HarnessTaskDigest()
        return watch.digest.value.withElapsed(System.currentTimeMillis() - watch.startedAtMs)
    }

    /** Render the digest; [markRendered] resets the per-render tool counters. */
    fun renderDigest(sessionId: String, markRendered: Boolean = true): String {
        val text = digest(sessionId).render()
        if (markRendered) watches[sessionId]?.digest?.update { it.markRendered() }
        return text
    }

    /**
     * Suspend until the turn ends (after a short idle debounce so queued follow-ups are not cut
     * off), an approval/question/plan is pending, [interrupt] returns true (main-agent message),
     * the turn failed, or [maxMillis] elapsed.
     */
    suspend fun waitFor(
        sessionId: String,
        maxMillis: Long,
        interrupt: suspend () -> Boolean = { false },
        /** Which pending items end the wait; "Ask me" passes a filter that leaves approvals to the user. */
        stopOnPending: (HarnessDigestPending) -> Boolean = { true },
    ): DelegateWaitResult {
        attachThread(sessionId)
        val watch = watches.getValue(sessionId)
        val deadline = System.currentTimeMillis() + maxMillis.coerceAtLeast(0)
        var idleSince: Long? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            val now = System.currentTimeMillis()
            val current = watch.digest.value
            val reason: DelegateWaitReason? = when {
                current.pending.any(stopOnPending) -> DelegateWaitReason.NEEDS_RESPONSE
                interrupt() -> DelegateWaitReason.INTERRUPTED
                watch.followLost -> DelegateWaitReason.ERROR
                else -> {
                    val running = current.running || now < watch.expectStartUntil
                    if (running) { idleSince = null; null } else {
                        val since = idleSince ?: now.also { idleSince = it }
                        if (now - since >= IDLE_DEBOUNCE_MS) {
                            if (current.lastTurnFailed) DelegateWaitReason.ERROR else DelegateWaitReason.TURN_ENDED
                        } else null
                    }
                }
            } ?: if (now >= deadline) DelegateWaitReason.TIMEOUT else null
            if (reason != null) {
                if (reason == DelegateWaitReason.TURN_ENDED || reason == DelegateWaitReason.ERROR) refreshChanges(watch)
                val finalDigest = digest(sessionId)
                val text = finalDigest.render()
                watch.digest.update { it.markRendered() }
                return DelegateWaitResult(reason, finalDigest, text, followLost = watch.followLost)
            }
            val sleep = minOf(POLL_MS, (deadline - now).coerceAtLeast(1L))
            withTimeoutOrNull(sleep) { watch.digest.first { it != current } }
        }
    }

    /** Answer a pending attention item. Returns a short confirmation for the model. */
    suspend fun respond(
        sessionId: String,
        attentionId: String,
        decision: DelegateDecision,
        text: String? = null,
        options: List<String>? = null,
    ): String {
        requireClient()
        val attention = runtime.attention
        val entry = attention.entries.value.firstOrNull { it.id == attentionId && it.sessionId == sessionId }
            ?: throw DelegationHarnessException("INTERACTION_NO_LONGER_PENDING",
                "Item '$attentionId' is no longer pending. Call harness_wait to see the current state.")
        val result = when (decision) {
            DelegateDecision.APPROVE -> attention.approve(sessionId, attentionId, true)
            DelegateDecision.DENY -> attention.approve(sessionId, attentionId, false)
            DelegateDecision.PLAN_APPROVE -> attention.reviewPlan(sessionId, attentionId, true)
            DelegateDecision.PLAN_REJECT -> attention.reviewPlan(sessionId, attentionId, false)
            DelegateDecision.ANSWER -> {
                val selected = options.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
                val custom = selected.isEmpty()
                val answer = if (custom) text.orEmpty().trim() else selected.joinToString(", ")
                if (answer.isEmpty()) throw DelegationHarnessException("ANSWER_EMPTY", "Provide 'text' or 'options' to answer.")
                attention.answer(sessionId, attentionId, answer, custom, selected)
            }
        }
        if (result is HarnessRpcResult.Failure) throw DelegationHarnessException(result.error.code,
            "The harness rejected the response (${result.error.code}).")
        watches[sessionId]?.expectStartUntil = System.currentTimeMillis() + START_GRACE_MS
        return "ok: ${decision.name.lowercase()} sent for $attentionId (${entry.kindLabel()})"
    }

    private fun HarnessAttentionEntry.kindLabel() = when {
        approval != null -> "approval"
        plan != null -> "plan"
        else -> "question"
    }

    // ---- bounded reads -------------------------------------------------------------------------------

    /** Newest-first page [page] (0 = newest) of the transcript as plain text, <= [maxChars]. */
    suspend fun readTranscript(sessionId: String, page: Int, maxChars: Int): String = guarded("READ_FAILED") {
        val client = requireClient()
        val limit = DelegateHarnessSupport.clampChars(maxChars)
        var beforeSeq: Long? = null
        var records: List<kotlinx.serialization.json.JsonObject> = emptyList()
        var hasMore = false
        repeat(page.coerceIn(0, MAX_TRANSCRIPT_PAGES) + 1) {
            // session/page needs throughSeq: pin every page to the newest sequence seen for this thread.
            val result = HarnessLatestPage.page(
                client, sessionWire(sessionId), watches[sessionId]?.digest?.value?.lastSeq, beforeSeq, TRANSCRIPT_PAGE_MESSAGES
            )
            val value = (result as? HarnessRpcResult.Success)?.value ?: throw DelegationHarnessException(
                (result as? HarnessRpcResult.Failure)?.error?.code ?: "READ_FAILED",
                "The transcript could not be read" +
                    ((result as? HarnessRpcResult.Failure)?.error?.message?.take(160)?.let { ": $it" } ?: "."))
            records = value.objectArray("records")
            hasMore = value.boolean("hasMore") == true
            beforeSeq = records.mapNotNull { (it["event"]?.jsonObjectOrNull() ?: it).long("seq") }.minOrNull()
        }
        val lines = records.flatMap { harnessParseRecord(it) }.map { item ->
            "[${item.role.name.lowercase()}] " + item.text.replace('\n', ' ').take(TRANSCRIPT_LINE_CHARS)
        }
        val body = if (lines.isEmpty()) "(no messages on this page)" else lines.joinToString("\n")
        DelegateHarnessSupport.clip(body + if (hasMore) "\n(older messages: page=${page + 1})" else "", limit)
    }

    /** Directory listing of a project-relative [path]. */
    suspend fun listFiles(sessionId: String, path: String?, maxChars: Int): String = guarded("READ_FAILED") {
        val relative = DelegateHarnessSupport.requireProjectRelative(path)
        val scopeInfo = runtime.workspaces.scope(sessionId)
        val listing = runtime.files.invoke(scopeInfo, "workspace.list", JSONObject().put("path", relative)) as? JSONArray
            ?: throw DelegationHarnessException("NOT_A_DIRECTORY", "'$relative' is not a directory.")
        val rows = (0 until minOf(listing.length(), MAX_LIST_ENTRIES)).mapNotNull { listing.optJSONObject(it) }.map { row ->
            val dir = row.optBoolean("directory")
            row.optString("name") + if (dir) "/" else " (${row.optLong("size")} B)"
        }
        val more = if (listing.length() > MAX_LIST_ENTRIES) "\n(+${listing.length() - MAX_LIST_ENTRIES} more)" else ""
        DelegateHarnessSupport.clip(if (rows.isEmpty()) "(empty)" else rows.joinToString("\n") + more,
            DelegateHarnessSupport.clampChars(maxChars))
    }

    /** UTF-8 text of a project file; [page] selects consecutive windows of [maxChars] bytes. */
    suspend fun readFileText(sessionId: String, path: String, page: Int, maxChars: Int): String = guarded("READ_FAILED") {
        val relative = DelegateHarnessSupport.requireProjectRelative(path)
        if (relative == ".") throw DelegationHarnessException("PATH_IS_DIRECTORY", "Provide a file path, not the project root.")
        val chunk = DelegateHarnessSupport.clampChars(maxChars)
        val scopeInfo = runtime.workspaces.scope(sessionId)
        val offset = page.coerceAtLeast(0).toLong() * chunk
        val (bytes, total) = withContext(Dispatchers.IO) {
            if (scopeInfo.localRoot != null) {
                val file = scopeInfo.localFile(relative)
                if (!file.isFile) throw DelegationHarnessException("FILE_NOT_FOUND", "'$relative' is not a file.")
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(minOf(offset, raf.length()))
                    val buffer = ByteArray(chunk)
                    val read = raf.read(buffer).coerceAtLeast(0)
                    buffer.copyOf(read) to raf.length()
                }
            } else {
                val all = runtime.files.readBytes(scopeInfo, relative, REMOTE_READ_BYTES)
                val start = minOf(offset, all.size.toLong()).toInt()
                all.copyOfRange(start, minOf(all.size, start + chunk)) to all.size.toLong()
            }
        }
        if (DelegateHarnessSupport.looksBinary(bytes)) return@guarded "(binary file, $total bytes; not shown)"
        val text = DelegateHarnessSupport.decodeUtf8Chunk(bytes)
        val end = offset + bytes.size
        val footer = if (end < total) "\n(bytes $offset-$end of $total; next page=${page + 1})" else ""
        DelegateHarnessSupport.clip(text + footer, chunk + 80)
    }

    /** Changed files of the latest turn with +/- counts. */
    suspend fun readChanges(sessionId: String, maxChars: Int): String = guarded("READ_FAILED") {
        val watch = watches[sessionId] ?: run { attachThread(sessionId); watches.getValue(sessionId) }
        val summary = fetchChanges(watch)
        if (summary == null) {
            return@guarded if (watch.digest.value.changesSeq == null) "(no file changes reported yet)" else "(change summary unavailable)"
        }
        val rows = summary.files.map { f ->
            f.path + (if (f.binary) " [binary]" else if (f.oversized) " [large]" else " +${f.added}/-${f.deleted}")
        }
        DelegateHarnessSupport.clip("${summary.total} files, +${summary.added}/-${summary.deleted}\n" + rows.joinToString("\n"),
            DelegateHarnessSupport.clampChars(maxChars))
    }

    /** Files the harness presented as deliverables. */
    fun readDeliverables(sessionId: String, maxChars: Int): String {
        val list = digest(sessionId).deliverables
        val body = if (list.isEmpty()) "(no deliverables presented yet)"
        else list.joinToString("\n") { it.path + (it.description?.let { d -> " - $d" } ?: "") }
        return DelegateHarnessSupport.clip(body, DelegateHarnessSupport.clampChars(maxChars))
    }

    /** Copy a project file to Android storage ([destination]). Returns the byte count. */
    suspend fun exportFile(sessionId: String, path: String, destination: File, maxBytes: Long): Long = guarded("EXPORT_FAILED") {
        val relative = DelegateHarnessSupport.requireProjectRelative(path)
        if (relative == ".") throw DelegationHarnessException("PATH_IS_DIRECTORY", "Provide a file path.")
        val scopeInfo = runtime.workspaces.scope(sessionId)
        withContext(Dispatchers.IO) {
            destination.parentFile?.mkdirs()
            val temp = File(destination.parentFile, destination.name + ".part")
            try {
                val size = if (scopeInfo.localRoot != null) {
                    val source = scopeInfo.localFile(relative)
                    if (!source.isFile) throw DelegationHarnessException("FILE_NOT_FOUND", "'$relative' is not a file.")
                    if (source.length() > maxBytes) throw DelegationHarnessException("FILE_TOO_LARGE", "'$relative' is larger than the allowed size.")
                    source.inputStream().use { input -> temp.outputStream().use { out -> input.copyTo(out) } }
                } else {
                    val bytes = runtime.files.readBytes(scopeInfo, relative, maxBytes.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt())
                    temp.writeBytes(bytes)
                    bytes.size.toLong()
                }
                if (!temp.renameTo(destination)) {
                    temp.copyTo(destination, overwrite = true)
                }
                size
            } finally {
                temp.delete()
            }
        }
    }

    // ---- watching ---------------------------------------------------------------------------------

    private fun sessionWire(sessionId: String) = buildJsonObject {
        put("kind", "session"); put("sessionId", sessionId)
    }

    private suspend fun followLoop(watch: Watch) {
        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val client = readyClient()
            if (client == null) {
                failures++
            } else {
                try {
                    client.followSession(buildJsonObject {
                        put("address", sessionWire(watch.sessionId))
                        put("maxMessages", 120)
                        put("assistantStream", false)
                    }).collect { frame ->
                        failures = 0
                        watch.followLost = false
                        val before = watch.digest.value
                        watch.digest.update { it.foldFrame(frame) }
                        val after = watch.digest.value
                        if (before.running && !after.running || after.changesSeq != before.changesSeq && !after.running) {
                            scope.launch { refreshChanges(watch) }
                        }
                    }
                    failures++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failures++
                }
            }
            if (failures >= FOLLOW_LOST_AFTER) watch.followLost = true
            delay(minOf(30_000L, 1_000L shl minOf(failures, 5)))
        }
    }

    private suspend fun collectEmits(watch: Watch) {
        runtime.attention.emits.collect { emit ->
            val session = (emit.args.firstOrNull() as? JsonPrimitive)?.contentOrNull
            if (session != watch.sessionId) return@collect
            when (emit.event) {
                "api-session/status" -> when ((emit.args.getOrNull(1) as? JsonPrimitive)?.contentOrNull) {
                    "true" -> watch.digest.update { it.withRunning(true) }
                    "false" -> watch.digest.update { it.withRunning(false) }
                }
                "api-session/error" -> {
                    val detail = emit.args.getOrNull(1)?.let { element ->
                        (element as? JsonPrimitive)?.contentOrNull
                            ?: element.jsonObjectOrNull()?.string("code") ?: element.jsonObjectOrNull()?.string("name")
                    }
                    watch.digest.update { it.withError("session error" + (detail?.let { d -> ": ${d.take(160)}" } ?: "")) }
                }
            }
        }
    }

    private suspend fun collectAttention(watch: Watch) {
        runtime.attention.entries.collect { entries ->
            val mine = entries.filter { it.sessionId == watch.sessionId }.map(::pendingOf)
            watch.digest.update { it.withPending(mine) }
        }
    }

    private fun pendingOf(entry: HarnessAttentionEntry): HarnessDigestPending {
        entry.approval?.let { return HarnessDigestPending("approval", entry.id, listOf(it.title, it.summary).filter { s -> s.isNotBlank() }.joinToString(": ")) }
        entry.plan?.let { return HarnessDigestPending("plan", entry.id, listOf(it.title, it.summary).filter { s -> s.isNotBlank() }.joinToString(": ")) }
        val q = entry.question
        val title = if (q == null) "" else q.prompt.ifBlank { q.title }
        return HarnessDigestPending("question", entry.id, title, q?.options.orEmpty())
    }

    private suspend fun refreshChanges(watch: Watch) {
        runCatching { fetchChanges(watch) }.getOrNull()?.let { summary ->
            watch.digest.update { d ->
                d.withChangedFiles(summary.files.map { HarnessDigestFile(it.path, it.added, it.deleted) }, summary.total)
            }
        }
    }

    private suspend fun fetchChanges(watch: Watch): com.example.llamadroid.ui.agent.harness.NativeHarnessChangesSummary? {
        val d = watch.digest.value
        val seq = d.changesSeq ?: return null
        val turn = d.changesTurn ?: return null
        val client = readyClient() ?: return null
        val value = (client.fetchJson(NativeHarnessProjectReviewWire.CHANGES_SUMMARY_PATH,
            NativeHarnessProjectReviewWire.query(watch.sessionId, seq)) as? HarnessRpcResult.Success)?.value ?: return null
        watch.changesFetchedSeq = seq
        return parseNativeHarnessChangesSummary(value, NativeHarnessProjectReviewCoordinates(watch.sessionId, seq, turn))
    }

    // ---- lifecycle --------------------------------------------------------------------------------

    /** Stop watching one session (keeps the harness session itself). */
    fun detach(sessionId: String) {
        watches.remove(sessionId)?.jobs?.forEach { it.cancel() }
        HarnessDelegatedSessions.unregister(runtimeId, sessionId)
        threadOrder.remove(sessionId)
        refreshThreadNumbers()
    }

    /** Cancel every collector and unregister notification suppression. Never stops the runtime. */
    override fun close() {
        watches.keys.toList().forEach { HarnessDelegatedSessions.unregister(runtimeId, it) }
        watches.clear()
        scope.cancel()
    }

    private suspend fun <T> guarded(code: String, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: DelegationHarnessException) {
        throw failure
    } catch (failure: Exception) {
        val detail = harnessLifecycleErrorCode(failure)
        throw DelegationHarnessException(code, "Harness operation failed ($detail).", failure)
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val START_GRACE_MS = 15_000L
        private const val IDLE_DEBOUNCE_MS = 1_500L
        private const val POLL_MS = 500L
        private const val FOLLOW_LOST_AFTER = 8
        private const val MAX_REMOTE_INPUT_BYTES = 64L * 1024 * 1024
        private const val REMOTE_READ_BYTES = 1024 * 1024
        private const val MAX_LIST_ENTRIES = 200
        private const val MAX_TRANSCRIPT_PAGES = 20
        private const val TRANSCRIPT_PAGE_MESSAGES = 20
        private const val TRANSCRIPT_LINE_CHARS = 600

        /** Pick the catalog value for [wanted] (exact, then loose match); null when unavailable. */
        internal fun pickPermissionPreset(wanted: String, available: List<String>): String? {
            if (available.isEmpty()) return wanted // catalog unreadable: try the canonical name
            available.firstOrNull { it == wanted }?.let { return it }
            val key = if (wanted.startsWith("danger")) "danger" else "workspace"
            return available.firstOrNull { it.contains(key, ignoreCase = true) }
        }
    }
}
