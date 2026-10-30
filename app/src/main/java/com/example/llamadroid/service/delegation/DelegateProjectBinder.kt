package com.example.llamadroid.service.delegation

import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity
import org.json.JSONArray
import org.json.JSONObject

/** Persistence the binder needs: the chat's tasks and ONE atomic save of every binding field. */
interface DelegateBindStore {
    suspend fun chatTasks(chatId: Long): List<NativeChatDelegateTaskEntity>
    suspend fun saveBinding(taskId: Long, binding: DelegateBindingFields)
}

/** All task-row fields the binder writes, in one update. JSON shapes match the old `enterProject`. */
data class DelegateBindingFields(
    val workspaceId: String,
    val harnessSessionId: String,
    val harnessThreadsJson: String,
    val harnessConversationId: Long,
    val harnessModelJson: String,
)

data class BindResult(
    val project: DelegateHarnessProject,
    val thread: DelegateHarnessThread,
    /** 1-based position of [thread] in the task's thread list. */
    val threadNumber: Int,
    val threadCount: Int,
    /** An existing project thread was reopened (its harness conversation is kept). */
    val resumed: Boolean,
    /** The wanted thread was gone and a new one replaced it. */
    val replaced: Boolean,
    val projectCreated: Boolean,
    val approvalNote: String?,
    /** Model-facing note, only when [replaced]. */
    val subagentNote: String?,
    /** One line for the main chat, only when [replaced]. */
    val mainNotice: String?,
    val binding: HarnessModelBinding,
) {
    val modelLabel: String get() = binding.model

    /** One line for the subagent. */
    fun describe(): String {
        val head = if (projectCreated) "The app created project \"${project.title}\" with thread $threadNumber"
        else "The app opened project \"${project.title}\" with thread $threadNumber"
        val tail = when {
            resumed -> "; DeepSeek keeps its earlier conversation."
            else -> "."
        }
        return head + tail
    }
}

/**
 * Opens a delegate task's harness project and thread BEFORE the subagent model runs. Order, project:
 * `task.workspaceId`, `options.projectId`, the chat's latest project, else a new project (explicit [bind]
 * `newProject` or the chat has none). Thread: `task.harnessSessionId` (same project), `options.threadId`,
 * [DelegateThreadReuse.lastThread], else a new thread. The model and approval policy are applied to
 * every thread, adopted or new. Nothing is saved unless everything succeeded.
 *
 * Input copying is NOT done here: the toolbox copies pending inputs (`copyPendingInputs`) right after bind.
 */
class DelegateProjectBinder(private val port: DelegateHarnessPort, private val store: DelegateBindStore) {

    suspend fun bind(
        task: NativeChatDelegateTaskEntity,
        options: DelegationTaskOptions,
        policy: DelegationApprovalPolicy,
        newProject: Boolean = false,
        freshThread: Boolean = false,
        model: suspend () -> HarnessModelBinding,
    ): BindResult {
        val chatTasks = store.chatTasks(task.chatId)
        val suppress = policy != DelegationApprovalPolicy.ASK_ME
        val (project, projectCreated) = resolveProject(task, options, chatTasks, newProject)

        val wanted = if (freshThread) null else wantedThread(task, options, chatTasks, project.workspaceId)
        var replaced = false
        var thread: DelegateHarnessThread? = null
        var resumed = false
        if (wanted != null) {
            thread = adopt(project, wanted, suppress)
            if (thread != null) resumed = true else replaced = true
        }
        val created = thread == null
        val active = thread ?: port.createThread(project, task.title, suppress)
        val binding = model()
        port.selectModel(active.sessionId, binding)
        val approval = port.applyApprovalPolicy(active.sessionId, policy)

        val threads = DelegationThreadLives.parseThreads(task.harnessThreadsJson, null, task.title).toMutableList()
        if (threads.none { it.first == active.sessionId }) threads += active.sessionId to active.title
        store.saveBinding(task.id, DelegateBindingFields(
            workspaceId = project.workspaceId,
            harnessSessionId = active.sessionId,
            harnessThreadsJson = JSONArray().also { a ->
                threads.forEach { (id, title) -> a.put(JSONObject().put("id", id).put("title", title)) }
            }.toString(),
            harnessConversationId = active.conversationId,
            harnessModelJson = modelJson(binding),
        ))
        return BindResult(
            project = project, thread = active,
            threadNumber = threads.indexOfFirst { it.first == active.sessionId } + 1, threadCount = threads.size,
            resumed = resumed && !created, replaced = replaced, projectCreated = projectCreated,
            approvalNote = approval.note,
            subagentNote = if (replaced) SUBAGENT_NOTE else null,
            mainNotice = if (replaced) MAIN_NOTICE else null,
            binding = binding,
        )
    }

    private suspend fun resolveProject(
        task: NativeChatDelegateTaskEntity,
        options: DelegationTaskOptions,
        chatTasks: List<NativeChatDelegateTaskEntity>,
        newProject: Boolean,
    ): Pair<DelegateHarnessProject, Boolean> {
        val existingId = task.workspaceId?.takeIf { it.isNotBlank() }
            ?: options.projectId?.takeIf { it.isNotBlank() }
            ?: if (newProject) null else chatProjectId(chatTasks)
        try {
            return if (existingId != null) port.openProject(existingId) to false
            else port.createProject(task.title) to true
        } catch (e: DelegationHarnessException) {
            throw DelegationHarnessException("PROJECT_UNAVAILABLE",
                "The DeepSeek project for this task could not be opened (${e.code}).", e)
        }
    }

    private fun chatProjectId(tasks: List<NativeChatDelegateTaskEntity>): String? =
        tasks.filter { !it.workspaceId.isNullOrBlank() }
            .maxWithOrNull(compareBy<NativeChatDelegateTaskEntity> { it.createdAt }.thenBy { it.id })?.workspaceId

    private fun wantedThread(
        task: NativeChatDelegateTaskEntity,
        options: DelegationTaskOptions,
        chatTasks: List<NativeChatDelegateTaskEntity>,
        workspaceId: String,
    ): String? =
        (if (task.workspaceId == workspaceId) task.harnessSessionId?.takeIf { it.isNotBlank() } else null)
            ?: options.threadId?.takeIf { it.isNotBlank() }
            ?: DelegateThreadReuse.lastThread(chatTasks, workspaceId)

    /** The adopted thread, or null when the thread is definitely gone; other errors propagate. */
    private suspend fun adopt(project: DelegateHarnessProject, sessionId: String, suppress: Boolean): DelegateHarnessThread? {
        val thread = try {
            port.adoptThread(project, sessionId, suppress)
        } catch (e: DelegationHarnessException) {
            if (e.code == "THREAD_NOT_FOUND") return null
            throw e
        }
        return if (port.sessionKnown(sessionId)) thread else null
    }

    private fun modelJson(b: HarnessModelBinding): String =
        JSONObject().put("provider", b.provider).put("model", b.model)
            .put("contextTokens", b.contextTokens ?: JSONObject.NULL)
            .put("maxOutputTokens", b.maxOutputTokens ?: JSONObject.NULL)
            .put("route", b.routeKind.name).toString()

    companion object {
        const val SUBAGENT_NOTE = "The previous DeepSeek conversation could not be restored; DeepSeek still has the project " +
            "files. Start your next harness_send with a short summary of the earlier work, and tell the main assistant " +
            "in your next message_main or finish_task summary that the old DeepSeek conversation was lost."
        const val MAIN_NOTICE = "The earlier DeepSeek conversation could not be restored; a new thread was opened in the same project."
    }
}
