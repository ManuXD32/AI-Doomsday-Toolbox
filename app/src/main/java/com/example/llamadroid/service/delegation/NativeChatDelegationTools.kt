package com.example.llamadroid.service.delegation

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.model.NativeChatDelegateTaskState
import com.example.llamadroid.service.AgentTool
import com.example.llamadroid.service.NativeChatToolConfig
import com.example.llamadroid.service.NativeChatToolExtension
import com.example.llamadroid.service.NativeChatToolProgress
import com.example.llamadroid.service.NativeChatToolResult
import com.example.llamadroid.service.OllamaService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The five main-LLM DeepSeek delegation tools, plugged into `NativeChatToolRuntime`. */
class NativeChatDelegationTools(context: Context) : NativeChatToolExtension {
    private val appContext = context.applicationContext

    override val id: String = "deepseek_delegation"

    private val toolNames = setOf(TOOL_START, TOOL_STATUS, TOOL_MESSAGE, TOOL_CANCEL, TOOL_SCHEDULE)

    override fun isEnabled(config: NativeChatToolConfig): Boolean = config.toolsEnabled && config.deepSeekDelegationEnabled

    override fun handles(toolName: String): Boolean = toolName in toolNames

    override fun isMutating(toolName: String): Boolean =
        toolName == TOOL_START || toolName == TOOL_CANCEL || toolName == TOOL_SCHEDULE

    override fun statusText(toolName: String): String? {
        val res = when (toolName) {
            TOOL_START -> R.string.native_chat_delegation_service_status_start
            TOOL_STATUS -> R.string.native_chat_delegation_service_status_status
            TOOL_MESSAGE -> R.string.native_chat_delegation_service_status_message
            TOOL_CANCEL -> R.string.native_chat_delegation_service_status_cancel
            TOOL_SCHEDULE -> R.string.native_chat_delegation_service_status_schedule
            else -> return null
        }
        return appContext.getString(res)
    }

    override fun tools(config: NativeChatToolConfig): List<AgentTool> = listOf(
        AgentTool(
            name = TOOL_START,
            description = "Hand a coding, file or project job to the DeepSeek subagent: a background agent with a terminal and files. Use it to write or change more than a small snippet of code, create/edit/convert files, build an app, site or script, run/test/debug code, or analyse attached files. Do not paste large code in the chat. Not for chit-chat or short answers. One task per chat. Returns immediately.",
            parameters = mapOf(
                "task" to "Self-contained brief: goal, constraints, acceptance criteria, expected output files",
                "title" to "Short task title",
                "attachments" to "Chat file ids to hand over, e.g. f12,img55",
                "project" to "new (default) or continue"
            ),
            requiredParams = listOf("task"),
            schemaJson = DelegationToolArgs.startSchema()
        ),
        AgentTool(
            name = TOOL_STATUS,
            description = "Get the status of this chat's DeepSeek task (progress, files, result when finished). Built instantly by the app.",
            parameters = mapOf("detail" to "brief (default) or full"),
            schemaJson = DelegationToolArgs.statusSchema()
        ),
        AgentTool(
            name = TOOL_MESSAGE,
            description = "Send a message or question to the DeepSeek subagent and wait for its reply. Also resumes an interrupted task.",
            parameters = mapOf(
                "message" to "Message for the subagent",
                "wait_seconds" to "Seconds to wait for the reply, 0-120 (default 45)"
            ),
            requiredParams = listOf("message"),
            schemaJson = DelegationToolArgs.messageSchema()
        ),
        AgentTool(
            name = TOOL_CANCEL,
            description = "Cancel this chat's active DeepSeek task.",
            parameters = mapOf(
                "reason" to "Why the task is cancelled",
                "stop_runtime" to "true to also stop the DeepSeek runtime (default false)"
            ),
            schemaJson = DelegationToolArgs.cancelSchema()
        ),
        AgentTool(
            name = TOOL_SCHEDULE,
            description = "Set a reminder to check the DeepSeek task later. When it fires you receive a notice and should call deepseek_task_status. One reminder per task; a new one replaces the old; minutes=0 cancels.",
            parameters = mapOf(
                "minutes" to "Minutes until the check (2-1440); 0 cancels",
                "note" to "Optional note shown in the notice"
            ),
            requiredParams = listOf("minutes"),
            schemaJson = DelegationToolArgs.scheduleSchema()
        )
    )

