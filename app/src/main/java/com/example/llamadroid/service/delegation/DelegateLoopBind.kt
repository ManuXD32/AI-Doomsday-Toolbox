package com.example.llamadroid.service.delegation

import com.example.llamadroid.data.dao.NativeChatDelegateTaskDao
import com.example.llamadroid.data.model.NativeChatDelegateTaskEntity

/** [DelegateBindStore] over the task table: the chat's tasks and one `update` of the five binding fields. */
internal class DelegateRoomBindStore(private val taskDao: NativeChatDelegateTaskDao) : DelegateBindStore {
    override suspend fun chatTasks(chatId: Long): List<NativeChatDelegateTaskEntity> =
        taskDao.getIdsForChat(chatId).mapNotNull { taskDao.getById(it) }

    override suspend fun saveBinding(taskId: Long, binding: DelegateBindingFields) {
        val latest = taskDao.getById(taskId) ?: return
        taskDao.update(
            latest.copy(
                workspaceId = binding.workspaceId, harnessSessionId = binding.harnessSessionId,
                harnessThreadsJson = binding.harnessThreadsJson, harnessConversationId = binding.harnessConversationId,
                harnessModelJson = binding.harnessModelJson, updatedAt = System.currentTimeMillis()
            )
        )
    }
}

/** Pure text helpers for what the loop tells the subagent after the app bound its project and thread. */
internal object DelegateLoopBind {
    /** The one-line project note (plus approval and replaced-thread notes) for the subagent. */
    fun projectNote(result: BindResult, inputsNote: String? = null): String =
        listOfNotNull(result.describe(), result.approvalNote, result.subagentNote, inputsNote)
            .joinToString(" ")

    /** Task-row option changes after a successful bind: the one-shot flags are consumed. */
    fun consumed(options: DelegationTaskOptions): DelegationTaskOptions =
        options.copy(newProject = false, freshThread = false)
}