    override fun awareness(config: NativeChatToolConfig): String? {
        if (!isEnabled(config)) return null
        val whenToUse = when (config.deepSeekDelegationMode) {
            DelegationMode.AUTO -> WHEN_AUTO
            DelegationMode.PREFER -> WHEN_PREFER
            DelegationMode.ONLY_WHEN_ASKED -> WHEN_ONLY_ASKED
        }
        return "$whenToUse\n$OPERATIONAL_RULES"
    }

    override fun turnHint(config: NativeChatToolConfig, latestUserText: String): String? {
        if (!isEnabled(config)) return null
        return NativeChatDelegationHints.hintFor(latestUserText, config.deepSeekDelegationMode)
    }

    override suspend fun execute(
        toolCall: OllamaService.ToolCall,
        config: NativeChatToolConfig,
        chatId: Long?,
        onProgress: (NativeChatToolProgress) -> Unit
    ): Result<NativeChatToolResult> {
        if (!isEnabled(config)) return ok("tool_error: DeepSeek delegation is disabled for this chat.")
        if (chatId == null || chatId <= 0L) return ok("tool_error: DeepSeek tasks need a saved chat.")
        return try {
            ok(
                when (toolCall.name) {
                    TOOL_START -> start(toolCall.arguments, chatId)
                    TOOL_STATUS -> status(toolCall.arguments, chatId)
                    TOOL_MESSAGE -> message(toolCall.arguments, chatId)
                    TOOL_CANCEL -> cancel(toolCall.arguments, chatId)
                    TOOL_SCHEDULE -> schedule(toolCall.arguments, chatId)
                    else -> "tool_error: Unknown tool '${toolCall.name}'."
                }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ok("tool_error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun ok(text: String) = Result.success(NativeChatToolResult(text))

    private val api get() = NativeChatDelegation.get(appContext)

    private suspend fun start(args: Map<String, String>, chatId: Long): String {
        val task = DelegationToolArgs.boundedText(DelegationToolArgs.arg(args, "task", "brief", "goal"), MAX_TASK_CHARS)
            ?: return "tool_error: task is required."
        val title = DelegationToolArgs.boundedText(DelegationToolArgs.arg(args, "title"), DelegationToolArgs.MAX_TITLE_CHARS)
        val request = DelegationStartRequest(
            chatId = chatId,
            task = task,
            title = title,
            attachmentRefs = DelegationToolArgs.parseAttachmentRefs(DelegationToolArgs.arg(args, "attachments", "files")),
            continueProject = DelegationToolArgs.parseProjectContinue(DelegationToolArgs.arg(args, "project"))
        )
        return when (val result = api.startTask(request)) {
            is DelegationStartResult.Started -> DelegationNotices.startedText()
            is DelegationStartResult.Rejected -> "tool_error: ${result.message}"
        }
    }

    private suspend fun status(args: Map<String, String>, chatId: Long): String {
        val detail = if (DelegationToolArgs.parseFullDetail(DelegationToolArgs.arg(args, "detail"))) DelegationStatusDetail.FULL else DelegationStatusDetail.BRIEF
        return api.statusText(chatId, detail)
    }

    private suspend fun message(args: Map<String, String>, chatId: Long): String {
        val message = DelegationToolArgs.boundedText(DelegationToolArgs.arg(args, "message", "text"), DelegationToolArgs.MAX_MESSAGE_CHARS)
            ?: return "tool_error: message is required."
        val wait = DelegationToolArgs.coerceWaitSeconds(DelegationToolArgs.arg(args, "wait_seconds", "wait"))
        val reply = api.messageSubagent(chatId, message, wait)
        val text = reply.text?.trim().orEmpty()
        return if (text.isNotEmpty()) text else "no reply yet (state ${reply.state}); check again later"
    }

    private suspend fun cancel(args: Map<String, String>, chatId: Long): String {
        val reason = DelegationToolArgs.boundedText(DelegationToolArgs.arg(args, "reason"), DelegationToolArgs.MAX_NOTE_CHARS)
        val stopRuntime = DelegationToolArgs.coerceBoolean(DelegationToolArgs.arg(args, "stop_runtime", "stopRuntime"), false)
        val taskId = activeTask(chatId)?.id
        val cancelled = api.cancelTask(chatId, reason, stopRuntime)
        if (taskId != null) DelegationAlarmScheduler.cancel(appContext, taskId)
        return if (cancelled) "DeepSeek task cancelled." else "tool_error: There is no active DeepSeek task in this chat."
    }

    private suspend fun schedule(args: Map<String, String>, chatId: Long): String {
        val raw = DelegationToolArgs.coerceInt(DelegationToolArgs.arg(args, "minutes", "delay_minutes"))
            ?: return "tool_error: minutes is required (an integer; 0 cancels)."
        val note = DelegationToolArgs.boundedText(DelegationToolArgs.arg(args, "note"), DelegationToolArgs.MAX_NOTE_CHARS)
        val task = activeTask(chatId) ?: return "tool_error: There is no active DeepSeek task in this chat to schedule a check for."
        if (raw <= 0) {
            DelegationAlarmScheduler.cancel(appContext, task.id)
            return "Scheduled check cancelled."
        }
        val fireAt = DelegationAlarmScheduler.schedule(appContext, chatId, task.id, raw, note)
            ?: return "tool_error: Could not schedule the check (the limit of ${DelegationAlarmPolicy.MAX_ALARMS_PER_TASK} reminders per task was reached). Use deepseek_task_status instead."
        val clamped = DelegationAlarmPolicy.clampMinutes(raw)
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(fireAt))
        return "Check scheduled in $clamped minutes (local time $time). When it fires you will get a ${DelegationNotices.TAG_CHECK} notice; then call deepseek_task_status."
    }

    private suspend fun activeTask(chatId: Long) = AppDatabase.getDatabase(appContext)
        .nativeChatDelegateTaskDao()
        .getForChatInStates(chatId, NativeChatDelegateTaskState.ACTIVE.toList())

    companion object {
        const val TOOL_START = "deepseek_task_start"
        const val TOOL_STATUS = "deepseek_task_status"
        const val TOOL_MESSAGE = "deepseek_task_message"
        const val TOOL_CANCEL = "deepseek_task_cancel"
        const val TOOL_SCHEDULE = "deepseek_schedule_check"
        private const val MAX_TASK_CHARS = 12_000

        private const val WHEN_AUTO = """DeepSeek delegation: a background coding agent with a terminal and files. Call deepseek_task_start when the user asks to:
- write or change more than a small snippet of code
- create, edit or convert files
- build an app, site, script or project
- run, test or debug code
- analyse attached files or a repo
- do multi-step work that needs a terminal
Do this instead of pasting large code in the chat. If in doubt, ask the user whether to delegate.
Do not use it for chit-chat, short factual answers or small explanations.
Examples: "make a snake game in HTML" -> deepseek_task_start. "fix the bug in main.py" -> deepseek_task_start. "what is a mutex?" -> answer directly."""

        private const val WHEN_PREFER = """DeepSeek delegation: a background coding agent with a terminal and files. Delegate with deepseek_task_start every coding, file or project request by default, even small ones that create or change files. Never paste code in the chat instead of delegating.
Answer directly only for chit-chat, short factual answers and small explanations.
Examples: "write a script that renames files" -> deepseek_task_start. "convert this CSV to JSON" -> deepseek_task_start. "what is a mutex?" -> answer directly."""

        private const val WHEN_ONLY_ASKED = """DeepSeek delegation: a background coding agent with a terminal and files. Use the deepseek_* tools only when the user explicitly asks for DeepSeek, delegation or a background task. Otherwise answer yourself and do not call deepseek_task_start."""

        private const val OPERATIONAL_RULES = """- Write a self-contained brief (goal, constraints, acceptance criteria, expected output files); the subagent cannot see this chat. Pass chat file ids in attachments.
- The task runs in the background; keep chatting with the user meanwhile. Only one task can be active per chat.
- Check progress with deepseek_task_status, talk to the subagent with deepseek_task_message, and set reminders with deepseek_schedule_check (10-30 minutes is typical). Re-arm the reminder if the task is still running.
- When a task finishes you receive a [DeepSeek task finished] notice with the result and files: relay the result to the user. Never invent results."""
    }
}
